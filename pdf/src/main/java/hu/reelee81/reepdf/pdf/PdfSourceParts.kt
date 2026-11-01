package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import hu.reelee81.reepdf.core.SourcePartLayout
import hu.reelee81.reepdf.core.SourcePartPlan
import hu.reelee81.reepdf.core.WorkFiles
import java.io.File
import java.io.IOException
import java.util.Locale

class PdfSourceParts private constructor(val dir: File, private val files: List<File>, val layout: SourcePartLayout) {
    class Location(val file: File, val page: Int)

    class Incoming(
        val file: File,
        val password: ByteArray?,
        val onMissingAppearance: () -> Unit = {},
        val onOwnRotation: (page: Int, quarterTurns: Int) -> Unit = { _, _ -> }
    )

    class Inputs(
        val incoming: List<Incoming> = emptyList(),
        val replacements: Map<Int, File> = emptyMap(),
        val emptyPage: PageSize? = null,
        val copierDir: File? = null,
        val generateAppearances: (input: File, output: File) -> Unit = PdfMuPdfAnnotationAppearances::generate
    )

    val total: Int get() = layout.total

    fun file(part: Int): File = files[part]

    fun locate(page: Int): Location {
        val part = layout.partOf(page)
        return Location(files[part], page - layout.start(part))
    }

    fun <T> batch(block: (Batch) -> T): T = Batch().use(block)

    inner class Batch : AutoCloseable {
        private val opened = HashMap<Int, PdfDocument>()
        private val costs = HashMap<Int, PdfPageCost>()

        fun page(page: Int): PdfPage {
            val part = layout.partOf(page)
            return document(part).getPage(page - layout.start(part) + 1)
        }

        fun cost(page: Int): Long {
            val part = layout.partOf(page)
            return costs.getOrPut(part) { PdfPageCost(document(part)) }.of(page - layout.start(part) + 1)
        }

        private fun document(part: Int): PdfDocument =
            opened.getOrPut(part) { PdfDocument(PdfIo.reader(files[part].absolutePath)) }

        override fun close() {
            for (document in opened.values) runCatching { document.close() }
            opened.clear()
            costs.clear()
        }
    }

    fun copyPages(
        first: Int,
        last: Int,
        dest: PdfDocument,
        batchSize: Int,
        progress: PdfProgress,
        openPart: (File) -> PdfReader = { PdfIo.reader(it.absolutePath) },
        budget: Long = PdfBatchBudget.bytes(),
        onCopied: ((first: Int, last: Int, dest: PdfDocument) -> Unit)? = null
    ) {
        val batch = batchSize.coerceAtLeast(1)
        for ((part, localFirst, localLast) in layout.runs(first, last)) {
            var from = localFirst + 1
            val to1 = localLast + 1
            while (from <= to1) {
                val to = PdfDocument(openPart(files[part])).use { src ->
                    val end = PdfPageCost.batchEnd(src, from, to1, batch, budget)
                    src.copyPagesTo(from, end, dest)
                    onCopied?.invoke(layout.start(part) + from - 1, layout.start(part) + end - 1, dest)
                    runCatching { PdfCopySupport.flushCopiedObjects(dest, src) }
                    end
                }
                flushLast(dest, to - from + 1)
                progress.advance(to - from + 1)
                from = to + 1
            }
        }
    }

    fun write(
        plan: SourcePartPlan,
        batchSize: Int,
        progress: PdfProgress,
        inputs: Inputs = Inputs(),
        openPart: (File) -> PdfReader = { PdfIo.reader(it.absolutePath) },
        budget: Long = PdfBatchBudget.bytes(),
        onCopied: ((first: Int, last: Int, dest: PdfDocument) -> Unit)? = null
    ): PdfSourceParts {
        val pageCounts = plan.pageCounts(layout)
        val targets = targetsOf(plan)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.name}")
        val written = ArrayList<Pair<File, File>>()
        try {
            for ((index, part) in plan.parts.withIndex()) {
                if (part !is SourcePartPlan.Part.Write) continue
                val target = targets[index]
                val work = File(dir, "${target.name}$WORK_SUFFIX")
                written += work to target
                writePart(part.pieces, work, batchSize, progress, inputs, openPart, budget, onCopied)
            }
        } catch (e: Throwable) {
            for ((work, _) in written) runCatching { work.delete() }
            throw e
        }
        progress.idle()

        for ((work, target) in written) WorkFiles.replace(work, target)
        val kept = targets.toSet()
        for (file in files) {
            if (file !in kept && file.exists() && !file.delete()) throw IOException("Could not delete ${file.name}")
        }

