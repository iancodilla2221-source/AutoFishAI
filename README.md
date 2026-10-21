# AutoFish AI — Android prototype (tracking-bar adaptation)

This starter Android Studio project now includes an experimental heuristic for the screenshot's vertical fishing bar:
- Looks for a purple/magenta vertical tracking-bar outline.
- Looks for a green target band and a pale bright ball.
- Sends short touch-hold pulses when the ball appears below the target and stops pulsing when it appears above.
- Uses OCR to look for visible Collect/Claim/Catch text when no tracking bar is detected.
- Includes Start/Stop controls and an Android Accessibility Service.

## Important limitations
This is a **best-effort prototype**, not a trained AI model or a tested finished APK. Pixel-color heuristics can fail with different resolutions, themes, visual effects, or game UI changes. The app may tap or hold incorrectly; watch tests and stop it if behavior is wrong. The hold/release mechanics vary by game, and the implementation uses repeated short press gestures as an approximation. It does not guarantee automatic fishing or collection.

Android requires a one-time manual Accessibility permission grant. This cannot be bypassed by an app. No APK has been compiled or tested in this environment.

## Build
Open this folder in Android Studio with Android SDK 35 installed, sync Gradle, then Build > Build APK(s).
