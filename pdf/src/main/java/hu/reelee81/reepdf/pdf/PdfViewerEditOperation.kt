package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.ProgressMath
import hu.reelee81.reepdf.core.ProgressNotifyPacing
import hu.reelee81.reepdf.core.ProgressTicker
import hu.reelee81.reepdf.core.SourcePageArrays
import hu.reelee81.reepdf.core.WorkFiles
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PdfViewerEditOperation(
    private val files: PdfLayoutChangeOperation.Files,
    private val saveSelection: () -> Unit,
    private val input: Input,
    private val progress: PdfLayoutChangeOperation.Progress,
    private val copyEdited: (File) -> Unit,
    private val warn: (message: String, error: Throwable) -> Unit = { _, _ -> }
) {
    class Input(
        val config: NupConfig,
        val builtConfig: NupConfig?,
        val crops: Map<Int, PdfCropRect>,
        val rotation: IntArray,
        val position: IntArray,
        val defaultPosition: Int,
        val batchSize: Int,
        val selected: List<Int>
    )

    @Volatile private var backedUp = false

    suspend fun execute(report: (Int?) -> Unit): PdfLayoutChangeOperation.Result = withContext(Dispatchers.IO) {
        val tracker = ProgressTracker(progress.clock)
        tracker.reset()
        val job = coroutineContext[Job]

        val work = File(files.workDir, "${files.nup.name}.edited")
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
                files.backup.backup()
                backedUp = true
                saveSelection()

                try {
                    if (work.exists()) work.delete()

                    copyEdited(work)

                    val editedPages = PdfDocument(PdfIo.reader(work.absolutePath)).use { it.numberOfPages }
                    val source = readableSource()
                    val sourcePages = source.total
                    val per = (input.config.rows * input.config.cols).coerceAtLeast(1)
                    val layoutUnits = ProgressMath.layoutUnits(sourcePages, per)
                    val stage = tracker.stage(editedPages + sourcePages + layoutUnits)

                    val projected = try {
                        PdfEditProjection.project(
                            source = source,
                            editedNup = work,
                            batchSize = input.batchSize,
                            onProgress = stage::advance,
                            writeStage = { pages -> tracker.stage(pages + layoutUnits) }
                        ) { job?.ensureActive() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: PdfEditProjection.NotProjectableException) {
                        null
                    }
                    stage.idle()

                    if (projected != null && projected.changed > 0) {
                        rebuilt(tracker, projected.source)
                    } else {
                        if (projected == null) {
                            if (files.nup.exists()) files.nup.delete()
                            if (!work.renameTo(files.nup)) work.copyTo(files.nup, overwrite = true)
                        }
                        val sizesConfig = input.builtConfig ?: input.config
                        val pageSizes = PdfPageSizes.forThumbnails(source, sourcePages, sizesConfig, input.batchSize, tracker::stage)
                        PdfLayoutChangeOperation.Result(
                            input.crops, input.rotation, input.position, input.defaultPosition, input.builtConfig,
                            input.selected, tracker.snapshot(), pageSizes
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    files.backup.restore()
                    backedUp = false
                    throw e
                }
            }
        } finally {
            runCatching { work.delete() }
            runCatching {
                files.workDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("edited_") || f.name.startsWith("flattened_")) f.delete()
                }
            }
        }
    }

    private fun rebuilt(tracker: ProgressTracker, source: PdfSourceParts): PdfLayoutChangeOperation.Result {
        val total = source.total
        val rotation = SourcePageArrays.rotationsSized(input.rotation, total)
        val position = SourcePageArrays.positionsSized(input.position, total, input.defaultPosition)

        val work = File(files.workDir, "${files.nup.name}.part")
        if (work.exists()) work.delete()
        val pageSizes = PdfNupBuilder.build(
            source = source,
            totalSrcPages = total,
            outFile = work,
            workDir = files.workDir,
            config = input.config,
            rotation = rotation,
            position = position,
            crop = input.crops,
            batchSize = input.batchSize,
            progressStage = tracker::stage,
            warn = warn
        )
        WorkFiles.replace(work, files.nup)

        return PdfLayoutChangeOperation.Result(
            input.crops, rotation, position, input.defaultPosition, input.config,
            input.selected, tracker.snapshot(), pageSizes
        )
    }

    suspend fun restoreIfBackedUp() {
        if (!backedUp) return
        withContext(Dispatchers.IO) {
            files.backup.restore()
        }
    }

    private fun readableSource(): PdfSourceParts {
        if (!PdfSourceParts.exists(files.sourceDir)) return PdfSourceParts.empty(files.sourceDir)
        return try { PdfSourceParts.open(files.sourceDir) } catch (_: Exception) { PdfSourceParts.empty(files.sourceDir) }
    }
}
