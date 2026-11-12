package hu.reelee81.reepdf

import hu.reelee81.reepdf.pdf.PdfViewerDocument

object OpenViewerDocuments {
    private val documents = mutableListOf<PdfViewerDocument<*>>()

    fun add(document: PdfViewerDocument<*>) {
        synchronized(documents) {
            documents.removeAll { it.isClosedWithoutError }
            documents += document
        }
    }

    suspend fun awaitClosing() {
        val closing = synchronized(documents) { documents.filter { it.isClosed } }
        for (document in closing) document.awaitClosed()
        synchronized(documents) { documents.removeAll(closing) }
    }
}
