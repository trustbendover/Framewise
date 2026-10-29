# Framewise 0.3.1 · Sony a6100 photo assistant

An Android prototype for a Samsung Galaxy S23 FE running Android 15. It recommends photographic settings while leaving every camera change to the photographer.

## What it does

- Shows the a6100 live view directly over the camera's own Wi-Fi (Sony Camera Remote API).
- Crops the shared app image locally, displays a preview histogram, and marks covered or delayed input.
- Offers five creative priorities, subject movement choices, lens limits, a maximum ISO, and locks for shutter/aperture/ISO.
- Calculates exposure from explicitly entered current settings and the camera's MM meter reading. Without a meter reading, unlocked ISO remains unknown.
- Explains each recommendation and reports constraints that cannot be satisfied together.
- Lets you choose a subset of recommendations, then record the changes you actually made on the camera. It recalculates exposure warnings for that subset.
- Optionally reads the cropped scene with Google Gemini (free tier available), or OpenAI, once per request or every 30 seconds after explicit activation.
- Stores only camera preferences and a small text decision log locally. API keys and captured frames remain in memory.

## What's new in 0.3.1

- Live view now connects straight to your camera over its own Wi-Fi. You no longer need the Sony app.
- Framewise reads your shutter speed, aperture and ISO from the camera and fills them in for you.
- AI suggestions keep using your mobile data while you're connected to the camera.
- The Sony app option is still there, but it disconnects as soon as you switch away from it, so the direct connection is the better choice.
- You'll now see these "What's new" notes before you update, and once after the update.

Notes for every version are in [`release-notes/`](release-notes/) (see [CHANGELOG.md](CHANGELOG.md)).

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

1. Raise `versionCode` and `versionName` in `android/app/build.gradle` (e.g. `5` / `0.3.2`).
2. Write the patch notes in `release-notes/v0.3.2.md`: a `## What's new in Framewise 0.3.2` heading and short, friendly bullet points (no jargon, e.g. "Live view now connects straight to your camera"). Add the file to `CHANGELOG.md` and copy the same bullets into `public/whats-new.json` (the tests check they match). Don't add a `versionCode` line; the workflow appends it.
3. Commit, then tag and push: `git tag v0.3.2 && git push origin v0.3.2`.
4. `.github/workflows/release.yml` runs the tests, checks the tag matches `versionName` and that the notes file exists, builds a release APK signed with the release key, verifies the signature, and publishes `Framewise-0.3.2-android.apk` plus `.sha256` as a GitHub Release. The release description is the notes file plus a final `versionCode: N` line that the in-app updater reads. Phones show the notes as "What's new in 0.3.2" before installing, and once more after the update.

Required repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `FRAMEWISE_KEYSTORE_BASE64` | `base64 -w0 framewise-release.jks` |
| `FRAMEWISE_KEYSTORE_PASSWORD` | keystore password |
| `FRAMEWISE_KEY_ALIAS` | key alias (`framewise`) |
| `FRAMEWISE_KEY_PASSWORD` | key password |

The keystore itself is never committed (`*.jks` is git-ignored). Keep a private backup: if it is lost, every user has to uninstall once to move to a new key. Locally, a signed release builds with `FRAMEWISE_KEYSTORE_FILE=/path/framewise-release.jks` plus the three password/alias variables exported, then `./gradlew assembleRelease`.

## Live view: connect straight to the camera

**Set up the camera once (a6100):**

1. MENU → Network → **Ctrl w/ Smartphone** → **On**. Set **Always Connected** to On if you like, and make sure **Airplane Mode** is Off.
2. Choose **Connection**. The camera shows a QR code; press the **Delete (trash) button** to see the Wi-Fi name (`DIRECT-xxxx:ILCE-6100`) and password.
3. Close (force-stop) Imaging Edge Mobile. The camera only talks to one app at a time.

**In Framewise:** Setup → Connection → type the camera Wi-Fi password (the name is optional) → **Connect to camera** → pick the camera in Android's "Connect to device" prompt. Framewise then:

- joins only the camera Wi-Fi for its own camera traffic (Android `WifiNetworkSpecifier`); the phone keeps mobile data as its main connection, so AI and update checks still work;
- finds the camera's Remote API (SSDP, then `192.168.122.1:10000`/`:8080`), switches it to remote shooting and starts live view;
- shows the newest live-view frame (only the latest frame is kept, in memory) and reads shutter, aperture, ISO, exposure mode and compensation once a second (`getEvent`) to fill in the manual fields.

Plain HTTP is allowed **only** to `192.168.122.1` (`res/xml/network_security_config.xml`); everything else stays HTTPS-only. The password is stored only on the phone (app-private settings) when *Remember* is on.

If the camera connects but live view never starts, your firmware may limit Wi-Fi remote control. Sony documents live view over **USB** for the a6100 (MENU → Network → **PC Remote Function** → PC Remote, with Ctrl w/ Smartphone Off). A USB-C cable connection is planned for 0.4.

### Removed: sharing Imaging Edge's screen (0.3.2)

Imaging Edge Mobile sends the camera a Wi-Fi off command and closes as soon as it leaves the foreground, so screen sharing could never give a steady picture. 0.3.2 removed that option along with its screen-capture, notification and overlay permissions.

## Using Framewise

