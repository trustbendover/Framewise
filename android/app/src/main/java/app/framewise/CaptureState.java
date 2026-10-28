package app.framewise;

import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shared, in-memory capture status. Only the newest usable frame (plus one fallback) is
 * held in memory. No recording or image files are created.
 *
 * <p>The state machine is: idle -> requesting (Android prompt open) -> starting (service and
 * projection being created) -> capturing -> stopped. Every transition carries a human
 * readable {@code step} so the UI can say exactly where a connection attempt got stuck.
 */
final class CaptureState {
    /** Frames rendered within this window before Framewise returns to the foreground are discarded:
     *  they usually show the app-switch animation or Framewise itself rather than Sony's preview. */
    private static final long RESUME_GUARD_MS = 700;
    /** Consecutive all-black frames before the UI warns about protected (FLAG_SECURE) content. */
    static final int BLACK_FRAME_LIMIT = 3;

    private static String image;
    private static long timestamp;
    private static long frameElapsed;
    private static String previousImage;
    private static long previousTimestamp;
    private static String state = "idle";
    private static String step = "Not sharing.";
    private static String error;
    private static boolean visible = true;
    private static boolean hostForeground = true;
    private static boolean hostMultiWindow = false;
    private static long startedElapsed;
    private static int frames;
    private static int blackFrames;
    private static boolean overlay;

    private CaptureState() {}

    static synchronized boolean isCapturing() { return "capturing".equals(state) || "starting".equals(state); }
    static synchronized boolean isActive() { return isCapturing() || "requesting".equals(state); }

    static synchronized void requesting(String message) {
        state = "requesting";
        step = message;
        error = null;
    }

    static synchronized void starting(String message) {
        state = "starting";
        step = message;
        error = null;
        image = null; previousImage = null;
        timestamp = 0; previousTimestamp = 0;
        frames = 0; blackFrames = 0;
        visible = true;
        startedElapsed = SystemClock.elapsedRealtime();
    }

    static synchronized void started(String message) {
        state = "capturing";
        step = message;
        error = null;
    }

    static synchronized void step(String message) { if (!"idle".equals(state) && !"stopped".equals(state)) step = message; }

    static synchronized long sinceStartMs() { return startedElapsed == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - startedElapsed; }
    static synchronized int frameCount() { return frames; }

    /** Returns false when the frame was not kept (Framewise itself is on screen). */
    static synchronized boolean frame(String dataUrl, boolean black) {
        if (!isCapturing()) return false;
        if (hostForeground && !hostMultiWindow) return false;
        frames++;
        blackFrames = black ? blackFrames + 1 : 0;
        if (frames == 1) step = "Receiving Sony's screen. Return to Framewise any time; the last Sony frame is kept.";
        if (black && blackFrames >= BLACK_FRAME_LIMIT) {
            step = "Frames are completely black. Imaging Edge (or the camera screen) may be protected from capture, or the camera preview is not running.";
        } else if (!black && blackFrames == 0 && frames > 1 && step.startsWith("Frames are completely black")) {
            step = "Receiving Sony's screen again.";
        }
        previousImage = image; previousTimestamp = timestamp;
        image = dataUrl;
        timestamp = System.currentTimeMillis();
        frameElapsed = SystemClock.elapsedRealtime();
        return true;
    }

    static synchronized void hostResumed(boolean multiWindow) {
        hostForeground = true;
        hostMultiWindow = multiWindow;
        // Drop the frame grabbed during the switch back to Framewise, keep the one before it.
        if (image != null && SystemClock.elapsedRealtime() - frameElapsed < RESUME_GUARD_MS && previousImage != null) {
            image = previousImage; timestamp = previousTimestamp;
        }
    }

    static synchronized void hostPaused() { hostForeground = false; }
    static synchronized void hostMultiWindow(boolean multiWindow) { hostMultiWindow = multiWindow; }

    static synchronized void visibility(boolean value) {
        visible = value;
        if (isCapturing()) step = value ? "The shared app is visible again." : "The shared app is hidden, so Android pauses its frames. The last frame is kept.";
    }

    static synchronized void overlay(boolean shown) { overlay = shown; }

    static synchronized void stopped(String message) {
        image = null; previousImage = null;
        timestamp = 0; previousTimestamp = 0;
        state = "stopped";
        error = message;
        step = message == null ? "Sharing stopped." : message;
        visible = false;
        startedElapsed = 0;
        overlay = false;
    }

    static synchronized String json() {
        JSONObject value = new JSONObject();
        try {
            value.put("image", image == null ? JSONObject.NULL : image);
            value.put("timestamp", timestamp);
            value.put("state", state);
            value.put("step", step);
            value.put("error", error == null ? JSONObject.NULL : error);
            value.put("visible", visible);
            value.put("frames", frames);
            value.put("black", blackFrames >= BLACK_FRAME_LIMIT);
            value.put("overlay", overlay);
        } catch (JSONException ignored) { /* All values are finite primitive values. */ }
        return value.toString();
    }
}
