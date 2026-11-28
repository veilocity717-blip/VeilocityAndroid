# VeilocityAndroid v2.8.17 — unified image overlay

- Uses the same overlay for images opened from Court, country archives and the Library.
- Isolates the overlay in a Shadow DOM so country-page CSS cannot alter the viewer's image sizing, layout or drag surface.
- Keeps the image preview cache, full-resolution loading, Fit, zoom, unrestricted drag, and dismiss controls.
- Library image links now point to the underlying image and are intercepted by the global overlay.
- Removes unused country-specific image click-routing code.
- Preserves the HTML document under the overlay, rather than navigating to a separate viewer.

The APK has not been compiled or tested on an Android device. Test on the Fold5 and S25 Ultra, especially portrait country images, Court images, and Library images.
