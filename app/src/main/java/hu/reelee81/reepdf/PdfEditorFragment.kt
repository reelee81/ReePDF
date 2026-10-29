@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package hu.reelee81.reepdf

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresExtension
import androidx.pdf.PdfDocument
import androidx.pdf.PdfWriteHandle
import androidx.pdf.ink.EditablePdfViewerFragment
import androidx.pdf.view.PdfView

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 18)
class PdfEditorFragment : EditablePdfViewerFragment() {

    private val pageNavigator = PdfPageNavigator {
        (activity as? PdfActivity)?.onPdfOperationStateChanged()
    }

    val currentPageIndex: Int
        get() = pageNavigator.currentPageIndex

    val isPageNavigationInProgress: Boolean
        get() = pageNavigator.isInProgress

    val isDocumentLoading: Boolean
        get() = pageNavigator.isLoading

    val isAwaitingDocument: Boolean
        get() = pageNavigator.isAwaitingDocument

    val visiblePageIndices: List<Int>
        get() = pageNavigator.visiblePageIndices

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageNavigator.restore(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pageNavigator.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        pageNavigator.onViewStateRestored()
    }

    fun loadDocumentAtPage(uri: Uri, pageIndex: Int, refresh: Boolean = false) {
        if (refresh) pageNavigator.treatAsRefresh()
        if (documentUri != uri) pageNavigator.beginDocumentLoad()
        pageNavigator.scrollToPage(pageIndex)
        documentUri = uri
    }

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        pdfView.isSaveEnabled = false
        pageNavigator.bind(pdfView)
    }

    override fun onResume() {
        super.onResume()
        hideToolboxUntilEditing()
    }

    override fun onPause() {
        super.onPause()
        if (activity?.isFinishing == true && isEditModeEnabled) {
            isEditModeEnabled = false
            hideToolboxUntilEditing()
        }
    }

    private fun hideToolboxUntilEditing() {
        if (!isEditModeEnabled) isToolboxVisible = false
    }

    override fun onRequestImmersiveMode(enterImmersive: Boolean) {
        if (isEditModeEnabled) {
            super.onRequestImmersiveMode(enterImmersive)
        } else {
            isToolboxVisible = false
        }
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        hideToolboxUntilEditing()
        pageNavigator.onDocumentLoaded(document)
        (activity as? PdfEditorActivity)?.onEditorDocumentLoaded()
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        (activity as? PdfEditorActivity)?.onEditorDocumentLoadFailed()
        pageNavigator.onDocumentError()
    }

    override fun onApplyEditsSuccess(handle: PdfWriteHandle) {
        (activity as? PdfEditorActivity)?.onApplyEditsSuccess(handle)
    }

    override fun onApplyEditsFailed(error: Throwable) {
        (activity as? PdfEditorActivity)?.onApplyEditsFailed(error)
    }

    override fun onDestroyView() {
        pageNavigator.unbind()
        super.onDestroyView()
    }
}