        return PdfSourceParts(dir, targets, SourcePartLayout(pageCounts))
    }

    private fun targetsOf(plan: SourcePartPlan): List<File> {
        var previous = -1
        var next = (files.maxOfOrNull { numberOf(it) ?: 0 } ?: 0) + 1
        var newPartsStarted = false
        return plan.parts.map { part ->
            val old = when (part) {
                is SourcePartPlan.Part.Keep -> part.part
                is SourcePartPlan.Part.Write -> part.replaces
            }
            if (old == null) {
                newPartsStarted = true
                File(dir, nameOf(next++))
            } else {
                require(!newPartsStarted && old > previous && old < files.size) { "The parts of the plan are out of order" }
                previous = old
                files[old]
            }
        }
    }

    private fun writePart(
        pieces: List<SourcePartPlan.Piece>,
        out: File,
        batchSize: Int,
        progress: PdfProgress,
        inputs: Inputs,
        openPart: (File) -> PdfReader,
        budget: Long,
        onCopied: ((first: Int, last: Int, dest: PdfDocument) -> Unit)?
    ) {
        if (out.exists()) out.delete()
        PdfDocument(PdfWriter(out.absolutePath, PdfIo.writerProperties())).use { dest ->
            dest.setFlushUnusedObjects(true)
            for (piece in pieces) {
                when (piece) {
                    is SourcePartPlan.Piece.Pages -> copyPages(piece.first, piece.last, dest, batchSize, progress, openPart, budget, onCopied)
                    is SourcePartPlan.Piece.Incoming -> copyIncoming(inputs, piece, dest, batchSize, progress, budget)
                    is SourcePartPlan.Piece.Replacement -> {
                        val file = checkNotNull(inputs.replacements[piece.key]) { "No replacement for ${piece.key}" }
                        PdfDocument(PdfIo.reader(file.absolutePath)).use { repl ->
                            repl.copyPagesTo(1, 1, dest)
                            runCatching { PdfCopySupport.flushCopiedObjects(dest, repl) }
                        }
                        flushLast(dest, 1)
                        progress.advance(1)
                    }
                    SourcePartPlan.Piece.EmptyPage -> {
                        val page = dest.addNewPage(checkNotNull(inputs.emptyPage) { "No empty page size" })
                        runCatching { page.flush() }
                        progress.advance(1)
                    }
                }
            }
        }
    }

    private fun copyIncoming(
        inputs: Inputs,
        piece: SourcePartPlan.Piece.Incoming,
        dest: PdfDocument,
        batchSize: Int,
        progress: PdfProgress,
        budget: Long
    ) {
        val input = inputs.incoming[piece.input]
        val copierDir = checkNotNull(inputs.copierDir) { "No work folder for the incoming pages" }
        val batch = batchSize.coerceAtLeast(1)
        var from = piece.first + 1
        val to1 = piece.last + 1
        while (from <= to1) {
            val to = PdfDocument(PdfIo.reader(input.file.absolutePath, input.password)).use { src ->
                val end = PdfPageCost.batchEnd(src, from, to1, batch, budget)
                src.copyPagesTo(
                    from, end, dest,
                    PdfVisibleAppearanceCopier(copierDir, input.onMissingAppearance, inputs.generateAppearances)
                )
                val firstCopied = dest.numberOfPages - (end - from)
                for (number in firstCopied..dest.numberOfPages) {
                    val quarterTurns = runCatching { PdfOwnRotation.take(dest.getPage(number)) }.getOrDefault(0)
                    if (quarterTurns != 0) input.onOwnRotation(from - 1 + number - firstCopied, quarterTurns)
                }
                runCatching { PdfCopySupport.flushCopiedObjects(dest, src) }
                end
            }
            flushLast(dest, to - from + 1)
            progress.advance(to - from + 1)
            from = to + 1
        }
    }

    private fun flushLast(dest: PdfDocument, count: Int) {
        for (i in 0 until count) {
            val p = dest.numberOfPages - i
            if (p >= 1) runCatching { dest.getPage(p).flush() }
        }
    }

    companion object {
        private const val WORK_SUFFIX = ".work"
        private val PART_NAME = Regex("""(\d+)\.pdf""")

        fun exists(dir: File): Boolean = partFiles(dir).isNotEmpty()

        fun open(dir: File): PdfSourceParts {
            val files = partFiles(dir)
            val counts = files.map { file -> PdfDocument(PdfIo.reader(file.absolutePath)).use { it.numberOfPages } }
            return PdfSourceParts(dir, files, SourcePartLayout(counts))
        }

        fun empty(dir: File): PdfSourceParts = PdfSourceParts(dir, emptyList(), SourcePartLayout(emptyList()))

        private fun partFiles(dir: File): List<File> =
            dir.listFiles { file -> file.isFile && numberOf(file) != null }
                .orEmpty()
                .sortedBy { numberOf(it) }

        private fun numberOf(file: File): Int? = PART_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()

        private fun nameOf(number: Int): String = String.format(Locale.ROOT, "%04d.pdf", number)
    }
}
