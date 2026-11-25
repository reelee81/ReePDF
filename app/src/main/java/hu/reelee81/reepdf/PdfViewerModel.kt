package hu.reelee81.reepdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.reelee81.reepdf.core.PageBatch
import hu.reelee81.reepdf.pdf.PdfMarkSave
import hu.reelee81.reepdf.pdf.ProgressTracker
import hu.reelee81.reepdf.viewer.ViewerEditPlan
import hu.reelee81.reepdf.viewer.ViewerEditSaveResult
import hu.reelee81.reepdf.viewer.ViewerResume
import hu.reelee81.reepdf.viewer.ViewerState
import hu.reelee81.reepdf.viewer.ViewerWait
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
    private var journal: AndroidEditJournal? = null
    private var fonts: AndroidTextFonts? = null

    fun start(
        title: String,
        file: File,
        page: Int,
        outputUri: String?,
        journal: AndroidEditJournal,
        fonts: AndroidTextFonts,
        resume: ViewerResume?
    ) {
        this.file = file
        this.outputUri = outputUri
        this.journal = journal
        this.fonts = fonts
        state = ViewerState(title, true, viewModelScope).also { viewer ->
            viewer.editor.journal = journal
            val loading = viewModelScope.async(Dispatchers.IO) { fonts.preloaded() }
            val reading = resume?.let {
                viewModelScope.async { journal.read(withContext(Dispatchers.IO) { AndroidEditJournal.identity(file) }) }
            }
            viewer.open(ViewerWait.Reason.OPENING, page, resume, reading?.let { edits -> { edits.await() } }) {
                viewer.editor.textFonts = loading.await()
                opened(file, fresh = resume == null)
            }
        }
    }

    suspend fun saveEdits(cacheDir: () -> File, plan: ViewerEditPlan, uriFor: (File) -> String): ViewerEditSaveResult {
        val current = document ?: return ViewerEditSaveResult.Failed(CLOSED_MESSAGE)
        val output = withContext(Dispatchers.IO) { File(cacheDir(), "edited_${System.currentTimeMillis()}.pdf") }
        return try {
            val markPlan = current.markPlan(plan)
            val uri = withContext(Dispatchers.IO) {
                val tracker = ProgressTracker { System.currentTimeMillis() }
                if (PdfMarkSave.write(File(current.path), output, markPlan, PageBatch.PAGES, tracker::stage, fonts) { ensureActive() }) {
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
                ViewerEditSaveResult.Saved { opened(output, fresh = false) }
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
        state?.editor?.journal = null
        journal?.discard()
        state?.close()
    }

    override fun onCleared() {
        state?.editor?.journal = null
        state?.close()
    }

    private suspend fun opened(file: File, fresh: Boolean): AndroidViewerDocument {
        val identity = withContext(Dispatchers.IO) { AndroidEditJournal.identity(file) }
        journal?.let { target ->
            if (fresh) target.discard()
            target.begin(identity)
        }
        return AndroidViewerDocument.open(file.absolutePath).also { document = it }
    }

    private companion object {
        const val CLOSED_MESSAGE = "The viewer document is closed"
    }
}
