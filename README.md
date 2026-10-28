# Framewise 0.2 · Sony a6100 photo assistant

An Android prototype for a Samsung Galaxy S23 FE running Android 15. It recommends photographic settings while leaving every camera change to the photographer.

## What it does

- Shows a user-shared preview from Sony Imaging Edge Mobile through Android MediaProjection.
- Crops the shared app image locally, displays a preview histogram, and marks covered or delayed input.
- Offers five creative priorities, subject movement choices, lens limits, a maximum ISO, and locks for shutter/aperture/ISO.
- Calculates exposure from explicitly entered current settings and the camera's MM meter reading. Without a meter reading, unlocked ISO remains unknown.
- Explains each recommendation and reports constraints that cannot be satisfied together.
- Lets you choose a subset of recommendations, then record the changes you actually made on the camera. It recalculates exposure warnings for that subset.
- Optionally reads the cropped scene with Google Gemini (free tier available), or OpenAI, once per request or every 30 seconds after explicit activation.
- Stores only camera preferences and a small text decision log locally. API keys and captured frames remain in memory.

## Install the provided prototype

Transfer `Framewise-0.2-android.apk` to your own Android phone and open it. This uses the same signing key and a higher version code than 0.1, so Android can install it as an update. Android may ask you to allow installation from the application you use to open the APK. This is a locally signed **debug prototype**, not a Play Store release. No camera, microphone, file-storage, accessibility or overlay permission is requested. Android's screen-sharing system prompt and a visible foreground-service notification govern live capture.

1. Open Framewise and choose **Explore a sample first** to check the interface offline, or **Open photo** to try a real saved image without a live camera connection.
2. Open **Setup → Camera & lens**. Select the lens label and enter its current focal length and available aperture limits. The lens name alone does not identify its exact version.
3. Connect the a6100 to **Sony Imaging Edge Mobile** over its Wi-Fi, then open remote shooting. On the camera: `MENU → Network → Ctrl w/ Smartphone → On → Connection`.
4. In Framewise choose **Setup → Live view → Share Sony live view**. In the Android permission sheet choose **A single app**, then **Imaging Edge Mobile**. Share the Sony app, not the whole screen or Framewise.
5. On Samsung, open Recents, tap the app icon above its card, select **Open in split screen view**, and choose the other app. Keep Sony's live preview visible. If either application does not allow split screen or sharing, stop and use the offline/sample advisor until a wired capture connection is implemented and tested.
6. Use **Crop** to trim Sony menus and controls out of the shared image.
7. Put the a6100 in **M mode with fixed ISO**. Enter shutter speed, aperture, ISO and the camera's MM meter reading. Positive MM values mean the current exposure is above the camera's metered target. Do not enter a saturated/flashing meter value as an exact reading.
8. Choose your creative direction, lock anything you want to preserve, then tap **Suggest my settings**.
9. Change whichever settings you want on the camera. Check only those settings in the suggestion cards, then confirm that you changed them. Confirmation updates the app's records; there is no camera-write interface.
10. Read the meter again after changing settings or light. Verify with a test photograph and the camera histogram.

## Optional AI

