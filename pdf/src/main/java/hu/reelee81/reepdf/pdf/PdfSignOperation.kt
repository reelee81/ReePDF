package hu.reelee81.reepdf.pdf

import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.CompressionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.PdfCrops
import hu.reelee81.reepdf.core.PdfSignedGroups
import hu.reelee81.reepdf.core.ProgressMath
import hu.reelee81.reepdf.core.ProgressNotifyPacing
import hu.reelee81.reepdf.core.ProgressTicker
import hu.reelee81.reepdf.core.SignatureGeometry
import hu.reelee81.reepdf.core.SourcePageArrays
import hu.reelee81.reepdf.core.SourcePartPlan
import hu.reelee81.reepdf.core.WorkFiles
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class PdfSignOperation(
    private val files: PdfLayoutChangeOperation.Files,
    private val saveSelection: () -> Unit,
    private val input: Input,
    private val progress: PdfLayoutChangeOperation.Progress,
    private val renderer: SignedPageRenderer,
    private val warn: (message: String, error: Throwable) -> Unit = { _, _ -> }
) {
    class Input(
        val signature: SignedPageRenderer.Signature,
        val highDpi: Boolean,
        val nupIndices: List<Int>,
        val perGroup: Int,
        val config: NupConfig,
        val crops: Map<Int, PdfCropRect>,
        val rotation: IntArray,
        val position: IntArray,
        val defaultPosition: Int,
        val batchSize: Int
    )

    @Volatile private var backedUp = false

    suspend fun execute(report: (Int?) -> Unit): PdfLayoutChangeOperation.Result = withContext(Dispatchers.IO) {
        val tracker = ProgressTracker(progress.clock)
        tracker.reset()
        val job = coroutineContext[Job]
        val signed = mutableMapOf<Int, File>()

        try {
            ProgressTicker.run(
                tick = progress.tickMs.milliseconds,
                percent = {
                    val now = progress.clock()
                    if (now - tracker.startedAt < ProgressNotifyPacing.REVEAL_MS) null
                    else tracker.percent(now, progress.idleMs)
                },
                report = report,
                onCancel = tracker::cancel
            ) {
                saveSelection()

                files.backup.backup { job?.ensureActive() }
                backedUp = true

                try {
                    if (!PdfSourceParts.exists(files.sourceDir)) {
                        throw FileNotFoundException(files.sourceDir.path)
                    }
                    val original = PdfSourceParts.open(files.sourceDir)
                    val total = original.total
                    val per = (input.config.rows * input.config.cols).coerceAtLeast(1)
                    val signedPageWeight = ProgressMath.signedPageWeight(
                        highDpi = input.highDpi,
                        scanEffect = input.signature.scanEffect,
                        grayscale = input.signature.grayscale
                    )
                    val expected = SourcePartPlan.replaceGroups(original.layout, input.perGroup, input.nupIndices.toSet())
                    val stage = tracker.stage(
                        input.nupIndices.size.toLong() * signedPageWeight + expected.writtenPages + ProgressMath.layoutUnits(total, per)
                    )

                    renderSignedPages(signed, stage, signedPageWeight)

                    val plan = SourcePartPlan.replaceGroups(original.layout, input.perGroup, signed.keys)
                    val source = original.write(
                        plan,
                        input.batchSize,
                        tracker.stage(plan.writtenPages + ProgressMath.layoutUnits(total, per)),
                        PdfSourceParts.Inputs(replacements = signed)
                    )

                    val signedStarts = signed.keys.map { it * input.perGroup }.filter { it < total }
                    val merged = PdfSignedGroups.mergedIndices(signedStarts, input.perGroup, total)
                    val newTotal = source.total

                    val crops = PdfCrops.afterRemoval(PdfCrops.withCrop(input.crops, signedStarts, null), merged)
                    val rotation = SourcePageArrays.rotationsSized(
                        PdfSignedGroups.afterMerge(input.rotation, total, signedStarts, merged, signedValue = 0, missingValue = 0),
                        newTotal
                    )
                    val position = SourcePageArrays.positionsSized(
                        SourcePageArrays.afterRemoval(input.position, merged),
                        newTotal,
                        input.defaultPosition
                    )

                    val work = File(files.workDir, "${files.nup.name}.part")
                    if (work.exists()) work.delete()
                    val pageSizes = PdfNupBuilder.build(
                        source = source,
                        totalSrcPages = newTotal,
                        outFile = work,
                        workDir = files.workDir,
                        config = input.config,
                        rotation = rotation,
                        position = position,
                        crop = crops,
                        batchSize = input.batchSize,
                        progressStage = tracker::stage,
                        warn = warn
                    )
                    WorkFiles.replace(work, files.nup)

                    PdfLayoutChangeOperation.Result(
                        crops, rotation, position, input.defaultPosition, input.config,
                        emptyList(), tracker.snapshot(), pageSizes
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { files.backup.restore() }.exceptionOrNull()?.let(e::addSuppressed)
                    backedUp = false
                    throw e
                }
            }
        } finally {
            signed.values.forEach { runCatching { it.delete() } }
        }
    }

    private suspend fun renderSignedPages(signed: MutableMap<Int, File>, stage: PdfProgress, signedPageWeight: Int) {
        val writerProps = signedPageWriterProperties()
        val pageSize = if (input.signature.isLandscape) PageSize.A4.rotate() else PageSize.A4

        renderer.open(files.nup).use { pages ->
            for (idx in input.nupIndices) {
                yield()

                if (idx < 0 || idx >= pages.pageCount) continue

                val image = pages.render(idx, input.signature)
                val out = File(files.workDir, "signed_$idx.pdf")
                writeSignedPage(image, out, pageSize, writerProps)
                signed[idx] = out

                stage.advance(signedPageWeight)
                yield()
                delay(5L.milliseconds)
            }
        }
    }

    private fun writeSignedPage(image: SignedPageRenderer.RgbImage, out: File, pageSize: PageSize, writerProps: WriterProperties) {
        val imageData = ImageDataFactory.create(image.width, image.height, 3, 8, image.rgb, null)

        PdfDocument(PdfWriter(out.absolutePath, writerProps)).use { destDoc ->
            destDoc.setFlushUnusedObjects(true)

            val pg = destDoc.addNewPage(pageSize)
            val fit = SignatureGeometry.fitIntoPage(image.width, image.height, pageSize.width, pageSize.height)

            PdfCanvas(pg).addImageFittedIntoRectangle(
                imageData,
                Rectangle(fit.x, fit.y, fit.width, fit.height),
                false
            )
            pg.flush()
        }
    }

    suspend fun restoreIfBackedUp() {
        if (!backedUp) return
        withContext(Dispatchers.IO) {
            files.backup.restore()
        }
    }

    private companion object {
        fun signedPageWriterProperties(): WriterProperties = PdfIo.writerProperties()
            .setFullCompressionMode(true)
            .setCompressionLevel(CompressionConstants.BEST_COMPRESSION)
    }
}
