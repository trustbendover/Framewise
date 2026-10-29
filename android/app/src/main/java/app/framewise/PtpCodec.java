package app.framewise;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure PTP (ISO 15740) packet building and parsing for USB cameras, plus the Sony and Canon EOS
 * vendor extensions Framewise uses. No Android APIs here, so it is unit-tested on the JVM.
 * Written from the public PTP specification and protocol notes; no third-party code is included.
 */
final class PtpCodec {
    private PtpCodec() { }

    static final int TYPE_COMMAND = 1, TYPE_DATA = 2, TYPE_RESPONSE = 3, TYPE_EVENT = 4;
    static final int RC_OK = 0x2001, RC_DEVICE_BUSY = 0x2019, RC_CANON_NOT_READY = 0xA102;
    static final int OC_GET_DEVICE_INFO = 0x1001, OC_OPEN_SESSION = 0x1002, OC_CLOSE_SESSION = 0x1003, OC_GET_OBJECT = 0x1009;
    // Sony
    static final int SONY_VENDOR = 0x054C;
    static final int OC_SONY_SDIO_CONNECT = 0x9201, OC_SONY_GET_EXT_DEVICE_INFO = 0x9202, OC_SONY_GET_ALL_PROPS = 0x9209;
    static final long SONY_LIVEVIEW_HANDLE = 0xFFFFC002L;
    static final int SONY_FNUMBER = 0x5007, SONY_WB = 0x5005, SONY_MODE = 0x500E, SONY_EC = 0x5010, SONY_SHUTTER = 0xD20D, SONY_ISO = 0xD21E;
    // Canon EOS
    static final int CANON_VENDOR = 0x04A9;
    static final int OC_CANON_SET_PROP = 0x9110, OC_CANON_SET_REMOTE_MODE = 0x9114, OC_CANON_SET_EVENT_MODE = 0x9115,
        OC_CANON_GET_EVENT = 0x9116, OC_CANON_GET_VIEWFINDER = 0x9153;
    static final int CANON_EVT_PROP_CHANGED = 0xC189;
    static final int CANON_APERTURE = 0xD101, CANON_SHUTTER = 0xD102, CANON_ISO = 0xD103, CANON_EC = 0xD104,
        CANON_MODE = 0xD105, CANON_WB = 0xD109, CANON_EVF_OUTPUT = 0xD1B0;
    static final int CANON_EVF_TO_PC = 2;

    // ------------------------------------------------------------------ containers

    static byte[] command(int code, long transactionId, long... params) {
        ByteBuffer b = le(12 + 4 * params.length);
        b.putInt(12 + 4 * params.length).putShort((short) TYPE_COMMAND).putShort((short) code).putInt((int) transactionId);
        for (long p : params) b.putInt((int) p);
        return b.array();
    }

    static byte[] data(int code, long transactionId, byte[] payload) {
        ByteBuffer b = le(12 + payload.length);
        b.putInt(12 + payload.length).putShort((short) TYPE_DATA).putShort((short) code).putInt((int) transactionId).put(payload);
        return b.array();
    }

    /** Parsed 12-byte container header. */
    static final class Header {
        final long length; final int type; final int code; final long transactionId;
        Header(long length, int type, int code, long transactionId) { this.length = length; this.type = type; this.code = code; this.transactionId = transactionId; }
    }

    static Header header(byte[] bytes, int offset, int count) {
        if (count < 12) throw new IllegalArgumentException("PTP packet shorter than its header (" + count + " bytes)");
        ByteBuffer b = ByteBuffer.wrap(bytes, offset, count).order(ByteOrder.LITTLE_ENDIAN);
        long length = b.getInt() & 0xFFFFFFFFL;
        int type = b.getShort() & 0xFFFF, code = b.getShort() & 0xFFFF;
        long tid = b.getInt() & 0xFFFFFFFFL;
        if (length < 12 || type < TYPE_COMMAND || type > TYPE_EVENT) throw new IllegalArgumentException("not a PTP container (length " + length + ", type " + type + ")");
        return new Header(length, type, code, tid);
    }

