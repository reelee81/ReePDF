package hu.reelee81.reepdf.pdf

object PdfBatchBudget {
    fun bytes(): Long = Runtime.getRuntime().maxMemory() / 8
}
