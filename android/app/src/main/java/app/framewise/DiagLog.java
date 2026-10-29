package app.framewise;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * On-device diagnostics for beta testing: a short event log and crash/error list kept in app storage.
 * Nothing is sent automatically; the user shares a report through Android's share sheet.
 * Secrets (API keys, tokens, Wi-Fi passwords) are removed before anything is stored or shared.
 */
final class DiagLog {
    private DiagLog() { }

    static final int MAX_EVENTS = 150, MAX_ERRORS = 40;
    private static final Pattern[] SECRETS = {
        Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}"),
        Pattern.compile("sk-[A-Za-z0-9_\\-]{16,}"),
        Pattern.compile("(gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}"),
        Pattern.compile("(?i)(api[_-]?key|password|passphrase|authorization|x-goog-api-key)([\"'\\s:=]+)(Bearer\\s+)?[^\\s\"',;}]+"),
    };
    private static final Object LOCK = new Object();
    private static final Deque<JSONObject> events = new ArrayDeque<>();
    private static final Deque<JSONObject> errors = new ArrayDeque<>();
    private static File dir;
    private static volatile String extraSecret = "";
    private static volatile String cameraModel = "";
    private static volatile String connection = "none";
    /** fsync only for crash records (the process dies right after); normal events skip it to avoid UI-thread stalls. */
    private static volatile boolean syncNext;

    /** Removes API keys, tokens and password-like values. Also removes the saved camera Wi-Fi password. */
    static String redact(String text) {
        if (text == null) return "";
        String out = text;
        String pw = extraSecret;
        if (pw != null && pw.length() >= 4) out = out.replace(pw, "[removed]");
        for (Pattern p : SECRETS) {
            out = p.pattern().startsWith("(?i)(api") ? p.matcher(out).replaceAll("$1$2[removed]") : p.matcher(out).replaceAll("[removed]");
        }
        return out;
    }

    static void init(Context context, String cameraPassword) {
        synchronized (LOCK) {
            dir = new File(context.getFilesDir(), "diagnostics");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            extraSecret = cameraPassword == null ? "" : cameraPassword;
            events.clear(); errors.clear();
            load("events.json", events, MAX_EVENTS);
            load("errors.json", errors, MAX_ERRORS);
        }
    }

    static void setCameraPassword(String password) { extraSecret = password == null ? "" : password; }
    static void setCamera(String connectionKind, String model) {
        connection = connectionKind == null ? "none" : connectionKind;
        if (model != null && !model.isEmpty()) cameraModel = model;
    }

    static void event(String area, String message) { add(events, MAX_EVENTS, area, message, null, "events.json"); }

    static void error(String area, String message) {
        add(events, MAX_EVENTS, area, "ERROR: " + message, null, "events.json");
        add(errors, MAX_ERRORS, area, message, null, "errors.json");
    }

    /** Called from the uncaught-exception handler: writes synchronously, then lets Android crash normally. */
    static void crash(Thread thread, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String stack = trace.toString();
        if (stack.length() > 6000) stack = stack.substring(0, 6000);
        syncNext = true;
        add(errors, MAX_ERRORS, "crash", error.getClass().getName() + ": " + error.getMessage() + " (thread " + thread.getName() + ")", stack, "errors.json");
        synchronized (LOCK) { flag("pending-crash", true); syncNext = false; }
    }

    static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Handler) return;
        Thread.setDefaultUncaughtExceptionHandler(new Handler(previous));
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;
        Handler(Thread.UncaughtExceptionHandler previous) { this.previous = previous; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try { crash(t, e); } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(t, e);
        }
    }

    /** True once after a crash or new error since the last report; cleared by {@link #markReported()}. */
    static boolean hasUnreported() {
        synchronized (LOCK) {
            if (flagSet("pending-crash")) return true;
            long last = lastReported();
            for (JSONObject e : errors) if (e.optLong("at") > last) return true;
            return false;
        }
    }

    static boolean crashedLastTime() { synchronized (LOCK) { return flagSet("pending-crash"); } }

    static void markReported() {
        synchronized (LOCK) {
            flag("pending-crash", false);
            write("reported.txt", String.valueOf(System.currentTimeMillis()));
        }
    }

    static void clear() {
        synchronized (LOCK) { events.clear(); errors.clear(); persist("events.json", events); persist("errors.json", errors); flag("pending-crash", false); }
    }

    /** Native half of a test report: app, device, camera, events and errors. */
    static JSONObject nativeReport() throws JSONException {
        JSONObject r = new JSONObject();
        r.put("app", new JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE).put("buildType", BuildConfig.BUILD_TYPE));
        r.put("device", new JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT));
        r.put("camera", new JSONObject().put("connection", connection).put("model", cameraModel));
        synchronized (LOCK) {
            r.put("crashedLastTime", flagSet("pending-crash"));
            r.put("errors", new JSONArray(errors));
            r.put("events", new JSONArray(events));
        }
        return r;
    }

    // ------------------------------------------------------------------ storage

    private static void add(Deque<JSONObject> list, int max, String area, String message, String stack, String file) {
        try {
            JSONObject e = new JSONObject().put("at", System.currentTimeMillis()).put("area", area).put("message", redact(message));
            if (stack != null) e.put("stack", redact(stack));
            synchronized (LOCK) {
                list.addLast(e);
                while (list.size() > max) list.removeFirst();
                persist(file, list);
            }
        } catch (JSONException ignored) { }
    }

    /** Normal log writes go to one background thread (in order); crash records are written synchronously. */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FramewiseDiagWriter"); t.setDaemon(true); return t;
    });

    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<String, Long> WRITTEN = new java.util.HashMap<>();

    private static void persist(String name, Deque<JSONObject> list) {
        final String text = new JSONArray(list).toString();
        final long seq = SEQ.incrementAndGet();
        Runnable job = () -> writeIfNewest(name, text, seq);
        if (syncNext) { job.run(); return; }
        try { WRITER.execute(job); } catch (java.util.concurrent.RejectedExecutionException e) { job.run(); }
    }

    /** Never lets an older queued snapshot overwrite a newer one (for example a crash record). */
    static boolean writeIfNewest(String name, String text, long seq) {
        synchronized (WRITE_LOCK) {
            Long last = WRITTEN.get(name);
            if (last != null && last > seq) return false;
            WRITTEN.put(name, seq);
            write(name, text);
            return true;
        }
    }
    private static final Object WRITE_LOCK = new Object();

    private static void load(String name, Deque<JSONObject> into, int max) {
        String text = read(name);
        if (text == null) return;
        try {
            JSONArray a = new JSONArray(text);
            for (int i = Math.max(0, a.length() - max); i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) into.addLast(o); }
        } catch (JSONException corrupt) { write(name, "[]"); }
    }

    private static long lastReported() { try { String t = read("reported.txt"); return t == null ? 0 : Long.parseLong(t.trim()); } catch (NumberFormatException e) { return 0; } }
    private static boolean flagSet(String name) { return dir != null && new File(dir, name).exists(); }
    private static void flag(String name, boolean on) {
        if (dir == null) return;
        File f = new File(dir, name);
        if (on) write(name, "1"); else //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static void write(String name, String text) {
        if (dir == null) return;
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            if (syncNext) out.getFD().sync();
        } catch (IOException ignored) { return; }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(dir, name));
    }

    private static String read(String name) {
        if (dir == null) return null;
        File f = new File(dir, name);
        if (!f.isFile() || f.length() > 2_000_000) return null;
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }
}
