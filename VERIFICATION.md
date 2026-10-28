# Verification · Framewise 0.2.0

Target: Samsung Galaxy S23 FE, Android 15; Sony a6100; still photos.

Completed in this workspace:

- Android SDK 35 / JDK 17 debug APK compilation.
- Android lint checks (final report packaged with source).
- APK v2 signature verification and package/permission inspection.
- 68 passing tests: 21 exposure-engine cases, 8 application event-path cases and 39 development-server/provider cases. HTTP tests ran with permitted loopback access and fake AI providers; no provider requests were made.
- Browser verification at 393 × 852 pixels: no horizontal overflow, lens setup, creative selection, locked aperture, metered ISO calculation, partial-plan exposure warning, selective confirmation, clearing the meter after confirmation and no console errors.
- Version 0.2 phone-layout checks: Gemini default and model, correct Google key/data links, switching to OpenAI resets model and clears the key, switching back restores Gemini setup, no console errors.
- Screenshot `Framewise-0.2-free-api-preview.png` shows the browser-rendered free-tier setup at phone dimensions. It is not a screenshot from the physical phone.

Not performed:

- Installation or runtime execution on the physical S23 FE.
- Sony Imaging Edge Mobile split screen/capture compatibility on that phone.
- Camera behavior during shutter release, playback, changes in light, autofocus, power saving or disconnection.
- Real Gemini or OpenAI requests, API-key validity, billing tier, model access or live cellular routing.
- Instrumentation, battery/performance testing or a production/Play Store release.

These limits are material. The compiled APK is a first prototype for device testing. Local guidance is based on entered camera measurements; it does not independently meter the scene. Camera values and meter readings must be kept in sync manually. No camera settings are written by the app.
