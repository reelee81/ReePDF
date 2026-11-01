package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.exceptions.BadPasswordException
import com.itextpdf.kernel.pdf.PdfDocument
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PageGroups
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.ProgressMath
import hu.reelee81.reepdf.core.ProgressNotifyPacing
import hu.reelee81.reepdf.core.ProgressTicker
import hu.reelee81.reepdf.core.SourcePageArrays
import hu.reelee81.reepdf.core.SourcePartPlan
import hu.reelee81.reepdf.core.WorkFiles
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PdfImportOperation(
    private val files: PdfLayoutChangeOperation.Files,
    private val saveSelection: () -> Unit,
    private val input: Input,
    private val progress: PdfLayoutChangeOperation.Progress,
    private val imageToPdf: (File) -> File,
    private val repair: Repair = Repair(PdfMuPdfRepair::withRepairedParts),
    private val warn: (message: String, error: Throwable) -> Unit = { _, _ -> }
) {
    class Source(
        val file: File,
        val password: ByteArray?,
        val openFailure: Exception?,
        val isImage: Boolean = false,
        val name: String = file.name
    )

    class Input(
        val sources: List<Source>,
        val oldPerGroup: Int,
        val config: NupConfig,
        val crops: Map<Int, PdfCropRect>,
        val rotation: IntArray,
        val position: IntArray,
        val defaultPosition: Int,
        val batchSize: Int,
        val selected: List<Int>
    )

    fun interface Repair {
        fun withRepairedParts(
            input: File,
            password: ByteArray?,
            workDir: File,
            batchSize: Int,
            ensureActive: () -> Unit,
            block: (List<File>) -> Unit
        ): PdfMuPdfRepair.Repaired?
    }

    class Probe(val canOpen: Boolean, val noPages: Boolean, val failure: Exception?)

    private class Loss {
        var skippedPages = 0
        var missingContent = false
        var earlierRevision = false

        fun warningFor(name: String): PdfImportWarning? =
            if (skippedPages > 0 || missingContent || earlierRevision) {
                PdfImportWarning(name, skippedPages, missingContent, earlierRevision)
            } else null
    }

    private class OwnRotations(val first: Int, val quarterTurns: IntArray)

    @Volatile private var backedUp = false

    suspend fun execute(report: (Int?) -> Unit): PdfLayoutChangeOperation.Result = withContext(Dispatchers.IO) {
        val tracker = ProgressTracker(progress.clock)
        tracker.reset()
        val job = coroutineContext[Job]
        val ensureActive = { job?.ensureActive(); Unit }

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

                val oldTotal = sourcePageCount()
                val selectedPages: Set<Int> = if (input.selected.isNotEmpty() && oldTotal > 0) {
                    PageGroups.sourcePagesOfGroups(input.selected, input.oldPerGroup, oldTotal)
                } else emptySet()

                files.backup.backup(ensureActive)
                backedUp = true

                try {
                    var rotation = input.rotation
                    var position = input.position
                    val warnings = ArrayList<PdfImportWarning>()
                    val ownRotations = ArrayList<OwnRotations>()
                    for (source in input.sources) {
                        val loss = Loss()
                        if (source.isImage) {
                            val imagePdf = imageToPdf(source.file)
                            try {
                                append(listOf(imagePdf), null, tracker, loss, ownRotations)
                            } finally {
                                runCatching { imagePdf.delete() }
                            }
                        } else {
                            appendOrRepair(source, tracker, ensureActive, loss, ownRotations)
                        }
                        loss.warningFor(source.name)?.let(warnings::add)
                        runCatching { source.file.delete() }

                        val grownTotal = sourcePageCount()
                        rotation = SourcePageArrays.rotationsGrown(rotation, grownTotal)
                        position = SourcePageArrays.positionsSized(position, grownTotal, input.defaultPosition)
                    }

                    val source = PdfSourceParts.open(files.sourceDir)
                    val total = source.total
                    rotation = SourcePageArrays.rotationsSized(rotation, total)
                    for (own in ownRotations) rotation = SourcePageArrays.withOwnRotations(rotation, own.first, own.quarterTurns)
                    position = SourcePageArrays.positionsSized(position, total, input.defaultPosition)

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

                    val per = max(1, min(100, input.config.rows)) * max(1, min(100, input.config.cols))
                    val selected = if (selectedPages.isNotEmpty()) {
                        PageGroups.groupsOfSourcePages(selectedPages, per).toList()
                    } else emptyList()

                    PdfLayoutChangeOperation.Result(
                        input.crops, rotation, position, input.defaultPosition, input.config,
                        selected, tracker.snapshot(), pageSizes, importWarnings = warnings
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
            for (source in input.sources) runCatching { source.file.delete() }
        }
    }

    private fun appendOrRepair(
        source: Source,
        tracker: ProgressTracker,
        ensureActive: () -> Unit,
        loss: Loss,
        ownRotations: MutableList<OwnRotations>
    ) {
        val doneBefore = tracker.doneUnits()
        val failure = source.openFailure ?: try {
            append(listOf(source.file), source.password, tracker, loss, ownRotations)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracker.resetDoneUnits(doneBefore)
            e
        }
        loss.missingContent = false

        ensureActive()
        val repaired = repair.withRepairedParts(
            input = source.file,
            password = source.password,
            workDir = files.workDir,
            batchSize = input.batchSize,
            ensureActive = ensureActive
        ) { parts -> append(parts, null, tracker, loss, ownRotations) }
        if (repaired != null) {
            loss.skippedPages = repaired.skippedPages
            if (repaired.annotationsLost) loss.missingContent = true
            return
        }

        ensureActive()
        val recovered = PdfRevisionRecovery.withEarlierRevision(
            input = source.file,
            workDir = files.workDir,
            ensureActive = ensureActive,
            isUsable = { revision ->
                try {
                    PdfDocument(PdfIo.reader(revision.absolutePath, source.password)).use { it.numberOfPages > 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
        ) { revision -> append(listOf(revision), source.password, tracker, loss, ownRotations) }
        if (!recovered) throw failure
        loss.earlierRevision = true
    }

    private fun append(
        srcPdfs: List<File>,
        password: ByteArray?,
        tracker: ProgressTracker,
        loss: Loss,
        ownRotations: MutableList<OwnRotations>
    ) {
        val source = if (PdfSourceParts.exists(files.sourceDir)) PdfSourceParts.open(files.sourceDir) else PdfSourceParts.empty(files.sourceDir)
        val pages = srcPdfs.map { file -> PdfDocument(PdfIo.reader(file.absolutePath, password)).use { it.numberOfPages } }
        val pieces = pages.mapIndexedNotNull { index, count -> if (count > 0) SourcePartPlan.Piece.Incoming(index, 0, count - 1) else null }
        val plan = SourcePartPlan.append(source.layout, pieces)
        val per = (input.config.rows * input.config.cols).coerceAtLeast(1)
        val stage = tracker.stage(plan.writtenPages + ProgressMath.layoutUnits(source.total + pages.sum(), per))
        val firstPages = pages.runningFold(0, Int::plus)
        var turned: IntArray? = null

        source.write(
            plan,
            input.batchSize,
            stage,
            PdfSourceParts.Inputs(
                incoming = srcPdfs.mapIndexed { index, file ->
                    PdfSourceParts.Incoming(
                        file,
                        password,
                        onMissingAppearance = { loss.missingContent = true },
                        onOwnRotation = { page, quarterTurns ->
                            val collected = turned ?: IntArray(pages.sum()).also { turned = it }
                            collected[firstPages[index] + page] = quarterTurns
                        }
                    )
                },
                copierDir = files.workDir
            )
        )
        turned?.let { ownRotations += OwnRotations(source.total, it) }
    }

    suspend fun restoreIfBackedUp() {
        if (!backedUp) return
        withContext(Dispatchers.IO) {
            files.backup.restore()
        }
    }

    private fun sourcePageCount(): Int {
        if (!PdfSourceParts.exists(files.sourceDir)) return 0
        return try { PdfSourceParts.open(files.sourceDir).total } catch (_: Exception) { 0 }
    }

    companion object {
        fun probe(
            file: File,
            password: ByteArray?,
            rejectsPassword: (File, ByteArray?) -> Boolean = PdfMuPdfRepair::rejectsPassword
        ): Probe = try {
            val (encrypted, pageCount) = PdfDocument(PdfIo.reader(file.absolutePath, password)).use {
                it.reader.isEncrypted to it.numberOfPages
            }
            Probe(canOpen = !encrypted || !rejectsPassword(file, password), noPages = pageCount == 0, failure = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isBadPassword(e)) {
                Probe(canOpen = false, noPages = false, failure = null)
            } else {
                Probe(canOpen = true, noPages = false, failure = e)
            }
        }

        private fun isBadPassword(t: Throwable): Boolean {
            if (t is BadPasswordException) return true
            val msg = t.message?.lowercase().orEmpty()
            return msg.contains("bad user password") || msg.contains("bad password")
        }
    }
}
