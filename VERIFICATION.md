# Verification · Framewise 0.3.0

Target: Samsung Galaxy S23 FE, Android 15; Sony a6100; still photos.

Completed for 0.3.0 (Linux build box, JDK 17.0.21, Android SDK 35, build-tools 35.0.0, Gradle 8.11.1, AGP 8.9.3):

- `npm test`: 69 passing Node tests (engine, app event paths incl. kept-Sony-frame analysis, dev server).
- `./gradlew testDebugUnitTest`: 3 JVM unit tests (release/version comparison, BuildConfig update repo, black-frame detector).
- `./gradlew lintDebug lintRelease`: no issues.
- `assembleDebug` and `assembleRelease`; release APK signed with the Framewise release key (APK Signature Scheme v3) and verified with `apksigner`.
- Static inspection of Imaging Edge Mobile 7.8.6 (`com.sony.playmemories.mobile`) manifest: `finishOnCloseSystemDialogs=true` and `excludeFromRecents=true` on its screens, `resizeableActivity=false`; no FLAG_SECURE / `setSecure` calls and no screen-recording detection callbacks were found in its bytecode.

Not performed:

- Installation or runtime execution on the physical S23 FE (no device or emulator acceleration was available).
- End-to-end MediaProjection with Imaging Edge and the a6100, including whether the notification shade closes Imaging Edge.
- An in-app update from one release to a newer one on a device.
- Real Gemini or OpenAI requests, battery/performance testing, Play Store release.
