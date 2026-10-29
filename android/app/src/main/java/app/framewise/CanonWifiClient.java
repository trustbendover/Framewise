package app.framewise;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

/**
 * EXPERIMENTAL Canon EOS (2000D) live view over Wi-Fi using PTP/IP. Untested on real hardware.
 * The phone joins the camera's own access point in Android Wi-Fi settings first; Framewise then
 * opens the PTP/IP command and event channels on port 15740 and reuses the Canon PTP layer.
 * Reconnects with backoff while the user keeps it switched on.
 */
final class CanonWifiClient extends CameraFeed {
    private static final String PREFS = "framewise.canonwifi";
    private final Context context;
    private final ConnectivityManager connectivity;
    private boolean wanted;
    private Thread worker;
    private Socket commandSocket, eventSocket;

    CanonWifiClient(Context context) {
        super("canon-wifi");
        this.context = context.getApplicationContext();
        connectivity = this.context.getSystemService(ConnectivityManager.class);
        vendor = "Canon";
    }

    @Override protected String liveMessage() { return "Canon live view over Wi-Fi (experimental)."; }

    synchronized void connect(String manualIp) {
        stopWorker();
        wanted = true; error = null;
        String ip = manualIp == null ? "" : manualIp.trim();
        final int gen = ++generation;
        state = "connecting"; step = "Looking for the Canon on this phone's Wi-Fi…";
        worker = new Thread(() -> loop(gen, ip), "FramewiseCanonWifi");
        worker.start();
    }

    synchronized void disconnect() {
        wanted = false;
        stopWorker();
        state = "idle"; step = "Disconnected from the Canon."; error = null;
    }

    void close() { disconnect(); }

    private void stopWorker() { // holds this
        generation++;
        if (worker != null) worker.interrupt();
        worker = null;
        closeQuietly(commandSocket); closeQuietly(eventSocket);
        commandSocket = null; eventSocket = null;
        jpeg = null; frameTime = 0;
    }

    /** Reconnect delay after the n-th consecutive failure: 2, 4, 8, 16, then 30 s. */
    static long backoffMs(int failures) { return Math.min(30_000L, 1000L << Math.min(5, Math.max(1, failures))); }

    private void loop(int gen, String manualIp) {
        int failures = 0;
        while (current(gen)) {
            try {
                session(gen, manualIp);
                failures = 0;
            } catch (IOException | RuntimeException failure) {
                if (!current(gen)) return;
                failures++;
                String msg = failure instanceof SocketTimeoutException ? "the camera did not answer in time" : failure.getMessage();
                long wait = backoffMs(failures);
                DiagLog.error("canon-wifi", "attempt " + failures + ": " + msg);
                synchronized (this) {
                    if (gen != generation) return;
                    state = failures >= 6 ? "error" : "connecting";
                    error = failures >= 6 ? msg : null;
                    step = "Canon Wi-Fi: " + msg + ". Retrying in " + wait / 1000 + " s… (camera: Wi-Fi function → Remote control (EOS Utility), phone joined to the camera's Wi-Fi)";
                }
            } finally {
                // Only close this worker's sockets: after a reconnect the fields belong to the new worker.
                synchronized (this) { if (gen == generation) { closeQuietly(commandSocket); closeQuietly(eventSocket); commandSocket = null; eventSocket = null; } }
            }
            PtpCameraSession.sleep(backoffMs(failures));
        }
    }

