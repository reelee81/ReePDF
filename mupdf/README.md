# MuPDF build for ReePDF

ReePDF uses its own build of the MuPDF Java bindings for Android instead of
the `com.artifex.mupdf:fitz` package from <https://maven.ghostscript.com>.
The build is MuPDF 1.28.6 with the eighteen patches in [`patches/`](patches),
modified for ReePDF on 2026-11-08, 2026-11-10, 2026-11-11 and 2026-11-12.

## Modifications

1. `0001` — adds the missing JNI binding for
   `PDFDocument.setPageTreeCache(boolean)`. The Java method and the C
   function `pdf_set_page_tree_cache()` exist upstream, but the JNI
   implementation is missing, so the call fails with `UnsatisfiedLinkError`.
2. `0002` — adds `Context.releaseFreeMemory()`. It asks the Android
   allocator to return freed native memory to the system
   (`mallopt(M_PURGE_ALL)` from Android 14, `mallopt(M_PURGE)` from
   Android 9) and returns `false` where no such call exists (Android 8,
   non-Android builds). It does not empty the store.
3. `0003` — decodes JPEG 2000 (JPX) images at a reduced resolution when they
   are drawn scaled down. Upstream always decodes them at full resolution and
   scales afterwards. The reduction is skipped for indexed images, JP2 files
   with a palette box and images with a colour key, and the image is decoded
   again at full resolution if the reduced decode fails.
4. `0004` — adds `PDFDocument.clearXref()`, the Java binding of the C
   function `pdf_clear_xref()`. A document keeps every object it has parsed
   until it is closed; the call drops the cached objects that nothing else
   references, so they are parsed again from the file when needed. It must
   not be called on a repaired document (`wasRepaired()`), because repaired
   objects may not be parsed the same way from the file again.
5. `0005` — stops the long steps of an aborted page run early. Upstream
   notices a cookie abort only between two content stream operators, so one
   long operator (decoding or scaling a large image, rasterizing a huge path,
   converting an image through a tint transform, building an ICC transform)
   runs to its end after the abort. While a page is run with a cookie, the
   abort flag is now reachable from the context (and the calling thread):
   image decoding, the decode filters and readers, shading function sampling
   and ICC transform building throw an abort error, so nothing incomplete is
   stored; scaling, painting, blending, glyph drawing, path rasterizing,
   shading and colour conversion stop early, because their result is only
   drawn. Pattern tiles and glyphs drawn during an abort are not cached, and
   an abort is never turned into a stored fallback (end of stream, substitute
   font, full resolution JPEG 2000 retry). When the colour key step of a
   JPEG 2000 image fails, the decoded image is dropped too (upstream leaks
   it). Without an abort the output is unchanged. It needs `0006`–`0008`.
6. `0006` — adds `opj_set_abort_handler()` to OpenJPEG: the decoder asks the
   handler between code-blocks and every few rows of the wavelet, colour and
   level shift steps, and fails when it returns true.
7. `0007` — adds `jbig2_ctx_set_abort_callback()` to jbig2dec: the decoder
   asks the callback during arithmetic and MMR decoding and fails when it
   returns non-zero.
8. `0008` — adds `cmsSetAbortCheck()` to lcms2: building a transform asks the
   check while resampling a CLUT and reversing a tone curve, and that step
   fails when it returns true.
9. `0009` — decodes only the needed area of a JPEG 2000 (JPX) image when a
   part of it is drawn (a zoomed tile). Upstream always decodes the whole
   image (a zoomed tile of a 10200x14031 image needed about 2 GB). The area
   is decoded at the same reduced resolution as the whole image would be,
   placed on the same pixel grid, and subsampled with the same blocks, so
   tiles drawn from areas match each other and the whole image; the 90% rule
   of the image decode tuning does not apply to JPX images, and an area is
   not snapped to whole pixels again when it is scaled. The OpenJPEG stream
   skip callback returns the number of skipped bytes instead of the new
   position (skipping unused tiles of an area failed). Without an area
   request the output is unchanged. It needs `0010`.
10. `0010` — OpenJPEG decodes a requested area exactly like the same area of
    the whole image: the code-block margin for the 9/7 filter is 4 samples
    instead of 3 (tables F.2 and F.3 of the standard), and the buffer of the
    partial 5/3 transform is cleared.
11. `0011` — fixes the sub-pixel position of vertically flipped images in
    `fz_scale_pixmap_cached()`: the offset was taken from the bottom edge
    instead of the top, so an image at a fractional position was drawn as
    if mirrored around the half pixel. Images on whole pixels are unchanged.
12. `0012` — a sampled function no longer reads the second neighbouring
    sample of an input whose weight is exactly zero (the input lies on a
    sample, or the dimension has a single sample). Upstream reads 2^m
    samples for every evaluation of a function with m inputs, so a page with
    an 18-input shading function took seconds to draw. The result is the
    same value bit for bit.
