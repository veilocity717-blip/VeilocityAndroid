# v2.8.13 Court image previews

- Background pre-generation after the archive folder is selected or on later launches.
- Reduced-resolution JPEG previews (maximum edge 1600 px, quality 82) in private `court-photo-previews` storage.
- Full-resolution images remain available for zoom; existing original-photo cache preserved.
- 120 MB preview cache limit; no archive files are modified.
- Initial cache preparation can take time and first views may still read originals.
- Preview URLs are only used by the overlay; inline images retain their normal URLs.
- Image loading and device performance have not been tested.
