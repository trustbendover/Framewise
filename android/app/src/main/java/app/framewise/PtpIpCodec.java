package app.framewise;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * PTP/IP packet framing (CIPA DC-005, TCP port 15740). Pure Java, unit-tested on the JVM.
 * Every packet is [u32 length incl. header][u32 type][payload], little-endian.
 */
final class PtpIpCodec {
    private PtpIpCodec() { }

    static final int PORT = 15740;
    static final int INIT_COMMAND_REQUEST = 1, INIT_COMMAND_ACK = 2, INIT_EVENT_REQUEST = 3, INIT_EVENT_ACK = 4, INIT_FAIL = 5,
        OPERATION_REQUEST = 6, OPERATION_RESPONSE = 7, EVENT = 8, START_DATA = 9, DATA = 10, CANCEL = 11, END_DATA = 12,
        PROBE_REQUEST = 13, PROBE_RESPONSE = 14;
    static final int PROTOCOL_VERSION = 0x00010000;
    static final int DATAPHASE_NONE_OR_IN = 1, DATAPHASE_OUT = 2;
    static final int MAX_PACKET = 8 * 1024 * 1024;

    static final class Packet {
        final int type; final byte[] payload;
        Packet(int type, byte[] payload) { this.type = type; this.payload = payload; }
        ByteBuffer body() { return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); }
    }

    static byte[] packet(int type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(8 + payload.length).putInt(type).put(payload);
        return b.array();
    }

    /** Init Command Request: 16-byte GUID, NUL-terminated UTF-16LE friendly name, protocol version. */
    static byte[] initCommandRequest(byte[] guid, String friendlyName) {
        if (guid.length != 16) throw new IllegalArgumentException("GUID must be 16 bytes");
        byte[] name = (friendlyName + "\0").getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer b = ByteBuffer.allocate(16 + name.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(guid).put(name).putInt(PROTOCOL_VERSION);
        return packet(INIT_COMMAND_REQUEST, b.array());
    }

    static byte[] initEventRequest(long connectionNumber) {
        return packet(INIT_EVENT_REQUEST, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) connectionNumber).array());
    }

    static byte[] operationRequest(int code, long tid, boolean dataOut, long... params) {
        ByteBuffer b = ByteBuffer.allocate(4 + 2 + 4 + 4 * params.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(dataOut ? DATAPHASE_OUT : DATAPHASE_NONE_OR_IN).putShort((short) code).putInt((int) tid);
        for (long p : params) b.putInt((int) p);
        return packet(OPERATION_REQUEST, b.array());
    }

    static byte[] startData(long tid, long totalLength) {
        return packet(START_DATA, ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt((int) tid).putLong(totalLength).array());
    }

    static byte[] endData(long tid, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(4 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) tid).put(data);
        return packet(END_DATA, b.array());
    }

    static byte[] probeResponse() { return packet(PROBE_RESPONSE, new byte[0]); }

    static Packet read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] head = new byte[8];
        d.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        long length = b.getInt() & 0xFFFFFFFFL;
        int type = b.getInt();
        if (length < 8 || length > MAX_PACKET) throw new IOException("bad PTP/IP packet length " + length);
        byte[] payload = new byte[(int) length - 8];
        d.readFully(payload);
        return new Packet(type, payload);
    }

    /** Connection number from an Init Command Ack; throws with the camera's reason on Init Fail. */
    static long connectionNumber(Packet p) throws IOException {
        if (p.type == INIT_FAIL) {
            long reason = p.payload.length >= 4 ? p.body().getInt() & 0xFFFFFFFFL : -1;
            throw new IOException("the camera refused the connection (reason " + reason + "). Pair Framewise on the camera first");
        }
        if (p.type != INIT_COMMAND_ACK || p.payload.length < 20) throw new IOException("unexpected PTP/IP reply type " + p.type);
        return p.body().getInt() & 0xFFFFFFFFL;
    }

    /** Friendly name the camera reported in its Init Command Ack (after connection number + GUID). */
    static String ackName(Packet p) {
        if (p.payload.length <= 20) return "";
        int end = 20;
        while (end + 1 < p.payload.length && !(p.payload[end] == 0 && p.payload[end + 1] == 0)) end += 2;
        return new String(p.payload, 20, end - 20, StandardCharsets.UTF_16LE).trim();
    }

    /** Collects the data phase and response of one transaction from a sequence of packets. */
    static final class Collector {
        private final long tid;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private long expected = -1;
        int responseCode = -1;
        Collector(long tid) { this.tid = tid; }

        /** Returns true when the Operation Response for this transaction arrived. */
        boolean feed(Packet p) throws IOException {
            ByteBuffer b = p.body();
            switch (p.type) {
                case START_DATA:
                    if ((b.getInt() & 0xFFFFFFFFL) != tid) return false;
                    expected = b.getLong();
                    if (expected > MAX_PACKET) throw new IOException("camera data too large");
                    return false;
                case DATA: case END_DATA:
                    if ((b.getInt() & 0xFFFFFFFFL) != tid) return false;
                    if (data.size() + p.payload.length - 4 > MAX_PACKET) throw new IOException("camera data too large");
                    data.write(p.payload, 4, p.payload.length - 4);
                    return false;
                case OPERATION_RESPONSE: {
                    int code = b.getShort() & 0xFFFF;
                    if ((b.getInt() & 0xFFFFFFFFL) != tid) return false;
                    responseCode = code;
                    return true;
                }
                default: return false; // events/probes are handled by the caller
            }
        }
        byte[] data() { return data.toByteArray(); }
        boolean complete() { return expected < 0 || data.size() == expected; }
    }
}
