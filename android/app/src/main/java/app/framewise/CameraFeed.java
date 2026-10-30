package app.framewise;

import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared, thread-safe snapshot of a camera connection (state, newest JPEG, settings) read by the WebView bridge. */
abstract class CameraFeed implements PtpCameraSession.Sink {
    protected String state = "idle", step = "Not connected.", error, model, vendor;
    protected byte[] jpeg;
    protected long frameTime, settingsTime;
    protected int frames;
    protected JSONObject settings = new JSONObject();
    protected int generation;
    private final String kind;

    CameraFeed(String kind) { this.kind = kind; }

    /** Connecting or connected (not idle and not failed). */
    synchronized boolean active() { return !"idle".equals(state) && !"error".equals(state); }

    synchronized JSONObject status(long sinceFrame) {
        JSONObject v = new JSONObject();
        try {
            v.put("kind", kind).put("state", state).put("step", step).put("error", error == null ? JSONObject.NULL : error)
                .put("model", model == null ? JSONObject.NULL : model).put("vendor", vendor == null ? JSONObject.NULL : vendor)
                .put("frames", frames).put("timestamp", frameTime)
                .put("image", jpeg != null && frameTime > sinceFrame ? "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP) : JSONObject.NULL)
                .put("settings", settings).put("settingsTime", settingsTime);
        } catch (JSONException ignored) { }
        return v;
    }

    protected synchronized void setError(String message) {
        state = "error"; step = message; error = message;
        DiagLog.error(kind, message);
    }

    @Override public synchronized boolean current(int gen) { return gen == generation && !Thread.currentThread().isInterrupted(); }

    @Override public synchronized void model(int gen, String name) {
        if (gen != generation) return;
        model = name; step = "Connected to " + name + ". Starting live view…";
        DiagLog.setCamera(kind, name);
        DiagLog.event(kind, "session open: " + name);
    }

    @Override public synchronized void frame(int gen, byte[] frame) {
        if (gen != generation) return;
        jpeg = frame; frameTime = System.currentTimeMillis(); frames++;
        if (!"live".equals(state)) { state = "live"; step = liveMessage(); error = null; DiagLog.event(kind, "first live-view frame (" + frame.length + " bytes)"); }
    }

    @Override public synchronized void settings(int gen, JSONObject parsed) {
        if (gen != generation || parsed.length() == 0) return;
        settings = parsed; settingsTime = System.currentTimeMillis();
    }

    @Override public synchronized void hint(int gen, String message) { if (gen == generation) step = message; }

    protected abstract String liveMessage();
}