    /** Accumulates bulk-IN reads into the data payload and the response container of one transaction. */
    static final class Reassembler {
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        private long dataRemaining = -1;
        int responseCode = -1;
        long[] responseParams = new long[0];
        private final int maxBytes;
        Reassembler(int maxBytes) { this.maxBytes = maxBytes; }

        /** Feeds one bulk read; returns true when the response container has arrived. */
        boolean feed(byte[] buf, int count) {
            int pos = 0;
            while (pos < count) {
                if (dataRemaining > 0) {
                    int take = (int) Math.min(dataRemaining, count - pos);
                    write(buf, pos, take);
                    dataRemaining -= take; pos += take;
                    continue;
                }
                Header h = header(buf, pos, count - pos);
                if (h.type == TYPE_DATA) {
                    long body = h.length - 12;
                    if (body > maxBytes) throw new IllegalArgumentException("camera data too large (" + body + " bytes)");
                    pos += 12;
                    int take = (int) Math.min(body, count - pos);
                    write(buf, pos, take);
                    pos += take; dataRemaining = body - take;
                } else if (h.type == TYPE_RESPONSE) {
                    responseCode = h.code;
                    int n = (int) Math.max(0, Math.min(5, (Math.min(h.length, count - pos) - 12) / 4));
                    responseParams = new long[n];
                    ByteBuffer b = ByteBuffer.wrap(buf, pos + 12, n * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < n; i++) responseParams[i] = b.getInt() & 0xFFFFFFFFL;
                    return true;
                } else {
                    pos += (int) Math.min(h.length, count - pos); // ignore stray event containers
                }
            }
            return false;
        }

        private void write(byte[] buf, int off, int len) {
            if (payload.size() + len > maxBytes) throw new IllegalArgumentException("camera data too large");
            payload.write(buf, off, len);
        }
        byte[] data() { return payload.toByteArray(); }
        boolean needsMoreData() { return dataRemaining > 0; }
    }

    // ------------------------------------------------------------------ PTP DeviceInfo

    /** Returns the camera model string from a GetDeviceInfo dataset, or null. */
    static String deviceInfoModel(byte[] d) {
        try {
            Reader r = new Reader(d);
            r.u16(); r.u32(); r.u16(); r.string(); r.u16();
            for (int i = 0; i < 5; i++) r.skipArray16(); // operations, events, props, capture formats, image formats
            r.string(); // manufacturer
            String model = r.string();
            return model == null || model.isEmpty() ? null : model.trim();
        } catch (RuntimeException e) { return null; }
    }

    // ------------------------------------------------------------------ Sony

    /** Parses SDIO GetAllExtDevicePropInfo into prop code → current value. Strings become null entries. */
    static Map<Integer, Long> parseSonyProps(byte[] d) {
        try { return parseSonyProps(d, true); }
        catch (RuntimeException withEnabledByte) { return parseSonyProps(d, false); }
    }

    private static Map<Integer, Long> parseSonyProps(byte[] d, boolean hasEnabledByte) {
        Reader r = new Reader(d);
        long count = r.u64();
        if (count < 0 || count > 2000) throw new IllegalArgumentException("bad Sony property count " + count);
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (long i = 0; i < count; i++) {
            int code = r.u16(), type = r.u16();
            r.u8(); // get/set
            if (hasEnabledByte) r.u8();
            r.value(type); // factory default
            Long current = r.value(type);
            int form = r.u8();
            if (form == 1) { r.value(type); r.value(type); r.value(type); }
            else if (form == 2) { int n = r.u16(); for (int k = 0; k < n; k++) r.value(type); }
            else if (form != 0) throw new IllegalArgumentException("bad Sony form flag " + form);
            out.put(code, current);
        }
        if (r.remaining() > 16) throw new IllegalArgumentException("unparsed Sony property data");
        return out;
    }