    private void session(int gen, String manualIp) throws IOException {
        Network net = wifiNetwork();
        if (net == null) throw new IOException("this phone is not on Wi-Fi. Join the camera's Wi-Fi (EOS2000D_…) in Android settings");
        InetAddress camera = manualIp.isEmpty() ? cameraAddress(net) : InetAddress.getByName(manualIp);
        if (camera == null) throw new IOException("could not find the camera's address. Enter it under Camera IP");
        DiagLog.event("canon-wifi", "connecting to " + camera.getHostAddress());
        Socket cmd = net.getSocketFactory().createSocket();
        synchronized (this) { if (gen != generation) { closeQuietly(cmd); return; } commandSocket = cmd; }
        cmd.connect(new InetSocketAddress(camera, PtpIpCodec.PORT), 6000);
        cmd.setSoTimeout(10_000); cmd.setTcpNoDelay(true); cmd.setKeepAlive(true);
        InputStream cin = new BufferedInputStream(cmd.getInputStream());
        OutputStream cout = cmd.getOutputStream();
        cout.write(PtpIpCodec.initCommandRequest(guid(), "Framewise " + Build.MODEL)); cout.flush();
        PtpIpCodec.Packet ack = PtpIpCodec.read(cin);
        long connection = PtpIpCodec.connectionNumber(ack);
        String name = PtpIpCodec.ackName(ack);
        Socket evt = net.getSocketFactory().createSocket();
        synchronized (this) { if (gen != generation) { closeQuietly(evt); return; } eventSocket = evt; }
        evt.connect(new InetSocketAddress(camera, PtpIpCodec.PORT), 6000);
        evt.setSoTimeout(10_000);
        evt.getOutputStream().write(PtpIpCodec.initEventRequest(connection));
        InputStream ein = new BufferedInputStream(evt.getInputStream());
        PtpIpCodec.Packet eventAck = PtpIpCodec.read(ein);
        if (eventAck.type != PtpIpCodec.INIT_EVENT_ACK) throw new IOException("the camera did not open the event channel (type " + eventAck.type + ")");
        evt.setSoTimeout(0);
        // Drain the event channel (and answer probes) so the camera never blocks on a full socket buffer.
        Thread drain = new Thread(() -> {
            try {
                OutputStream eout = evt.getOutputStream();
                while (!evt.isClosed()) {
                    PtpIpCodec.Packet p = PtpIpCodec.read(ein);
                    if (p.type == PtpIpCodec.PROBE_REQUEST) { eout.write(PtpIpCodec.probeResponse()); eout.flush(); }
                }
            } catch (IOException | RuntimeException ignored) { }
        }, "FramewiseCanonEvents");
        drain.setDaemon(true);
        drain.start();
        IpTransport t = new IpTransport(cin, cout);
        model(gen, PtpCameraSession.open(t, true, name.isEmpty() ? "Canon EOS" : name));
        PtpCameraSession.runCanon(gen, t, this);
    }

    private Network wifiNetwork() {
        if (connectivity == null) return null;
        for (Network n : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** The camera's access point is the network's DHCP server / default gateway. */
    private InetAddress cameraAddress(Network net) {
        LinkProperties link = connectivity.getLinkProperties(net);
        if (link == null) return null;
        if (Build.VERSION.SDK_INT >= 30 && link.getDhcpServerAddress() instanceof Inet4Address) return link.getDhcpServerAddress();
        for (RouteInfo r : link.getRoutes()) if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) return r.getGateway();
        return null;
    }

    /** Stable per-install GUID: the camera pairs with this identity once. */
    private byte[] guid() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String hex = prefs.getString("guid", "");
        if (hex.length() != 32) {
            byte[] g = new byte[16];
            new SecureRandom().nextBytes(g);
            StringBuilder sb = new StringBuilder();
            for (byte b : g) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            hex = sb.toString();
            prefs.edit().putString("guid", hex).apply();
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static void closeQuietly(Socket s) { if (s != null) try { s.close(); } catch (IOException ignored) { } }

    /** PTP transactions over the PTP/IP command channel. Used only by the worker thread. */
    static final class IpTransport implements PtpCameraSession.Transport {
        private final InputStream in;
        private final OutputStream out;
        private long tid = 1;
        IpTransport(InputStream in, OutputStream out) { this.in = in; this.out = out; }

        @Override public PtpCameraSession.Result transact(int code, byte[] dataOut, long... params) throws IOException {
            for (int attempt = 0; ; attempt++) {
                long id = tid++;
                out.write(PtpIpCodec.operationRequest(code, id, dataOut != null, params));
                if (dataOut != null) { out.write(PtpIpCodec.startData(id, dataOut.length)); out.write(PtpIpCodec.endData(id, dataOut)); }
                out.flush();
                PtpIpCodec.Collector c = new PtpIpCodec.Collector(id);
                while (true) {
                    PtpIpCodec.Packet p = PtpIpCodec.read(in);
                    if (p.type == PtpIpCodec.PROBE_REQUEST) { out.write(PtpIpCodec.probeResponse()); out.flush(); continue; }
                    if (c.feed(p)) break;
                }
                if (c.responseCode == PtpCodec.RC_DEVICE_BUSY && attempt < 20) { PtpCameraSession.sleep(50); continue; }
                return new PtpCameraSession.Result(c.responseCode, c.data());
            }
        }

        @Override public void close() { }
    }
}
