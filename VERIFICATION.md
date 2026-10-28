# Verification · Framewise 0.3.1

Target: Samsung Galaxy S23 FE, Android 15; Sony a6100; still photos.

Completed for 0.3.1 (Linux build box, JDK 17.0.21, Android SDK 35, build-tools 35.0.0, Gradle 8.11.1, AGP 8.9.3):

- `npm test`: 74 passing Node tests (engine, app event paths, dev server, release notes ↔ whats-new.json ↔ versionName consistency, release-notes parsing that hides the `versionCode` line, camera-settings autofill).
- `./gradlew testDebugUnitTest`: 7 JVM unit tests (version comparison, BuildConfig update repo, black-frame detector, Sony shutter-speed parsing, `getEvent` exposure parsing incl. AUTO ISO, live-view packet header resync).
- `./gradlew lintDebug lintRelease`: no issues.
- `assembleRelease` signed with the Framewise release key (cert SHA-256 `355b0ae4…95bc`), verified with `apksigner`; manifest contains the network security config (cleartext only to 192.168.122.1) and the Wi-Fi permissions; `assets/whats-new.json` is packaged.
- Static inspection of Imaging Edge Mobile 7.8.6: `ContextManager.onStop` sends a Wi-Fi off command and closes all screens when the app leaves the foreground — the root cause of the frozen picture and disconnects with screen sharing.

Not performed:

- Installation or runtime execution on the physical S23 FE (no device or emulator acceleration was available).
- The direct camera connection against a real a6100 (Wi-Fi join prompt, Remote API discovery, live view, `getEvent` values). The a6100's support for Wi-Fi live view via the Camera Remote API is based on third-party reports, not tested here.
- An in-app update from 0.3 to 0.3.1 on a device, and the one-time "What's new" card after it.
- Real Gemini or OpenAI requests, battery/performance testing, Play Store release.
