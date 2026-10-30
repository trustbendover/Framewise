package app.framewise;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.PatternMatcher;
import android.os.SystemClock;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Direct Wi-Fi connection to the a6100 using Sony's Camera Remote API (the camera's
 * "Ctrl w/ Smartphone" JSON-RPC service), without Imaging Edge Mobile.
 *
 * <p>Steps: join the camera's DIRECT-xxxx Wi-Fi as an app-only, local network (Android's
 * WifiNetworkSpecifier dialog, or reuse a camera Wi-Fi the phone already joined) → find the
 * camera service by SSDP (fallback 192.168.122.1) → startRecMode → startLiveview → read the
 * liveview JPEG stream and poll getEvent for shutter/aperture/ISO. Internet traffic (AI,
 * updates) keeps using mobile data because the camera network never becomes the default.
 */
final class SonyCameraClient {
    static final String CAMERA_IP = "192.168.122.1";
    private static final String PREFS = "framewise.camera";
    private static final String SSDP_ST = "urn:schemas-sony-com:service:ScalarWebAPI:1";
    private static final int MAX_JPEG = 4 * 1024 * 1024;
    private static final Pattern LOCATION = Pattern.compile("(?im)^LOCATION:\\s*(\\S+)");
    private static final Pattern SERVICE = Pattern.compile(
        "<av:X_ScalarWebAPI_ServiceType>\\s*([^<\\s]+)\\s*</av:X_ScalarWebAPI_ServiceType>\\s*"
        + "<av:X_ScalarWebAPI_ActionList_URL>\\s*([^<\\s]+)\\s*</av:X_ScalarWebAPI_ActionList_URL>");
    private static final Pattern MODEL = Pattern.compile("<friendlyName>([^<]+)</friendlyName>");

    private final Context context;
    private final ConnectivityManager connectivity;
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final AtomicInteger session = new AtomicInteger();
    private final AtomicInteger rpcId = new AtomicInteger(1);

    // Guarded by this.
    private String state = "idle";
    private String step = "Not connected.";
    private String error;
    private String model;
    private byte[] jpeg;
    private long frameTime;
    private int frames;
    private JSONObject settings = new JSONObject();
    private long settingsTime;
    private Network network;
    private ConnectivityManager.NetworkCallback callback;
    private String endpoint;
    private Thread liveview;
    private Thread events;
    private HttpURLConnection liveConnection;
    private boolean ownsRequest;

    SonyCameraClient(Context context) {
        this.context = context.getApplicationContext();
        this.connectivity = this.context.getSystemService(ConnectivityManager.class);
    }

    // ---------------------------------------------------------------- public API

