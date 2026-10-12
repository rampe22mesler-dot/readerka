Synthetic DjVu fixtures; no user data or third-party book excerpts.

mixed.djvu: three identical 200x300 RGB pages with a red stripe at
x=20..179, y=60..79 (top-left raster coordinates), encoded with c44
from DjVuLibre 3.5.29, resolution 100 dpi, then bundled with djvm.
Only page 2 has the following synthetic UTF-8 OCR, set with djvused:
(page 0 0 200 300 (line 20 220 180 240
 (word 20 220 100 240 "Привет") (word 110 220 180 240 "DJVU")))

rotated.djvu: copy of mixed.djvu with page 2 set-rotation 1 (90 degrees).
OCR remains in original coordinates, testing the import transformation.
