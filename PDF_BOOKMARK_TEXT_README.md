# Veilocity v2.3.4 — PDF bookmark labels

Bookmark entries show `Page N — First readable line`, with the text limited to 25 Unicode characters. If no selectable text is available, the entry displays `Page N` only.

This uses Android's built-in PDF text extraction where available (newer Android releases). On older Android releases and image-only/scanned PDFs, bookmark labels remain page-number-only. No OCR or external services are used. Existing saved bookmarks remain compatible.
