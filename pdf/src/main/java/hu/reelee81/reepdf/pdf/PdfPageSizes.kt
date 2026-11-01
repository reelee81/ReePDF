package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.PageSizes
import kotlin.math.max
import kotlin.math.min

object PdfPageSizes {
    fun isNeeded(config: NupConfig): Boolean {
        val rows = max(1, min(100, config.rows))
        val cols = max(1, min(100, config.cols))
        return rows == 1 && cols == 1
    }

    fun fallback(config: NupConfig): Pair<Float, Float> {
        val rows = max(1, min(100, config.rows))
        val cols = max(1, min(100, config.cols))
        val isLandscape = config.isLandscape
        val basePs = if (isLandscape) PageSize.A4.rotate() else PageSize.A4
        val isSingleUp = (rows * cols) == 1

        val psForNupPageFallback = if (isSingleUp) {
            if (!isLandscape) PageSize.A4 else basePs
        } else {
            basePs
        }
        return psForNupPageFallback.width to psForNupPageFallback.height
    }

    fun forThumbnails(
        source: PdfSourceParts,
        totalPages: Int,
        config: NupConfig,
        batchSize: Int,
        progressStage: (Long) -> PdfProgress
    ): PageSizes? {
        if (!isNeeded(config)) return null
        val (fallbackW, fallbackH) = fallback(config)
        return read(source, totalPages, batchSize, fallbackW, fallbackH, progressStage(totalPages.toLong()))
    }

    fun read(
        source: PdfSourceParts,
        totalPages: Int,
        batchSize: Int,
        fallbackW: Float,
        fallbackH: Float,
        progress: PdfProgress
    ): PageSizes {
        val widths  = FloatArray(totalPages) { fallbackW }
        val heights = FloatArray(totalPages) { fallbackH }

        var startPage = 0
        while (startPage < totalPages) {
            val endExclusive = min(totalPages, startPage + batchSize)
            val lastPageToRead = min(endExclusive, source.total)

            for ((part, first, last) in source.layout.runs(startPage, lastPageToRead - 1)) {
                val partStart = source.layout.start(part)
                val pdfDoc = PdfDocument(PdfIo.reader(source.file(part).absolutePath))
                try {
                    for (local in first..last) {
                        val pageZeroBased = partStart + local
                        runCatching {
                            val page = pdfDoc.getPage(local + 1)

                            val (w, h) = PdfOwnRotation.shownSize(page)

                            widths[pageZeroBased] = w
                            heights[pageZeroBased] = h
                        }.onFailure {
                        }
                    }
                } finally {
                    runCatching { pdfDoc.close() }
                }
            }

            progress.advance(endExclusive - startPage)
            startPage = endExclusive
        }
        progress.idle()

        return PageSizes(widths, heights)
    }
}