    /** ssid may be empty (any DIRECT- network); password empty means "use the camera Wi-Fi the phone is already on". */
    synchronized void connect(String ssid, String password, boolean remember) {
        disconnectLocked(false);
        final int id = session.incrementAndGet();
        String cleanSsid = ssid == null ? "" : ssid.trim();
        String cleanPassword = password == null ? "" : password.trim();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (remember) prefs.edit().putString("ssid", cleanSsid).putString("password", cleanPassword).apply();
        else prefs.edit().remove("ssid").remove("password").apply();
        error = null; model = null; jpeg = null; frameTime = 0; frames = 0; settings = new JSONObject(); settingsTime = 0;

        Network existing = findExistingCameraNetwork();
        if (existing != null && cleanPassword.isEmpty()) {
            update(id, "connecting", "Using the camera Wi-Fi this phone is already connected to…");
            onNetwork(id, existing);
            return;
        }
        if (cleanPassword.isEmpty()) {
            fail(id, "Enter the camera Wi-Fi password (camera: MENU → Network → Ctrl w/ Smartphone → Connection, then press the Delete/trash button to show SSID and password).");
            return;
        }
        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setWpa2Passphrase(cleanPassword);
        if (cleanSsid.startsWith("DIRECT-") && cleanSsid.length() > 8) spec.setSsid(cleanSsid);
        else spec.setSsidPattern(new PatternMatcher("DIRECT-", PatternMatcher.PATTERN_PREFIX));
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec.build())
            .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network available) { onNetwork(id, available); }
            @Override public void onUnavailable() { fail(id, "Android could not join the camera Wi-Fi (cancelled, wrong password, or the camera's Wi-Fi is off). Check that the camera shows the SSID/QR screen and try again."); }
            @Override public void onLost(Network lost) { if (lost.equals(currentNetwork())) fail(id, "The camera Wi-Fi connection was lost (camera off, out of range, or its Wi-Fi was turned off)."); }
        };
        ownsRequest = true;
        update(id, "connecting", "Step 1 of 4: Android will show a dialog to join the camera Wi-Fi (DIRECT-…:ILCE-6100). Tap the camera, then Connect.");
        try { connectivity.requestNetwork(request, callback, 90_000); }
        catch (RuntimeException failure) {
            ownsRequest = false; callback = null;
            fail(id, "Android refused the Wi-Fi request (" + failure.getClass().getSimpleName() + ").");
        }
    }

    synchronized void disconnect() { disconnectLocked(true); }

    /** Joined (or joining) the camera's own DIRECT- Wi-Fi. */
    synchronized boolean active() { return !"idle".equals(state) && !"error".equals(state); }

    synchronized JSONObject status(long sinceFrame) {
        JSONObject value = new JSONObject();
        try {
            value.put("state", state);
            value.put("step", step);
            value.put("error", error == null ? JSONObject.NULL : error);
            value.put("model", model == null ? JSONObject.NULL : model);
            value.put("frames", frames);
            value.put("timestamp", frameTime);
            value.put("image", jpeg != null && frameTime > sinceFrame
                ? "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP) : JSONObject.NULL);
            value.put("settings", settings);
            value.put("settingsTime", settingsTime);
        } catch (JSONException ignored) { }
        return value;
    }

    synchronized JSONObject saved() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject value = new JSONObject();
        try {
            value.put("ssid", prefs.getString("ssid", ""));
            value.put("hasPassword", !prefs.getString("password", "").isEmpty());
        } catch (JSONException ignored) { }
        return value;
    }

    String savedPassword() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("password", ""); }

    void close() {
        disconnect();
        control.shutdown(); // lets the queued stopLiveview / network release finish
    }

    // ---------------------------------------------------------------- connection steps

    private synchronized Network currentNetwork() { return network; }

    private void onNetwork(int id, Network available) {
        synchronized (this) {
            if (id != session.get()) return;
            network = available;
        }
        update(id, "connecting", "Step 2 of 4: joined the camera Wi-Fi. Looking for the camera's remote service…");
        try {
            control.execute(() -> {
                try { startSession(id, available); }
                catch (IOException | JSONException | RuntimeException failure) {
                    fail(id, describe(failure));
                }
            });
        } catch (RuntimeException ignored) { }
    }

    private void startSession(int id, Network net) throws IOException, JSONException {
        String found = discover(net);
        synchronized (this) { if (id != session.get()) return; endpoint = found; }
        update(id, "connecting", "Step 3 of 4: camera found" + (model == null ? "" : " (" + model + ")") + ". Switching it to remote shooting…");
        List<String> apis = availableApis(net, found);
        if (apis.contains("setCameraFunction")) {
            try { call(net, found, "setCameraFunction", new JSONArray().put("Remote Shooting"), "1.0"); } catch (IOException ignored) { }
        }
        if (apis.contains("startRecMode")) call(net, found, "startRecMode", new JSONArray(), "1.0");
        long until = SystemClock.elapsedRealtime() + 15_000;
        while (!apis.contains("startLiveview") && SystemClock.elapsedRealtime() < until) {
            if (id != session.get()) return;
            sleep(500);
            apis = availableApis(net, found);
        }
        if (!apis.contains("startLiveview")) throw new IOException("the camera did not offer live view (is it in playback or a menu?)");
        update(id, "connecting", "Step 4 of 4: starting the camera's live view…");
        JSONArray result = call(net, found, "startLiveview", new JSONArray(), "1.0");
        String url = result.optString(0, "");
        if (!url.startsWith("http://" + CAMERA_IP)) throw new IOException("the camera returned no usable live-view address");
        Thread reader = new Thread(() -> readLiveview(id, net, url), "FramewiseLiveview");
        Thread poller = new Thread(() -> pollEvents(id, net, found), "FramewiseCameraEvents");
        synchronized (this) {
            if (id != session.get()) return;
            liveview = reader; events = poller;
        }
        reader.start();
        poller.start();
    }

    /** SSDP M-SEARCH on the camera network, then the device description; falls back to well-known URLs. */
    private String discover(Network net) {
        try (DatagramSocket socket = new DatagramSocket()) {
            net.bindSocket(socket);
            socket.setSoTimeout(1500);
            byte[] search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: "
                + SSDP_ST + "\r\nUSER-AGENT: Android/15 Framewise/" + BuildConfig.VERSION_NAME + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int attempt = 0; attempt < 3; attempt++) {
                socket.send(new DatagramPacket(search, search.length, group, 1900));
                byte[] buffer = new byte[2048];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                    Matcher location = LOCATION.matcher(new String(buffer, 0, reply.getLength(), StandardCharsets.US_ASCII));
                    if (location.find()) {
                        String endpoint = fromDescription(net, location.group(1));
                        if (endpoint != null) return endpoint;
                    }
                } catch (SocketTimeoutException ignored) { }
            }
        } catch (IOException | RuntimeException ignored) { }
        for (String candidate : new String[] {"http://" + CAMERA_IP + ":10000/sony/camera", "http://" + CAMERA_IP + ":8080/sony/camera"}) {
            try { call(net, candidate, "getVersions", new JSONArray(), "1.0"); return candidate; }
            catch (IOException | JSONException ignored) { }
        }
        return "http://" + CAMERA_IP + ":10000/sony/camera";
    }

    private String fromDescription(Network net, String location) {
        try {
            if (!location.startsWith("http://" + CAMERA_IP)) return null;
            String xml = new String(httpGet(net, location, 256 * 1024), StandardCharsets.UTF_8);
            Matcher name = MODEL.matcher(xml);
            if (name.find()) synchronized (this) { model = name.group(1).trim(); DiagLog.setCamera("wifi", model); }
            Matcher service = SERVICE.matcher(xml);
            while (service.find()) {
                if ("camera".equals(service.group(1)) && service.group(2).startsWith("http://" + CAMERA_IP)) return service.group(2) + "/camera";
            }
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    private List<String> availableApis(Network net, String url) throws IOException, JSONException {
        JSONArray result = call(net, url, "getAvailableApiList", new JSONArray(), "1.0");
        List<String> names = new ArrayList<>();
        JSONArray list = result.optJSONArray(0);
        if (list != null) for (int i = 0; i < list.length(); i++) names.add(list.optString(i));
        return names;
    }

    // ---------------------------------------------------------------- live view

    private void readLiveview(int id, Network net, String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(net, url, 8000);
            synchronized (this) {
                if (id != session.get()) return;
                liveConnection = connection;
            }
            if (connection.getResponseCode() != 200) throw new IOException("live view HTTP " + connection.getResponseCode());
            DataInputStream in = new DataInputStream(connection.getInputStream());
            update(id, "live", "Live view from the camera. Settings are read from the camera every second.");
            byte[] common = new byte[8];
            byte[] payloadHeader = new byte[128];
            while (id == session.get() && !Thread.currentThread().isInterrupted()) {
                if (!syncToStart(in, common)) throw new EOFException("stream ended");
                int type = common[1] & 0xff;
                in.readFully(payloadHeader);
                if ((payloadHeader[0] & 0xff) != 0x24 || (payloadHeader[1] & 0xff) != 0x35
                        || (payloadHeader[2] & 0xff) != 0x68 || (payloadHeader[3] & 0xff) != 0x79) continue;
                int size = ((payloadHeader[4] & 0xff) << 16) | ((payloadHeader[5] & 0xff) << 8) | (payloadHeader[6] & 0xff);
                int padding = payloadHeader[7] & 0xff;
                if (size < 0 || size > MAX_JPEG) throw new IOException("invalid live-view frame size");
                byte[] data = new byte[size];
                in.readFully(data);
                skipFully(in, padding);
                if (type == 0x01 && size > 2 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xD8) {
                    synchronized (this) {
                        if (id != session.get()) return;
                        jpeg = data;
                        frameTime = System.currentTimeMillis();
                        frames++;
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (id == session.get()) fail(id, "Live view stopped: " + describe(failure));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Reads the 8-byte common header, resynchronising on the 0xFF start byte if needed. */
    static boolean syncToStart(InputStream in, byte[] common) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            if (b != 0xFF) continue;
            common[0] = (byte) 0xFF;
            int read = 1;
            while (read < 8) {
                int n = in.read(common, read, 8 - read);
                if (n < 0) return false;
                read += n;
            }
            int type = common[1] & 0xff;
            if (type == 0x01 || type == 0x02) return true;
        }
        return false;
    }

    private static void skipFully(InputStream in, int count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) { if (in.read() < 0) throw new EOFException(); skipped = 1; }
            count -= (int) skipped;
        }
    }

    // ---------------------------------------------------------------- camera settings

    private void pollEvents(int id, Network net, String url) {
        int failures = 0;
        while (id == session.get() && !Thread.currentThread().isInterrupted()) {
            try {
                JSONArray result = call(net, url, "getEvent", new JSONArray().put(false), "1.0");
                JSONObject parsed = parseEvent(result);
                synchronized (this) {
                    if (id != session.get()) return;
                    if (parsed.length() > 0) { settings = parsed; settingsTime = System.currentTimeMillis(); }
                }
                failures = 0;
            } catch (IOException | JSONException failure) {
                if (++failures >= 5 && id == session.get()) { fail(id, "The camera stopped answering: " + describe(failure)); return; }
            }
            sleep(1000);
        }
    }

    /** Extracts exposure values from a getEvent result (an array of typed objects or arrays of them). */
    static JSONObject parseEvent(JSONArray result) throws JSONException {
        JSONObject out = new JSONObject();
        for (int i = 0; i < result.length(); i++) {
            Object item = result.opt(i);
            if (item instanceof JSONArray) {
                JSONArray nested = (JSONArray) item;
                for (int j = 0; j < nested.length(); j++) {
                    JSONObject entry = nested.optJSONObject(j);
                    if (entry != null) readEntry(entry, out);
                }
            } else if (item instanceof JSONObject) {
                readEntry((JSONObject) item, out);
            }
        }
        return out;
    }

    private static void readEntry(JSONObject entry, JSONObject out) throws JSONException {
        switch (entry.optString("type")) {
            case "shutterSpeed": {
                String text = entry.optString("currentShutterSpeed", "");
                out.put("shutterText", text);
                double seconds = parseShutter(text);
                if (seconds > 0) out.put("shutter", seconds);
                break;
            }
            case "fNumber": {
                String text = entry.optString("currentFNumber", "");
                out.put("apertureText", text);
                try { double f = Double.parseDouble(text); if (f > 0) out.put("aperture", f); } catch (NumberFormatException ignored) { }
                break;
            }
            case "isoSpeedRate": {
                String text = entry.optString("currentIsoSpeedRate", "");
                out.put("isoText", text);
                try { int iso = Integer.parseInt(text.replaceAll("[^0-9]", "")); if (iso > 0 && text.matches("\\d+")) out.put("iso", iso); } catch (NumberFormatException ignored) { }
                break;
            }
            case "exposureMode":
                out.put("mode", entry.optString("currentExposureMode", ""));
                break;
            case "exposureCompensation": {
                int index = entry.optInt("currentExposureCompensation", 0);
                int step = entry.optInt("stepIndexOfExposureCompensation", 1);
                out.put("exposureCompensation", step == 2 ? index / 2.0 : index / 3.0);
                break;
            }
            case "cameraStatus":
                out.put("cameraStatus", entry.optString("cameraStatus", ""));
                break;
            default: break;
        }
    }

    /** "1/250" → 0.004, "0.6\"" → 0.6, "2\"" → 2, "BULB" → -1. */
    static double parseShutter(String text) {
        if (text == null) return -1;
        String clean = text.trim().replace("\"", "").replace("″", "");
        try {
            int slash = clean.indexOf('/');
            if (slash > 0) {
                double num = Double.parseDouble(clean.substring(0, slash)), den = Double.parseDouble(clean.substring(slash + 1));
                return den > 0 ? num / den : -1;
            }
            double value = Double.parseDouble(clean);
            return value > 0 ? value : -1;
        } catch (NumberFormatException failure) { return -1; }
    }

    // ---------------------------------------------------------------- JSON-RPC / HTTP

    /** Opens an HTTP connection on the camera network only; the network security config allows cleartext just for 192.168.122.1. */
    private static HttpURLConnection open(Network net, String url, int timeoutMs) throws IOException {
        URL target = new URL(url);
        if (!"http".equals(target.getProtocol()) || !CAMERA_IP.equals(target.getHost())) throw new IOException("not the camera address: " + target.getHost());
        HttpURLConnection connection = (HttpURLConnection) net.openConnection(target);
        connection.setConnectTimeout(Math.min(timeoutMs, 6000));
        connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "Framewise/" + BuildConfig.VERSION_NAME);
        return connection;
    }

    private JSONArray call(Network net, String url, String method, JSONArray params, String version) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("method", method);
        body.put("params", params);
        body.put("id", rpcId.getAndIncrement());
        body.put("version", version);
        HttpURLConnection connection = open(net, url, method.equals("getEvent") ? 8000 : 10000);
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            if (connection.getResponseCode() != 200) throw new IOException(method + " HTTP " + connection.getResponseCode());
            JSONObject reply = new JSONObject(new String(readAll(connection.getInputStream(), 512 * 1024), StandardCharsets.UTF_8));
            JSONArray err = reply.optJSONArray("error");
            if (err != null) throw new IOException(method + " error " + err.optInt(0) + " " + err.optString(1, ""));
            JSONArray result = reply.optJSONArray("result");
            if (result == null) result = reply.optJSONArray("results");
            return result == null ? new JSONArray() : result;
        } finally { connection.disconnect(); }
    }

    private static byte[] httpGet(Network net, String url, int max) throws IOException {
        HttpURLConnection connection = open(net, url, 5000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            return readAll(connection.getInputStream(), max);
        } finally { connection.disconnect(); }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (out.size() + n > max) throw new IOException("response too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- helpers

    private Network findExistingCameraNetwork() {
        try {
            for (Network candidate : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                android.net.LinkProperties link = connectivity.getLinkProperties(candidate);
                if (link == null) continue;
                for (android.net.LinkAddress address : link.getLinkAddresses()) {
                    String host = address.getAddress().getHostAddress();
                    if (host != null && host.startsWith("192.168.122.")) return candidate;
                }
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private void disconnectLocked(boolean announce) {
        session.incrementAndGet();
        final Network net = network;
        final String url = endpoint;
        final ConnectivityManager.NetworkCallback owned = ownsRequest ? callback : null;
        callback = null; ownsRequest = false;
        Runnable release = () -> {
            if (owned != null) { try { connectivity.unregisterNetworkCallback(owned); } catch (RuntimeException ignored) { } }
        };
        if (net != null && url != null) {
            try {
                control.execute(() -> {
                    try { call(net, url, "stopLiveview", new JSONArray(), "1.0"); } catch (IOException | JSONException | RuntimeException ignored) { }
                    release.run();
                });
            } catch (RuntimeException ignored) { release.run(); }
        } else release.run();
        // Interrupting does not unblock a socket read: close the stream so the camera frees its single live-view slot now.
        final HttpURLConnection live = liveConnection;
        liveConnection = null;
        if (live != null) new Thread(live::disconnect, "FramewiseLiveviewClose").start();
        if (liveview != null) liveview.interrupt();
        if (events != null) events.interrupt();
        liveview = null; events = null; network = null; endpoint = null;
        jpeg = null; frameTime = 0;
        if (announce) { state = "idle"; step = "Disconnected from the camera."; error = null; }
    }

    private synchronized void update(int id, String newState, String message) {
        if (id != session.get()) return;
        if (!message.equals(step)) DiagLog.event("wifi", newState + ": " + message);
        state = newState; step = message; error = null;
    }

    private void fail(int id, String message) {
        synchronized (this) {
            if (id != session.get()) return;
            disconnectLocked(false);
            state = "error"; step = message; error = message;
            DiagLog.error("wifi", message);
        }
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        if (failure instanceof SocketTimeoutException) return "the camera did not respond in time. Keep it close and on the remote screen.";
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message.toLowerCase(Locale.ROOT).startsWith("http") ? "camera " + message : message;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
