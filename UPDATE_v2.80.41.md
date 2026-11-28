# v2.80.41 — Persistent Court translation

- Remember the selected Court language in Android private storage.
- Save completed translations in app-private files, independently of changing localhost WebView ports.
- Automatically restore saved translation when Court is opened again, including after closing the app.
- Invalidate saved translation when original Court text changes.
- Original English restores text; Clear saved translation removes saved language and cached content.
- Only fully successful translations are cached. The original archive HTML and attachment URLs remain unchanged.
- First translation may require downloading an ML Kit model.

Note: No Android build or device test performed.
