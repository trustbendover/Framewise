package app.framewise;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** User-started, non-recording screen capture. Only one JPEG preview is retained. */
public final class CaptureService extends Service {
    static final String ACTION_START = "app.framewise.START_CAPTURE";
    static final String ACTION_STOP = "app.framewise.STOP_CAPTURE";
    private static final String CHANNEL = "framewise_capture";
    private static final int NOTIFICATION_ID = 27;
    private final Object resourceLock = new Object();
    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private MediaProjection.Callback projectionCallback;
    private long lastFrame;
    private int captureWidth;
    private int captureHeight;
    private boolean stopped;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("FramewisePreview");
        thread.start();
        handler = new Handler(thread.getLooper());
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Sony preview sharing", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when Framewise can see the app you selected. Stop sharing at any time.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        synchronized (resourceLock) {
            if (projection != null) return START_NOT_STICKY;
            // Start the typed foreground service before acquiring the projection token.
            try {
                startForeground(NOTIFICATION_ID, makeNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } catch (RuntimeException error) {
                fail("Android could not display the sharing service. Keep Framewise open and start sharing again.");
                return START_NOT_STICKY;
            }
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("resultData", Intent.class)
                : intent.getParcelableExtra("resultData");
            if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                fail("Android did not provide permission to capture the selected app.");
                return START_NOT_STICKY;
            }
            try {
                stopped = false;
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                projection = manager.getMediaProjection(resultCode, resultData);
                if (projection == null) throw new IllegalStateException("No projection");
                projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() { stopSelf(); }
                    @Override public void onCapturedContentResize(int width, int height) {
                        synchronized (resourceLock) {
                            if (!stopped && display != null) resizeSurface(width, height);
                        }
                    }
                    @Override public void onCapturedContentVisibilityChanged(boolean isVisible) {
                        CaptureState.visibility(isVisible);
                    }
                };
                projection.registerCallback(projectionCallback, handler);
                int width, height;
                WindowManager windows = getSystemService(WindowManager.class);
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Rect bounds = windows.getMaximumWindowMetrics().getBounds();
                    width = bounds.width(); height = bounds.height();
                } else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    windows.getDefaultDisplay().getRealMetrics(metrics);
                    width = metrics.widthPixels; height = metrics.heightPixels;
                }
                CaptureState.started();
                createSurface(width, height);
                display = projection.createVirtualDisplay("Framewise selected app", captureWidth, captureHeight,
                    getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
            } catch (RuntimeException error) {
                fail("Screen sharing could not start. Tap Start sharing for a new Android permission prompt.");
            }
        }
        return START_NOT_STICKY;
    }

    private Notification makeNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle("Framewise is viewing your shared app")
            .setContentText("Latest preview only. Tap Stop to end sharing.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null, "Stop sharing", stop).build())
            .build();
    }

    private void createSurface(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid capture size");
        double scale = Math.min(1.0, 960.0 / Math.max(width, height));
        captureWidth = Math.max(1, (int) Math.round(width * scale));
        captureHeight = Math.max(1, (int) Math.round(height * scale));
        reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::readImage, handler);
    }

    private void resizeSurface(int width, int height) {
        if (width < 1 || height < 1) return;
        double scale = Math.min(1.0, 960.0 / Math.max(width, height));
        int nextWidth = Math.max(1, (int) Math.round(width * scale));
        int nextHeight = Math.max(1, (int) Math.round(height * scale));
        if (nextWidth == captureWidth && nextHeight == captureHeight) return;
        ImageReader previous = reader;
        try {
            display.setSurface(null);
            createSurface(width, height);
            display.resize(captureWidth, captureHeight, getResources().getDisplayMetrics().densityDpi);
            display.setSurface(reader.getSurface());
            if (previous != null) previous.close();
        } catch (RuntimeException error) {
            if (previous != null) previous.close();
            fail("Sharing changed size and could not continue. Start a new sharing session.");
        }
    }

    private void readImage(ImageReader source) {
        synchronized (resourceLock) {
            Image image = null;
            Bitmap padded = null;
            Bitmap cropped = null;
            try {
                image = source.acquireLatestImage();
                if (image == null || stopped) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastFrame < 1000) return;
                lastFrame = now;
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer pixels = plane.getBuffer();
                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();
                int width = image.getWidth();
                int height = image.getHeight();
                if (pixelStride != 4 || rowStride < width * pixelStride) return;
                int bitmapWidth = rowStride / pixelStride;
                // The final row may omit its padding, so copy into a bounded padded buffer.
                ByteBuffer full = ByteBuffer.allocate(rowStride * height);
                ByteBuffer input = pixels.duplicate();
                input.rewind();
                int bytes = Math.min(input.remaining(), full.remaining());
                input.limit(bytes);
                full.put(input);
                full.rewind();
                padded = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(full);
                cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                if (cropped.compress(Bitmap.CompressFormat.JPEG, 78, jpeg)) {
                    CaptureState.frame("data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP));
                }
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // A queued callback may belong to a reader that was closed during resize.
            } finally {
                if (image != null) image.close();
                if (cropped != null && cropped != padded) cropped.recycle();
                if (padded != null) padded.recycle();
            }
        }
    }

    private void fail(String message) {
        CaptureState.stopped(message);
        stopSelf();
    }

    @Override public void onDestroy() {
        synchronized (resourceLock) {
            stopped = true;
            if (projection != null && projectionCallback != null) projection.unregisterCallback(projectionCallback);
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            if (projection != null) { projection.stop(); projection = null; }
            if (CaptureState.isCapturing()) CaptureState.stopped(null);
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (thread != null) { thread.quitSafely(); thread = null; }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
