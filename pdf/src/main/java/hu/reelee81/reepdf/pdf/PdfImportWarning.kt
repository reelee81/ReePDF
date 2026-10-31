package hu.reelee81.reepdf.pdf

class PdfImportWarning(
    val name: String,
    val skippedPages: Int,
    val missingContent: Boolean,
    val earlierRevision: Boolean
)
