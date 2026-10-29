package app.framewise;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class PtpCodecTest {
    private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};

    @Test public void buildsCommandContainer() {
        byte[] c = PtpCodec.command(0x1002, 7, 1);
        ByteBuffer b = ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16, b.getInt()); assertEquals(1, b.getShort()); assertEquals(0x1002, b.getShort() & 0xFFFF);
        assertEquals(7, b.getInt()); assertEquals(1, b.getInt());
    }

    @Test public void reassemblesDataSplitAcrossReadsThenResponse() {
        byte[] payload = new byte[100];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        byte[] data = PtpCodec.data(0x1009, 3, payload);
        PtpCodec.Reassembler r = new PtpCodec.Reassembler(1 << 20);
        byte[] first = new byte[40]; System.arraycopy(data, 0, first, 0, 40);
        byte[] rest = new byte[data.length - 40]; System.arraycopy(data, 40, rest, 0, rest.length);
        assertTrue(!r.feed(first, first.length));
        assertTrue(!r.feed(rest, rest.length));
        byte[] resp = le(12).putInt(12).putShort((short) 3).putShort((short) 0x2001).putInt(3).array();
        assertTrue(r.feed(resp, resp.length));
        assertEquals(0x2001, r.responseCode);
        assertArrayEquals(payload, r.data());
    }

    @Test public void dataAndResponseInOneRead() {
        byte[] data = PtpCodec.data(0x1001, 1, new byte[] {9, 9});
        byte[] resp = le(16).putInt(16).putShort((short) 3).putShort((short) 0x2019).putInt(1).putInt(42).array();
        byte[] both = new byte[data.length + resp.length];
        System.arraycopy(data, 0, both, 0, data.length); System.arraycopy(resp, 0, both, data.length, resp.length);
        PtpCodec.Reassembler r = new PtpCodec.Reassembler(1024);
        assertTrue(r.feed(both, both.length));
        assertEquals(0x2019, r.responseCode);
        assertEquals(42, r.responseParams[0]);
        assertArrayEquals(new byte[] {9, 9}, r.data());
    }

    @Test public void rejectsGarbageAndOversizedData() {
        try { new PtpCodec.Reassembler(10).feed(new byte[] {1, 2, 3}, 3); fail(); } catch (IllegalArgumentException expected) { }
        byte[] big = PtpCodec.data(1, 1, new byte[64]);
        try { new PtpCodec.Reassembler(10).feed(big, big.length); fail(); } catch (IllegalArgumentException expected) { }
    }

    private static void ptpString(ByteArrayOutputStream o, String s) {
        if (s.isEmpty()) { o.write(0); return; }
        o.write(s.length() + 1);
        for (char ch : (s + "\0").toCharArray()) { o.write(ch & 0xFF); o.write(ch >> 8); }
    }

    @Test public void readsModelFromDeviceInfo() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(100); o.write(0); o.writeBytes(le(4).putInt(6).array()); o.write(100); o.write(0);
        ptpString(o, "sony.net"); o.write(0); o.write(0);
        for (int i = 0; i < 5; i++) { o.writeBytes(le(4).putInt(1).array()); o.write(1); o.write(0); }
        ptpString(o, "Sony Corporation"); ptpString(o, "ILCE-6100");
        assertEquals("ILCE-6100", PtpCodec.deviceInfoModel(o.toByteArray()));
        assertNull(PtpCodec.deviceInfoModel(new byte[3]));
    }

    private static byte[] sonyProps() {
        ByteBuffer b = le(512);
        b.putLong(4);
        b.putShort((short) PtpCodec.SONY_FNUMBER).putShort((short) 4).put((byte) 1).put((byte) 1).putShort((short) 0).putShort((short) 560).put((byte) 0);
        b.putShort((short) PtpCodec.SONY_SHUTTER).putShort((short) 6).put((byte) 1).put((byte) 1).putInt(0).putInt((1 << 16) | 250).put((byte) 0);
        b.putShort((short) PtpCodec.SONY_ISO).putShort((short) 6).put((byte) 1).put((byte) 1).putInt(0).putInt(800)
            .put((byte) 2).putShort((short) 2).putInt(100).putInt(800);
        b.putShort((short) PtpCodec.SONY_EC).putShort((short) 3).put((byte) 1).put((byte) 1).putShort((short) 0).putShort((short) -700)
            .put((byte) 1).putShort((short) -3000).putShort((short) 3000).putShort((short) 300);
        byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        return out;
    }

    @Test public void parsesSonyPropertiesIntoSettings() throws Exception {
        Map<Integer, Long> p = PtpCodec.parseSonyProps(sonyProps());
        assertEquals(4, p.size());
        JSONObject s = PtpCodec.sonySettings(p);
        assertEquals(5.6, s.getDouble("aperture"), 1e-9);
        assertEquals(1 / 250.0, s.getDouble("shutter"), 1e-9);
        assertEquals("1/250", s.getString("shutterText"));
        assertEquals(800, s.getLong("iso"));
        assertEquals(-0.67, s.getDouble("exposureCompensation"), 0.01);
    }

    @Test public void sonyAutoIsoAndMode() throws Exception {
        Map<Integer, Long> p = new HashMap<>();
        p.put(PtpCodec.SONY_ISO, 0x00FFFFFFL); p.put(PtpCodec.SONY_MODE, 1L);
        JSONObject s = PtpCodec.sonySettings(p);
        assertEquals("AUTO", s.getString("isoText"));
        assertEquals("Manual Exposure", s.getString("mode"));
    }

    @Test public void extractsSonyLiveviewJpeg() {
        assertArrayEquals(JPEG, PtpCodec.sonyLiveviewJpeg(JPEG));
        ByteBuffer b = le(16 + JPEG.length); b.putInt(16).putInt(JPEG.length).putLong(0).put(JPEG);
        assertArrayEquals(JPEG, PtpCodec.sonyLiveviewJpeg(b.array()));
        byte[] junk = new byte[20 + JPEG.length]; System.arraycopy(JPEG, 0, junk, 13, JPEG.length);
        assertArrayEquals(JPEG, PtpCodec.sonyLiveviewJpeg(junk));
        assertNull(PtpCodec.sonyLiveviewJpeg(new byte[10]));
    }

    @Test public void parsesCanonEventsAndDecodesValues() throws Exception {
        byte[] lens = "EF-S18-55mm f/3.5-5.6 III\0\0\0".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = le(200);
        int[][] props = {{PtpCodec.CANON_APERTURE, 0x30}, {PtpCodec.CANON_SHUTTER, 0x70}, {PtpCodec.CANON_ISO, 0x58}, {PtpCodec.CANON_EC, 0xF8}, {PtpCodec.CANON_MODE, 3}};
        for (int[] p : props) b.putInt(16).putInt(PtpCodec.CANON_EVT_PROP_CHANGED).putInt(p[0]).putInt(p[1]);
        b.putInt(12 + lens.length).putInt(PtpCodec.CANON_EVT_PROP_CHANGED).putInt(0xD1D8).put(lens);
        b.putInt(8).putInt(0);
        byte[] d = new byte[b.position()]; System.arraycopy(b.array(), 0, d, 0, d.length);
        Map<Integer, String> strings = new HashMap<>();
        Map<Integer, Long> values = PtpCodec.parseCanonEvents(d, strings);
        JSONObject s = PtpCodec.canonSettings(values, strings);
        assertEquals(5.6, s.getDouble("aperture"), 1e-9);
        assertEquals("1/125", s.getString("shutterText"));
        assertEquals(400, s.getLong("iso"));
        assertEquals(-1.0, s.getDouble("exposureCompensation"), 1e-9);
        assertEquals("Manual Exposure", s.getString("mode"));
        assertEquals("EF-S18-55mm f/3.5-5.6 III", s.getString("lensName"));
        assertEquals("18-55mm", s.getString("lensRange"));
    }

    @Test public void canonValueTables() {
        assertEquals(8.0, PtpCodec.canonAperture(0x38), 1e-9);
        assertEquals(1.0, PtpCodec.canonShutter(0x38), 1e-9);
        assertEquals(30.0, PtpCodec.nominalShutter(PtpCodec.canonShutter(0x10)), 1e-9);
        assertEquals(100, PtpCodec.canonIso(0x48));
        assertEquals(6400, PtpCodec.canonIso(0x78));
        assertEquals(-1, PtpCodec.canonIso(0));
        assertEquals(1 / 3.0, PtpCodec.canonExposureCompensation(3), 1e-9);
        assertEquals(-1, PtpCodec.canonShutter(0x0C), 1e-9);
    }

    @Test public void truncatedCanonEventsDoNotThrow() {
        byte[] d = le(12).putInt(400).putInt(PtpCodec.CANON_EVT_PROP_CHANGED).putInt(1).array();
        assertTrue(PtpCodec.parseCanonEvents(d, null).isEmpty());
    }

    @Test public void extractsCanonViewfinderJpeg() {
        ByteBuffer b = le(8 + 8 + JPEG.length); b.putInt(8).putInt(5).putInt(8 + JPEG.length).putInt(1).put(JPEG);
        assertArrayEquals(JPEG, PtpCodec.canonViewfinderJpeg(b.array()));
        assertNull(PtpCodec.canonViewfinderJpeg(new byte[4]));
    }

    @Test public void canonSetPropPayload() {
        ByteBuffer b = ByteBuffer.wrap(PtpCodec.canonSetPropPayload(PtpCodec.CANON_EVF_OUTPUT, 2)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(12, b.getInt()); assertEquals(0xD1B0, b.getInt()); assertEquals(2, b.getInt());
    }

    // ------------------------------------------------------------------ PTP/IP framing

    @Test public void ptpIpInitCommandRequestLayout() throws Exception {
        byte[] guid = new byte[16]; guid[0] = 7;
        byte[] p = PtpIpCodec.initCommandRequest(guid, "FW");
        ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(p.length, b.getInt()); assertEquals(PtpIpCodec.INIT_COMMAND_REQUEST, b.getInt());
        assertEquals(7, b.get()); b.position(24);
        assertEquals('F', b.getShort()); assertEquals('W', b.getShort()); assertEquals(0, b.getShort());
        assertEquals(PtpIpCodec.PROTOCOL_VERSION, b.getInt());
        PtpIpCodec.Packet back = PtpIpCodec.read(new ByteArrayInputStream(p));
        assertEquals(PtpIpCodec.INIT_COMMAND_REQUEST, back.type);
    }

    @Test public void ptpIpAckAndFail() throws Exception {
        byte[] name = "EOS2000D\0".getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer ack = le(20 + name.length + 4); ack.putInt(5).put(new byte[16]).put(name).putInt(PtpIpCodec.PROTOCOL_VERSION);
        PtpIpCodec.Packet p = PtpIpCodec.read(new ByteArrayInputStream(PtpIpCodec.packet(PtpIpCodec.INIT_COMMAND_ACK, ack.array())));
        assertEquals(5, PtpIpCodec.connectionNumber(p));
        assertEquals("EOS2000D", PtpIpCodec.ackName(p));
        try { PtpIpCodec.connectionNumber(new PtpIpCodec.Packet(PtpIpCodec.INIT_FAIL, le(4).putInt(1).array())); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("reason 1")); }
    }

    @Test public void ptpIpOperationAndDataCollection() throws Exception {
        ByteBuffer op = ByteBuffer.wrap(PtpIpCodec.operationRequest(0x9116, 9, false)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(18, op.getInt()); assertEquals(PtpIpCodec.OPERATION_REQUEST, op.getInt()); assertEquals(1, op.getInt());
        assertEquals(0x9116, op.getShort() & 0xFFFF); assertEquals(9, op.getInt());
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(PtpIpCodec.startData(9, 4));
        stream.writeBytes(PtpIpCodec.packet(PtpIpCodec.DATA, le(6).putInt(9).put((byte) 1).put((byte) 2).array()));
        stream.writeBytes(PtpIpCodec.packet(PtpIpCodec.EVENT, new byte[6]));
        stream.writeBytes(PtpIpCodec.endData(9, new byte[] {3, 4}));
        stream.writeBytes(PtpIpCodec.packet(PtpIpCodec.OPERATION_RESPONSE, le(6).putShort((short) 0x2001).putInt(9).array()));
        ByteArrayInputStream in = new ByteArrayInputStream(stream.toByteArray());
        PtpIpCodec.Collector c = new PtpIpCodec.Collector(9);
        while (!c.feed(PtpIpCodec.read(in))) { }
        assertEquals(0x2001, c.responseCode);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, c.data());
        assertTrue(c.complete());
    }

    @Test public void ptpIpRejectsBadLength() {
        try { PtpIpCodec.read(new ByteArrayInputStream(le(8).putInt(3).putInt(1).array())); fail(); }
        catch (java.io.IOException expected) { }
    }

    @Test public void reconnectBackoffAndFrameDelay() {
        assertEquals(2000, CanonWifiClient.backoffMs(1));
        assertEquals(30_000, CanonWifiClient.backoffMs(10));
        assertEquals(200, PtpCameraSession.frameDelay(10, false));
        assertEquals(40, PtpCameraSession.frameDelay(10, true));
        assertEquals(500, PtpCameraSession.frameDelay(5000, true));
    }

    @Test public void vendorDetection() {
        assertTrue(UsbCameraClient.isSupportedVendor(0x054C));
        assertTrue(UsbCameraClient.isSupportedVendor(0x04A9));
        assertEquals("Canon", UsbCameraClient.vendorName(0x04A9));
        assertTrue(!UsbCameraClient.isSupportedVendor(0x18D1));
    }

    @Test public void redactsSecretsFromReports() {
        DiagLog.setCameraPassword("camPass123");
        String r = DiagLog.redact("key AIzaSyA1234567890abcdefghijklmnop and sk-proj-abcdefghijklmnop1234 pw camPass123 \"apiKey\":\"hunter2\" password=abc");
        assertTrue(r, !r.contains("AIza") && !r.contains("sk-proj") && !r.contains("camPass123") && !r.contains("hunter2") && !r.contains("=abc"));
        DiagLog.setCameraPassword("");
    }
}
