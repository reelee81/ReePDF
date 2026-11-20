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
import androidx.core.graphics.get
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
import hu.reelee81.reepdf.core.NupArea
import hu.reelee81.reepdf.core.NupBox
import hu.reelee81.reepdf.core.NupCellArea
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
import hu.reelee81.reepdf.pdf.PdfEditProjection
import hu.reelee81.reepdf.pdf.PdfImportOperation
import hu.reelee81.reepdf.pdf.PdfIo
import hu.reelee81.reepdf.pdf.PdfLayoutChangeOperation
import hu.reelee81.reepdf.pdf.PdfMark
import hu.reelee81.reepdf.pdf.PdfMarkKind
import hu.reelee81.reepdf.pdf.PdfMarkPath
import hu.reelee81.reepdf.pdf.PdfMarkSave
import hu.reelee81.reepdf.pdf.PdfOutputOperation
import hu.reelee81.reepdf.pdf.PdfPageMark
import hu.reelee81.reepdf.pdf.PdfPageView
import hu.reelee81.reepdf.pdf.PdfRasterTarget
import hu.reelee81.reepdf.pdf.PdfRenderAbort
import hu.reelee81.reepdf.pdf.PdfRenderResult
import hu.reelee81.reepdf.pdf.PdfSearchCounter
import hu.reelee81.reepdf.pdf.PdfSignOperation
import hu.reelee81.reepdf.pdf.PdfSourceParts
import hu.reelee81.reepdf.pdf.PdfStreamEdit
import hu.reelee81.reepdf.pdf.PdfTextBaselines
import hu.reelee81.reepdf.pdf.PdfTextHits
import hu.reelee81.reepdf.pdf.PdfTextPoint
import hu.reelee81.reepdf.pdf.PdfTextSpan
import hu.reelee81.reepdf.pdf.PdfThumbnailScheduler
import hu.reelee81.reepdf.pdf.PdfViewerDocument
import hu.reelee81.reepdf.pdf.PdfViewerEditOperation
import hu.reelee81.reepdf.pdf.PdfViewerPageSizes
import hu.reelee81.reepdf.pdf.ProgressTracker
import hu.reelee81.reepdf.pdf.SignedPageRenderer
import hu.reelee81.reepdf.viewer.ViewerPageLayout
import hu.reelee81.reepdf.viewer.ViewerRender
import hu.reelee81.reepdf.viewer.ViewerTile
import hu.reelee81.reepdf.viewer.ViewerTilesRender
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
import kotlin.math.hypot
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
                        placer()
                        cropper()
                        editor()
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
            check(format(file, null) == "PDF-1.7 R-") { "format ${format(file, null)}" }
        }
        step("output with password (encryption)") {
            val file = output(password)
            check(runCatching { pages(file, null) }.isFailure) { "opens without password" }
            check(pages(file, password) == nupPages()) { "page count differs" }
            check(format(file, password) == "PDF-2.0 R6") { "format ${format(file, password)}" }
            val encrypted = File(dir, "encrypted_input.pdf")
            file.copyTo(encrypted, overwrite = true)
            import(encrypted, password)
        }
        step("viewer: aborted render, render, close while rendering") { viewerCore() }
        step("viewer: search, word selection, select all, copy") { viewerText() }
        step("editor: 2x2 labels, a neighbor's mark stays off a label whose crop is reset") { labelsCropReset() }
        step("editor: 2x2 fitted labels keep their size, no copy and no ghost") { labelsFit() }
    }

    private fun labelsCropReset() {
        reset()
        import(labelsPdf())
        change(PdfLayoutChangeOperation.Change.Crop(0, LABEL_CROP, false))
        change(PdfLayoutChangeOperation.Change.Rebuild(per()), LABELS_SCALED)
        val cropped = recordedCells(nup)
        check(cropped.size == LABELS) { "${cropped.size} labels on the sheet" }
        val neighbor = cropped[1].area
        val y = (neighbor.bottom + neighbor.top) / 2
        val from = neighbor.left + LABEL_STROKE_INSET
        val pen = PdfMark(
            PdfMark.newId(), PdfMarkKind.PEN, PEN_COLOR, LABEL_PEN_WIDTH,
            PdfMarkPath.Builder().moveTo(from.toFloat(), y.toFloat()).lineTo(((neighbor.left + neighbor.right) / 2).toFloat(), y.toFloat()).build()
        )
        val edited = File(dir, "labels_crop.pdf")
        check(saveMarks(nup, edited, PdfMarkSave.Plan(added = mapOf(0 to listOf(pen))))) { "nothing saved" }
        runBlocking { project(edited) }
        check(!sourceHasMark(0, pen.id)) { "the neighbor's mark reached the cropped label's source" }
        check(sourceHasMark(1, pen.id)) { "the mark is missing from its own label's source" }
        change(PdfLayoutChangeOperation.Change.Crop(0, null, false))
        val whole = recordedCells(nup)
        val inSource = map(checkNotNull(PdfEditProjection.inverse(cropped[0].matrix)), from + LABEL_STROKE_INSET, y)
        val oldPlace = map(whole[0].matrix, inSource.first, inSource.second)
        check(oldPlace.first in whole[0].area.left..whole[0].area.right && oldPlace.second in whole[0].area.bottom..whole[0].area.top) {
            "the check point $oldPlace is not on the reset label"
        }
        runBlocking {
            withViewerDocument(nup) { document ->
                val toPage = document.pageMarks(0).toPage
                val stray = countPixels(document, toPage, whole[0].area, ::isPen)
                check(stray == 0) { "the reset label shows $stray pixels of its neighbor's mark" }
                check(countPixels(document, toPage, whole[1].area, ::isPen) > 0) { "the neighbor lost its own mark" }
            }
        }
    }

    private fun labelsFit() {
        reset()
        import(labelsPdf())
        change(PdfLayoutChangeOperation.Change.Rebuild(per()), LABELS_FITTED)
        val before = recordedCells(nup)
        check(before.size == LABELS) { "${before.size} labels on the sheet" }
        val label = before[1]
        val start = map(label.matrix, LABEL_BAND_FROM, LABEL_BAND_Y)
        val end = map(label.matrix, LABEL_BAND_TO, LABEL_BAND_Y)
        val strip = PdfMark(
            PdfMark.newId(), PdfMarkKind.HIGHLIGHTER, HIGHLIGHT_COLOR, LABEL_STRIP_WIDTH,
            PdfMarkPath.Builder().moveTo(start.first.toFloat(), start.second.toFloat()).lineTo(end.first.toFloat(), end.second.toFloat()).build()
        )
        val edited = File(dir, "labels_fit.pdf")
        check(saveMarks(nup, edited, PdfMarkSave.Plan(added = mapOf(0 to listOf(strip))))) { "nothing saved" }
        val drawn = runBlocking { withViewerDocument(edited) { document -> countPixels(document, document.pageMarks(0).toPage, label.area, ::isStrip) } }
        check(drawn > 0) { "the strip is not drawn" }
        runBlocking { project(edited) }
        val after = recordedCells(nup)
        for (index in before.indices) {
            val (old, new) = before[index].area to after[index].area
            val same = abs(old.left - new.left) + abs(old.bottom - new.bottom) + abs(old.right - new.right) + abs(old.top - new.top) <= AREA_TOLERANCE
            check(same) { "label ${index + 1} changed size: $old -> $new" }
        }
        for (index in listOf(0, 2, 3)) check(!sourceHasMark(index, strip.id)) { "label ${index + 1} got a copy of the strip" }
        check(sourceHasMark(1, strip.id)) { "the strip is missing from its own label's source" }
        runBlocking {
            withViewerDocument(nup) { document ->
                val toPage = document.pageMarks(0).toPage
                val shown = countPixels(document, toPage, label.area, ::isStrip)
                check(abs(shown - drawn) <= drawn / STRIP_TOLERANCE_DIVISOR) { "the strip covers $shown pixels instead of $drawn" }
                val twice = countPixels(document, toPage, label.area, ::isDoubleStrip)
                check(twice == 0) { "$twice pixels of the strip are drawn twice" }
            }
        }
        change(PdfLayoutChangeOperation.Change.Rebuild(per()), LABELS_ONE_UP)
        change(PdfLayoutChangeOperation.Change.DeleteGroups(listOf(1)))
        change(PdfLayoutChangeOperation.Change.Rebuild(per()), LABELS_FITTED)
        check(PdfSourceParts.open(sourceDir).total == LABELS - 1) { "the label was not deleted" }
        runBlocking {
            withViewerDocument(nup) { document ->
                val ghost = countPixels(document, document.pageMarks(0).toPage, SHEET, ::isStrip)
                check(ghost == 0) { "$ghost pixels of the deleted label's strip are left on the sheet" }
            }
        }
    }

    private class RecordedCell(val matrix: DoubleArray, val area: NupArea)

    private fun labelArea(file: File, pageIndex: Int): NupArea? = PdfDocument(PdfIo.reader(file.absolutePath, null)).use { pdf ->
        val base = pdf.getPage(pageIndex + 1).pdfObject.getAsDictionary(PdfName.PieceInfo)?.getAsDictionary(PdfName(PIECE))
            ?.getAsDictionary(PdfName.Private)?.getAsDictionary(PdfName("Base")) ?: return@use null
        val cells = base.getAsArray(PdfName("Cells"))?.takeIf { it.size() == 7 } ?: return@use null
        val areas = base.getAsArray(PdfName("Areas"))?.takeIf { it.size() == 8 } ?: return@use null
        val matrix = DoubleArray(6) { cells.getAsNumber(1 + it).doubleValue() }
        val values = FloatArray(8) { areas.getAsNumber(it).floatValue() }
        NupCellArea.of(NupBox(values[0], values[1], values[2], values[3]), matrix, NupBox(values[4], values[5], values[6], values[7])) ?: NOTHING_VISIBLE
    }

    private fun touches(mark: PdfMark, area: NupArea): Boolean {
        val points = mark.path.points
        if (points.isEmpty()) return false
        val xs = points.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = points.filterIndexed { index, _ -> index % 2 == 1 }
        val reach = if (mark.kind == PdfMarkKind.PEN || mark.kind == PdfMarkKind.HIGHLIGHTER) mark.width / 2.0 else 0.0
        return area.intersects(xs.min() - reach, ys.min() - reach, xs.max() + reach, ys.max() + reach)
    }

    private fun recordedCells(file: File): List<RecordedCell> = PdfDocument(PdfIo.reader(file.absolutePath, null)).use { pdf ->
        val base = checkNotNull(
            pdf.getPage(1).pdfObject.getAsDictionary(PdfName.PieceInfo)?.getAsDictionary(PdfName(PIECE))
                ?.getAsDictionary(PdfName.Private)?.getAsDictionary(PdfName("Base"))
        ) { "no layout record" }
        val cells = checkNotNull(base.getAsArray(PdfName("Cells"))) { "no cells" }
        val areas = checkNotNull(base.getAsArray(PdfName("Areas"))) { "no areas" }
        (0 until cells.size() / 7).map { index ->
            val matrix = DoubleArray(6) { cells.getAsNumber(index * 7 + 1 + it).doubleValue() }
            val values = FloatArray(8) { areas.getAsNumber(index * 8 + it).floatValue() }
            val box = NupBox(values[0], values[1], values[2], values[3])
            val slot = NupBox(values[4], values[5], values[6], values[7])
            RecordedCell(matrix, checkNotNull(NupCellArea.of(box, matrix, slot)) { "label ${index + 1} has no visible area" })
        }
    }

    private fun map(matrix: DoubleArray, x: Double, y: Double): Pair<Double, Double> =
        (matrix[0] * x + matrix[2] * y + matrix[4]) to (matrix[1] * x + matrix[3] * y + matrix[5])

    private suspend fun countPixels(
        document: PdfViewerDocument<PdfRasterTarget>,
        toPage: DoubleArray,
        area: NupArea,
        matches: (Int, Int, Int) -> Boolean
    ): Int {
        val corners = listOf(map(toPage, area.left, area.bottom), map(toPage, area.right, area.top))
        val left = (corners.minOf { it.first } * LABEL_ZOOM).toInt()
        val top = (corners.minOf { it.second } * LABEL_ZOOM).toInt()
        val width = (corners.maxOf { it.first } * LABEL_ZOOM).toInt() - left
        val height = (corners.maxOf { it.second } * LABEL_ZOOM).toInt() - top
        check(width > 0 && height > 0) { "empty area $area" }
        val bitmap = createBitmap(width, height)
        try {
            val result = document.render(0, LABEL_ZOOM, left, top, AndroidBitmapRasterTarget(bitmap))
            if (result is PdfRenderResult.Failed) throw IllegalStateException("labels: ${result.error}", result.error)
            check(result == PdfRenderResult.Done) { "labels: $result" }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return pixels.count { matches(Color.red(it), Color.green(it), Color.blue(it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun isPen(red: Int, green: Int, blue: Int) = red >= PROBE_STRONG && green <= PROBE_WEAK && blue <= PROBE_WEAK

    private fun isStrip(red: Int, green: Int, blue: Int) = red >= PROBE_STRONG && green >= PROBE_STRONG && blue <= red - STRIP_BLUE_GAP

    private fun isDoubleStrip(red: Int, green: Int, blue: Int) = isStrip(red, green, blue) && blue <= DOUBLE_STRIP_BLUE

    private fun sourceHasMark(index: Int, id: String): Boolean {
        val location = PdfSourceParts.open(sourceDir).locate(index)
        return PdfDocument(PdfIo.reader(location.file.absolutePath, null)).use { pdf ->
            val xObjects = pdf.getPage(location.page + 1).pdfObject.getAsDictionary(PdfName.Resources)?.getAsDictionary(PdfName.XObject)
            xObjects?.keySet().orEmpty().any { name ->
                val form = xObjects?.getAsStream(name)?.takeIf { it.getAsName(PdfName.Subtype) == PdfName.Form }
                form != null && (form.getAsNumber(PdfName.Length)?.longValue() ?: Long.MAX_VALUE) <= LAYER_LIMIT &&
                    String(form.bytes, Charsets.ISO_8859_1).contains("($id)")
            }
        }
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

    private fun format(file: File, password: ByteArray?): String =
        PdfDocument(PdfIo.reader(file.absolutePath, password)).use { pdf ->
            val revision = pdf.trailer.getAsDictionary(PdfName.Encrypt)?.getAsNumber(PdfName.R)?.intValue()
            val mac = if (pdf.trailer.get(PdfName.AuthCode) != null) " MAC" else ""
            "${pdf.pdfVersion} R${revision ?: "-"}$mac"
        }

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

        val sourceLengths = lengths(sourceDir)
        val finished = runBlocking {
            val job = async(Dispatchers.Default) { operation.execute {} }
            while (job.isActive && lengths(sourceDir) == sourceLengths) delay(2.milliseconds)
            job.cancel()
            runCatching { job.await() }.isSuccess
        }
        if (finished) throw Skip("finished before the cancel")

        runBlocking { operation.restoreIfBackedUp() }
        check(snapshot(sourceDir) == sourceBefore) { "source not restored" }
        check(nup.readBytes().contentEquals(nupBefore)) { "layout not restored" }
    }

    private fun lengths(folder: File): Map<String, Long> =
        folder.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.length() }

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

    private fun placer() {
        val expected = nupPages()
        runBlocking {
            for (index in if (expected > 1) listOf(0, expected - 1) else listOf(0)) {
                val page = AndroidSinglePage.open(nup.absolutePath, index)
                try {
                    fitAndZoom(page, "placer page ${index + 1}", 0.0)
                } finally {
                    page.close()
                    OpenViewerDocuments.awaitClosing()
                }
            }
        }
    }

    private fun cropper() {
        val parts = PdfSourceParts.open(sourceDir)
        val total = parts.total
        check(total > 0) { "crop: no source pages" }
        val gutter = (CROP_GUTTER_DP * resources.displayMetrics.density).toDouble()
        val pages = if (total > 1) listOf(0 to 0, total - 1 to 1) else listOf(0 to 1)
        runBlocking {
            for ((sourcePage, turns) in pages) {
                val location = parts.locate(sourcePage)
                val view = PdfPageView.read(location.file, location.page, turns)
                val page = AndroidSinglePage.open(location.file.absolutePath, location.page, view)
                try {
                    fitAndZoom(page, "crop page ${sourcePage + 1}, $turns quarter turns", gutter)
                } finally {
                    page.close()
                    OpenViewerDocuments.awaitClosing()
                }
            }
        }
    }

    private fun editor() {
        val expected = nupPages()
        val indices = if (expected > 1) listOf(0, expected - 1) else listOf(0)
        val saved = File(dir, "editor_saved.pdf")
        val erased = File(dir, "editor_erased.pdf")
        val blanked = File(dir, "editor_blanked.pdf")
        runBlocking {
            val pages = withViewerDocument(nup) { document ->
                val sizes = document.pageSizes()
                val pages = indices.map { index ->
                    val before = document.pageMarks(index)
                    check(before.marks.isEmpty()) { "editor page ${index + 1}: ${before.marks.size} marks before editing" }
                    EditorPage(index, sizes.widths[index], sizes.heights[index], before.toPage).also { page ->
                        page.background = probe(document, page, page.extraY)
                    }
                }
                check(saveMarks(nup, saved, PdfMarkSave.Plan(added = pages.associate { it.index to it.added() }))) { "editor: nothing saved" }
                pages
            }
            withViewerDocument(saved) { document ->
                val erase = HashMap<Int, PdfStreamEdit>()
                for (page in pages) {
                    val marks = expectMarks(document, page, "saved", page.highlight, page.underline, page.pen, page.strikethrough, page.extra)
                    checkColor(probe(document, page, page.extraY), EXTRA_COLOR) { "editor page ${page.index + 1} saved: extra pen not drawn ($it)" }
                    val stream = marks.single { it.mark.id == page.extra.id }.stream
                    val edit = PdfStreamEdit(false, setOf(page.extra.id))
                    erase[stream] = edit
                    document.editStreams(mapOf(stream to edit))
                    checkErased(document, page, "erased in memory")
                    checkPenDrawn(document, page, "editor page ${page.index + 1} erased in memory")
                }
                check(saveMarks(saved, erased, PdfMarkSave.Plan(streams = erase))) { "editor: erasing saved nothing" }
            }
            withViewerDocument(erased) { document ->
                for (page in pages) {
                    expectMarks(document, page, "erased", page.highlight, page.underline, page.pen, page.strikethrough)
                    checkErased(document, page, "erased")
                }
            }
            project(erased)
            withViewerDocument(nup) { document ->
                for (page in pages) {
                    val area = labelArea(nup, page.index)
                    val kept = listOf(page.highlight, page.underline, page.pen, page.strikethrough)
                        .filter { area == null || touches(page.inUser(it), area) }
                    expectMarks(document, page, "projected", *kept.toTypedArray())
                }
                check(saveMarks(nup, blanked, PdfMarkSave.Plan(blankAll = true))) { "editor: erasing every page saved nothing" }
            }
            checkSourceMark(pages, true, "projected")
            withViewerDocument(blanked) { document ->
                for (page in pages) expectMarks(document, page, "every page erased")
            }
            project(blanked)
            withViewerDocument(nup) { document ->
                for (page in pages) expectMarks(document, page, "every page erased, projected")
            }
            checkSourceMark(pages, false, "every page erased, projected")
        }
    }

    private suspend fun <R> withViewerDocument(file: File, block: suspend (PdfViewerDocument<PdfRasterTarget>) -> R): R {
        val document = PdfViewerDocument.open { MuPdfViewerEngine.open(file.absolutePath) }
        try {
            return block(document)
        } finally {
            document.close()
            document.awaitClosed()
        }
    }

    private fun saveMarks(input: File, output: File, plan: PdfMarkSave.Plan): Boolean {
        val tracker = ProgressTracker(SystemClock::uptimeMillis)
        return PdfMarkSave.write(input, output, plan, BATCH, tracker::stage) {}
    }

    private suspend fun project(edited: File) {
        val operation = PdfViewerEditOperation(
            files = files(),
            saveSelection = {},
            input = PdfViewerEditOperation.Input(
                config = config,
                builtConfig = config,
                crops = crops,
                rotation = rotation.copyOf(),
                position = position.copyOf(),
                defaultPosition = DEFAULT_POSITION,
                batchSize = BATCH,
                selected = emptyList()
            ),
            progress = progress,
            copyEdited = { work -> edited.copyTo(work, overwrite = true) }
        )
        take(operation.execute {})
    }

    private suspend fun expectMarks(
        document: PdfViewerDocument<PdfRasterTarget>,
        page: EditorPage,
        label: String,
        vararg wanted: PdfMark
    ): List<PdfPageMark> {
        val name = "editor page ${page.index + 1} $label"
        val found = document.pageMarks(page.index).marks
        val ids = found.map { it.mark.id }.sorted()
        check(ids == wanted.map { it.id }.sorted()) { "$name: marks $ids, expected ${wanted.map { it.id }.sorted()}" }
        for (mark in wanted) {
            val read = found.single { it.mark.id == mark.id }.mark
            check(read.kind == mark.kind && read.color == mark.color) { "$name: ${mark.kind} read back as ${read.kind} #${Integer.toHexString(read.color)}" }
            check(samePath(read, mark)) { "$name: ${mark.kind} moved to ${read.path.points.joinToString()} width ${read.width}" }
        }
        if (wanted.any { it.id == page.pen.id }) checkPenDrawn(document, page, name)
        return found
    }

    private fun samePath(read: PdfMark, wanted: PdfMark): Boolean {
        val points = read.path.points
        val expected = wanted.path.points
        return read.path.verbs.contentEquals(wanted.path.verbs) &&
            points.size == expected.size &&
            points.indices.all { abs(points[it] - expected[it]) <= MARK_TOLERANCE } &&
            abs(read.width - wanted.width) <= MARK_TOLERANCE
    }

    private suspend fun checkPenDrawn(document: PdfViewerDocument<PdfRasterTarget>, page: EditorPage, name: String) {
        checkColor(probe(document, page, page.centerY), PEN_COLOR) { "$name: pen not drawn at the page centre ($it)" }
    }

    private suspend fun checkErased(document: PdfViewerDocument<PdfRasterTarget>, page: EditorPage, label: String) {
        val rgb = probe(document, page, page.extraY)
        val background = page.background
        val same = rgb.indices.all { abs(rgb[it] - background[it]) <= PROBE_SAME }
        check(same) { "editor page ${page.index + 1} $label: extra pen still drawn (${rgb.joinToString()}, page ${background.joinToString()})" }
    }

    private fun checkColor(rgb: IntArray, color: Int, message: (String) -> String) {
        val matches = rgb.indices.all { channel ->
            val wanted = (color shr (16 - 8 * channel)) and 0xFF
            if (wanted == 0xFF) rgb[channel] >= PROBE_STRONG else rgb[channel] <= PROBE_WEAK
        }
        check(matches) { message(rgb.joinToString()) }
    }

    private suspend fun probe(document: PdfViewerDocument<PdfRasterTarget>, page: EditorPage, pageY: Float): IntArray {
        val name = "editor page ${page.index + 1}"
        val zoom = max(1f, PROBE_LINE_PX / page.pen.width)
        val x = (page.centerX * zoom).toInt()
        val y = (pageY * zoom).toInt()
        val left = (x - PROBE_PX / 2).coerceAtLeast(0)
        val top = (y - PROBE_PX / 2).coerceAtLeast(0)
        val bitmap = createBitmap(PROBE_PX, PROBE_PX)
        try {
            val result = document.render(page.index, zoom, left, top, AndroidBitmapRasterTarget(bitmap))
            if (result is PdfRenderResult.Failed) throw IllegalStateException("$name: ${result.error}", result.error)
            check(result == PdfRenderResult.Done) { "$name: $result" }
            val pixel = bitmap[(x - left).coerceIn(0, PROBE_PX - 1), (y - top).coerceIn(0, PROBE_PX - 1)]
            return intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkSourceMark(pages: List<EditorPage>, expected: Boolean, label: String) {
        for (page in pages) {
            val found = sourceHasMark(page.index, page.pen.id)
            check(found == expected) { "editor source page ${page.index + 1} $label: pen ${if (found) "still on" else "missing from"} the source page" }
        }
    }


    private class EditorPage(val index: Int, width: Float, height: Float, private val toPage: DoubleArray) {
        private val markWidth = (min(width, height) * MARK_WIDTH_RATIO).coerceIn(MIN_MARK_WIDTH, MAX_MARK_WIDTH)
        val highlight = line(PdfMarkKind.HIGHLIGHTER, HIGHLIGHT_COLOR, width, height * HIGHLIGHT_Y)
        val underline = band(PdfMarkKind.UNDERLINE, UNDERLINE_COLOR, width, height * UNDERLINE_Y)
        val pen = line(PdfMarkKind.PEN, PEN_COLOR, width, height * PEN_Y)
        val strikethrough = band(PdfMarkKind.STRIKETHROUGH, STRIKETHROUGH_COLOR, width, height * STRIKETHROUGH_Y)
        val extra = line(PdfMarkKind.PEN, EXTRA_COLOR, width, height * EXTRA_Y)
        val centerX = width / 2f
        val centerY = height * PEN_Y
        val extraY = height * EXTRA_Y
        var background = IntArray(3)

        fun added(): List<PdfMark> = listOf(highlight, underline, pen, strikethrough, extra).map(::inUser)

        fun inUser(mark: PdfMark): PdfMark =
            mark.transformed(checkNotNull(PdfEditProjection.inverse(toPage)) { "editor page ${index + 1}: no usable transform" })

        private fun line(kind: PdfMarkKind, color: Int, width: Float, y: Float) = PdfMark(
            PdfMark.newId(),
            kind,
            color,
            markWidth,
            PdfMarkPath.Builder().moveTo(width * LINE_FROM, y).lineTo(width * LINE_TO, y).build()
        )

        private fun band(kind: PdfMarkKind, color: Int, width: Float, y: Float) = PdfMark(
            PdfMark.newId(),
            kind,
            color,
            0f,
            PdfMarkPath.Builder().moveTo(width * LINE_FROM, y - markWidth / 2).lineTo(width * LINE_TO, y - markWidth / 2)
                .lineTo(width * LINE_TO, y + markWidth / 2).lineTo(width * LINE_FROM, y + markWidth / 2).close().build()
        )
    }

    private suspend fun fitAndZoom(page: AndroidSinglePage, label: String, gutter: Double) {
        val sizes = page.pageSizes()
        val width = resources.displayMetrics.widthPixels
        val fit = ViewerViewport.fitWidthZoom(width, ViewerPageLayout(sizes.widths, sizes.heights, 0.0), gutter)
        val fitted = page.render(0, fit, 0, 0, pixels(sizes.widths[0] * fit), pixels(sizes.heights[0] * fit))
        check(fitted is ViewerRender.Done) { "$label: $fitted" }
        page.release(fitted.image)
        val zoom = fit * ViewerViewport.DOUBLE_TAP_FIT_FACTOR
        val left = ((pixels(sizes.widths[0] * zoom) - 2 * ZOOMED_TILE_PX) / 2).coerceAtLeast(0)
        val top = ((pixels(sizes.heights[0] * zoom) - ZOOMED_TILE_PX) / 2).coerceAtLeast(0)
        val first = ViewerTile(0, zoom, left, top, ZOOMED_TILE_PX, ZOOMED_TILE_PX)
        for (tiles in listOf(listOf(first), listOf(first, first.copy(left = left + ZOOMED_TILE_PX)))) {
            val drawn = page.renderTiles(tiles)
            check(drawn is ViewerTilesRender.Done && drawn.images.size == tiles.size) { "$label zoomed: $drawn" }
            drawn.images.forEach(page::release)
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
        val all = document.highlight(index, null, null)
        checkRects(all.rects, limit) { "$label select all" }
        val lines = document.baselines(index, null, null)
        checkBaselines(lines, all.boxes) { "$label baselines" }
        checkCentered(lines, all.rects) { "$label centered boxes" }
        checkUprightColumns(document, index, lines, label)
        val word = document.copy(index, null, null).split(WHITESPACE)
            .firstOrNull { it.length >= 2 && it.all(Char::isLetterOrDigit) } ?: return
        val hits = document.search(index, word)
        check(hits.count >= 1) { "$label: the copied word '$word' is not found" }
        val counted = document.count(index, word)
        check(counted == hits.count) { "$label: '$word' counts $counted, the search finds ${hits.count}" }
        checkRects(hits.rects, limit) { "$label search" }
    }

    private suspend fun checkUprightColumns(document: PdfViewerDocument<PdfRasterTarget>, index: Int, lines: PdfTextBaselines, label: String) {
        val runs = lines.runs
        for (line in 0 until lines.count) {
            val run = line * PdfTextBaselines.RUN_SIZE
            if (runs[run + 7] == 0f) continue
            val length = hypot(runs[run + 2] - runs[run], runs[run + 3] - runs[run + 1])
            if (length < UPRIGHT_MIN_LETTERS * runs[run + 6]) continue
            val from = PdfTextPoint(runs[run], runs[run + 1])
            val to = PdfTextPoint(runs[run + 2], runs[run + 3])
            val text = document.copy(index, from, to).trim()
            check(text.codePointCount(0, text.length) >= 2 && '\n' !in text) { "$label: upright line ${line + 1} copies '$text'" }
            val boxes = document.highlight(index, from, to).rects.size / PdfTextHits.RECT_SIZE
            check(boxes == 1) { "$label: upright line ${line + 1} is highlighted in $boxes boxes" }
            val needle = text.substring(0, text.offsetByCodePoints(0, 2))
            check(document.search(index, needle).count >= 1) { "$label: upright line ${line + 1} start '$needle' is not found" }
        }
    }

    private fun checkBaselines(lines: PdfTextBaselines, rects: FloatArray, label: () -> String) {
        val count = rects.size / PdfTextHits.RECT_SIZE
        check(lines.count == count) { "${label()}: ${lines.count} lines for $count highlighted lines" }
        check(lines.estimated == 0) { "${label()}: ${lines.estimated} of ${lines.count} lines without their first letter" }
        for (line in 0 until count) {
            val run = line * PdfTextBaselines.RUN_SIZE
            val rect = line * PdfTextHits.RECT_SIZE
            for (end in 0..1) {
                val x = lines.runs[run + end * 2]
                val y = lines.runs[run + end * 2 + 1]
                val inside = x >= rects[rect] - BASELINE_SLACK_PT && x <= rects[rect + 2] + BASELINE_SLACK_PT &&
                    y >= rects[rect + 1] - BASELINE_SLACK_PT && y <= rects[rect + 3] + BASELINE_SLACK_PT
                check(inside) { "${label()}: line ${line + 1} baseline $x, $y outside its box ${rects.copyOfRange(rect, rect + 4).joinToString()}" }
            }
        }
    }

    private fun checkCentered(lines: PdfTextBaselines, rects: FloatArray, label: () -> String) {
        check(rects.size / PdfTextHits.RECT_SIZE == lines.count) { "${label()}: ${rects.size / PdfTextHits.RECT_SIZE} boxes for ${lines.count} lines" }
        for (line in 0 until lines.count) {
            val run = line * PdfTextBaselines.RUN_SIZE
            if (lines.runs[run + 7] != 0f) continue
            val rect = line * PdfTextHits.RECT_SIZE
            val size = lines.runs[run + 6]
            val middleX = (lines.runs[run] + lines.runs[run + 2]) / 2
            val middleY = (lines.runs[run + 1] + lines.runs[run + 3]) / 2
            val boxX = (rects[rect] + rects[rect + 2]) / 2
            val boxY = (rects[rect + 1] + rects[rect + 3]) / 2
            val lift = (boxX - middleX) * lines.runs[run + 4] + (boxY - middleY) * lines.runs[run + 5]
            check(abs(lift - TEXT_MIDDLE * size) <= CENTER_TOLERANCE * size + CENTER_TOLERANCE_PT) {
                "${label()}: line ${line + 1} box middle $lift above its baseline, wanted ${TEXT_MIDDLE * size}"
            }
        }
    }

    private suspend fun checkWordBaseline(document: PdfViewerDocument<PdfRasterTarget>, word: PdfTextSpan) {
        val lines = document.baselines(0, word.from, word.to)
        check(lines.count == 1 && lines.estimated == 0) { "the word's baseline: ${lines.count} lines, ${lines.estimated} estimated" }
        checkBaselines(lines, word.rects) { "the word's baseline" }
        val run = lines.runs
        val onBaseline = abs(run[1] - TEXT_BASELINE_PT) <= BASELINE_TOLERANCE_PT && abs(run[3] - TEXT_BASELINE_PT) <= BASELINE_TOLERANCE_PT
        check(onBaseline) { "the word's baseline is at ${run[1]} to ${run[3]}, the text is drawn at $TEXT_BASELINE_PT" }
        val upright = abs(run[4]) <= BASELINE_TOLERANCE_PT && abs(run[5] + 1f) <= BASELINE_TOLERANCE_PT
        check(upright && abs(run[6] - TEXT_SIZE_PT) <= BASELINE_TOLERANCE_PT) { "the word's baseline points ${run[4]}, ${run[5]} at size ${run[6]}" }
        val middle = (word.rects[1] + word.rects[3]) / 2
        val wanted = TEXT_BASELINE_PT - TEXT_MIDDLE * TEXT_SIZE_PT
        check(abs(middle - wanted) <= BASELINE_TOLERANCE_PT) { "the word's box is centered at $middle, the letters at $wanted" }
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
                checkWordBaseline(document, word)
                val empty = document.word(0, PdfTextPoint(TEXT_X_PT, EMPTY_Y_PT), WORD_TOLERANCE_PT)
                check(empty == null) { "a word was selected on an empty area" }
                selectEveryWord(document)
                dragBetweenStaggeredLines(document)
                checkRects(document.highlight(0, null, null).rects, limit) { "select all" }
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

    private fun labelsPdf(): File {
        val file = File(dir, "labels.pdf")
        PdfDocument(PdfWriter(file.absolutePath, PdfIo.writerProperties())).use { pdf ->
            repeat(LABELS) { index ->
                PdfCanvas(pdf.addNewPage(PageSize.A6))
                    .setFillColorGray(LABEL_GRAY).rectangle(60.0, 150.0, 140.0, 120.0).fill()
                    .setFillColorGray(0f).rectangle(60.0, 300.0, 40.0 + index * 10, 20.0).fill()
                    .release()
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
        const val SMALL_SIDE_PX = 100f
        const val ZOOMED_TILE_PX = 512
        const val CROP_GUTTER_DP = 24f
        const val MARK_WIDTH_RATIO = 0.05f
        const val MIN_MARK_WIDTH = 0.5f
        const val MAX_MARK_WIDTH = 24f
        const val MARK_TOLERANCE = 0.05f
        const val LINE_FROM = 0.3f
        const val LINE_TO = 0.7f
        const val HIGHLIGHT_Y = 0.25f
        const val UNDERLINE_Y = 0.375f
        const val PEN_Y = 0.5f
        const val STRIKETHROUGH_Y = 0.625f
        const val EXTRA_Y = 0.75f
        const val HIGHLIGHT_COLOR = 0xFFFF00
        const val UNDERLINE_COLOR = 0x35C369
        const val PEN_COLOR = 0xFF0000
        const val STRIKETHROUGH_COLOR = 0xDD0000
        const val EXTRA_COLOR = 0x0000FF
        const val PROBE_PX = 5
        const val PROBE_LINE_PX = 12f
        const val PROBE_STRONG = 180
        const val PROBE_WEAK = 90
        const val PROBE_SAME = 3
        const val LAYER_LIMIT = 1_000_000L
        const val PIECE = "hureelee81reepdf"
        const val LABELS = 4
        const val LABEL_GRAY = 0.6f
        val LABEL_CROP = PdfCropRect(0.25f, 0.25f, 0.75f, 0.75f)
        val LABELS_SCALED = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = false)
        val LABELS_FITTED = NupConfig(2, 2, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = true)
        val LABELS_ONE_UP = NupConfig(1, 1, 5, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = true)
        val SHEET = NupArea(0.0, 0.0, A4_WIDTH_PT.toDouble(), A4_HEIGHT_PT.toDouble())
        val NOTHING_VISIBLE = NupArea(0.0, 0.0, 0.0, 0.0)
        const val LABEL_ZOOM = 2f
        const val LABEL_STROKE_INSET = 4.0
        const val LABEL_PEN_WIDTH = 3f
        const val LABEL_STRIP_WIDTH = 10f
        const val LABEL_BAND_FROM = 65.0
        const val LABEL_BAND_TO = 195.0
        const val LABEL_BAND_Y = 285.0
        const val AREA_TOLERANCE = 0.01
        const val STRIP_BLUE_GAP = 40
        const val DOUBLE_STRIP_BLUE = 125
        const val STRIP_TOLERANCE_DIVISOR = 20
        const val HEAVY_ZOOM = 2f
        const val HEAVY_FILLS = 8_000
        const val CLOSE_AFTER_MS = 50L
        const val ABORT_LIMIT_MS = 1_000L
        const val BIG_IMAGE_SIDE = 16_000
        const val IMAGE_ABORT_AFTER_MS = 50L
        const val IMAGE_ABORT_LIMIT_MS = 300L
        const val THUMBNAIL_WAIT_S = 120L
        const val TEXT_RECT_LIMIT = 100f
        const val BASELINE_SLACK_PT = 0.5f
        const val BASELINE_TOLERANCE_PT = 0.05f
        const val TEXT_MIDDLE = 0.27f
        const val CENTER_TOLERANCE = 0.01f
        const val CENTER_TOLERANCE_PT = 0.001f
        const val UPRIGHT_MIN_LETTERS = 1.5f
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
