package hu.reelee81.reepdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.reelee81.reepdf.core.PageBatch
import hu.reelee81.reepdf.pdf.PdfMarkSave
import hu.reelee81.reepdf.pdf.ProgressTracker
import hu.reelee81.reepdf.viewer.ViewerEditPlan
import hu.reelee81.reepdf.viewer.ViewerEditSaveResult
import hu.reelee81.reepdf.viewer.ViewerState
import hu.reelee81.reepdf.viewer.ViewerWait
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PdfViewerModel : ViewModel() {
    var state: ViewerState? = null
        private set
    var file: File? = null
        private set
    var outputUri: String? = null
        private set

    @Volatile
    private var document: AndroidViewerDocument? = null

    fun start(title: String, file: File, page: Int, outputUri: String?) {
        this.file = file
        this.outputUri = outputUri
        state = ViewerState(title, true, viewModelScope).also { viewer ->
            viewer.open(ViewerWait.Reason.OPENING, page) { opened(file) }
        }
    }

    suspend fun saveEdits(cacheDir: () -> File, plan: ViewerEditPlan, uriFor: (File) -> String): ViewerEditSaveResult {
        val current = document ?: return ViewerEditSaveResult.Failed(CLOSED_MESSAGE)
        val output = withContext(Dispatchers.IO) { File(cacheDir(), "edited_${System.currentTimeMillis()}.pdf") }
        return try {
            val markPlan = current.markPlan(plan)
            val uri = withContext(Dispatchers.IO) {
                val tracker = ProgressTracker { System.currentTimeMillis() }
                if (PdfMarkSave.write(File(current.path), output, markPlan, PageBatch.PAGES, tracker::stage) { ensureActive() }) {
                    uriFor(output)
                } else {
                    output.delete()
                    null
                }
            }
            if (uri == null) {
                ViewerEditSaveResult.Unchanged
            } else {
                file = output
                outputUri = uri
                ViewerEditSaveResult.Saved { opened(output) }
            }
        } catch (e: CancellationException) {
            discard(output)
            throw e
        } catch (e: Exception) {
            discard(output)
            ViewerEditSaveResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private suspend fun discard(output: File) {
        withContext(NonCancellable + Dispatchers.IO) { output.delete() }
    }

    fun close() {
        state?.close()
    }

    override fun onCleared() {
        state?.close()
    }

    private suspend fun opened(file: File): AndroidViewerDocument =
        AndroidViewerDocument.open(file.absolutePath).also { document = it }

    private companion object {
        const val CLOSED_MESSAGE = "The viewer document is closed"
    }
}
