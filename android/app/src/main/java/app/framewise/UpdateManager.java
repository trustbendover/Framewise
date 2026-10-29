package app.framewise;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * In-app updater backed by the public GitHub Releases of {@link BuildConfig#UPDATE_REPO}.
 *
 * <p>Flow: read {@code releases/latest} (no token) → compare version with the installed one →
 * download the {@code .apk} asset and its {@code .sha256} asset → verify the SHA-256 and that the
 * APK is the same package, a newer versionCode and signed with the same key → install with a
 * {@link PackageInstaller} session. Android always shows its own confirmation dialog; the
 * "Install unknown apps" permission for Framewise is requested first when needed.
 */
final class UpdateManager {
    interface Listener { void onUpdateStatus(JSONObject status); }

    static final String ACTION_INSTALL_STATUS = "app.framewise.UPDATE_INSTALL_STATUS";
    private static final String API = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases?per_page=30";
    private static final String PREFS = "framewise.updates";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    private static final Pattern VERSION_CODE = Pattern.compile("versionCode\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX64 = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    private static Listener listener;
    private static JSONObject lastStatus;
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Bridge calls arrive on the WebView JavaBridge thread and the UI thread: claim work atomically.
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Release available;
    private File verifiedApk;

    static final class Release {
        String tag, versionName, notes, pageUrl, apkUrl, apkName, shaUrl;
        /** Notes of every published release newer than the installed app, newest first. */
        JSONArray notesByVersion = new JSONArray();
        long versionCode, apkSize;
    }

    UpdateManager(Context context) { this.context = context.getApplicationContext(); }

    static synchronized void setListener(Listener value) {
        listener = value;
        if (value != null && lastStatus != null) value.onUpdateStatus(lastStatus);
    }

    static synchronized void publish(String state, String message, JSONObject extra) {
        JSONObject status = extra == null ? new JSONObject() : extra;
        try {
            status.put("state", state);
            status.put("message", message);
            status.put("currentVersion", BuildConfig.VERSION_NAME);
            status.put("currentCode", BuildConfig.VERSION_CODE);
        } catch (JSONException ignored) { }
        if ("error".equals(state)) DiagLog.error("updater", message);
        lastStatus = status;
        if (listener != null) listener.onUpdateStatus(status);
    }

    static synchronized String lastStatusJson() {
        return lastStatus == null ? "{\"state\":\"idle\",\"currentVersion\":\"" + BuildConfig.VERSION_NAME + "\"}" : lastStatus.toString();
    }

    /** Silent launch check, at most every few hours; only reports when an update exists. */
    void checkOnLaunch() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS && !prefs.getBoolean("updatePending", false)) return;
        check(false);
    }

    void check(boolean manual) {
        if (!busy.compareAndSet(false, true)) return;
        if (manual) publish("checking", "Checking GitHub for a newer Framewise…", null);
        try { executor.execute(() -> {
            try {
                Release release = fetchLatest();
                SharedPreferences.Editor prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                prefs.putLong("lastCheck", System.currentTimeMillis());
                boolean newer = isNewer(release);
                prefs.putBoolean("updatePending", newer).apply();
                if (!newer) {
                    available = null;
                    if (manual) publish("current", "Framewise " + BuildConfig.VERSION_NAME + " is the latest version.", null);
                    return;
                }
                if (release.apkUrl == null || release.shaUrl == null) {
                    if (manual) publish("error", "Release " + release.tag + " has no APK with a .sha256 checksum attached yet. Try again later.", null);
                    return;
                }
                available = release;
                JSONObject extra = new JSONObject();
                extra.put("latestVersion", release.versionName);
                extra.put("notes", release.notes == null ? "" : release.notes);
                extra.put("notesByVersion", release.notesByVersion);
                extra.put("url", release.pageUrl == null ? "" : release.pageUrl);
                extra.put("size", release.apkSize);
                publish("available", "Framewise " + release.versionName + " is available.", extra);
            } catch (IOException error) {
                if (manual) publish("error", "Could not reach GitHub (" + error.getMessage() + "). Camera Wi-Fi has no internet: check updates on mobile data or home Wi-Fi.", null);
            } catch (JSONException | RuntimeException error) {
                if (manual) publish("error", "GitHub's release information could not be read.", null);
            } finally {
                busy.set(false);
            }
        }); } catch (java.util.concurrent.RejectedExecutionException closed) { busy.set(false); }
    }

    /** Downloads, verifies and then installs. Call from the UI thread with the visible activity. */
    void downloadAndInstall(Activity activity) {
        if (busy.get()) return;
        Release release = available;
        if (release == null) { check(true); return; }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            publish("permission", "Allow Framewise to install updates: switch on \u201cAllow from this source\u201d, then come back.", null);
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("awaitingInstallPermission", true).apply();
            } catch (RuntimeException error) {
                publish("error", "Open Settings → Apps → Framewise → Install unknown apps, allow it, then try again.", null);
            }
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        try { executor.execute(() -> {
            try {
                File apk = download(release);
                verifiedApk = apk;
                publish("installing", "Verified. Android will ask you to confirm the update…", null);
                install(apk);
            } catch (UpdateException error) {
                publish("error", error.getMessage(), null);
            } catch (IOException error) {
                publish("error", "Download failed (" + error.getMessage() + "). Check your internet connection and try again.", null);
            } catch (RuntimeException error) {
                publish("error", "The update could not be installed (" + error.getClass().getSimpleName() + ").", null);
            } finally {
                busy.set(false);
            }
        }); } catch (java.util.concurrent.RejectedExecutionException closed) { busy.set(false); }
    }

    /** Called from onResume: continues after the user came back from the "Install unknown apps" screen. */
    void onHostResumed(Activity activity) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("awaitingInstallPermission", false)) return;
        prefs.edit().putBoolean("awaitingInstallPermission", false).apply();
        if (activity.getPackageManager().canRequestPackageInstalls()) downloadAndInstall(activity);
        else publish("error", "Framewise still isn't allowed to install updates. Enable \u201cAllow from this source\u201d to update in-app.", null);
    }

    void close() { executor.shutdownNow(); }

    private Release fetchLatest() throws IOException, JSONException {
        JSONArray list = new JSONArray(new String(get(API, 2 * 1024 * 1024, "application/vnd.github+json"), StandardCharsets.UTF_8));
        return pickNewest(list, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
    }

    /**
     * From GitHub's releases list: the newest published release (drafts and pre-releases skipped),
     * with the notes of every release newer than the installed one so skipped updates are shown too.
     */
    static Release pickNewest(JSONArray list, long installedCode, String installedName) throws JSONException {
        java.util.List<Release> newer = new java.util.ArrayList<>();
        Release best = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject json = list.optJSONObject(i);
            if (json == null || json.optBoolean("draft") || json.optBoolean("prerelease")) continue;
            Release r = parseRelease(json);
            if (r.tag.isEmpty()) continue;
            if (best == null || newerThan(r, best)) best = r;
            if (isNewer(r, installedCode, installedName)) newer.add(r);
        }
        if (best == null) throw new JSONException("no published releases");
        newer.sort((a, b) -> newerThan(a, b) ? -1 : newerThan(b, a) ? 1 : 0);
        for (Release r : newer) best.notesByVersion.put(new JSONObject().put("version", r.versionName).put("notes", r.notes == null ? "" : r.notes));
        return best;
    }

    private static boolean newerThan(Release a, Release b) {
        if (a.versionCode > 0 && b.versionCode > 0 && a.versionCode != b.versionCode) return a.versionCode > b.versionCode;
        return compareVersions(a.versionName, b.versionName) > 0;
    }

    static Release parseRelease(JSONObject json) throws JSONException {
        Release release = new Release();
        release.tag = json.optString("tag_name", "");
        release.versionName = release.tag.replaceFirst("^[vV]", "");
        release.notes = json.optString("body", "");
        release.pageUrl = json.optString("html_url", null);
        Matcher code = VERSION_CODE.matcher(release.notes == null ? "" : release.notes);
        release.versionCode = code.find() ? Long.parseLong(code.group(1)) : -1;
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (!url.startsWith("https://github.com/")) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".apk") && release.apkUrl == null) {
                    release.apkUrl = url; release.apkName = name; release.apkSize = asset.optLong("size", -1);
                }
            }
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (!url.startsWith("https://github.com/")) continue;
                if (name.toLowerCase(Locale.ROOT).endsWith(".sha256")
                        && (release.shaUrl == null || (release.apkName != null && name.startsWith(release.apkName)))) {
                    release.shaUrl = url;
                }
            }
        }
        return release;
    }

    private static boolean isNewer(Release release) { return isNewer(release, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME); }

    static boolean isNewer(Release release, long installedCode, String installedName) {
        if (release.versionCode > 0) return release.versionCode > installedCode;
        return compareVersions(release.versionName, installedName) > 0;
    }

    static int compareVersions(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        return 0;
    }

    private static int[] parse(String version) {
        int[] parts = new int[3];
        Matcher m = VERSION.matcher(version == null ? "" : version);
        if (!m.find()) return parts;
        for (int i = 0; i < 3; i++) parts[i] = m.group(i + 1) == null ? 0 : Integer.parseInt(m.group(i + 1));
        return parts;
    }

    private static final class UpdateException extends Exception {
        UpdateException(String message) { super(message); }
    }

    private File download(Release release) throws IOException, UpdateException {
        publish("downloading", "Downloading checksum…", null);
        String shaText = new String(get(release.shaUrl, 4096, "application/octet-stream"), StandardCharsets.US_ASCII);
        Matcher hex = HEX64.matcher(shaText);
        if (!hex.find()) throw new UpdateException("The release checksum file is not a valid SHA-256 value. Update cancelled.");
        String expected = hex.group(1).toLowerCase(Locale.ROOT);

        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cache folder unavailable");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File apk = new File(dir, "Framewise-update.apk");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable"); }
        HttpURLConnection connection = open(release.apkUrl, "application/octet-stream");
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            long total = connection.getContentLengthLong();
            if (total > MAX_APK_BYTES) throw new UpdateException("The update is unexpectedly large. Update cancelled.");
            byte[] buffer = new byte[64 * 1024];
            long done = 0; int lastPercent = -1; int read;
            while ((read = in.read(buffer)) != -1) {
                done += read;
                if (done > MAX_APK_BYTES) throw new UpdateException("The update is unexpectedly large. Update cancelled.");
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (percent != lastPercent && percent % 5 == 0) {
                    lastPercent = percent;
                    JSONObject extra = new JSONObject();
                    try { extra.put("progress", percent); } catch (JSONException ignored) { }
                    publish("downloading", percent >= 0 ? "Downloading update… " + percent + "%" : "Downloading update…", extra);
                }
            }
        } finally { connection.disconnect(); }

        String actual = hex(digest.digest());
        if (!actual.equals(expected)) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            throw new UpdateException("The download did not match the release checksum, so it was deleted. Nothing was installed.");
        }
        publish("verifying", "Checksum OK. Checking the app signature…", null);
        verifyPackage(apk);
        return apk;
    }

    private void verifyPackage(File apk) throws UpdateException {
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = Build.VERSION.SDK_INT >= 33
            ? pm.getPackageArchiveInfo(apk.getPath(), PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES))
            : pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) throw new UpdateException("The downloaded file is not a valid Android app. Nothing was installed.");
        if (!context.getPackageName().equals(archive.packageName)) throw new UpdateException("The downloaded app is not Framewise. Nothing was installed.");
        if (archive.getLongVersionCode() <= BuildConfig.VERSION_CODE) throw new UpdateException("The release APK is not newer than this app. Nothing was installed.");
        try {
            PackageInfo installed = Build.VERSION.SDK_INT >= 33
                ? pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES))
                : pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (!sameSigner(installed.signingInfo, archive.signingInfo)) {
                throw new UpdateException("This update is signed with a different key than the installed Framewise "
                    + "(the old 0.2 test build used a debug key). Android cannot update across keys: uninstall Framewise once, "
                    + "then install the APK from the GitHub release page. Later updates will work in-app.");
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new UpdateException("Could not read the installed app's signature.");
        }
    }

    private static boolean sameSigner(SigningInfo installed, SigningInfo update) {
        if (installed == null || update == null) return false;
        Signature[] current = installed.hasMultipleSigners() ? installed.getApkContentsSigners() : installed.getSigningCertificateHistory();
        Signature[] next = update.hasMultipleSigners() ? update.getApkContentsSigners() : update.getSigningCertificateHistory();
        if (current == null || next == null) return false;
        for (Signature a : current) for (Signature b : next) if (Arrays.equals(a.toByteArray(), b.toByteArray())) return true;
        return false;
    }

    private void install(File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        if (Build.VERSION.SDK_INT >= 33) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("framewise.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                session.fsync(out);
            }
            // The PendingIntent must be mutable so PackageInstaller can add the status extras.
            Intent callback = new Intent(context, UpdateReceiver.class).setAction(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException error) {
            try { installer.abandonSession(sessionId); } catch (RuntimeException ignored) { }
            throw error;
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "Framewise/" + BuildConfig.VERSION_NAME + " (Android updater)");
        if (accept.contains("github")) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        int code = connection.getResponseCode();
        if (code == 404) { connection.disconnect(); throw new IOException("no published release yet"); }
        if (code == 403 || code == 429) { connection.disconnect(); throw new IOException("GitHub rate limit, try again in an hour"); }
        if (code < 200 || code >= 300) { connection.disconnect(); throw new IOException("HTTP " + code); }
        if (!"https".equals(connection.getURL().getProtocol())) { connection.disconnect(); throw new IOException("insecure redirect"); }
        return connection;
    }

    private static byte[] get(String url, int maxBytes, String accept) throws IOException {
        HttpURLConnection connection = open(url, accept);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxBytes) throw new IOException("response too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally { connection.disconnect(); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b));
        return text.toString();
    }
}
