# Veilocity Android v2.8.22 — Starter Arkhive & downloads empty state

- Based on tested v2.8.21 PDF/HTML floating search build fix.
- When the user selects the Arkhive directory using the existing Android folder picker, copy four bundled starter files only if missing: index.html, veilocity-downloads.html, logo.webp, logo2.webp.
- Existing files are never overwritten.
- When no country HTML directories exist, display a Download Country Archives panel and /sdcard/Arkhive/ extraction instructions.
- Support plain unversioned country folders as well as versioned folders.
- Retains SAF storage access (one-time folder selection). The app cannot silently create /sdcard/Arkhive without broader Android storage permission; create/select that folder in the picker first.
- Dynamic archives.js and manifest.js remain generated at request time; refresh after adding folders.