1. Open Framewise and choose **Explore a sample first** to check the interface offline, or **Open photo** to try a real saved image without a live camera connection.
2. Open **Setup → Camera & lens**. Select the lens label and enter its current focal length and available aperture limits. The lens name alone does not identify its exact version.
3. On the camera: `MENU → Network → Ctrl w/ Smartphone → On → Connection`.
4. In Framewise choose **Setup → Live view**, enter the camera Wi-Fi password and tap **Connect to camera**.
5. Optionally use **Crop** to trim the preview before AI analysis.
6. Put the a6100 in **M mode with fixed ISO**. Enter shutter speed, aperture, ISO and the camera's MM meter reading. Positive MM values mean the current exposure is above the camera's metered target. Do not enter a saturated/flashing meter value as an exact reading.
7. Choose your creative direction, lock anything you want to preserve, then tap **Suggest my settings**.
8. Change whichever settings you want on the camera. Check only those settings in the suggestion cards, then confirm that you changed them. Confirmation updates the app's records; there is no camera-write interface.
9. Read the meter again after changing settings or light. Verify with a test photograph and the camera histogram.

## Optional AI

In **Setup → AI connection**, select **Google Gemini · free tier available**. Create a Gemini API key in [Google AI Studio](https://aistudio.google.com/api-keys) using a project on the **Free tier**, then enter it on your own phone. The default model is `gemini-3.1-flash-lite`, a low-latency image-capable model supporting structured output. Google sets per-project/model quotas and account eligibility. Check your active limits in AI Studio; the app cannot read your billing tier or guarantee your project is free. Keep the project on the Free tier for zero API charges. Billing-enabled projects can incur charges.

Enable **Include AI scene reading**, then request a suggestion and accept the frame-sharing dialog. Only the cropped frame and entered camera context are sent to the selected service. **Start live AI** asks permission to send one cropped frame every 30 seconds; single-frame requests are the easiest way to conserve free quota. It stops when the app becomes hidden, the source stops, configuration changes, or an analysis fails. Quota errors stop analysis; there are no automatic retries or switches to another provider. Source changes invalidate in-flight results. Camera Wi-Fi often has no internet; the native client attempts to use a validated cellular network for just the AI connection, without changing Sony's camera connection.

OpenAI remains an explicitly selected paid option with default model `gpt-4.1-mini`. Changing providers forgets the previous session key. Each key is sent only to its selected provider's fixed HTTPS API endpoint.

Cloud image processing is governed by the selected provider's terms. Google's unpaid-service data handling varies by region and project setup; its terms state an EEA/Switzerland/UK exception to the general unpaid-service improvement use. See [Google API terms](https://ai.google.dev/gemini-api/terms). OpenAI requests use `store:false`, which does not establish zero provider retention; see [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data).

## App-specific customization and training

The app supplies your a6100 context, actual entered lens limits, photographic priorities, locked settings, and a domain-specific scene-reading instruction with photography reference cases. This customizes an existing model; it is **not a newly trained or fine-tuned photography model**. No photos have been collected or used for training. The local exposure/constraint kernel remains free and works without a model or API key.

A future fine-tuned model would require appropriately licensed scene examples, actual capture measurements, the photographer's intended result, and evaluated outcomes. EXIF alone does not establish the best creative setting. Fully offline image AI is another possible direction, but no on-device language/vision model is currently bundled or benchmarked on your phone.

## Accuracy and first-device checks

Direct Wi-Fi live view has been confirmed working on a real a6100 with a Galaxy S23 FE. Shutter release, playback, camera power saving and app updates may pause the preview.

Shutter, aperture and ISO are read from the camera when connected; check them before relying on them. A rendered preview cannot establish absolute illumination or RAW highlight headroom; its histogram describes display pixels. Recommendations use your entered MM meter target and creative goal, not an independently measured exposure. Single-frame AI cannot determine subject speed reliably; explicit movement selections take priority. Flash metering, depth/distance calibration, exact lens sharpness models, custom white-balance measurement and a direct camera-control protocol are not implemented.

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
- `android/.../SonyCameraClient.java`: direct Wi-Fi connection to the camera's Remote API, live-view frames and settings readout.
- `.github/workflows/release.yml`: tag-triggered signed release build and GitHub Release publishing.
- `android/.../UpdateManager.java`, `UpdateReceiver.java`: GitHub Releases check, checksum/signature-verified download and PackageInstaller session install.
- `android/.../AiClient.java`: bounded, direct HTTPS Responses requests, structured scene validation, session-only credentials and per-connection internet routing.
- `server.mjs`: optional loopback-only development API; fixed upstream, origin and host checks, bounded requests.

## Primary references

- [Sony a6100 smartphone live view](https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002670835.html)
- [Sony HDMI information display](https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002647431.html)
- [Sony USB webcam limitations](https://support.d-imaging.sony.co.jp/app/webcam/en/instruction/)
- [Samsung split screen](https://www.samsung.com/us/support/answer/ANS10002022/)
- [OpenAI image inputs](https://developers.openai.com/api/docs/guides/images-vision)
- [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)
- [Gemini image inputs](https://ai.google.dev/gemini-api/docs/image-understanding)
- [Gemini REST API](https://ai.google.dev/api/generate-content)
- [Gemini model capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite)
- [Gemini pricing and free tier](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini active rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)
