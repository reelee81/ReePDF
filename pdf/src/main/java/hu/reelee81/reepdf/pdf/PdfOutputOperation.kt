package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import hu.reelee81.reepdf.core.OutputExports
import hu.reelee81.reepdf.core.OutputFileName
import hu.reelee81.reepdf.core.PdfOperationResult
import hu.reelee81.reepdf.core.ProgressNotifyPacing
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
        val batchSize: Int,
        val messages: Messages,
        val budget: Long = PdfBatchBudget.bytes()
    )

    class Result(val file: File) : PdfOperationResult

    @Volatile private var output: File? = null

    suspend fun execute(report: (Int?) -> Unit): Result = withContext(Dispatchers.IO) {
        val tracker = ProgressTracker(progress.clock)
        tracker.reset()
        val job = coroutineContext[Job]

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

            if (!files.nup.exists()) throw IllegalStateException(input.messages.layoutMissing)

            val named = exportNamed { job?.ensureActive() }

            input.password?.let { encryptInPlace(named, it, tracker::stage) }

            Result(named)
        }
    }

    suspend fun discard() {
        val named = output ?: return
        withContext(Dispatchers.IO) {
            runCatching { encryptedWork(named).delete() }
            if (named != files.nup && named.isFile) runCatching { named.delete() }
        }
    }

    private fun exportNamed(ensureActive: () -> Unit): File {
        val safe = OutputFileName.sanitizeBase(input.baseName)
        if ("$safe.pdf" in files.protectedNames) throw IOException(input.messages.nameReserved)

        OutputExports.removeOld(files.workDir, files.protectedNames)

        val out = File(files.workDir, "$safe.pdf")
        output = out
        WorkFiles.copy(files.nup, out, ensureActive)
        return out
    }

    private fun encryptedWork(file: File): File = File(file.parentFile, ENCRYPT_WORK_NAME)

    private fun encryptInPlace(file: File, password: ByteArray, stage: (Long) -> PdfProgress) {
        val tmp = encryptedWork(file)

        val wp = PdfIo.writerProperties().apply {
            setStandardEncryption(
                password,
                password,
                0,
                EncryptionConstants.ENCRYPTION_AES_256
            )
        }

        PdfDocument(
            PdfWriter(tmp.absolutePath, wp)
        ).use { dest ->
            dest.setFlushUnusedObjects(true)

            val batch = input.batchSize
            val total = PdfDocument(PdfIo.reader(file.absolutePath)).use { it.numberOfPages }
            val progress = stage(total.toLong())
            var from = 1
            while (from <= total) {
                val to = PdfDocument(PdfIo.reader(file.absolutePath)).use { src ->
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

        if (!file.delete()) {
            runCatching { tmp.delete() }
            throw IOException(input.messages.replaceFailed)
        }
        if (!tmp.renameTo(file)) {
            throw IOException(input.messages.renameFailed)
        }
    }

    private companion object {
        const val ENCRYPT_WORK_NAME = "output_encrypt.work"
    }
}
