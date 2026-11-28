# Veilocity Android v2.8.30 — PDF Search Highlight Overlay

- Highlight matches using a transparent view over the PDF image instead of painting on the rendered bitmap.
- Keep the highlight layer in the same page-sized container as the PDF, so it moves with zoom and scrolling.
- Prefer platform PDF search-result rectangles when available; fall back to text-content bounds.
- Clear highlights when Find closes or the PDF is closed.
- Keep HTML search, floating Find, Go to Page, bookmarks, and archive handling unchanged.

**Limitations:** PDF text coordinates depend on Android 15+ PDF APIs and the PDF text layer. Image-only PDFs require OCR, which is not included. Android build/device testing has not been performed.
