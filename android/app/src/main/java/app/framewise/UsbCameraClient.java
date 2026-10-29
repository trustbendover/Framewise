package app.framewise;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;
import java.io.IOException;
import java.util.Locale;

/**
 * EXPERIMENTAL USB-C camera connection over PTP (untested on real hardware so far).
 * Sony a6100 (PC Remote) via Sony's SDIO extension, Canon EOS 2000D via the EOS extension.
 * All USB I/O runs on one worker thread; the bridge only reads a snapshot.
 */
final class UsbCameraClient extends CameraFeed {
    static final String ACTION_PERMISSION = "app.framewise.USB_PERMISSION";
    private static final int TIMEOUT_MS = 3000, MAX_DATA = 4 * 1024 * 1024;

    private final Context context;
    private final UsbManager usb;
    private final BroadcastReceiver receiver;

    // Guarded by this.
    private boolean wanted;
    /** One Android permission dialog at a time (attach events and taps must not stack dialogs). */
    private boolean permissionPending;
    private Thread worker;
    private UsbDeviceConnection openConnection;

    UsbCameraClient(Context context) {
        super("usb");
        this.context = context.getApplicationContext();
        this.usb = this.context.getSystemService(UsbManager.class);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { onUsbIntent(intent); }
        };
        IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        register(filter);
    }

    // Before Android 13 there is no exported flag. A spoofed permission broadcast is harmless:
    // tryStart() re-checks UsbManager.hasPermission() before touching the device.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void register(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
    }

    static boolean isSupportedVendor(int vendorId) { return vendorId == PtpCodec.SONY_VENDOR || vendorId == PtpCodec.CANON_VENDOR; }
    static String vendorName(int vendorId) { return vendorId == PtpCodec.SONY_VENDOR ? "Sony" : vendorId == PtpCodec.CANON_VENDOR ? "Canon" : "Unknown"; }

    synchronized void connect() {
        wanted = true;
        error = null;
        tryStart();
    }

    synchronized void disconnect() {
        wanted = false;
        stopWorker();
        state = "idle"; step = "Disconnected from the USB camera."; error = null;
        DiagLog.event("usb", "disconnect requested");
    }

    void close() {
        disconnect();
        try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
    }

    /** Called when Android opens Framewise for a newly plugged-in camera. */
    synchronized void onAttachedIntent(Intent intent) { if (wanted) tryStart(); }

    @Override protected String liveMessage() { return "Live view over USB-C (experimental)."; }

    // ------------------------------------------------------------------ device discovery and permission

    private UsbDevice findCamera() {
        if (usb == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) if (isSupportedVendor(d.getVendorId()) && findPtpInterface(d) != null) return d;
        return null;
    }

    private static UsbInterface findPtpInterface(UsbDevice d) {
        UsbInterface fallback = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) return intf;
            if (fallback == null && bulk(intf, UsbConstants.USB_DIR_IN) != null && bulk(intf, UsbConstants.USB_DIR_OUT) != null) fallback = intf;
        }
        return fallback;
    }

    private static UsbEndpoint bulk(UsbInterface intf, int dir) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == dir) return e;
        }
        return null;
    }

    private void tryStart() { // holds this
        if (worker != null) return;
        if (usb == null) { setError("This phone does not support USB host mode (USB OTG)."); return; }
        UsbDevice device = findCamera();
        if (device == null) {
            state = "waiting";
            boolean other = !usb.getDeviceList().isEmpty();
            step = other
                ? "A USB device is connected, but it is not a supported Sony or Canon camera in PTP mode. Sony: MENU → Network → PC Remote Function → On, USB Connection: PC Remote. Canon: just switch the camera on."
                : "Plug the camera into the phone's USB-C port (use a USB-C to Micro-USB/USB-C data cable or OTG adapter), then switch it on.";
            DiagLog.event("usb", "no supported camera; devices=" + usb.getDeviceList().size());
            return;
        }
        vendor = vendorName(device.getVendorId());
        if (!usb.hasPermission(device)) {
            if (permissionPending) return;
            permissionPending = true;
            state = "connecting"; step = "Android will ask to allow Framewise to use the " + vendor + " camera. Tap OK.";
            Intent intent = new Intent(ACTION_PERMISSION).setPackage(context.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags));
            return;
        }
        final int gen = ++generation;
        state = "connecting"; step = "Opening the " + vendor + " camera over USB…";
        DiagLog.event("usb", String.format(Locale.ROOT, "start %s vid=%04x pid=%04x", vendor, device.getVendorId(), device.getProductId()));
        worker = new Thread(() -> run(gen, device), "FramewiseUsbCamera");
        worker.start();
    }

    private void onUsbIntent(Intent intent) {
        String action = intent.getAction();
        UsbDevice device = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class) : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        synchronized (this) {
            if (ACTION_PERMISSION.equals(action)) {
                permissionPending = false;
                if (!wanted) return;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) tryStart();
                else setError("USB permission was denied. Tap Connect over USB-C again and choose OK.");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (device == null || !isSupportedVendor(device.getVendorId())) return;
                DiagLog.event("usb", "camera unplugged");
                stopWorker();
                if (wanted) { state = "waiting"; step = "The camera was unplugged. Plug it back in and Framewise reconnects automatically."; }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (wanted && device != null && isSupportedVendor(device.getVendorId())) { DiagLog.event("usb", "camera plugged in"); tryStart(); }
            }
        }
    }

    private void stopWorker() { // holds this
        generation++;
        if (worker != null) worker.interrupt();
        worker = null;
        final UsbDeviceConnection c = openConnection;
        openConnection = null;
        if (c != null) { try { c.close(); } catch (RuntimeException ignored) { } } // unblocks pending bulk transfers
        jpeg = null; frameTime = 0;
    }

    // ------------------------------------------------------------------ worker

    private void run(int gen, UsbDevice device) {
        UsbDeviceConnection connection = null;
        UsbInterface intf = findPtpInterface(device);
        try {
            if (intf == null) throw new IOException("no PTP interface on the camera");
            connection = usb.openDevice(device);
            if (connection == null) throw new IOException("Android could not open the USB camera");
            synchronized (this) { if (gen != generation) return; openConnection = connection; }
            if (!connection.claimInterface(intf, true)) throw new IOException("the USB interface is busy (close other camera apps)");
            UsbTransport ptp = new UsbTransport(connection, bulk(intf, UsbConstants.USB_DIR_OUT), bulk(intf, UsbConstants.USB_DIR_IN));
            model(gen, PtpCameraSession.open(ptp, true, vendor + " camera"));
            if (device.getVendorId() == PtpCodec.SONY_VENDOR) PtpCameraSession.runSony(gen, ptp, this);
            else PtpCameraSession.runCanon(gen, ptp, this);
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                if (gen != generation) return;
                worker = null; openConnection = null;
                String msg = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                setError("USB camera stopped: " + msg + ". Check the cable and camera USB mode, then tap Connect over USB-C.");
            }
        } finally {
            if (connection != null) {
                try { connection.releaseInterface(intf); } catch (RuntimeException ignored) { }
                try { connection.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** One PTP session over a bulk endpoint pair. Not thread-safe: used only by the worker thread. */
    static final class UsbTransport implements PtpCameraSession.Transport {
        private final UsbDeviceConnection c;
        private final UsbEndpoint out, in;
        private long tid = 1;
        private final byte[] buffer;

        UsbTransport(UsbDeviceConnection c, UsbEndpoint out, UsbEndpoint in) throws IOException {
            if (out == null || in == null) throw new IOException("camera has no bulk endpoints");
            this.c = c; this.out = out; this.in = in;
            buffer = new byte[64 * 1024];
        }

        @Override public PtpCameraSession.Result transact(int code, byte[] dataOut, long... params) throws IOException {
            long id = tid++;
            for (int attempt = 0; ; attempt++) {
                send(PtpCodec.command(code, id, params));
                if (dataOut != null) send(PtpCodec.data(code, id, dataOut));
                PtpCodec.Reassembler r = new PtpCodec.Reassembler(MAX_DATA);
                long deadline = SystemClock.elapsedRealtime() + 10_000;
                boolean done = false;
                while (!done) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("stopped");
                    int n = c.bulkTransfer(in, buffer, buffer.length, TIMEOUT_MS);
                    if (n < 0) {
                        if (SystemClock.elapsedRealtime() > deadline) throw new IOException(String.format(Locale.ROOT, "no answer to 0x%04X (cable unplugged?)", code));
                        continue;
                    }
                    if (n == 0) continue;
                    try { done = r.feed(buffer, n); }
                    catch (IllegalArgumentException bad) { throw new IOException(String.format(Locale.ROOT, "bad PTP reply to 0x%04X: %s", code, bad.getMessage())); }
                }
                if (r.responseCode == PtpCodec.RC_DEVICE_BUSY && attempt < 20) { PtpCameraSession.sleep(50); id = tid++; continue; }
                return new PtpCameraSession.Result(r.responseCode, r.data());
            }
        }

        private void send(byte[] packet) throws IOException {
            int n = c.bulkTransfer(out, packet, packet.length, TIMEOUT_MS);
            if (n != packet.length) throw new IOException("USB write failed (cable unplugged or camera asleep?)");
        }

        @Override public void close() { }
    }
}
