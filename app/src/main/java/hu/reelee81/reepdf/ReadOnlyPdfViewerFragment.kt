@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package hu.reelee81.reepdf

import android.net.Uri
import android.os.Bundle
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment

class ReadOnlyPdfViewerFragment : PdfViewerFragment() {

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

    fun scrollToPage(pageIndex: Int) {
        pageNavigator.scrollToPage(pageIndex)
    }

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        pdfView.isSaveEnabled = false
        pageNavigator.bind(pdfView)
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        isToolboxVisible = false
        pageNavigator.onDocumentLoaded(document)
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        pageNavigator.onDocumentError()
    }

    override fun onRequestImmersiveMode(enterImmersive: Boolean) {
        isToolboxVisible = false
    }

    override fun onResume() {
        super.onResume()
        isToolboxVisible = false
    }

    override fun onDestroyView() {
        pageNavigator.unbind()
        super.onDestroyView()
    }
}
