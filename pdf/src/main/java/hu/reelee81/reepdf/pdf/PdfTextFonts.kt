package hu.reelee81.reepdf.pdf

fun interface PdfTextFonts {
    fun bytes(weight: Int): ByteArray
}
