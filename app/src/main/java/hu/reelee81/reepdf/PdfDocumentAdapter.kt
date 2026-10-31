package hu.reelee81.reepdf

import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class PdfDocumentAdapter(
    private val filePath: String,
    private val fileName: String
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttrs: PrintAttributes?,
        newAttrs: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(fileName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        val changed = newAttrs != oldAttrs
        callback?.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val outcome = write(destination, cancellationSignal, callback)
            main.post(outcome)
        }, "ReePDF-print").start()
    }

    private fun write(
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ): () -> Unit {
        var outcome: () -> Unit = {}
        try {
            FileInputStream(filePath).use { inputStream ->
                destination?.let { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { readCount ->
                                bytesRead = readCount
                            } > 0 && cancellationSignal?.isCanceled != true) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        outcome = if (cancellationSignal?.isCanceled == true) {
                            { callback?.onWriteCancelled() }
                        } else {
                            { callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            outcome = { callback?.onWriteFailed(e.message) }
            Log.e("ReePDF", "Print write failed: ${e.message}", e)
        }
        return outcome
    }
}
