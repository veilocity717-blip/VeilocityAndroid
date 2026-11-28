# Veilocity Offline — Android v1.9

Based on the existing working Veilocity Arkhive v1.8 project.

Changes:
- Android launcher name: **Veilocity Offline**.
- Launcher icon: provided Veilocity logo, with adaptive and legacy icons.
- Signed APK filename: `Veilocity-Offline-v1.9-release.apk`.
- Version code 9, version name 1.9.
- Original app ID `org.veilocity.arkhive` preserved for upgrades.
- Existing video playback and collapsible controls left unchanged.

## Build
1. Open this project in Android Studio.
2. Sync Gradle.
3. Build > Generate Signed Bundle / APK > APK.
4. Choose release and sign using the **same keystore** as the installed version.
5. Install the generated APK.

The logo embedded in the APK is the Android launcher icon. To display the same logo on the HTML archive landing page, also place `logo.png` in the **root of the archive folder** alongside `index.html` and `archives.js`, then regenerate `archives.js` with the manifest script if needed.

This project has not been compiled or device-tested in this environment.

## v2.0 — Stop video on Back

Both the on-screen Back arrow and Android system Back pause all HTML5 videos in the current page before navigating backward. The existing click-to-load and single-video playback behavior is preserved. This targets videos in the main document; cross-origin iframe players are not controlled.
