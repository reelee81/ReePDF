package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PageGroups
import hu.reelee81.reepdf.core.PageSizes
import hu.reelee81.reepdf.core.PdfCropRect
import hu.reelee81.reepdf.core.PdfCrops
import hu.reelee81.reepdf.core.PdfOperationResult
import hu.reelee81.reepdf.core.ProgressMath
import hu.reelee81.reepdf.core.ProgressNotifyPacing
import hu.reelee81.reepdf.core.ProgressTicker
import hu.reelee81.reepdf.core.SourcePageArrays
import hu.reelee81.reepdf.core.SourcePageRuns
import hu.reelee81.reepdf.core.SourcePartPlan
import hu.reelee81.reepdf.core.WorkFileBackup
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfLayoutChangeOperation(
    private val files: Files,
    private val saveSelection: () -> Unit,
    private val input: Input,
    private val progress: Progress,
    private val warn: (message: String, error: Throwable) -> Unit = { _, _ -> }
) {
    class Files(val workDir: File, val sourceDir: File, val nup: File, val backup: WorkFileBackup)

    sealed interface Change {
        class Crop(val nupIndex: Int, val crop: PdfCropRect?, val applyToAll: Boolean) : Change
        class Rotate(val nupIndices: List<Int>) : Change
        class Position(val nupIndices: List<Int>, val position: Int) : Change
        class Rebuild(val oldPerGroup: Int, val resetPositions: Boolean = false) : Change
        class DeleteGroups(val nupIndices: List<Int>) : Change
        data object AddEmptyPage : Change
        class Reorder(val groupOrder: List<Int>) : Change
    }

    class Input(
        val change: Change,
        val perGroup: Int,
        val config: NupConfig,
        val crops: Map<Int, PdfCropRect>,
        val rotation: IntArray,
        val position: IntArray,
        val defaultPosition: Int,
        val batchSize: Int,
        val selected: List<Int>
    )

    class Progress(val clock: () -> Long, val tickMs: Long, val idleMs: Long)

    class Result(
        val crops: Map<Int, PdfCropRect>,
        val rotation: IntArray,
        val position: IntArray,
        val defaultPosition: Int,
        val config: NupConfig?,
        val selected: List<Int>,
        val progress: ProgressTracker.Snapshot,
        override val pageSizes: PageSizes?,
        val cleared: Boolean = false
    ) : PdfOperationResult

    @Volatile private var backedUp = false

    private val rewritesSource: Boolean
        get() = input.change is Change.DeleteGroups || input.change is Change.AddEmptyPage || input.change is Change.Reorder

    suspend fun execute(report: (Int?) -> Unit): Result? = withContext(Dispatchers.IO) {
        val tracker = ProgressTracker(progress.clock)
        tracker.reset()

        val built = ProgressTicker.run(
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

            val source = openSource()
            val total = source.total
            if (total <= 0 && input.change !is Change.Rebuild) return@run null

            val removed = (input.change as? Change.DeleteGroups)
                ?.let { SourcePageRuns.removedPages(it.nupIndices, input.perGroup, total) }
            if (removed != null && total - removed.size <= 0) {
                return@run Built(emptyMap(), IntArray(0), IntArray(0), null, emptyList(), cleared = true)
            }

            files.backup.backup()
            backedUp = true

            try {
                val written = rewriteSource(source, total, removed, tracker::stage)
                val newTotal = written.total
                val (crops, rotation, position) = changedArrays(total, newTotal, removed)

                val work = File(files.workDir, "${files.nup.name}.part")
                if (work.exists()) work.delete()
                val pageSizes = PdfNupBuilder.build(
                    source = written,
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
                if (files.nup.exists()) files.nup.delete()
                work.renameTo(files.nup)

                Built(crops, rotation, position, pageSizes, selectedAfter(total))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (rewritesSource) {
                    files.backup.restore()
                    backedUp = false
                }
                throw e
            }
        } ?: return@withContext null

        Result(built.crops, built.rotation, built.position, input.defaultPosition, input.config, built.selected, tracker.snapshot(), built.pageSizes, built.cleared)
    }

    private fun rewriteSource(source: PdfSourceParts, total: Int, removed: Set<Int>?, stage: (Long) -> PdfProgress): PdfSourceParts {
        val per = (input.config.rows * input.config.cols).coerceAtLeast(1)
        return when (val change = input.change) {
            is Change.DeleteGroups -> {
                val plan = SourcePartPlan.removePages(source.layout, removed!!)
                source.write(plan, input.batchSize, stage(plan.writtenPages + ProgressMath.layoutUnits(total - removed.size, per)))
            }
            Change.AddEmptyPage -> {
                val firstPageSize = source.batch { src ->
                    val p = src.page(0).pageSize
                    PageSize(p.width, p.height)
                }
                val plan = SourcePartPlan.append(source.layout, listOf(SourcePartPlan.Piece.EmptyPage))
                source.write(
                    plan,
                    input.batchSize,
                    stage(plan.writtenPages + ProgressMath.layoutUnits(total + 1, per)),
                    PdfSourceParts.Inputs(emptyPage = firstPageSize)
                )
            }
            is Change.Reorder -> {
                val plan = SourcePartPlan.reorder(source.layout, SourcePageArrays.reorderedIndices(change.groupOrder, input.perGroup, total))
                source.write(plan, input.batchSize, stage(plan.writtenPages + ProgressMath.layoutUnits(total, per)))
            }
            else -> source
        }
    }

    private fun selectedAfter(total: Int): List<Int> = when (val change = input.change) {
        is Change.Rebuild -> regrouped(change.oldPerGroup, total)
        Change.AddEmptyPage -> regrouped(input.perGroup, total)
        is Change.DeleteGroups -> {
            val deleted = change.nupIndices.toSet()
            input.selected.filter { it !in deleted }.map { index -> index - deleted.count { it < index } }
        }
        else -> input.selected
    }

    private fun regrouped(oldPerGroup: Int, total: Int): List<Int> {
        if (input.selected.isEmpty() || total <= 0) return emptyList()
        val newPer = (input.config.rows.coerceAtLeast(1)) * (input.config.cols.coerceAtLeast(1))
        val selectedPages = PageGroups.sourcePagesOfGroups(input.selected, oldPerGroup, total)
        return PageGroups.groupsOfSourcePages(selectedPages, newPer).toList()
    }

    private fun changedArrays(total: Int, newTotal: Int, removed: Set<Int>?): Triple<Map<Int, PdfCropRect>, IntArray, IntArray> {
        val per = input.perGroup
        val rotation = SourcePageArrays.rotationsSized(input.rotation, total)
        val position = SourcePageArrays.positionsSized(input.position, total, input.defaultPosition)
        return when (val change = input.change) {
            is Change.Crop -> {
                val groupStart = change.nupIndex * per
                val indices = if (change.applyToAll) 0 until total else groupStart until min(total, groupStart + per)
                Triple(PdfCrops.withCrop(input.crops.filterKeys { it < total }, indices, change.crop), rotation, position)
            }
            is Change.Rotate ->
                Triple(input.crops, SourcePageArrays.withRotatedGroups(rotation, change.nupIndices, per, total), position)
            is Change.Position ->
                Triple(input.crops, rotation, SourcePageArrays.withGroupPosition(position, change.nupIndices, per, total, change.position))
            is Change.Rebuild ->
                Triple(input.crops, rotation, if (change.resetPositions) IntArray(total) { input.defaultPosition } else position)
            is Change.DeleteGroups ->
                Triple(
                    PdfCrops.afterRemoval(input.crops, removed!!),
                    SourcePageArrays.afterRemoval(rotation, removed),
                    SourcePageArrays.afterRemoval(position, removed)
                )
            Change.AddEmptyPage ->
                Triple(
                    input.crops,
                    SourcePageArrays.rotationsSized(SourcePageArrays.rotationsGrown(input.rotation, newTotal), newTotal),
                    SourcePageArrays.positionsSized(input.position, newTotal, input.defaultPosition)
                )
            is Change.Reorder -> {
                val newToOld = SourcePageArrays.reorderedIndices(change.groupOrder, per, total)
                Triple(
                    PdfCrops.afterReorder(input.crops, newToOld),
                    SourcePageArrays.rotationsAfterReorder(input.rotation, newToOld),
                    SourcePageArrays.positionsAfterReorder(input.position, newToOld, input.defaultPosition)
                )
            }
        }
    }

    private class Built(
        val crops: Map<Int, PdfCropRect>,
        val rotation: IntArray,
        val position: IntArray,
        val pageSizes: PageSizes?,
        val selected: List<Int>,
        val cleared: Boolean = false
    )

    suspend fun restoreIfBackedUp() {
        if (!backedUp) return
        withContext(Dispatchers.IO) {
            files.backup.restore()
        }
    }

    private fun openSource(): PdfSourceParts {
        if (input.change is Change.Rebuild) {
            if (!PdfSourceParts.exists(files.sourceDir)) throw FileNotFoundException(files.sourceDir.path)
            return PdfSourceParts.open(files.sourceDir)
        }
        return try { PdfSourceParts.open(files.sourceDir) } catch (_: Exception) { PdfSourceParts.empty(files.sourceDir) }
    }
}
