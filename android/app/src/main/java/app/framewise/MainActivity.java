package app.framewise;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.View;
import android.widget.FrameLayout;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 100;
    private static final int NOTIFICATION_REQUEST = 101;
    private static final int PHOTO_REQUEST = 102;
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final Map<String, String> ASSETS;
    static {
        Map<String, String> paths = new HashMap<>();
        paths.put("/assets/index.html", "text/html");
        paths.put("/assets/app.js", "text/javascript");
        paths.put("/assets/styles.css", "text/css");
        paths.put("/assets/engine.mjs", "text/javascript");
        paths.put("/assets/demo.svg", "image/svg+xml");
        ASSETS = Collections.unmodifiableMap(paths);
    }
    private WebView webView;
    private AiClient aiClient;
    private boolean requestingCapture;
    private boolean requestingPhoto;
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();

    // JavaScript is required by the bundled UI. All WebView network resources and
    // navigations are allowlisted below; untrusted pages never receive the bridge.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff11130f);
        getWindow().setNavigationBarColor(0xff11130f);
        aiClient = new AiClient(this);
        webView = new WebView(this);
        webView.setBackgroundColor(0xff11130f);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        webView.addJavascriptInterface(new NativeBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String type = ASSETS.get(url.getPath());
                if ("https".equals(url.getScheme()) && APP_HOST.equals(url.getHost()) && type != null
                        && "GET".equals(request.getMethod())) {
                    try {
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Cache-Control", "no-store");
                        headers.put("X-Content-Type-Options", "nosniff");
                        return new WebResourceResponse(type, "UTF-8", 200, "OK", headers,
                            getAssets().open(url.getPath().substring("/assets/".length())));
                    } catch (IOException error) { return blockedResponse(404, "Not Found"); }
                }
                return blockedResponse(403, "Blocked");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("https".equals(url.getScheme()) && APP_HOST.equals(url.getHost())
                        && "/assets/index.html".equals(url.getPath())) return false;
                // The privileged bridge is exposed only to the app's own bundled page.
                if (request.isForMainFrame() && ("https".equals(url.getScheme()) || "http".equals(url.getScheme()))) {
                    openExternal(url);
                }
                return true;
            }
        });
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xff11130f);
        container.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(container);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) bars.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        webView.loadUrl("https://" + APP_HOST + "/assets/index.html");
    }

    private static WebResourceResponse blockedResponse(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason,
            Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }

    private void openExternal(Uri url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, url)); }
        catch (android.content.ActivityNotFoundException ignored) { }
    }

    private void requestCapture() {
        if (requestingCapture || CaptureState.isCapturing()) return;
        requestingCapture = true;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else launchCaptureConsent();
    }

    private void launchCaptureConsent() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try { startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST); }
        catch (RuntimeException error) {
            requestingCapture = false;
            CaptureState.stopped("Android could not open screen sharing. Try again.");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATION_REQUEST) launchCaptureConsent();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PHOTO_REQUEST) {
            requestingPhoto = false;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                publishPhoto(null, "Photo selection was cancelled.");
                return;
            }
            Uri uri = data.getData();
            if (!"content".equals(uri.getScheme())) {
                publishPhoto(null, "Choose a photo from Android's photo or document picker.");
                return;
            }
            try { photoExecutor.execute(() -> importPhoto(uri)); }
            catch (RejectedExecutionException error) { publishPhoto(null, "Reopen the app and choose your photo again."); }
            return;
        }
        if (requestCode != CAPTURE_REQUEST) return;
        requestingCapture = false;
        if (resultCode != RESULT_OK || data == null) {
            CaptureState.stopped("Screen sharing was cancelled. No camera preview was captured.");
            return;
        }
        Intent service = new Intent(this, CaptureService.class)
            .setAction(CaptureService.ACTION_START)
            .putExtra("resultCode", resultCode)
            .putExtra("resultData", data);
        try { startForegroundService(service); }
        catch (RuntimeException error) {
            CaptureState.stopped("Android could not start screen sharing. Keep Framewise open and try again.");
        }
    }

    @Override protected void onDestroy() {
        photoExecutor.shutdownNow();
        if (aiClient != null) { aiClient.close(); aiClient = null; }
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        // The explicitly started foreground capture continues while Sony's app is visible.
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    final class NativeBridge {
        @JavascriptInterface public void startCapture() { runOnUiThread(MainActivity.this::requestCapture); }

        @JavascriptInterface public void stopCapture() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, CaptureService.class));
                CaptureState.stopped(null);
            });
        }

        @JavascriptInterface public void openSony() {
            runOnUiThread(() -> {
                Intent sony = getPackageManager().getLaunchIntentForPackage("com.sony.playmemories.mobile");
                if (sony != null) {
                    try { startActivity(sony); return; }
                    catch (android.content.ActivityNotFoundException ignored) { }
                }
                openExternal(Uri.parse("https://support.d-imaging.sony.co.jp/app/iemobile/en/"));
            });
        }

        @JavascriptInterface public void pickPhoto() {
            runOnUiThread(() -> {
                if (requestingPhoto || isDestroyed()) return;
                requestingPhoto = true;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*");
                try { startActivityForResult(picker, PHOTO_REQUEST); }
                catch (android.content.ActivityNotFoundException error) {
                    requestingPhoto = false;
                    publishPhoto(null, "Android's image picker could not open on this device.");
                }
            });
        }

        @JavascriptInterface public void copyText(String text) {
            if (text == null || text.length() > 20000) return;
            runOnUiThread(() -> {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Framewise photo settings", text));
            });
        }

        @JavascriptInterface public String getCaptureFrame() { return CaptureState.json(); }

        @JavascriptInterface public String getStatus() {
            JSONObject result = new JSONObject();
            try {
                result.put("native", true);
                result.put("captureSupported", true);
                result.put("photoPickerSupported", true);
                result.put("androidVersion", Build.VERSION.SDK_INT);
                result.put("androidRelease", Build.VERSION.RELEASE);
                if (aiClient != null) {
                    JSONObject ai = aiClient.status();
                    result.put("aiConfigured", ai.optBoolean("aiConfigured", false));
                    result.put("model", ai.optString("model", ""));
                    result.put("provider", ai.optString("provider", "gemini"));
                }
            } catch (JSONException ignored) { }
            return result.toString();
        }

        @JavascriptInterface public String aiStatus() {
            return aiClient == null ? "{\"aiConfigured\":false}" : aiClient.status().toString();
        }

        @JavascriptInterface public String configureAI(String key, String model) {
            if (aiClient == null) return "{\"error\":\"The app is closing. Reopen it and try again.\"}";
            try {
                aiClient.configure(key, model);
                return aiClient.status().toString();
            } catch (RuntimeException error) {
                JSONObject payload = new JSONObject();
                try { payload.put("error", "The AI settings could not be saved. Check the key and model."); }
                catch (JSONException ignored) { }
                return payload.toString();
            }
        }

        @JavascriptInterface public String configureProviderAI(String key, String model, String provider) {
            if (aiClient == null) return "{\"error\":\"The app is closing. Reopen it and try again.\"}";
            try {
                aiClient.configure(key, model, provider);
                return aiClient.status().toString();
            } catch (RuntimeException error) {
                JSONObject payload = new JSONObject();
                try { payload.put("error", "The AI settings could not be saved. Check the provider, key and model."); }
                catch (JSONException ignored) { }
                return payload.toString();
            }
        }

        @JavascriptInterface public void analyze(String image, String context, String requestId) {
            if (aiClient == null) { publishAnalysis(requestId, null, "The app is closing. Try again after reopening it."); return; }
            aiClient.analyze(image, context, new AiClient.Callback() {
                @Override public void onResult(JSONObject scene) { publishAnalysis(requestId, scene, null); }
                @Override public void onError(String message) { publishAnalysis(requestId, null, message); }
            });
        }
    }

    private void importPhoto(Uri uri) {
        Bitmap decoded = null;
        Bitmap resized = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Missing image");
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 32000 || bounds.outHeight > 32000
                    || (long) bounds.outWidth * bounds.outHeight > 100_000_000L) {
                publishPhoto(null, "Choose a JPEG, PNG, or other Android-supported photo up to 100 megapixels.");
                return;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            options.inSampleSize = 1;
            // Decode at most about 6.6 MP, then reduce to a 1280px preview.
            while ((bounds.outWidth + options.inSampleSize - 1) / options.inSampleSize > 2560
                    || (bounds.outHeight + options.inSampleSize - 1) / options.inSampleSize > 2560) {
                options.inSampleSize *= 2;
            }
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Missing image");
                decoded = BitmapFactory.decodeStream(input, null, options);
            }
            if (decoded == null) {
                publishPhoto(null, "This photo could not be read. Try a JPEG or PNG copy.");
                return;
            }
            if (Thread.currentThread().isInterrupted()) return;
            double scale = Math.min(1.0, 1280.0 / Math.max(decoded.getWidth(), decoded.getHeight()));
            resized = scale < 1.0 ? Bitmap.createScaledBitmap(decoded,
                Math.max(1, (int) Math.round(decoded.getWidth() * scale)),
                Math.max(1, (int) Math.round(decoded.getHeight() * scale)), true) : decoded;
            Matrix orientation = readOrientation(uri);
            if (!orientation.isIdentity()) {
                Bitmap upright = Bitmap.createBitmap(resized, 0, 0, resized.getWidth(), resized.getHeight(), orientation, true);
                if (resized != decoded && resized != upright) resized.recycle();
                resized = upright;
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 78, jpeg)) throw new IOException("Encode failed");
            publishPhoto("data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP), null);
        } catch (IOException | RuntimeException error) {
            publishPhoto(null, "The photo could not be opened. Try a JPEG saved on your phone.");
        } catch (OutOfMemoryError error) {
            publishPhoto(null, "This photo is too large to open. Choose a smaller JPEG copy.");
        } finally {
            if (resized != null && resized != decoded) resized.recycle();
            if (decoded != null) decoded.recycle();
        }
    }

    private Matrix readOrientation(Uri uri) {
        Matrix matrix = new Matrix();
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return matrix;
            int value = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (value) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
                default: break;
            }
        } catch (IOException | RuntimeException ignored) { /* Some supported image formats have no EXIF metadata. */ }
        return matrix;
    }

    private void publishPhoto(String image, String error) {
        JSONObject payload = new JSONObject();
        try {
            if (image != null) {
                payload.put("image", image);
                payload.put("timestamp", System.currentTimeMillis());
            } else payload.put("error", error == null ? "The photo could not be opened." : error);
        } catch (JSONException ignored) { }
        String script = "if(typeof window.onNativePhoto==='function'){window.onNativePhoto("
            + JSONObject.quote(payload.toString()) + ");}";
        runOnUiThread(() -> { if (webView != null && !isDestroyed()) webView.evaluateJavascript(script, null); });
    }

    private void publishAnalysis(String requestId, JSONObject scene, String error) {
        JSONObject payload = new JSONObject();
        try {
            if (scene != null) payload.put("scene", scene);
            else payload.put("error", error == null ? "Analysis failed. Try again." : error);
        } catch (JSONException ignored) { }
        String script = "if(typeof window.onNativeAnalysis==='function'){window.onNativeAnalysis("
            + JSONObject.quote(requestId == null ? "" : requestId) + ","
            + JSONObject.quote(payload.toString()) + ");}";
        runOnUiThread(() -> { if (webView != null && !isDestroyed()) webView.evaluateJavascript(script, null); });
    }
}
