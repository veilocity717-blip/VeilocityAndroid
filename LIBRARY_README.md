# Library support (v2.2)

Place `1_Library` beside the country/language folders in the archive root. Put PDF documents in it. Run `python3 Veilocity_Offline_PC.py` on a PC to create `1_Library/index.html` and add the Library button to the root landing page.

Android v2.2 includes a virtual Library page at `/files/1_Library/index.html` that lists PDFs in the root of `1_Library` even if you have not run the PC script. For nested PDFs, run the PC script to generate an index; the Android virtual page currently lists only PDFs directly in `1_Library`. The Library button is inserted above the Court of Illusions link when present, otherwise before search.

PDFs open in the integrated offline viewer.
