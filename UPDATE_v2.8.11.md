# v2.8.11 — Fold5 court-image cache and overlay controls

- Stores a bounded, persistent cache of original Court archive images in the app's private files directory (`court-photo-cache`). This does not modify the selected archive.
- Starts warming the cache in the background after the archive folder is selected and on subsequent app launches. Android does not permit reading a user's archive before folder access has been granted.
- Serves cached Court images through the same localhost URL once they are ready; uncached images continue to load from the original archive. The first load may remain slow while caching is underway.
- Cache limit: approximately 300 MiB; maximum individual cached image: 24 MiB. Large images still load directly.
- Moves the image-overlay controls inward from the right edge and reduces their widths for narrow or folded displays.
- Preserves inline images, zoom/pan and dismissible overlay from v2.8.10.

Notes: Caching original images does not reduce their decode cost. This is a targeted storage-read improvement, not a guarantee of faster first display. Clearing app data removes the private cache. Re-selecting a different archive creates separate cache keys. If archive files change at the same path, clear app data to remove stale cached images (this also resets app preferences), or reinstall after removing app data.

Build and device tests were not performed in this environment.
