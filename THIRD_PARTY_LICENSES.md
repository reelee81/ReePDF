# Third‑party libraries and licenses

This project uses several third‑party libraries.  
Below is a non‑exhaustive but important list of runtime and test dependencies,
together with the license that applies to each upstream project.

For the full license texts, please refer to the linked pages.

---

## Core runtime libraries (Apache License 2.0)

These libraries are part of AndroidX / Material and are covered by
the **Apache License 2.0**:

- `androidx.core:core-ktx:1.19.1`
- `androidx.appcompat:appcompat:1.8.0`
- `com.google.android.material:material:1.14.0`
- `androidx.activity:activity:1.13.0`
- `androidx.constraintlayout:constraintlayout:2.2.2`
- `androidx.recyclerview:recyclerview:1.4.0`
- `androidx.fragment:fragment:1.9.1`
- `androidx.window:window:1.5.1`
- `androidx.lifecycle:lifecycle-runtime:2.11.1`
- `androidx.autofill:autofill:1.3.0`

Many additional transitive AndroidX modules may also be pulled in at build time.
They are usually licensed under Apache 2.0 as well.

References (examples):
- <https://developer.android.com/jetpack/androidx>
- <https://www.apache.org/licenses/LICENSE-2.0>


## Compose Multiplatform (Apache License 2.0)

The app's own PDF viewer and editor, the signature placement screen and the
crop screen (module `:viewer`) are built with
**Compose Multiplatform 1.12.2** by JetBrains:

- `org.jetbrains.compose.runtime:runtime:1.12.2`
- `org.jetbrains.compose.foundation:foundation:1.12.2`
- `org.jetbrains.compose.ui:ui:1.12.2`
- `org.jetbrains.compose.components:components-resources:1.12.2`

On Android these modules pull in the matching Jetpack Compose libraries
(`androidx.compose.*`), which are also licensed under the Apache License 2.0.

References:
- <https://github.com/JetBrains/compose-multiplatform>
- <https://www.apache.org/licenses/LICENSE-2.0>


## iText Core for Android (AGPL-3.0)

The app uses **iText Core for Android 9.8.1** via the following modules:

- `com.itextpdf.android:kernel-android:9.8.1`
- `com.itextpdf.android:layout-android:9.8.1`
- `com.itextpdf.android:forms-android:9.8.1`
- `com.itextpdf.android:bouncy-castle-adapter-android:9.8.1`
- `com.itextpdf.android:bouncy-castle-connector-android:9.8.1`

These libraries are licensed under the **GNU Affero General Public License v3**,
or can be used under a separate commercial license purchased from iText.

References:
- <https://itextpdf.com/>
- <https://github.com/itext/itext-java>
- <https://www.gnu.org/licenses/agpl-3.0.html>


## MuPDF (AGPL-3.0)

The app uses **MuPDF 1.28.6** (Java bindings with native libraries) for the
built-in PDF viewer and editor, the page thumbnails, the signature placement
and crop screens, the rendering of signed pages, the measurement of the
visible content bounds of PDF pages and the repair of damaged PDFs:

- `hu.reelee81.reepdf:mupdf-fitz:1.28.6-reepdf3` — a modified build of MuPDF
  1.28.6 made for ReePDF: the unmodified upstream source plus twenty-two patches
  (modified on 2026-11-08, 2026-11-10, 2026-11-11, 2026-11-12, 2026-11-15 and 2026-11-20): the missing
  JNI binding for `PDFDocument.setPageTreeCache`, a new
  `Context.releaseFreeMemory()`, decoding JPEG 2000 images at a reduced
  resolution when they are drawn scaled down, a new `PDFDocument.clearXref()`,
  stopping the long steps of an aborted page drawing early, with small abort
  hooks added to the bundled OpenJPEG, jbig2dec and Little CMS, decoding only
  the needed area of a JPEG 2000 image when a zoomed part of it is drawn, with
  an exact area decode in the bundled OpenJPEG, the right sub-pixel position
  for vertically flipped scaled images, skipping the zero-weight samples of
  sampled functions, and removing four cases of undefined behavior (a NaN
  function input, an empty rasterizer clip, mesh shading edge values and
  pattern tile ranges converted or multiplied out of the int range), and
  remembering where inline images end so that they are not decoded again
  just to find their end, drawing the repeats of a very large pattern
  cell directly instead of through one huge tile, reading a number that is
  longer than the lexer buffer as one number, keeping the page maps when
  only the dictionary of a stream changes, and extracting vertical text as
  whole columns with boxes on the glyphs. The patches, the build script and the exact
  upstream commits are in the [`mupdf`](mupdf) folder of this repository; the
  built AAR is published as an asset of the GitHub release `mupdf`.

MuPDF is developed by Artifex Software, Inc. and is licensed under the
**GNU Affero General Public License v3**, or can be used under a separate
commercial license purchased from Artifex.

The native library also contains third-party components bundled by MuPDF
(FreeType, HarfBuzz, libjpeg, OpenJPEG, jbig2dec, Little CMS, MuJS, Gumbo,
Brotli, cmark-gfm and Extract), each under its own license; see the
`thirdparty` directory of the MuPDF source distribution. zlib is not bundled;
the library uses the zlib of the Android system.

References:
- <https://mupdf.com/>
- <https://github.com/ArtifexSoftware/mupdf>
- <https://artifex.com/licensing>
- <https://www.gnu.org/licenses/agpl-3.0.html>


## Core library desugaring (OpenJDK / GPLv2 with Classpath Exception)

- `com.android.tools:desugar_jdk_libs_nio:2.1.5`

This is a Google‑provided subset of OpenJDK libraries that enables
`java.nio` and other newer JDK APIs on older Android API levels.

The project itself is hosted by Google and combines code under
**GPLv2 with the Classpath Exception** from OpenJDK with Google’s
build tooling.

References:
- <https://github.com/google/desugar_jdk_libs>


## Test libraries

These are used only in unit or instrumented tests:

- `junit:junit:4.13.2` – Eclipse Public License 1.0  
  <https://junit.org/junit4/>

- `androidx.test.ext:junit:1.3.0` – Apache License 2.0
- `androidx.test.espresso:espresso-core:3.7.0` – Apache License 2.0

AndroidX test components are generally licensed under Apache 2.0:
<https://developer.android.com/testing>


---

This file is provided for convenience only and is not a legal document.  
If you use or redistribute this project, you are responsible for verifying
the current licenses of all dependencies involved.
