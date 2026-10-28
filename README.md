# Framewise 0.3 · Sony a6100 photo assistant

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

## What's new in 0.3

- **Auto-updater** from this repository's [GitHub Releases](https://github.com/trustbendover/Framewise/releases): Framewise checks on launch (at most every 6 hours) and from **Setup → Updates → Check for updates**.
- **Live view fix** for Imaging Edge Mobile (see [Live view: why it kicked you out](#live-view-why-it-kicked-you-out)).
- Step-by-step sharing status messages, black-frame (protected content) detection, an optional floating **Framewise** button over Sony, and recovery when Android recreates the screen.
- Release builds are signed with a dedicated release key by GitHub Actions.

## Install

Download `Framewise-<version>-android.apk` from the [latest release](https://github.com/trustbendover/Framewise/releases/latest) on the phone and open it. Android asks once to allow installs from your browser or file manager.

> **One-time step when coming from 0.2:** the 0.2 test APK was signed with a debug key; 0.3 and later are signed with the Framewise release key. Android cannot update an app across signing keys, so **uninstall Framewise 0.2 once**, then install 0.3. (Only the saved camera preferences and the session notes are lost; API keys were never stored.) From then on updates install inside the app.

No camera, microphone or file-storage permission is requested. Android's screen-sharing prompt and a visible notification govern live capture. *Install unknown apps* (for in-app updates) and *Display over other apps* (for the optional floating button) are asked for only when you use those features.

## Updates

1. On launch Framewise reads `https://api.github.com/repos/trustbendover/Framewise/releases/latest` (public, no token). If the release tag (e.g. `v0.3.1`, or a `versionCode: N` line in the notes) is newer than the installed version, a banner appears.
2. **Setup → Updates → Install** downloads the release's `.apk` asset and its `.sha256` asset, checks the SHA-256, and checks that the APK is Framewise with a higher versionCode **signed by the same key** as the installed app. Any mismatch deletes the file and installs nothing.
3. The first time, Android opens *Install unknown apps* for Framewise; switch on *Allow from this source* and go back. The download continues automatically.
4. The APK is installed through a `PackageInstaller` session; Android shows its own confirmation, then Framewise restarts as the new version.

Updates need internet: the camera's Wi-Fi has none, so check on mobile data or home Wi-Fi.

## Releasing a new version (maintainer)

1. Raise `versionCode` and `versionName` in `android/app/build.gradle` (e.g. `4` / `0.3.1`) and commit.
2. Tag and push: `git tag v0.3.1 && git push origin v0.3.1`.
3. `.github/workflows/release.yml` runs the tests, checks the tag matches `versionName`, builds a release APK signed with the release key, verifies the signature, and publishes `Framewise-0.3.1-android.apk` plus `Framewise-0.3.1-android.apk.sha256` as a GitHub Release. Phones pick it up on their next check.

Required repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `FRAMEWISE_KEYSTORE_BASE64` | `base64 -w0 framewise-release.jks` |
| `FRAMEWISE_KEYSTORE_PASSWORD` | keystore password |
| `FRAMEWISE_KEY_ALIAS` | key alias (`framewise`) |
| `FRAMEWISE_KEY_PASSWORD` | key password |

The keystore itself is never committed (`*.jks` is git-ignored). Keep a private backup: if it is lost, every user has to uninstall once to move to a new key. Locally, a signed release builds with `FRAMEWISE_KEYSTORE_FILE=/path/framewise-release.jks` plus the three password/alias variables exported, then `./gradlew assembleRelease`.

## Live view: why it kicked you out

Imaging Edge Mobile (`com.sony.playmemories.mobile`, inspected in version 7.8.6) declares `android:finishOnCloseSystemDialogs="true"` on its home, Wi-Fi, camera-list and remote-shooting screens, marks them `excludeFromRecents`, and sets `resizeableActivity="false"`. In practice:

- Whenever Android sends its "close system dialogs" signal (pressing Home, opening Recents, and most likely the system screen-sharing picker as it hands over to the chosen app), Android finishes those Imaging Edge screens, even when they are in the background. This is the most likely cause of the "it opens and instantly kicks me out" symptom: the shared app vanishes and Android ends the capture. It matches the manifest, but it has not been confirmed on the phone.
- Imaging Edge does **not** support split screen on phones, so the 0.2 instructions (single app + split screen) could not work.

What 0.3 does instead:

1. **Share Sony live view** asks Android for an **entire-screen** share (Android 14+ `MediaProjectionConfig.createConfigForDefaultDisplay()`), then Framewise **opens Imaging Edge itself** with a normal app launch (no system UI involved).
2. While Sony is on screen, Framewise keeps the newest frame (1 per second, in memory only). Frames are ignored while Framewise itself is on screen, and the frame grabbed during the switch back is discarded, so the last Sony image stays.
3. Go back to Framewise with the floating **Framewise** button (optional, needs *Display over other apps*) or the notification's **Back to Framewise** action — not Home/Recents. Use **Open Sony app** to return to the camera.
4. The status line reports each step (notification permission → Android prompt → service → capture → frames) and the reason if Android ends sharing. If sharing ends within seconds without frames, Framewise explains the Imaging Edge behaviour. All-black frames are flagged as protected content or a stopped preview.
5. Android 14/15 rules are respected: typed `mediaProjection` foreground service started before `getMediaProjection`, one use per consent token, callback registered before the single virtual display, resize handled on that display. The activity no longer restarts on split-screen/dark-mode/density changes, and the page reattaches to a running capture.

**Share a single app instead** still offers Android's original picker for testing, but because the picker relaunches Imaging Edge it is expected to show the old behaviour.

Not yet verified on a real S23 FE + a6100 (no device was available while building 0.3). In particular, whether opening the notification shade also closes Imaging Edge needs a phone test; the floating button avoids the shade entirely.

## Using Framewise

1. Open Framewise and choose **Explore a sample first** to check the interface offline, or **Open photo** to try a real saved image without a live camera connection.
2. Open **Setup → Camera & lens**. Select the lens label and enter its current focal length and available aperture limits. The lens name alone does not identify its exact version.
3. On the camera: `MENU → Network → Ctrl w/ Smartphone → On → Connection`.
4. In Framewise choose **Setup → Live view**. Optionally tap **Allow a Framewise button over Sony** once. Then tap **Share Sony live view** and **Start** in Android's prompt (entire screen). Framewise opens Imaging Edge.
5. In Imaging Edge connect to the camera and open remote shooting. When the preview is showing, come back with the floating **Framewise** button or the notification's **Back to Framewise** (avoid Home and Recents, they make Imaging Edge close).
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

This is a working compiled prototype, **not yet tested with an actual S23 FE and a6100**. The live route depends on Imaging Edge staying open while it is shared (see above). Shutter release, playback, camera power saving and app updates may pause the camera preview. A fresh screenshot timestamp proves the shared app rendered a frame, not that Sony received a fresh camera frame. Stop sharing from Framewise, Android's sharing indicator, or the foreground notification. Locking the phone can stop capture.

The shared preview carries no dependable shutter/aperture/ISO metadata. Enter those values yourself. A rendered preview cannot establish absolute illumination or RAW highlight headroom; its histogram describes display pixels. Recommendations use your entered MM meter target and creative goal, not an independently measured exposure. Single-frame AI cannot determine subject speed reliably; explicit movement selections take priority. Flash metering, depth/distance calibration, exact lens sharpness models, custom white-balance measurement and a direct camera-control protocol are not implemented.

## Build from source

Requires JDK 17, Android SDK platform 35 and build-tools 35.0.0. Open `android/` in Android Studio, set your local SDK path, and build the debug APK, or run (a debug build is signed with your own debug key, so it cannot update a release install):

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
- `android/.../CaptureService.java`: permission-backed foreground MediaProjection service, one virtual display, resized surface, black-frame detection, optional floating return button and one in-memory JPEG at up to 1 FPS / 960 pixels.
- `.github/workflows/release.yml`: tag-triggered signed release build and GitHub Release publishing.
- `android/.../UpdateManager.java`, `UpdateReceiver.java`: GitHub Releases check, checksum/signature-verified download and PackageInstaller session install.
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
