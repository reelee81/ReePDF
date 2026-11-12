package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import hu.reelee81.reepdf.core.OutputExports
import hu.reelee81.reepdf.core.OutputFileName
import hu.reelee81.reepdf.core.PdfOperationResult
import hu.reelee81.reepdf.core.ProgressTicker
import hu.reelee81.reepdf.core.WorkFiles
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PdfOutputOperation(
    private val files: Files,
    private val saveSelection: () -> Unit,
    private val input: Input,
    private val progress: PdfLayoutChangeOperation.Progress
) {
    class Files(val workDir: File, val nup: File, val protectedNames: Set<String>)

    class Messages(val layoutMissing: String, val nameReserved: String, val replaceFailed: String, val renameFailed: String)

    class Input(
        val baseName: String,
        val password: ByteArray?,
        val packed: Boolean,
        val batchSize: Int,
        val messages: Messages,
        val budget: Long = PdfBatchBudget.bytes(),
        val direct: Boolean = false
    )

    class Result(val file: File, val name: String = file.name) : PdfOperationResult

    @Volatile private var output: File? = null

    suspend fun execute(report: (Int?) -> Unit): Result = withContext(Dispatchers.IO) {
        val tracker = ProgressTracker(progress.clock)
        tracker.reset()
        val job = coroutineContext[Job]

        ProgressTicker.run(
            tick = progress.tickMs.milliseconds,
            percent = { progress.percentOf(tracker) },
            report = report,
            onCancel = tracker::cancel
        ) {
            saveSelection()

            if (!files.nup.exists()) throw IllegalStateException(input.messages.layoutMissing)

            if (input.direct) return@run Result(files.nup, namedTarget().name)

            val named = if (input.packed || input.password != null) {
                packNamed(input.password, tracker::stage) { job?.ensureActive() }
            } else {
                copyNamed { job?.ensureActive() }
            }

            Result(named)
        }
    }

    suspend fun discard() {
        val named = output ?: return
        withContext(Dispatchers.IO) {
            runCatching { packWork().delete() }
            if (named != files.nup && named.isFile) runCatching { named.delete() }
        }
    }

    private fun namedTarget(): File {
        val safe = OutputFileName.sanitizeBase(input.baseName)
        if ("$safe.pdf" in files.protectedNames) throw IOException(input.messages.nameReserved)

        OutputExports.removeOld(files.workDir, files.protectedNames)

        val out = File(files.workDir, "$safe.pdf")
        output = out
        return out
    }

    private fun copyNamed(ensureActive: () -> Unit): File {
        val out = namedTarget()
        WorkFiles.copy(files.nup, out, ensureActive)
        return out
    }

    private fun packWork(): File = File(files.workDir, PACK_WORK_NAME)

    private fun packNamed(password: ByteArray?, stage: (Long) -> PdfProgress, ensureActive: () -> Unit): File {
        val out = namedTarget()
        val work = packWork()
        if (work.exists()) work.delete()

        val wp = PdfIo.writerProperties().apply {
            if (password != null) {
                setStandardEncryption(
                    password,
                    password,
                    0,
                    EncryptionConstants.ENCRYPTION_AES_256
                )
            }
        }

        try {
            PdfDocument(
                PdfWriter(work.absolutePath, wp)
            ).use { dest ->
                dest.setFlushUnusedObjects(true)

                val batch = input.batchSize
                val total = PdfDocument(PdfIo.reader(files.nup.absolutePath)).use { it.numberOfPages }
                val progress = stage(total.toLong())
                var from = 1
                while (from <= total) {
                    ensureActive()
                    val to = PdfDocument(PdfIo.reader(files.nup.absolutePath)).use { src ->
                        val end = PdfPageCost.batchEnd(src, from, total, batch, input.budget)
                        src.copyPagesTo(from, end, dest)
                        runCatching { PdfCopySupport.flushCopiedObjects(dest, src) }
                        end
                    }

                    val added = to - from + 1
                    for (i in 0 until added) {
                        val p = dest.numberOfPages - i
                        if (p >= 1) runCatching { dest.getPage(p).flush() }
                    }
                    progress.advance(added)
                    from = to + 1
                }
                progress.idle()
            }
        } catch (e: Exception) {
            runCatching { work.delete() }
            throw e
        }

        if (out.exists() && !(out.isFile && out.delete())) {
            runCatching { work.delete() }
            throw IOException(input.messages.replaceFailed)
        }
        if (!work.renameTo(out)) {
            runCatching { work.delete() }
            throw IOException(input.messages.renameFailed)
        }
        return out
    }

    private companion object {
        const val PACK_WORK_NAME = "output_pack.work"
    }
}