    static JSONObject sonySettings(Map<Integer, Long> p) {
        JSONObject out = new JSONObject();
        try {
            Long f = p.get(SONY_FNUMBER);
            if (f != null && f > 0 && f < 0xFFFF) { double v = f / 100.0; out.put("aperture", v); out.put("apertureText", trim(v)); }
            Long s = p.get(SONY_SHUTTER);
            if (s != null) {
                long num = (s >>> 16) & 0xFFFF, den = s & 0xFFFF;
                if (s == 0) out.put("shutterText", "BULB");
                else if (num > 0 && den > 0) { out.put("shutter", (double) num / den); out.put("shutterText", den == 1 ? num + "\"" : num == 1 ? "1/" + den : trim((double) num / den) + "\""); }
            }
            Long iso = p.get(SONY_ISO);
            if (iso != null) {
                long v = iso & 0xFFFFFF;
                if (v == 0xFFFFFF || v == 0) out.put("isoText", "AUTO");
                else { out.put("iso", v); out.put("isoText", String.valueOf(v)); }
            }
            Long ec = p.get(SONY_EC);
            if (ec != null) out.put("exposureCompensation", Math.round((short) (long) ec / 1000.0 * 3) / 3.0);
            Long mode = p.get(SONY_MODE);
            if (mode != null) out.put("mode", sonyMode(mode));
            Long wb = p.get(SONY_WB);
            if (wb != null) out.put("whiteBalance", whiteBalance(wb));
        } catch (JSONException ignored) { }
        return out;
    }

    static String sonyMode(long m) {
        switch ((int) m) {
            case 1: return "Manual Exposure";
            case 2: return "Program Auto";
            case 3: return "Aperture Priority";
            case 4: return "Shutter Priority";
            case 0x8000: case 0x8001: return "Intelligent Auto";
            default: return String.format(Locale.ROOT, "Mode 0x%04X", m);
        }
    }

    static String whiteBalance(long wb) {
        switch ((int) wb) {
            case 2: return "Auto";
            case 4: return "Daylight";
            case 6: return "Tungsten";
            case 7: return "Flash";
            case 0x8011: return "Shade";
            case 0x8010: return "Cloudy";
            default: return String.format(Locale.ROOT, "0x%04X", wb);
        }
    }

    /** Sony live-view object: either a bare JPEG or a header with [offset, size] in front of it. */
    static byte[] sonyLiveviewJpeg(byte[] d) {
        if (d == null || d.length < 4) return null;
        if (isJpegStart(d, 0)) return d;
        if (d.length >= 8) {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            long offset = b.getInt(0) & 0xFFFFFFFFL, size = b.getInt(4) & 0xFFFFFFFFL;
            if (offset >= 8 && size > 2 && offset + size <= d.length && isJpegStart(d, (int) offset)) {
                byte[] out = new byte[(int) size];
                System.arraycopy(d, (int) offset, out, 0, (int) size);
                return out;
            }
        }
        return scanJpeg(d);
    }

    // ------------------------------------------------------------------ Canon EOS

