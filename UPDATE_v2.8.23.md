# Veilocity Android v2.8.23 — Bundled landing page

- Starts its loopback server and displays the bundled Veilocity homepage without selecting an SD-card folder.
- Serves starter artwork and the download catalogue from APK assets.
- Displays a Download Country Archives notice with `/sdcard/Arkhive/` instructions when no country folders are available.
- Retains the existing Choose Folder control and reads installed archives from the SAF-selected folder.
- Preserves user-owned files and all PDF/image/search functionality.

The app does **not** silently create `/sdcard/Arkhive/`: Android requires the user to select/grant access to that location through the existing folder picker.

Build and device testing are still required.
