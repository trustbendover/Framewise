package app.framewise;

import android.annotation.SuppressLint;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
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
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * User-started, non-recording screen capture. Only one JPEG preview is retained.
 *
 * <p>Android 14+ rules followed here: the service is started with the mediaProjection
 * foreground type only after consent, {@code getMediaProjection} is called once per consent
 * token, a {@link MediaProjection.Callback} is registered before {@code createVirtualDisplay},
 * and exactly one virtual display is created per projection (resizes reuse it).
 */
public final class CaptureService extends Service {
    static final String ACTION_START = "app.framewise.START_CAPTURE";
    static final String ACTION_STOP = "app.framewise.STOP_CAPTURE";
    static final String SONY_PACKAGE = "com.sony.playmemories.mobile";
    private static final String CHANNEL = "framewise_capture";
    private static final int NOTIFICATION_ID = 27;
    /** A projection that ends this quickly without frames almost always means the shared app closed itself. */
    private static final long EARLY_STOP_MS = 8000;
    private final Object resourceLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
    private boolean stopReported;
    private View bubble;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("FramewisePreview");
        thread.start();
        handler = new Handler(thread.getLooper());
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Sony preview sharing", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when Framewise can see your screen. Stop sharing at any time.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            reportStop(null);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        synchronized (resourceLock) {
            if (projection != null) return START_NOT_STICKY;
            CaptureState.step("Step 3 of 4: starting the sharing service…");
            // Android 14+: the typed foreground service must be running before getMediaProjection().
            try {
                startForeground(NOTIFICATION_ID, makeNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } catch (RuntimeException error) {
                boolean background = Build.VERSION.SDK_INT >= 31 && error instanceof ForegroundServiceStartNotAllowedException;
                fail(background
                    ? "Android blocked the sharing service because Framewise was in the background. Stay in Framewise while you accept the prompt, then try again."
                    : "Android refused the sharing service (" + error.getClass().getSimpleName() + "). Tap Share again for a new prompt.");
                return START_NOT_STICKY;
            }
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("resultData", Intent.class)
                : intent.getParcelableExtra("resultData");
            if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                fail("Android did not provide permission to capture the screen.");
                return START_NOT_STICKY;
            }
            try {
                stopped = false;
                stopReported = false;
                CaptureState.step("Step 4 of 4: connecting to Android's screen capture…");
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                projection = manager.getMediaProjection(resultCode, resultData);
                if (projection == null) throw new IllegalStateException("No projection");
                projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        long age = CaptureState.sinceStartMs();
                        int frames = CaptureState.frameCount();
                        if (age < EARLY_STOP_MS && frames == 0) {
                            reportStop("Android ended sharing right after it started. This happens when the shared app closes itself: "
                                + "Imaging Edge closes its screens whenever Android shows a system dialog, the Home screen or Recents. "
                                + "Use \u201cShare Sony live view\u201d, pick \u201cEntire screen\u201d, and let Framewise open Imaging Edge for you.");
                        } else {
                            reportStop("Android ended sharing (the shared app closed, the screen locked, or sharing was stopped from the status bar). Tap Share to start again.");
                        }
                        stopSelf();
                    }
                    @Override public void onCapturedContentResize(int width, int height) {
                        synchronized (resourceLock) {
                            if (!stopped && display != null) resizeSurface(width, height);
                        }
                    }
                    @Override public void onCapturedContentVisibilityChanged(boolean isVisible) {
                        CaptureState.visibility(isVisible);
                    }
                };
                // Must be registered before createVirtualDisplay on Android 14+.
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
                createSurface(width, height);
                display = projection.createVirtualDisplay("Framewise shared screen", captureWidth, captureHeight,
                    getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
                CaptureState.started("Sharing is on. Open Imaging Edge and start remote shooting; Framewise keeps the latest Sony frame.");
                mainHandler.post(this::showBubble);
            } catch (SecurityException error) {
                fail("Android rejected the screen-capture permission (it can be used only once). Tap Share again for a fresh prompt.");
            } catch (RuntimeException error) {
                fail("Screen sharing could not start (" + error.getClass().getSimpleName() + "). Tap Share again for a new Android prompt.");
            }
        }
        return START_NOT_STICKY;
    }

    private Notification makeNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle("Framewise is viewing your screen")
            .setContentText("Latest preview only, nothing is recorded. Tap to return to Framewise.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null, "Back to Framewise", open).build())
            .addAction(new Notification.Action.Builder(null, "Stop sharing", stop).build())
            .build();
    }

    /**
     * Optional floating "Framewise" button shown over other apps while sharing. Returning to
     * Framewise through it starts the activity directly, so Android does not send the
     * "close system dialogs" signal that makes Imaging Edge close its remote-shooting screen
     * (which happens with Home, Recents and some system panels).
     */
    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        if (bubble != null || stopped || !Settings.canDrawOverlays(this)) return;
        try {
            TextView view = new TextView(this);
            view.setText(R.string.bubble_label);
            view.setTextColor(Color.parseColor("#11130f"));
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics()));
            view.setPadding(pad + pad / 2, pad, pad + pad / 2, pad);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.parseColor("#d6ee94"));
            background.setCornerRadius(pad * 3);
            view.setBackground(background);
            view.setContentDescription(getString(R.string.bubble_description));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = pad;
            params.y = pad * 12;
            WindowManager windows = getSystemService(WindowManager.class);
            view.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY; int startX, startY; boolean moved;
                @Override public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX(); downY = event.getRawY(); startX = params.x; startY = params.y; moved = false; return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                            if (Math.abs(dx) + Math.abs(dy) > pad) moved = true;
                            if (moved) { params.x = startX - Math.round(dx); params.y = startY + Math.round(dy); windows.updateViewLayout(v, params); }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!moved) openFramewise();
                            return true;
                        default: return false;
                    }
                }
            });
            windows.addView(view, params);
            bubble = view;
            CaptureState.overlay(true);
        } catch (RuntimeException ignored) {
            bubble = null; // Overlay permission revoked or window rejected; the notification still works.
        }
    }

    private void hideBubble() {
        if (bubble == null) return;
        try { getSystemService(WindowManager.class).removeView(bubble); } catch (RuntimeException ignored) { }
        bubble = null;
        CaptureState.overlay(false);
    }

    private void openFramewise() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException ignored) { }
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
                boolean black = isBlack(pixels, rowStride, width, height);
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
                    CaptureState.frame("data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP), black);
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

    /** True when a sparse grid of pixels is entirely (near) black: typical for FLAG_SECURE windows. */
    static boolean isBlack(ByteBuffer pixels, int rowStride, int width, int height) {
        int limit = pixels.limit();
        int stepX = Math.max(1, width / 24), stepY = Math.max(1, height / 40);
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int offset = y * rowStride + x * 4;
                if (offset + 2 >= limit) continue;
                int r = pixels.get(offset) & 0xff, g = pixels.get(offset + 1) & 0xff, b = pixels.get(offset + 2) & 0xff;
                if (r > 12 || g > 12 || b > 12) return false;
            }
        }
        return true;
    }

    private void reportStop(String message) {
        if (stopReported) return;
        stopReported = true;
        CaptureState.stopped(message);
    }

    private void fail(String message) {
        reportStop(message);
        stopSelf();
    }

    @Override public void onDestroy() {
        synchronized (resourceLock) {
            stopped = true;
            if (projection != null && projectionCallback != null) projection.unregisterCallback(projectionCallback);
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            if (projection != null) { projection.stop(); projection = null; }
            if (CaptureState.isCapturing()) reportStop(null);
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (thread != null) { thread.quitSafely(); thread = null; }
        }
        mainHandler.post(this::hideBubble);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