13. `0013` — a NaN function input is taken as the start of the function's
    domain in all four function types. Upstream lets NaN through the domain
    clamp; a sampled function then turns it into an array index, which reads
    outside the samples on x86 (ARM gives 0 and a NaN output). Numbers read
    from a PDF are never NaN, only computed inputs can be.
14. `0014` — `fz_reset_rasterizer()` resets an empty clip to an empty clip of
    zero size instead of multiplying the infinite coordinates of the empty
    rectangle by the antialiasing scale (a signed integer overflow). Nothing
    is drawn either way.
15. `0015` — the colour values and steps of a mesh shading edge are converted
    to fixed point with saturation at the int limits (NaN as 0), and the
    steps are added with an explicit wrap-around. Upstream uses a plain cast,
    which overflows for an almost horizontal edge: x86 gives `INT_MIN`, ARM
    saturates. The result is now what ARM gave before, on every platform.
    Adds `fz_saturate_float_to_int()` to `draw-imp.h`.
16. `0016` — the tile range of a pattern is converted to int with the same
    saturation (upstream: a plain cast, which overflows for an empty area far
    from the pattern origin). It needs `0015`.

`0013`–`0016` remove undefined behaviour found with UBSan on the test corpus
and with a random function test; on Android (ARM) the output is unchanged.

17. `0017` — a document remembers where the data of each inline image ends
    (8 bytes per image, at most 4 MB per document), so running the same
    content again copies the image data without decoding it. Upstream decodes
    every inline image on every run only to find its end. It is used only for
    images without a filter or with a single image filter, not for older
    document versions or while a local xref is active, and it is dropped
    whenever an object, a stream or the xref changes. The output is the same.
18. `0018` — a tiling pattern whose cell is larger than 8 million pixels in
    device space is drawn by drawing its repeats directly, not by drawing one
    cell into a tile and repeating the tile. Upstream does this only when the
    area needs a single repeat; at a high zoom a cell tile can be over the
    pixmap size limit (the page fails with "Overly large image") or take
    hundreds of MB. The repeats are placed exactly instead of on whole pixels,
    so their edges can be anti-aliased slightly differently.

Everything else is unchanged upstream code. The Java package stays
`com.artifex.mupdf.fitz`.

## Sources

`build.sh` downloads exactly these commits from
<https://github.com/ArtifexSoftware>:

- `mupdf` `da5f5eb890d7b3fb76903160b5f7f48ba1301492` (tag `1.28.6`; the
  build setup follows `mupdf-android-fitz` 1.28.5, the newest upstream
  Android release)
- the third-party submodules of that commit that the Android build uses,
  listed with their commits in `build.sh`

## Building

Requirements: Android NDK r30 (`30.0.16248370`), Android SDK platform
`android-37.0`, GNU make (the system one; the NDK's own make 4.3 hangs or
crashes on this makefile), JDK 9 or newer, git, python3, patch.

```
bash mupdf/build.sh          # fetch + build
bash mupdf/build.sh clean    # delete work/mupdf_build
```

The build uses MuPDF's own `platform/java/Android.mk` (the same makefile the
upstream `mupdf-android-fitz` project builds), `APP_PLATFORM` android-26 (the
app's minimum SDK) and the ABIs `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
The result is `mupdf/dist/mupdf-fitz-<version>.aar`; `mupdf/dist/` is not
tracked by git.

## Checking for updates

```
bash mupdf/check_update.sh
```

It compares the version in `build.sh` with the newest MuPDF release tag on
GitHub (and shows the newest `mupdf-android-fitz` and Maven `fitz` versions).
If a newer release exists, it also reports whether upstream now contains each
fix (then that patch can be dropped) and whether each patch still applies to
the new release; files of the third-party submodules are taken from the
submodule commits of that release. `MUPDF_CHECK_TAG=<tag>` runs the same
check against a given release, for example the current one after editing a
patch. Exit code: 0 up to date, 3 newer release available, 1 error.

## Publishing

Gradle looks for `mupdf-fitz-<version>.aar` first in `mupdf/dist/`,
then in the assets of the GitHub release `mupdf` of this repository. The
version is the MuPDF version followed by `-reepdf` (`1.28.6-reepdf`). A
published asset is never replaced under the same name, because Gradle does
not download a fixed version again: if a changed build of an already
published MuPDF version has to be published, it gets a number
(`1.28.6-reepdf2`, `1.28.6-reepdf3`, …). Before publishing, the same name can
be rebuilt freely, because Gradle reads `mupdf/dist/` directly.

The build is reproducible: building the same version again with the same
tools gives a byte-identical file.

| Version | SHA-256 |
|---|---|
| `1.28.6-reepdf` | `37d10493d432db9cce5347768ffe94078b5373c684ab5433b15e83c9ef299cc2` |

## License

MuPDF is Copyright (C) Artifex Software, Inc. and is licensed under the GNU
Affero General Public License version 3 or later. The patches and the build
script are under the same license.
