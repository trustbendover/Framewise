package app.framewise;

import android.os.SystemClock;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Vendor PTP logic shared by every transport (USB bulk and PTP/IP over Wi-Fi). EXPERIMENTAL. */
final class PtpCameraSession {
    private PtpCameraSession() { }

    private static final int RC_SESSION_ALREADY_OPEN = 0x201E;

    interface Transport extends AutoCloseable {
        Result transact(int code, byte[] dataOut, long... params) throws IOException;
        @Override void close();
    }

    static final class Result {
        int code; byte[] data = new byte[0];
        Result(int code, byte[] data) { this.code = code; this.data = data == null ? new byte[0] : data; }
    }

    interface Sink {
        boolean current(int gen);
        void model(int gen, String name);
        void frame(int gen, byte[] jpeg);
        void settings(int gen, JSONObject settings);
        void hint(int gen, String message);
    }

    /** Opens the session (skip when PTP/IP already did it) and returns the model name. */
    static String open(Transport t, boolean openSession, String fallback) throws IOException {
        if (openSession) {
            Result open = t.transact(PtpCodec.OC_OPEN_SESSION, null, 1);
            if (open.code != PtpCodec.RC_OK && open.code != RC_SESSION_ALREADY_OPEN) throw new IOException(String.format(Locale.ROOT, "OpenSession failed (0x%04X)", open.code));
        }
        String name = PtpCodec.deviceInfoModel(t.transact(PtpCodec.OC_GET_DEVICE_INFO, null).data);
        return name == null ? fallback : name;
    }

    /** Adapts the delay between frames to how long a frame takes (slow links get fewer requests). */
    static long frameDelay(long lastTransactMs, boolean gotFrame) {
        if (!gotFrame) return 200;
        return Math.max(40, Math.min(500, lastTransactMs / 2));
    }

    static void runSony(int gen, Transport t, Sink sink) throws IOException {
        for (int phase : new int[] {1, 2}) check(t.transact(PtpCodec.OC_SONY_SDIO_CONNECT, null, phase, 0, 0), "Sony SDIO connect " + phase);
        check(t.transact(PtpCodec.OC_SONY_GET_EXT_DEVICE_INFO, null, 0xC8), "Sony extended device info");
        check(t.transact(PtpCodec.OC_SONY_SDIO_CONNECT, null, 3, 0, 0), "Sony SDIO connect 3");
        long lastProps = 0;
        int empty = 0;
        while (sink.current(gen)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastProps > 1000) {
                lastProps = now;
                Result props = t.transact(PtpCodec.OC_SONY_GET_ALL_PROPS, null);
                if (props.code == PtpCodec.RC_OK) {
                    try { sink.settings(gen, PtpCodec.sonySettings(PtpCodec.parseSonyProps(props.data))); }
                    catch (RuntimeException parse) { DiagLog.error("ptp", "Sony props parse: " + parse.getMessage() + " (" + props.data.length + " bytes)"); }
                }
            }
            long start = SystemClock.elapsedRealtime();
            Result lv = t.transact(PtpCodec.OC_GET_OBJECT, null, PtpCodec.SONY_LIVEVIEW_HANDLE);
            byte[] frame = lv.code == PtpCodec.RC_OK ? PtpCodec.sonyLiveviewJpeg(lv.data) : null;
            if (frame != null) { empty = 0; sink.frame(gen, frame); }
            else if (++empty == 50) {
                DiagLog.error("ptp", String.format(Locale.ROOT, "Sony live view: no frames (last rc 0x%04X, %d bytes)", lv.code, lv.data.length));
                sink.hint(gen, "Connected, but the camera sends no live view. On the a6100 set MENU → Network → PC Remote Function → On, and close other camera apps.");
            }
            sleep(frameDelay(SystemClock.elapsedRealtime() - start, frame != null));
        }
    }

    static void runCanon(int gen, Transport t, Sink sink) throws IOException {
        check(t.transact(PtpCodec.OC_CANON_SET_REMOTE_MODE, null, 1), "Canon remote mode");
        check(t.transact(PtpCodec.OC_CANON_SET_EVENT_MODE, null, 1), "Canon event mode");
        Map<Integer, Long> props = new HashMap<>();
        Map<Integer, String> strings = new HashMap<>();
        for (int i = 0; i < 3; i++) pollCanon(gen, t, sink, props, strings);
        Result evf = t.transact(PtpCodec.OC_CANON_SET_PROP, PtpCodec.canonSetPropPayload(PtpCodec.CANON_EVF_OUTPUT, PtpCodec.CANON_EVF_TO_PC));
        if (evf.code != PtpCodec.RC_OK) DiagLog.error("ptp", String.format(Locale.ROOT, "Canon EVF output rc 0x%04X", evf.code));
        long lastEvents = 0;
        int empty = 0;
        while (sink.current(gen)) {
            long now = SystemClock.elapsedRealtime();
            // GetEvent doubles as the keep-alive: Canon drops idle remote sessions.
            if (now - lastEvents > 500) { lastEvents = now; pollCanon(gen, t, sink, props, strings); }
            long start = SystemClock.elapsedRealtime();
            Result lv = t.transact(PtpCodec.OC_CANON_GET_VIEWFINDER, null, 0x00100000, 0, 0);
            byte[] frame = lv.code == PtpCodec.RC_OK ? PtpCodec.canonViewfinderJpeg(lv.data) : null;
            if (frame != null) { empty = 0; sink.frame(gen, frame); }
            else if (++empty == 50) {
                DiagLog.error("ptp", String.format(Locale.ROOT, "Canon live view: no frames (last rc 0x%04X, %d bytes)", lv.code, lv.data.length));
                sink.hint(gen, "Connected, but the camera sends no live view. Put the 2000D in P, Tv, Av or M and make sure it isn't showing a menu.");
            }
            sleep(frameDelay(SystemClock.elapsedRealtime() - start, frame != null));
        }
    }

    private static void pollCanon(int gen, Transport t, Sink sink, Map<Integer, Long> props, Map<Integer, String> strings) throws IOException {
        Result ev = t.transact(PtpCodec.OC_CANON_GET_EVENT, null);
        if (ev.code != PtpCodec.RC_OK) return;
        props.putAll(PtpCodec.parseCanonEvents(ev.data, strings));
        sink.settings(gen, PtpCodec.canonSettings(props, strings));
    }

    static void check(Result r, String what) throws IOException {
        if (r.code != PtpCodec.RC_OK) throw new IOException(String.format(Locale.ROOT, "%s failed (camera answered 0x%04X)", what, r.code));
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
