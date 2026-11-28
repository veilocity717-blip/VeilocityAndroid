# Veilocity Android v2.8.24 — Court Empty State & Folder Guidance

- Missing `court-of-illusions-trafficking/ENGLISH - Court.html` now shows a styled, helpful Court page instead of an HTTP error.
- Shows expected `/sdcard/Arkhive/court-of-illusions-trafficking/` location and a Download Court Archive link.
- When no folder is selected, shows a visual copy of the ▣ folder-selection icon and an actionable Choose Arkhive Folder button.
- The guidance is absent after a folder is selected; a missing Court document still gets clear installation instructions.
- The Choose/Change Folder button opens the existing Android folder picker.
- Existing Court files are served unchanged. No changes to PDFs, attachments, floating search, Library or country navigation.

## Test
1. Launch without selecting an Arkhive folder and tap Court: styled empty state and choose button.
2. Tap Choose Arkhive Folder: Android folder picker opens.
3. Select a folder with no Court: missing-Court page without initial-selection guidance.
4. Add `court-of-illusions-trafficking/ENGLISH - Court.html` and reopen: actual offline Court loads.
5. Verify PDF and HTML floating search, Library and downloads.
