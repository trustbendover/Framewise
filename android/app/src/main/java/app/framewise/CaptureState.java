package app.framewise;

import org.json.JSONException;
import org.json.JSONObject;

/** Only the newest frame is held in memory. No recording or image files are created. */
final class CaptureState {
    private static String image;
    private static long timestamp;
    private static String state = "idle";
    private static String error;
    private static boolean visible = true;

    private CaptureState() {}

    static synchronized boolean isCapturing() { return "capturing".equals(state); }

    static synchronized void started() {
        image = null;
        timestamp = 0;
        state = "capturing";
        error = null;
        visible = true;
    }

    static synchronized void frame(String dataUrl) {
        if (!isCapturing()) return;
        image = dataUrl;
        timestamp = System.currentTimeMillis();
    }

    static synchronized void visibility(boolean value) { visible = value; }

    static synchronized void stopped(String message) {
        image = null;
        timestamp = 0;
        state = "stopped";
        error = message;
        visible = false;
    }

    static synchronized String json() {
        JSONObject value = new JSONObject();
        try {
            value.put("image", image == null ? JSONObject.NULL : image);
            value.put("timestamp", timestamp);
            value.put("state", state);
            value.put("error", error == null ? JSONObject.NULL : error);
            value.put("visible", visible);
        } catch (JSONException ignored) { /* All values are finite primitive values. */ }
        return value.toString();
    }
}
