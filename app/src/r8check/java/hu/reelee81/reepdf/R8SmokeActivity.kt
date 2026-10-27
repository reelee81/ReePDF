package hu.reelee81.reepdf

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.NupGeometry.DEFAULT_POSITION
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.SignatureGeometry
import hu.reelee81.reepdf.core.WorkFileBackup
import hu.reelee81.reepdf.pdf.MuPdfSignedPageRenderer
import hu.reelee81.reepdf.pdf.PdfImportOperation
import hu.reelee81.reepdf.pdf.PdfIo
import hu.reelee81.reepdf.pdf.PdfLayoutChangeOperation
import hu.reelee81.reepdf.pdf.PdfOutputOperation
import hu.reelee81.reepdf.pdf.PdfSignOperation
import hu.reelee81.reepdf.pdf.PdfSourceParts
import hu.reelee81.reepdf.pdf.SignedPageRenderer
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
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
                    step("file: ${file.relativeTo(inputDir).path}") { import(file) }
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
            delay(CANCEL_AFTER_MS)
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
                batchSize = BATCH,
                messages = PdfOutputOperation.Messages("layout missing", "name reserved", "replace failed", "rename failed")
            ),
            progress = progress
        )
        return runBlocking { operation.execute {} }.file
    }

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
        const val BATCH = 20
        const val SIGNATURE_W = 400
        const val SIGNATURE_H = 100
        const val SHOWN_LINES = 40
        const val CANCEL_FILE = "1000.pdf"
        const val CANCEL_AFTER_MS = 3_000L
    }
}
