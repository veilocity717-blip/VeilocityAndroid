# Veilocity Offline v2.1 — Offline PDF viewing

- Tap a local PDF link inside an archive HTML page to open it in the app.
- PDF pages are rendered locally with Android PdfRenderer; no network or external PDF application is required.
- Use the ◀ and ▶ buttons to move between pages. Tap ✕ or Android Back to return to the HTML page.
- Existing click-to-load MP4, single-video playback, Back-button pause, launcher artwork and navigation controls are retained.
- Only PDF links served from the selected local archive (`http://127.0.0.1:<port>/files/...pdf`) are intercepted. Remote PDF URLs retain existing external-link handling.
- PDFs are temporarily copied into the app cache because PdfRenderer requires a seekable file descriptor; the temporary file is removed when the viewer closes.
- Password-protected or damaged PDFs may not open. Very large pages may take time to render.
- The project was not compiled in this environment. Build in Android Studio using the existing keystore.
- Release lint workaround from v2.0.1 is retained.
