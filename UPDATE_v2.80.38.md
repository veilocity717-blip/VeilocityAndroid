# v2.80.38 — Court translation navigation fix

- Fixed the injected Court translation JavaScript: emit actual newline characters rather than literal backslash-n sequences.
- Intercept the Court page Google Translate link at the Android WebView layer and focus the in-app ML Kit selector instead.
- Applies only to local ENGLISH - Court.html. All attachment/media viewers remain unchanged.
- Updated versionName 2.80.38 and versionCode 318.

Full Android build and device testing have not been performed.
