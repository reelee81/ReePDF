package hu.reelee81.reepdf

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.artifex.mupdf.fitz.AbortException
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.CompressionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.NupGeometry.A4_HEIGHT_PT
import hu.reelee81.reepdf.core.NupGeometry.A4_WIDTH_PT
import hu.reelee81.reepdf.core.NupGeometry.DEFAULT_POSITION
import hu.reelee81.reepdf.core.PageBatch
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.SignatureGeometry
import hu.reelee81.reepdf.core.WorkFileBackup
import hu.reelee81.reepdf.pdf.MuPdfSignedPageRenderer
import hu.reelee81.reepdf.pdf.MuPdfViewerEngine
import hu.reelee81.reepdf.pdf.PdfImportOperation
import hu.reelee81.reepdf.pdf.PdfIo
import hu.reelee81.reepdf.pdf.PdfLayoutChangeOperation
import hu.reelee81.reepdf.pdf.PdfOutputOperation
import hu.reelee81.reepdf.pdf.PdfRasterTarget
import hu.reelee81.reepdf.pdf.PdfRenderAbort
import hu.reelee81.reepdf.pdf.PdfRenderResult
import hu.reelee81.reepdf.pdf.PdfSearchCounter
import hu.reelee81.reepdf.pdf.PdfSignOperation
import hu.reelee81.reepdf.pdf.PdfSourceParts
import hu.reelee81.reepdf.pdf.PdfTextHits
import hu.reelee81.reepdf.pdf.PdfTextPoint
import hu.reelee81.reepdf.pdf.PdfThumbnailScheduler
import hu.reelee81.reepdf.pdf.PdfViewerDocument
import hu.reelee81.reepdf.pdf.PdfViewerPageSizes
import hu.reelee81.reepdf.pdf.SignedPageRenderer
import hu.reelee81.reepdf.viewer.ViewerViewport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class R8SmokeActivity : Activity() {
    private lateinit var status: TextView

    private lateinit var dir: File
    private lateinit var sourceDir: File
    private lateinit var nup: File

    private var config = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
    private var crops: Map<Int, PdfCropRect> = emptyMap()
    private var rotation = IntArray(0)
    private var position = IntArray(0)

    private val progress = PdfLayoutChangeOperation.Progress(SystemClock::uptimeMillis, 500L, 1_000L)
    private val password = "r8smoke".toByteArray(Charsets.UTF_8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { text = getString(R.string.in_progress_my) }
        setContentView(ScrollView(this).apply { addView(status) })
        thread { runAll() }
    }

    private class Skip(message: String) : Exception(message)

    private fun reset() {
        dir.deleteRecursively()
        dir.mkdirs()
        config = NupConfig(1, 1, 0, isLandscape = false, drawFrame = false, scaleIndividually = false, fitIndividually = false)
        crops = emptyMap()
        rotation = IntArray(0)
        position = IntArray(0)
    }

    private fun runAll() {
        dir = File(cacheDir, "r8smoke")
        sourceDir = File(dir, "source_temp")
        nup = File(dir, "nup_temp.pdf")
        val inputDir = File(checkNotNull(getExternalFilesDir(null)), "r8smoke").apply { mkdirs() }
        val resultFile = File(inputDir, "result.txt").apply { writeText("") }
        val results = ArrayList<String>()

        fun step(name: String, block: () -> Unit) {
            val line = try {
                block()
                "OK   $name"
            } catch (s: Skip) {
                "SKIP $name: ${s.message}"
            } catch (t: Throwable) {
                Log.e(TAG, "FAIL $name", t)
                "FAIL $name: $t"
            }
            Log.i(TAG, line)
            results += line
            runCatching { resultFile.appendText(line + "\n") }
            val text = results.takeLast(SHOWN_LINES).joinToString("\n")
            runOnUiThread { status.text = text }
        }

        val all = intent?.getStringExtra("only") == null

        if (all) {
            inputDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("pdf", true) }
                .sortedBy { it.path }
                .forEach { file ->
                    reset()
                    step("file: ${file.relativeTo(inputDir).path}") {
                        import(file)
                        viewer()
                        thumbnails()
                    }
                }
        }

        reset()
        step("cancel during import, then restore") { cancelImport(inputDir) }

        if (all) runMainSequence(::step)

        val failed = results.count { it.startsWith("FAIL") }
        val skipped = results.count { it.startsWith("SKIP") }
        val summary = "DONE: ${results.size - failed - skipped} OK, $failed FAIL, $skipped SKIP"
        Log.i(TAG, summary)
        runCatching { resultFile.appendText(summary + "\n") }
        val text = (results.filter { it.startsWith("FAIL") } + summary).joinToString("\n")
        runOnUiThread {
            status.text = text
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun runMainSequence(step: (String, () -> Unit) -> Unit) {
        reset()
        step("import: generated PDF") { import(generatedPdf()) }
        step("import: JPEG image") { import(generatedJpeg(), isImage = true) }

        step("layout: 2x2, scale + fit (MuPDF bounds)") {
            change(
                PdfLayoutChangeOperation.Change.Rebuild(per()),
                NupConfig(2, 2, 2, isLandscape = false, drawFrame = true, scaleIndividually = true, fitIndividually = true)
            )
        }
        step("crop") { change(PdfLayoutChangeOperation.Change.Crop(0, PdfCropRect(0.1f, 0.1f, 0.9f, 0.9f), false)) }
        step("rotate") { change(PdfLayoutChangeOperation.Change.Rotate(listOf(0))) }
        step("position") { change(PdfLayoutChangeOperation.Change.Position(listOf(0), 3)) }
        step("empty page") { change(PdfLayoutChangeOperation.Change.AddEmptyPage) }
        step("reorder") { change(PdfLayoutChangeOperation.Change.Reorder((nupPages() - 1 downTo 0).toList())) }
        step("delete") { change(PdfLayoutChangeOperation.Change.DeleteGroups(listOf(0))) }

        val signature = generatedSignature()
        step("sign 144 dpi") { sign(signature, highDpi = false, scanEffect = false, grayscale = false) }
        step("sign 300 dpi, scan effect + grayscale") { sign(signature, highDpi = true, scanEffect = true, grayscale = true) }

        step("output without password") {
            val file = output(null)
            check(pages(file, null) == nupPages()) { "page count differs" }
        }
        step("output with password (encryption)") {
            val file = output(password)
            check(runCatching { pages(file, null) }.isFailure) { "opens without password" }
            check(pages(file, password) == nupPages()) { "page count differs" }
            val encrypted = File(dir, "encrypted_input.pdf")
            file.copyTo(encrypted, overwrite = true)
            import(encrypted, password)
        }
        step("viewer: aborted render, render, close while rendering") { viewerCore() }
        step("viewer: search, word selection, select all, copy") { viewerText() }
    }

    private fun files() = PdfLayoutChangeOperation.Files(
        dir,
        sourceDir,
        nup,
        WorkFileBackup(dir, "source_temp", "nup_temp.pdf", "source_temp_copy", "nup_temp_copy.pdf")
    )

    private fun per() = (config.rows * config.cols).coerceAtLeast(1)

    private fun nupPages(): Int = pages(nup, null)

    private fun pages(file: File, password: ByteArray?): Int =
        PdfDocument(PdfIo.reader(file.absolutePath, password)).use { it.numberOfPages }

    private fun take(result: PdfLayoutChangeOperation.Result?) {
        checkNotNull(result) { "no result" }
        crops = result.crops
        rotation = result.rotation
        position = result.position
        result.config?.let { config = it }
        check(nupPages() > 0) { "empty layout" }
    }

    private fun import(file: File, password: ByteArray? = null, isImage: Boolean = false) {
        val before = if (PdfSourceParts.exists(sourceDir)) PdfSourceParts.open(sourceDir).total else 0
        val copy = File(dir, "in_${System.nanoTime()}_${file.name}")
        file.copyTo(copy)
        val source = if (isImage) {
            PdfImportOperation.Source(copy, null, null, isImage = true)
        } else {
            val probe = PdfImportOperation.probe(copy, password)
            if (!probe.canOpen) {
                copy.delete()
                throw Skip("password protected")
            }
            PdfImportOperation.Source(copy, password, probe.failure)
        }
        val operation = PdfImportOperation(
            files = files(),
            saveSelection = {},
            input = PdfImportOperation.Input(
                sources = listOf(source),
                oldPerGroup = per(),
                config = config,
                crops = crops,
                rotation = rotation,
                position = position,
                defaultPosition = DEFAULT_POSITION,
                batchSize = BATCH,
                selected = emptyList()
            ),
            progress = progress,
            imageToPdf = ::imageToPdf
        )
        take(runBlocking { operation.execute {} })
        check(PdfSourceParts.open(sourceDir).total > before) { "no pages added" }
    }

    private fun cancelImport(inputDir: File) {
        val big = inputDir.walkTopDown().firstOrNull { it.isFile && it.name == CANCEL_FILE } ?: throw Skip("$CANCEL_FILE missing")
        import(generatedPdf())
        val sourceBefore = snapshot(sourceDir)
        val nupBefore = nup.readBytes()

        val copy = File(dir, "in_${System.nanoTime()}_${big.name}")
        big.copyTo(copy)
        val probe = PdfImportOperation.probe(copy, null)
        val operation = PdfImportOperation(
            files = files(),
            saveSelection = {},
            input = PdfImportOperation.Input(
                sources = listOf(PdfImportOperation.Source(copy, null, probe.failure)),
                oldPerGroup = per(),
                config = config,
                crops = crops,
                rotation = rotation,
                position = position,
                defaultPosition = DEFAULT_POSITION,
                batchSize = BATCH,
                selected = emptyList()
            ),
            progress = progress,
            imageToPdf = ::imageToPdf
        )

        val finished = runBlocking {
            val job = async(Dispatchers.Default) { operation.execute {} }
            delay(CANCEL_AFTER_MS.milliseconds)
            job.cancel()
            runCatching { job.await() }.isSuccess
        }
        if (finished) throw Skip("finished before the cancel")

        runBlocking { operation.restoreIfBackedUp() }
        check(snapshot(sourceDir) == sourceBefore) { "source not restored" }
        check(nup.readBytes().contentEquals(nupBefore)) { "layout not restored" }
    }

    private fun snapshot(folder: File): Map<String, List<Byte>> =
        folder.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes().toList() }

    private fun change(change: PdfLayoutChangeOperation.Change, newConfig: NupConfig = config) {
        val operation = PdfLayoutChangeOperation(
            files = files(),
            saveSelection = {},
            input = PdfLayoutChangeOperation.Input(
                change = change,
                perGroup = per(),
                config = newConfig,
                crops = crops,
                rotation = rotation.copyOf(),
                position = position.copyOf(),
                defaultPosition = DEFAULT_POSITION,
                batchSize = BATCH,
                selected = emptyList()
            ),
            progress = progress
        )
        take(runBlocking { operation.execute {} })
    }

    private fun sign(signature: File, highDpi: Boolean, scanEffect: Boolean, grayscale: Boolean) {
        val operation = PdfSignOperation(
            files = files(),
            saveSelection = {},
            input = PdfSignOperation.Input(
                signature = SignedPageRenderer.Signature(
                    file = signature,
                    width = SIGNATURE_W,
                    height = SIGNATURE_H,
                    refPx = SignatureGeometry.refPxForSignatureDpi(if (highDpi) 1 else 0),
                    xMm = 20f,
                    yMm = 20f,
                    isLandscape = config.isLandscape,
                    scanEffect = scanEffect,
                    grayscale = grayscale
                ),
                highDpi = highDpi,
                nupIndices = listOf(0),
                perGroup = per(),
                config = config,
                crops = crops,
                rotation = rotation.copyOf(),
                position = position.copyOf(),
                defaultPosition = DEFAULT_POSITION,
                batchSize = BATCH
            ),
            progress = progress,
            renderer = MuPdfSignedPageRenderer()
        )
        take(runBlocking { operation.execute {} })
    }

    private fun output(password: ByteArray?): File {
        val operation = PdfOutputOperation(
            files = PdfOutputOperation.Files(dir, nup, setOf("nup_temp.pdf", "source_temp", "source_temp_copy", "nup_temp_copy.pdf")),
            saveSelection = {},
            input = PdfOutputOperation.Input(
                baseName = "R8 smoke",
                password = password,
                packed = true,
                batchSize = BATCH,
                messages = PdfOutputOperation.Messages("layout missing", "name reserved", "replace failed", "rename failed")
            ),
            progress = progress
        )
        return runBlocking { operation.execute {} }.file
    }

    private fun viewer() {
        val expected = nupPages()
        runBlocking {
            val document = PdfViewerDocument.open { MuPdfViewerEngine.open(nup.absolutePath) }
            try {
                check(document.pageCount == expected) { "viewer sees ${document.pageCount} pages, iText $expected" }
                val sizes = document.pageSizes()
                val unreadable = sizes.unreadable.count { it }
                check(unreadable == 0) { "viewer cannot read the size of $unreadable pages" }
                val first = smallBitmap(document, sizes, 0)
                try {
                    if (expected > 1) smallBitmap(document, sizes, expected - 1).recycle()
                    document.trimMemory()
                    val again = smallBitmap(document, sizes, 0)
                    try {
                        check(first.sameAs(again)) { "viewer page 1: drawn differently after the loaded objects were dropped" }
                    } finally {
                        again.recycle()
                    }
                } finally {
                    first.recycle()
                }
                zoomedTile(document, sizes, 0)
                if (expected > 1) zoomedTile(document, sizes, expected - 1)
                pageText(document, sizes, 0)
                if (expected > 1) pageText(document, sizes, expected - 1)
            } finally {
                document.close()
                document.awaitClosed()
            }
        }
    }

    private fun thumbnails() {
        val expected = nupPages()
        val density = resources.displayMetrics.density
        val boxW = (112f * density).toInt().coerceAtLeast(1)
        val boxH = (158f * density).toInt().coerceAtLeast(1)
        val results = LinkedBlockingQueue<Pair<String, Result<AndroidBitmapRasterTarget>>>()
        val scheduler = PdfThumbnailScheduler(
            open = { path -> PdfViewerDocument.open { MuPdfViewerEngine.open(path, pageTreeCache = false) } },
            newTarget = { width, height -> AndroidBitmapRasterTarget(createBitmap(width, height)) },
            release = { target -> target.bitmap.recycle() },
            finished = { key, result -> results.put(key to result) }
        )
        try {
            val pages = if (expected > 1) listOf(0, expected - 1) else listOf(0)
            pages.forEach { index -> scheduler.request("thumbnail page ${index + 1}", nup.absolutePath, index, boxW, boxH) }
            repeat(pages.size) {
                val (key, result) = checkNotNull(results.poll(THUMBNAIL_WAIT_S, TimeUnit.SECONDS)) {
                    "no thumbnail after $THUMBNAIL_WAIT_S s"
                }
                val bitmap = result.getOrElse { error -> throw IllegalStateException("$key: $error", error) }.bitmap
                try {
                    val fits = bitmap.width <= boxW && bitmap.height <= boxH && (bitmap.width == boxW || bitmap.height == boxH)
                    check(fits) { "$key: ${bitmap.width}x${bitmap.height} in a $boxW x $boxH box" }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            scheduler.shutdown()
        }
    }

    private suspend fun smallBitmap(document: PdfViewerDocument<PdfRasterTarget>, sizes: PdfViewerPageSizes, index: Int): Bitmap {
        val width = sizes.widths[index]
        val height = sizes.heights[index]
        val zoom = SMALL_SIDE_PX / max(width, height)
        val bitmap = createBitmap(pixels(width * zoom), pixels(height * zoom))
        try {
            val result = document.render(index, zoom, 0, 0, AndroidBitmapRasterTarget(bitmap))
            if (result is PdfRenderResult.Failed) {
                throw IllegalStateException("viewer page ${index + 1}: ${result.error}", result.error)
            }
            check(result == PdfRenderResult.Done) { "viewer page ${index + 1}: $result" }
            return bitmap
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    private suspend fun zoomedTile(document: PdfViewerDocument<PdfRasterTarget>, sizes: PdfViewerPageSizes, index: Int) {
        val zoom = ViewerViewport.MAX_ZOOM
        val left = ((pixels(sizes.widths[index] * zoom) - ZOOMED_TILE_PX) / 2).coerceAtLeast(0)
        val top = ((pixels(sizes.heights[index] * zoom) - ZOOMED_TILE_PX) / 2).coerceAtLeast(0)
        val bitmap = createBitmap(ZOOMED_TILE_PX, ZOOMED_TILE_PX)
        try {
            val result = document.render(index, zoom, left, top, AndroidBitmapRasterTarget(bitmap))
            if (result is PdfRenderResult.Failed) {
                throw IllegalStateException("viewer page ${index + 1} zoomed: ${result.error}", result.error)
            }
            check(result == PdfRenderResult.Done) { "viewer page ${index + 1} zoomed: $result" }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun pageText(document: PdfViewerDocument<PdfRasterTarget>, sizes: PdfViewerPageSizes, index: Int) {
        val label = "viewer page ${index + 1}"
        val limit = TEXT_RECT_LIMIT * max(sizes.widths[index], sizes.heights[index])
        checkRects(document.highlight(index, null, null), limit) { "$label select all" }
        val word = document.copy(index, null, null).split(WHITESPACE)
            .firstOrNull { it.length >= 2 && it.all(Char::isLetterOrDigit) } ?: return
        val hits = document.search(index, word)
        check(hits.count >= 1) { "$label: the copied word '$word' is not found" }
        val counted = document.count(index, word)
        check(counted == hits.count) { "$label: '$word' counts $counted, the search finds ${hits.count}" }
        checkRects(hits.rects, limit) { "$label search" }
    }

    private fun checkRects(rects: FloatArray, limit: Float, label: () -> String) {
        check(rects.size % PdfTextHits.RECT_SIZE == 0) { "${label()}: ${rects.size} coordinates" }
        for (offset in rects.indices step PdfTextHits.RECT_SIZE) {
            val left = rects[offset]
            val top = rects[offset + 1]
            val right = rects[offset + 2]
            val bottom = rects[offset + 3]
            val inside = listOf(left, top, right, bottom).all { it.isFinite() && abs(it) <= limit }
            check(inside && right >= left && bottom >= top) { "${label()}: rectangle $left, $top, $right, $bottom" }
        }
    }

    private fun viewerText() {
        val file = textPdf()
        runBlocking {
            val document = PdfViewerDocument.open { MuPdfViewerEngine.open(file.absolutePath) }
            try {
                val accented = document.search(0, "őrség")
                val plain = document.search(0, "orseg")
                check(accented.count == 2) { "'őrség': ${accented.count} hits" }
                check(plain.count == 1) { "'orseg': ${plain.count} hits" }
                val counter = PdfSearchCounter({
                    PdfViewerDocument.open {
                        MuPdfViewerEngine.open(file.absolutePath, pageTreeCache = false, emptiesStoreOnClose = false)
                    }
                }, reopenPages = 1)
                try {
                    val counted = listOf(counter.count(0, "őrség"), counter.count(0, "orseg"), counter.count(0, "ŐRSÉG"))
                    check(counted == listOf(2, 1, 2)) { "the search counter counts $counted" }
                } finally {
                    counter.release()
                }
                val limit = TEXT_RECT_LIMIT * A4_HEIGHT_PT
                checkRects(accented.rects, limit) { "search" }
                val first = hitBox(accented, 0)
                val second = hitBox(accented, 1)
                for (box in listOf(first, second)) {
                    val onLine = box[1] < TEXT_BASELINE_PT - TEXT_SIZE_PT / 2 &&
                        box[3] > TEXT_BASELINE_PT && box[3] < TEXT_BASELINE_PT + TEXT_SIZE_PT
                    check(onLine) { "hit box ${box.joinToString()} is not on the text line" }
                }
                check(first[2] <= second[0]) { "the hits are not in reading order" }
                val center = PdfTextPoint((second[0] + second[2]) / 2, (second[1] + second[3]) / 2)
                val word = checkNotNull(document.word(0, center, WORD_TOLERANCE_PT)) { "no word under the second hit" }
                val copied = document.copy(0, word.from, word.to).trim()
                check(copied == "őrség") { "the selected word copies as '$copied'" }
                val empty = document.word(0, PdfTextPoint(TEXT_X_PT, EMPTY_Y_PT), WORD_TOLERANCE_PT)
                check(empty == null) { "a word was selected on an empty area" }
                selectEveryWord(document)
                dragBetweenStaggeredLines(document)
                checkRects(document.highlight(0, null, null), limit) { "select all" }
                check(TEXT in document.copy(0, null, null)) { "select all does not copy the line" }
                check(TEXT in document.text(0)) { "the page text misses the line" }
            } finally {
                document.close()
                document.awaitClosed()
            }
        }
    }

    private suspend fun dragBetweenStaggeredLines(document: PdfViewerDocument<PdfRasterTarget>) {
        val upper = hitBox(document.search(0, DRAG_FROM), 0)
        val lower = hitBox(document.search(0, DRAG_TO), 0)
        val start = checkNotNull(document.word(0, PdfTextPoint(upper[0] + 1f, (upper[1] + upper[3]) / 2), WORD_TOLERANCE_PT)).from
        val x = (upper[0] + upper[2]) / 2
        val expected = "$DRAG_FROM\n$DRAG_TO"
        var previous = 0
        var y = (upper[1] + upper[3]) / 2
        while (y <= (lower[1] + lower[3]) / 2) {
            val caret = checkNotNull(document.caret(0, PdfTextPoint(x, y))) { "no text position at $x, $y" }
            val copied = document.copy(0, start, caret)
            check(copied.isNotEmpty() && expected.startsWith(copied) && copied.length >= previous) {
                "dragging to $x, $y selects '$copied'"
            }
            previous = copied.length
            y += DRAG_STEP_PT
        }
        check(previous > DRAG_FROM.length + 1) { "the drag never reached '$DRAG_TO'" }
    }

    private suspend fun selectEveryWord(document: PdfViewerDocument<PdfRasterTarget>) {
        val plain = (STAGGERED.flatMap { it.text.split(' ') } + listOf(SIGN, SIGNED_WORD)).flatMap { word ->
            PROBES_ACROSS.map { across -> TouchProbe(word, across, word) }
        }
        for (probe in plain + PUNCTUATED) {
            val hits = document.search(0, probe.token)
            check(hits.count == 1) { "'${probe.token}': ${hits.count} hits" }
            val box = hitBox(hits, 0)
            for (down in PROBES_DOWN) {
                val point = PdfTextPoint(box[0] + (box[2] - box[0]) * probe.across, box[1] + (box[3] - box[1]) * down)
                val span = checkNotNull(document.word(0, point, WORD_TOLERANCE_PT)) {
                    "no word under '${probe.token}' at ${probe.across}, $down"
                }
                val copied = document.copy(0, span.from, span.to)
                check(copied == probe.selects) { "a touch on '${probe.token}' at ${probe.across}, $down selects '$copied'" }
            }
        }
    }

    private fun hitBox(hits: PdfTextHits, hit: Int): FloatArray {
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (rect in hits.starts[hit] until hits.starts[hit + 1]) {
            val offset = rect * PdfTextHits.RECT_SIZE
            box[0] = min(box[0], hits.rects[offset])
            box[1] = min(box[1], hits.rects[offset + 1])
            box[2] = max(box[2], hits.rects[offset + 2])
            box[3] = max(box[3], hits.rects[offset + 3])
        }
        return box
    }

    private fun viewerCore() {
        val engine = MuPdfViewerEngine.open(generatedPdf().absolutePath)
        try {
            check(engine.pageCount == 5) { "viewer sees ${engine.pageCount} pages" }
            val size = engine.pageSize(0)
            val zoom = SMALL_SIDE_PX / max(size.width, size.height)
            val bitmap = createBitmap(pixels(size.width * zoom), pixels(size.height * zoom))
            try {
                val target = AndroidBitmapRasterTarget(bitmap)
                bitmap.eraseColor(Color.RED)
                val aborted = PdfRenderAbort().apply { abort() }
                val thrown = runCatching { engine.render(0, zoom, 0, 0, target, aborted) }.exceptionOrNull()
                check(thrown == null || thrown is AbortException) { "aborted render: $thrown" }
                check(isWhite(bitmap)) { "the aborted render is not blank white" }
                engine.render(0, zoom, 0, 0, target, PdfRenderAbort())
                check(!isWhite(bitmap)) { "the render after the abort drew nothing" }
            } finally {
                bitmap.recycle()
            }
        } finally {
            engine.close()
        }
        closeWhileRendering(heavyPdf())
        abortInsideImage()
    }

    private fun abortInsideImage() {
        val engine = MuPdfViewerEngine.open(bigImagePdf().absolutePath)
        try {
            val bitmap = createBitmap(pixels(A4_WIDTH_PT), pixels(A4_HEIGHT_PT))
            try {
                val target = AndroidBitmapRasterTarget(bitmap)
                val abort = PdfRenderAbort()
                var abortedAt = 0L
                val timer = thread {
                    Thread.sleep(IMAGE_ABORT_AFTER_MS)
                    abortedAt = SystemClock.uptimeMillis()
                    abort.abort()
                }
                runCatching { engine.render(0, 1f, 0, 0, target, abort) }
                val returnedAt = SystemClock.uptimeMillis()
                timer.join()
                val latency = returnedAt - abortedAt
                Log.i(TAG, "viewer: abort inside an image -> returned $latency ms after the abort")
                if (latency < 0) throw Skip("the image was drawn before the abort")
                check(latency < IMAGE_ABORT_LIMIT_MS) { "the abort inside the image took $latency ms" }
                engine.render(0, 1f, 0, 0, target, PdfRenderAbort())
                val afterAbort = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    engine.emptyStore()
                    engine.render(0, 1f, 0, 0, target, PdfRenderAbort())
                    check(!isWhite(bitmap)) { "the image page drew nothing" }
                    check(bitmap.sameAs(afterAbort)) { "the render after the abort differs from a clean render" }
                } finally {
                    afterAbort.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            engine.close()
        }
    }

    private fun closeWhileRendering(file: File) {
        val bitmap = createBitmap(pixels(A4_WIDTH_PT * HEAVY_ZOOM), pixels(A4_HEIGHT_PT * HEAVY_ZOOM))
        try {
            val raster = AndroidBitmapRasterTarget(bitmap)
            val started = CountDownLatch(1)
            val target = PdfRasterTarget { left, top ->
                started.countDown()
                raster.whiteDevice(left, top)
            }
            val (result, abortMs) = runBlocking {
                val document = PdfViewerDocument.open { MuPdfViewerEngine.open(file.absolutePath) }
                val render = async(Dispatchers.Default) { document.render(0, HEAVY_ZOOM, 0, 0, target) }
                check(started.await(5, TimeUnit.SECONDS)) { "the render did not start" }
                delay(CLOSE_AFTER_MS.milliseconds)
                val closedAt = SystemClock.uptimeMillis()
                document.close()
                val result = render.await()
                val abortMs = SystemClock.uptimeMillis() - closedAt
                document.awaitClosed()
                val late = runCatching { document.render(0, HEAVY_ZOOM, 0, 0, target) }.exceptionOrNull()
                check(late is IllegalStateException) { "render after close: $late" }
                result to abortMs
            }
            Log.i(TAG, "viewer: close while rendering -> $result after $abortMs ms")
            if (result == PdfRenderResult.Done) throw Skip("finished before the close")
            check(result == PdfRenderResult.Aborted) { "close while rendering: $result" }
            check(abortMs < ABORT_LIMIT_MS) { "the abort took $abortMs ms" }
        } finally {
            bitmap.recycle()
        }
    }

    private fun heavyPdf(): File {
        val file = File(dir, "heavy.pdf")
        PdfDocument(PdfWriter(file.absolutePath, PdfIo.writerProperties())).use { pdf ->
            val canvas = PdfCanvas(pdf.addNewPage(PageSize.A4))
            repeat(HEAVY_FILLS) { index ->
                canvas.rectangle(20.0 + index % 50 * 5, 20.0 + index / 50 % 80 * 5, 300.0, 400.0).fill()
            }
            canvas.release()
        }
        return file
    }

    private fun bigImagePdf(): File {
        val file = File(dir, "big_image.pdf")
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, Deflater(Deflater.BEST_SPEED)).use { out ->
            val row = ByteArray(BIG_IMAGE_SIDE) { x -> (x * 255 / BIG_IMAGE_SIDE).toByte() }
            repeat(BIG_IMAGE_SIDE) { out.write(row) }
        }
        PdfDocument(PdfWriter(file.absolutePath, PdfIo.writerProperties())).use { pdf ->
            val image = PdfStream(compressed.toByteArray()).apply {
                put(PdfName.Type, PdfName.XObject)
                put(PdfName.Subtype, PdfName.Image)
                put(PdfName.Width, PdfNumber(BIG_IMAGE_SIDE))
                put(PdfName.Height, PdfNumber(BIG_IMAGE_SIDE))
                put(PdfName.ColorSpace, PdfName.DeviceGray)
                put(PdfName.BitsPerComponent, PdfNumber(8))
                put(PdfName.Filter, PdfName.FlateDecode)
                compressionLevel = CompressionConstants.NO_COMPRESSION
            }
            val canvas = PdfCanvas(pdf.addNewPage(PageSize.A4))
            canvas.addXObjectFittedIntoRectangle(PdfImageXObject(image), Rectangle(0f, 0f, A4_WIDTH_PT, A4_HEIGHT_PT))
            canvas.release()
        }
        return file
    }

    private fun isWhite(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.all { it == Color.WHITE }
    }

    private fun pixels(size: Float): Int = ceil(size).toInt().coerceAtLeast(1)

    private fun generatedPdf(): File {
        val file = File(dir, "generated.pdf")
        PdfDocument(PdfWriter(file.absolutePath, PdfIo.writerProperties())).use { pdf ->
            repeat(5) { index ->
                val page = pdf.addNewPage(PageSize.A4)
                PdfCanvas(page).rectangle(40.0 + index * 20, 40.0, 200.0, 300.0).fill().release()
            }
        }
        return file
    }

    private fun textPdf(): File {
        val file = File(dir, "text.pdf")
        val document = AndroidPdfDocument()
        try {
            val page = document.startPage(AndroidPdfDocument.PageInfo.Builder(A4_WIDTH_PT.toInt(), A4_HEIGHT_PT.toInt(), 1).create())
            val paint = Paint().apply {
                typeface = ResourcesCompat.getFont(this@R8SmokeActivity, R.font.roboto_regular)
                textSize = TEXT_SIZE_PT
                color = Color.BLACK
                isAntiAlias = true
            }
            page.canvas.drawText(TEXT, TEXT_X_PT, TEXT_BASELINE_PT, paint)
            for (placed in STAGGERED) page.canvas.drawText(placed.text, placed.x, placed.baseline, paint)
            page.canvas.drawText(SIGN, TEXT_X_PT, SIGN_BASELINE_PT, paint)
            page.canvas.drawText(SIGNED_WORD, TEXT_X_PT + paint.measureText(SIGN) + SIGN_GAP_PT, SIGN_BASELINE_PT, paint)
            page.canvas.drawText(PUNCTUATED.map { it.token }.distinct().joinToString(" "), TEXT_X_PT, PUNCTUATED_BASELINE_PT, paint)
            document.finishPage(page)
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun generatedJpeg(): File {
        val file = File(dir, "generated.jpg")
        val bitmap = createBitmap(400, 300)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawCircle(200f, 150f, 100f, Paint().apply { color = Color.BLUE })
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun generatedSignature(): File {
        val file = File(dir, "signature.png")
        val bitmap = createBitmap(SIGNATURE_W, SIGNATURE_H)
        Canvas(bitmap).drawLine(10f, 80f, 390f, 20f, Paint().apply {
            color = Color.BLACK
            strokeWidth = 8f
        })
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun imageToPdf(image: File): File {
        val out = File(image.parentFile, "${image.nameWithoutExtension}_image.pdf")
        val data = ImageDataFactory.create(image.absolutePath)
        val rect = Rectangle(data.width.coerceAtLeast(1f), data.height.coerceAtLeast(1f))
        PdfDocument(PdfWriter(out.absolutePath, PdfIo.writerProperties())).use { pdf ->
            val page = pdf.addNewPage(PageSize(rect))
            PdfCanvas(page).addImageFittedIntoRectangle(data, rect, false)
        }
        return out
    }

    private companion object {
        const val TAG = "ReePDFR8Smoke"
        const val BATCH = PageBatch.PAGES
        const val SIGNATURE_W = 400
        const val SIGNATURE_H = 100
        const val SHOWN_LINES = 40
        const val CANCEL_FILE = "1000.pdf"
        const val CANCEL_AFTER_MS = 3_000L
        const val SMALL_SIDE_PX = 100f
        const val ZOOMED_TILE_PX = 512
        const val HEAVY_ZOOM = 2f
        const val HEAVY_FILLS = 8_000
        const val CLOSE_AFTER_MS = 50L
        const val ABORT_LIMIT_MS = 1_000L
        const val BIG_IMAGE_SIDE = 16_000
        const val IMAGE_ABORT_AFTER_MS = 50L
        const val IMAGE_ABORT_LIMIT_MS = 300L
        const val THUMBNAIL_WAIT_S = 120L
        const val TEXT_RECT_LIMIT = 100f
        val WHITESPACE = Regex("\\s+")
        const val TEXT = "ŐRSÉG őrség ORSEG"
        const val TEXT_SIZE_PT = 24f
        const val TEXT_X_PT = 50f
        const val TEXT_BASELINE_PT = 100f
        const val EMPTY_Y_PT = 500f
        const val WORD_TOLERANCE_PT = 2f
        val STAGGERED = listOf(
            PlacedText("Alfa", 50f, 242f),
            PlacedText("Beta", 150f, 227.6f),
            PlacedText("Gamma", 150f, 256.4f),
            PlacedText("Delta", 300f, 242f),
            PlacedText("het nyolc u", 50f, 442f),
            PlacedText("harom negy", 50f, 470.8f)
        )
        val PROBES_ACROSS = floatArrayOf(0.1f, 0.5f, 0.95f)
        val PROBES_DOWN = floatArrayOf(0.15f, 0.5f, 0.85f)
        const val SIGN = "№"
        const val SIGNED_WORD = "szam"
        const val SIGN_BASELINE_PT = 600f
        const val SIGN_GAP_PT = 12f
        const val DRAG_FROM = "Beta"
        const val DRAG_TO = "Gamma"
        const val DRAG_STEP_PT = 0.5f
        val PUNCTUATED = listOf(
            TouchProbe("(kW)", 0.5f, "kW"),
            TouchProbe("(kW)", 0.08f, "("),
            TouchProbe("(kW)", 0.95f, ")"),
            TouchProbe("adatai:", 0.4f, "adatai"),
            TouchProbe("adatai:", 0.97f, ":"),
            TouchProbe("teszt.png", 0.1f, "teszt.png"),
            TouchProbe("teszt.png", 0.5f, "teszt.png")
        )
        const val PUNCTUATED_BASELINE_PT = 680f
    }

    private class PlacedText(val text: String, val x: Float, val baseline: Float)

    private class TouchProbe(val token: String, val across: Float, val selects: String)
}