    /** Canon GetEvent (0x9116) records: [u32 size][u32 type][payload]; PropValueChanged carries [u32 prop][value]. */
    static Map<Integer, Long> parseCanonEvents(byte[] d, Map<Integer, String> strings) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        int pos = 0;
        while (pos + 8 <= d.length) {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            long size = b.getInt(pos) & 0xFFFFFFFFL;
            int type = b.getInt(pos + 4);
            if (size < 8 || pos + size > d.length) break;
            if (type == 0 && size == 8) break; // terminator
            if (type == CANON_EVT_PROP_CHANGED && size >= 16) {
                int prop = b.getInt(pos + 8);
                long valueLen = size - 12;
                if (valueLen == 4) out.put(prop, b.getInt(pos + 12) & 0xFFFFFFFFL);
                else if (strings != null && valueLen > 4) {
                    String text = new String(d, pos + 12, (int) valueLen, StandardCharsets.US_ASCII);
                    int nul = text.indexOf('\0');
                    text = (nul >= 0 ? text.substring(0, nul) : text).trim();
                    if (!text.isEmpty() && text.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) strings.put(prop, text);
                    else out.put(prop, b.getInt(pos + 12) & 0xFFFFFFFFL);
                }
            }
            pos += (int) size;
        }
        return out;
    }

    /** Canon EOS aperture code: 8 units per stop, 0x08 = f/1.0. */
    static double canonAperture(long code) {
        if (code <= 0 || code == 0xFF || code == 0xFFFFFFFFL) return -1;
        return nominalAperture(Math.pow(2, (code - 8) / 16.0));
    }

    /** Canon EOS shutter code: 8 units per stop, 0x38 = 1 s, 0x70 = 1/125. 0x0C = bulb. */
    static double canonShutter(long code) {
        if (code <= 0x0C || code == 0xFF || code == 0xFFFFFFFFL) return -1;
        return Math.pow(2, (0x38 - code) / 8.0);
    }

    /** Canon EOS ISO code: 0x48 = ISO 100, 8 units per stop, 0 = auto. */
    static long canonIso(long code) {
        if (code == 0 || code == 0xFFFFFFFFL || code < 0x28) return -1;
        return nominalIso(100 * Math.pow(2, (code - 0x48) / 8.0));
    }

    /** Canon EOS exposure compensation: signed byte, 8 units per EV, thirds at 3 and 5. */
    static double canonExposureCompensation(long code) {
        byte v = (byte) code;
        return Math.round(v / 8.0 * 3) / 3.0;
    }

    static String canonMode(long m) {
        switch ((int) m) {
            case 0: return "Program Auto";
            case 1: return "Shutter Priority";
            case 2: return "Aperture Priority";
            case 3: return "Manual Exposure";
            case 4: return "Bulb";
            case 9: case 22: return "Scene Intelligent Auto";
            default: return String.format(Locale.ROOT, "Mode %d", m);
        }
    }

    static JSONObject canonSettings(Map<Integer, Long> p, Map<Integer, String> strings) {
        JSONObject out = new JSONObject();
        try {
            Long a = p.get(CANON_APERTURE);
            if (a != null && canonAperture(a) > 0) { out.put("aperture", canonAperture(a)); out.put("apertureText", trim(canonAperture(a))); }
            Long s = p.get(CANON_SHUTTER);
            if (s != null) {
                double sec = canonShutter(s);
                if (sec > 0) { double nominal = nominalShutter(sec); out.put("shutter", nominal); out.put("shutterText", nominal >= 0.3 ? trim(nominal) + "\"" : "1/" + Math.round(1 / nominal)); }
                else if (s == 0x0C) out.put("shutterText", "BULB");
            }
            Long iso = p.get(CANON_ISO);
            if (iso != null) { long v = canonIso(iso); if (v > 0) { out.put("iso", v); out.put("isoText", String.valueOf(v)); } else out.put("isoText", "AUTO"); }
            Long ec = p.get(CANON_EC);
            if (ec != null) out.put("exposureCompensation", canonExposureCompensation(ec));
            Long mode = p.get(CANON_MODE);
            if (mode != null) out.put("mode", canonMode(mode));
            if (strings != null) for (String text : strings.values()) {
                if (text.matches("(?i).*(EF|RF|mm).*")) {
                    out.put("lensName", text);
                    java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+)(?:-(\\d+))?\\s*mm").matcher(text);
                    if (mm.find()) out.put("lensRange", mm.group(0));
                }
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** Canon GetViewFinderData: [u32 size][u32 type][jpeg...] records; type 1 holds the JPEG. */
    static byte[] canonViewfinderJpeg(byte[] d) {
        if (d == null || d.length < 8) return null;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 0;
        while (pos + 8 <= d.length) {
            long size = b.getInt(pos) & 0xFFFFFFFFL;
            int type = b.getInt(pos + 4);
            if (size < 8 || pos + size > d.length) break;
            if (type == 1 && isJpegStart(d, pos + 8)) {
                byte[] out = new byte[(int) size - 8];
                System.arraycopy(d, pos + 8, out, 0, out.length);
                return out;
            }
            pos += (int) size;
        }
        return scanJpeg(d);
    }

    static byte[] canonSetPropPayload(int prop, long value) {
        return le(12).putInt(12).putInt(prop).putInt((int) value).array();
    }

    // ------------------------------------------------------------------ helpers

    static boolean isJpegStart(byte[] d, int pos) { return pos >= 0 && pos + 1 < d.length && (d[pos] & 0xFF) == 0xFF && (d[pos + 1] & 0xFF) == 0xD8; }

    private static byte[] scanJpeg(byte[] d) {
        for (int i = 0; i + 1 < d.length; i++) {
            if (!isJpegStart(d, i)) continue;
            for (int j = d.length - 2; j > i; j--) {
                if ((d[j] & 0xFF) == 0xFF && (d[j + 1] & 0xFF) == 0xD9) {
                    byte[] out = new byte[j + 2 - i];
                    System.arraycopy(d, i, out, 0, out.length);
                    return out;
                }
            }
            return null;
        }
        return null;
    }

    private static final double[] APERTURES = {1, 1.1, 1.2, 1.4, 1.6, 1.8, 2, 2.2, 2.5, 2.8, 3.2, 3.5, 4, 4.5, 5, 5.6, 6.3, 7.1, 8, 9, 10, 11, 13, 14, 16, 18, 20, 22, 25, 29, 32, 36, 40, 45};
    private static final double[] SHUTTERS = {30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3.2, 2.5, 2, 1.6, 1.3, 1, 0.8, 0.6, 0.5, 0.4, 0.3, 1/4.0, 1/5.0, 1/6.0, 1/8.0, 1/10.0, 1/13.0, 1/15.0, 1/20.0, 1/25.0, 1/30.0, 1/40.0, 1/50.0, 1/60.0, 1/80.0, 1/100.0, 1/125.0, 1/160.0, 1/200.0, 1/250.0, 1/320.0, 1/400.0, 1/500.0, 1/640.0, 1/800.0, 1/1000.0, 1/1250.0, 1/1600.0, 1/2000.0, 1/2500.0, 1/3200.0, 1/4000.0};
    private static final long[] ISOS = {50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 51200, 102400};

    static double nominalAperture(double v) { double best = APERTURES[0]; for (double a : APERTURES) if (Math.abs(Math.log(a / v)) < Math.abs(Math.log(best / v))) best = a; return best; }
    static double nominalShutter(double v) { double best = SHUTTERS[0]; for (double a : SHUTTERS) if (Math.abs(Math.log(a / v)) < Math.abs(Math.log(best / v))) best = a; return best; }
    static long nominalIso(double v) { long best = ISOS[0]; for (long a : ISOS) if (Math.abs(Math.log(a / v)) < Math.abs(Math.log(best / v))) best = a; return best; }

    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0); }
    private static ByteBuffer le(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }

    /** Little-endian dataset reader with PTP data types. */
    static final class Reader {
        private final ByteBuffer b;
        Reader(byte[] d) { b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN); }
        int remaining() { return b.remaining(); }
        int u8() { return b.get() & 0xFF; }
        int u16() { return b.getShort() & 0xFFFF; }
        long u32() { return b.getInt() & 0xFFFFFFFFL; }
        long u64() { return b.getLong(); }
        void skipArray16() { long n = u32(); if (n > 10000) throw new IllegalArgumentException("bad array"); b.position(b.position() + (int) n * 2); }
        String string() {
            int n = u8();
            if (n == 0) return "";
            char[] c = new char[n];
            for (int i = 0; i < n; i++) c[i] = (char) u16();
            int end = n; while (end > 0 && c[end - 1] == 0) end--;
            return new String(c, 0, end);
        }
        /** Reads one value of a PTP datatype; returns its numeric value (null for strings/arrays). */
        Long value(int type) {
            switch (type) {
                case 1: return (long) b.get();
                case 2: return (long) u8();
                case 3: return (long) b.getShort();
                case 4: return (long) u16();
                case 5: return (long) b.getInt();
                case 6: return u32();
                case 7: case 8: return b.getLong();
                case 9: case 10: b.position(b.position() + 16); return null;
                case 0xFFFF: string(); return null;
                default:
                    if ((type & 0x4000) != 0) {
                        long n = u32();
                        if (n > 10000) throw new IllegalArgumentException("bad array length");
                        for (long i = 0; i < n; i++) value(type & ~0x4000);
                        return null;
                    }
                    throw new IllegalArgumentException("unknown PTP datatype 0x" + Integer.toHexString(type));
            }
        }
    }
}
