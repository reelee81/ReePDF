package hu.reelee81.reepdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.reelee81.reepdf.viewer.ViewerState
import hu.reelee81.reepdf.viewer.ViewerWait
import java.io.File

class PdfViewerModel : ViewModel() {
    var state: ViewerState? = null
        private set
    var file: File? = null
        private set
    var outputUri: String? = null
        private set

    fun start(title: String, canEdit: Boolean, file: File, page: Int, outputUri: String?) {
        this.file = file
        this.outputUri = outputUri
        state = ViewerState(title, canEdit, viewModelScope).also { viewer ->
            viewer.open(ViewerWait.Reason.OPENING, page) { AndroidViewerDocument.open(file.absolutePath) }
        }
    }

    fun openEdited(file: File, outputUri: String, page: Int) {
        this.file = file
        this.outputUri = outputUri
        state?.open(ViewerWait.Reason.OPENING_EDITED, page) { AndroidViewerDocument.open(file.absolutePath) }
    }

    fun close() {
        state?.close()
    }

    override fun onCleared() {
        state?.close()
    }
}