In **Setup → AI connection**, select **Google Gemini · free tier available**. Create a Gemini API key in [Google AI Studio](https://aistudio.google.com/api-keys) using a project on the **Free tier**, then enter it on your own phone. The default model is `gemini-3.1-flash-lite`, a low-latency image-capable model supporting structured output. Google sets per-project/model quotas and account eligibility. Check your active limits in AI Studio; the app cannot read your billing tier or guarantee your project is free. Keep the project on the Free tier for zero API charges. Billing-enabled projects can incur charges.

Enable **Include AI scene reading**, then request a suggestion and accept the frame-sharing dialog. Only the cropped frame and entered camera context are sent to the selected service. **Start live AI** asks permission to send one cropped frame every 30 seconds; single-frame requests are the easiest way to conserve free quota. It stops when the app becomes hidden, the source stops, configuration changes, or an analysis fails. Quota errors stop analysis; there are no automatic retries or switches to another provider. Source changes invalidate in-flight results. Camera Wi-Fi often has no internet; the native client attempts to use a validated cellular network for just the AI connection, without changing Sony's camera connection.

OpenAI remains an explicitly selected paid option with default model `gpt-4.1-mini`. Changing providers forgets the previous session key. Each key is sent only to its selected provider's fixed HTTPS API endpoint.

Cloud image processing is governed by the selected provider's terms. Google's unpaid-service data handling varies by region and project setup; its terms state an EEA/Switzerland/UK exception to the general unpaid-service improvement use. See [Google API terms](https://ai.google.dev/gemini-api/terms). OpenAI requests use `store:false`, which does not establish zero provider retention; see [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data).

## App-specific customization and training

The app supplies your a6100 context, actual entered lens limits, photographic priorities, locked settings, and a domain-specific scene-reading instruction with photography reference cases. This customizes an existing model; it is **not a newly trained or fine-tuned photography model**. No photos have been collected or used for training. The local exposure/constraint kernel remains free and works without a model or API key.

A future fine-tuned model would require appropriately licensed scene examples, actual capture measurements, the photographer's intended result, and evaluated outcomes. EXIF alone does not establish the best creative setting. Fully offline image AI is another possible direction, but no on-device language/vision model is currently bundled or benchmarked on your phone.

## Accuracy and first-device checks

This is a working compiled prototype, **not yet tested with an actual S23 FE and a6100**. The live route depends on Sony IEM allowing app capture and remaining visible in split screen. Shutter release, playback, camera power saving and app updates may pause the camera preview. A fresh screenshot timestamp proves the shared app rendered a frame, not that Sony received a fresh camera frame. Stop sharing from Framewise, Android's sharing indicator, or the foreground notification. Locking the phone can stop capture.

The shared preview carries no dependable shutter/aperture/ISO metadata. Enter those values yourself. A rendered preview cannot establish absolute illumination or RAW highlight headroom; its histogram describes display pixels. Recommendations use your entered MM meter target and creative goal, not an independently measured exposure. Single-frame AI cannot determine subject speed reliably; explicit movement selections take priority. Flash metering, depth/distance calibration, exact lens sharpness models, custom white-balance measurement and a direct camera-control protocol are not implemented.

## Build from source

Requires JDK 17, Android SDK platform 35 and build-tools 35.0.0. Open `android/` in Android Studio, set your local SDK path, and build the debug APK, or run:

```powershell
cd android
.\gradlew.bat assembleDebug
```

The Gradle assets source set packages `../public` relative to the `android` directory (`../../public` relative to `android/app`). Keep the source-tree layout intact. The APK appears at `android/app/build/outputs/apk/debug/app-debug.apk`.

An optional desktop developer companion runs with Node 22 or newer and no npm dependencies:

```powershell
node server.mjs
```

Open the printed localhost URL. A desktop browser can import a photo or read a webcam/capture input. This companion is not the phone's server: the Android APK contains its own UI, capture service and HTTPS AI client.

```powershell
node --test tests/*.test.mjs
```

## Architecture

- `public/engine.mjs`: deterministic exposure/constraint kernel; no DOM, network or image-based light metering.
- `public/app.js`, `index.html`, `styles.css`: bundled responsive UI and manual decision workflow.
- `android/.../MainActivity.java`: allowlisted asset WebView and narrow native bridge.
- `android/.../CaptureService.java`: permission-backed foreground MediaProjection service, one virtual display, resized surface and one in-memory JPEG at up to 1 FPS / 960 pixels.
- `android/.../AiClient.java`: bounded, direct HTTPS Responses requests, structured scene validation, session-only credentials and per-connection internet routing.
- `server.mjs`: optional loopback-only development API; fixed upstream, origin and host checks, bounded requests.

## Primary references

- [Sony a6100 smartphone live view](https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002670835.html)
- [Sony HDMI information display](https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002647431.html)
- [Sony USB webcam limitations](https://support.d-imaging.sony.co.jp/app/webcam/en/instruction/)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Samsung split screen](https://www.samsung.com/us/support/answer/ANS10002022/)
- [OpenAI image inputs](https://developers.openai.com/api/docs/guides/images-vision)
- [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)
- [Gemini image inputs](https://ai.google.dev/gemini-api/docs/image-understanding)
- [Gemini REST API](https://ai.google.dev/api/generate-content)
- [Gemini model capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite)
- [Gemini pricing and free tier](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini active rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)
