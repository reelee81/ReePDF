package hu.reelee81.reepdf.pdf

interface PdfProgress {
    fun advance(units: Int)
    fun idle()
}
