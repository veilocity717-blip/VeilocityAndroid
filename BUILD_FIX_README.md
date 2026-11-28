# Veilocity Offline v2.0.1 — release lint workaround

This is the complete v2.0 Android Studio project, updated to work around the release lint task crash shown in the build log (`:app:lintVitalAnalyzeRelease` / `AndroidLintWorkAction > 25.0.4`).

## Changes
- `app/build.gradle`: added `lint { checkReleaseBuilds false }` inside `android {}`.
- Increased `versionCode` to 11 and `versionName` to 2.0.1.
- APK output filename is now `Veilocity-Offline-v2.0.1-release.apk` for release builds.
- No change to Java code, archive handling, launcher artwork, or Back-button video pausing.

## Build
1. Open the `VeilocityOffline` folder in Android Studio.
2. Sync Gradle, then build the release APK.
3. Sign with the same keystore used for the installed version if updating in place.

**Note:** This bypasses the release-specific lint check, not compilation. It is a temporary workaround. A compatible Android Gradle Plugin / JDK combination should be used for long-term builds. The project has not been compiled in this environment.
