# Veilocity Android v2.8.21 — Floating PDF Find

Based on the working v2.8.20 PDF layout fix.

- Replaces the PDF search input and results dialogs with a compact draggable non-modal panel.
- Uses the existing Android 15+ PdfRenderer text extraction.
- Previous/Next cycles through matching **pages**; the panel shows matching-page counts.
- Search runs in small UI-loop batches, and typing a new query cancels the previous scan.
- Closing a PDF closes its floating search panel and cancels any pending scan.
- HTML floating Find, PDF layout, zoom, bookmarks, SD-card files and navigation are otherwise unchanged.

Note: Text search does not work on scanned/image-only PDFs without extractable text. Matches are identified by page; text within the rendered PDF is not highlighted.

Build and device testing are still required.
