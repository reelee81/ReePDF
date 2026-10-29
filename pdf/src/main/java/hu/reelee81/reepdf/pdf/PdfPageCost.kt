package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfIndirectReference
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfStream
import hu.reelee81.reepdf.core.PageBatch

class PdfPageCost(private val document: PdfDocument, private val objectBytes: Long = OBJECT_BYTES) {
    private val seen = HashSet<PdfIndirectReference>()

    fun of(page: Int): Long {
        val pageDict = document.getPage(page).pdfObject
        val pending = ArrayDeque<PdfObject>()
        addChildren(pageDict, pending)
        if (!pageDict.containsKey(PdfName.Resources)) inheritedResources(pageDict)?.let { pending += it }
        var bytes = 0L
        var objects = 0L
        while (pending.isNotEmpty()) {
            when (val obj = resolve(pending.removeLast()) ?: continue) {
                is PdfStream -> {
                    objects++
                    bytes += obj.getAsNumber(PdfName.Length)?.longValue()?.coerceAtLeast(0) ?: 0
                    addChildren(obj, pending)
                }
                is PdfDictionary -> if (!isPageNode(obj)) {
                    objects++
                    addChildren(obj, pending)
                }
                is PdfArray -> {
                    objects++
                    for (i in 0 until obj.size()) pending += obj.get(i, false)
                }
                else -> objects++
            }
        }
        return bytes + objects * objectBytes
    }

    private fun resolve(value: PdfObject): PdfObject? {
        val ref = value as? PdfIndirectReference ?: value.indirectReference
        if (ref != null && !seen.add(ref)) return null
        return if (value is PdfIndirectReference) value.refersTo else value
    }

    private fun addChildren(dict: PdfDictionary, pending: ArrayDeque<PdfObject>) {
        for (key in dict.keySet()) {
            if (key == PdfName.Parent) continue
            dict.get(key, false)?.let { pending += it }
        }
    }

    private fun inheritedResources(page: PdfDictionary): PdfObject? {
        val visited = HashSet<PdfDictionary>()
        var node = page.getAsDictionary(PdfName.Parent)
        while (node != null && visited.add(node)) {
            node.get(PdfName.Resources, false)?.let { return it }
            node = node.getAsDictionary(PdfName.Parent)
        }
        return null
    }

    private fun isPageNode(dict: PdfDictionary): Boolean {
        val type = dict.getAsName(PdfName.Type)
        return type == PdfName.Page || type == PdfName.Pages
    }

    companion object {
        const val OBJECT_BYTES = 512L

        fun batchEnd(
            document: PdfDocument,
            from: Int,
            last: Int,
            batchSize: Int,
            budget: Long = PdfBatchBudget.bytes(),
            objectBytes: Long = OBJECT_BYTES
        ): Int {
            val cost = PdfPageCost(document, objectBytes)
            return PageBatch.end(from, last, batchSize, budget, cost::of)
        }
    }
}
