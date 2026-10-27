package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import hu.reelee81.reepdf.core.NupBox
import hu.reelee81.reepdf.core.NupCellLayout
import hu.reelee81.reepdf.core.NupConfig
import hu.reelee81.reepdf.core.NupGeometry.DEFAULT_POSITION
import hu.reelee81.reepdf.core.PageBatch
import hu.reelee81.reepdf.core.PageSizes
import hu.reelee81.reepdf.core.PdfCropRect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object PdfNupBuilder {
    @Throws(Exception::class)
    fun build(
        source: PdfSourceParts,
        totalSrcPages: Int,
        outFile: File,
        workDir: File,
        config: NupConfig,
        rotation: IntArray,
        position: IntArray,
        crop: Map<Int, PdfCropRect>,
        batchSize: Int,
        progressStage: (Long) -> PdfProgress,
        budget: Long = PdfBatchBudget.bytes(),
        warn: (message: String, error: Throwable) -> Unit = { _, _ -> }
    ): PageSizes? {
        val writerProps = PdfIo.writerProperties()
        val fitBounds = HashMap<Int, PdfMuPdfFitBounds?>()
        val pageSizes = if (PdfPageSizes.isNeeded(config)) {
            val (fallbackW, fallbackH) = PdfPageSizes.fallback(config)
            PageSizes(FloatArray(totalSrcPages) { fallbackW }, FloatArray(totalSrcPages) { fallbackH })
        } else null

        try {

            val editGeneration = PdfEditProjection.newGeneration()
            val rows = config.rows
            val cols = config.cols
            val land = config.isLandscape
            val drawFrame = config.drawFrame
            val scaleIndRequested = config.scaleIndividually
            val fitIndRequested = config.fitIndividually
            val basePs = if (land) PageSize.A4.rotate()
            else PageSize.A4

            val per      = (rows * cols).coerceAtLeast(1)

            val totalOutPages = if (totalSrcPages == 0) 0 else (totalSrcPages + per - 1) / per
            if (totalOutPages == 0) {
                FileOutputStream(outFile).use {

                }
                return pageSizes
            }
            val sizeUnits = if (per == 1 && pageSizes != null) 1 else 0
            val progress = progressStage(
                totalSrcPages.toLong() + totalOutPages + if (per == 1) totalSrcPages else 0
            )

            val partFiles = mutableListOf<File>()

            var outPageStart = 0
            var chunkIndex = 0
            while (outPageStart < totalOutPages) {

                val srcStart = outPageStart * per

                val partFile = File((outFile.parentFile ?: workDir), outFile.name + ".p" + chunkIndex.toString())

                fun fitBoundsOf(page: Int): PdfMuPdfFitBounds? = fitBounds.getOrPut(source.layout.partOf(page)) {
                    runCatching { PdfMuPdfFitBounds(source.locate(page).file.absolutePath) }
                        .onFailure { warn("MuPDF open failed", it) }
                        .getOrNull()
                }

                val outPageEndInclusive = source.batch { src ->
                    val outPageEnd = PageBatch.end(outPageStart, totalOutPages - 1, batchSize, budget) { out ->
                        (out * per until min(totalSrcPages, (out + 1) * per)).sumOf { src.cost(it) }
                    }
                    val srcEnd = min(totalSrcPages - 1, (outPageEnd + 1) * per - 1)

                    PdfDocument(
                        PdfWriter(partFile.absolutePath, writerProps)
                    ).use { pdf ->
                        pdf.setFlushUnusedObjects(true)

                        var idxLocal = 0
                        var globalIdx = srcStart
                        val editCells = ArrayList<PdfEditProjection.Cell>()
                        while (globalIdx <= srcEnd) {
                            val page1Based = globalIdx + 1
                            val sp = src.page(globalIdx)
                            if (pageSizes != null) {
                                runCatching {
                                    val box = sp.cropBox ?: sp.mediaBox
                                    pageSizes.widths[globalIdx] = box.width
                                    pageSizes.heights[globalIdx] = box.height
                                }
                            }
                            val pageBox = PdfCopySupport.visibleBox(sp)
                            val sz = crop[globalIdx]?.applyTo(pageBox) ?: pageBox

                            val contentBox =
                                if (scaleIndRequested && fitIndRequested) {
                                    runCatching { fitBoundsOf(globalIdx)?.contentBox(source.layout.local(globalIdx), sz) }
                                        .onFailure { warn("Fit measurement failed on page $page1Based", it) }
                                        .getOrNull()
                                } else {
                                    null
                                }

                            if (idxLocal % per == 0) {
                                pdf.addNewPage(basePs)
                            }

                            val currentPage = pdf.lastPage
                            val canvas      = PdfCanvas(currentPage)

                            val psForThisPage = currentPage.pageSize
                            val cell = NupCellLayout.place(
                                config = config,
                                pageWidth = psForThisPage.width,
                                pageHeight = psForThisPage.height,
                                slot = idxLocal,
                                pageBox = pageBox.toNupBox(),
                                cropBox = sz.toNupBox(),
                                contentBox = contentBox?.toNupBox(),
                                quarterTurns = rotation.getOrElse(globalIdx) { 0 },
                                position = position.getOrElse(globalIdx) { DEFAULT_POSITION }
                            )

                            val fx  = runCatching { PdfCopySupport.flattenedFormXObject(sp, pdf, sz) }.getOrNull()
                                ?: sp.copyAsFormXObject(pdf)
                            fx.bBox = PdfArray(sz)
                            runCatching { PdfEditProjection.tagLayerSource(fx, sp, globalIdx, editGeneration) }
                            canvas.saveState()

                            val m = cell.matrix
                            PdfCopySupport.concatPlacementMatrix(canvas, m[0], m[1], m[2], m[3], m[4], m[5])
                            editCells += PdfEditProjection.Cell(globalIdx, m)

                            canvas.addXObjectWithTransformationMatrix(fx, 1f, 0f, 0f, 1f, 0f, 0f)
                            runCatching { fx.flush() }
                            canvas.restoreState()

                            if (drawFrame) {
                                canvas.setStrokeColor(ColorConstants.BLACK)
                                    .setLineWidth(1f)
                                    .rectangle(cell.x.toDouble(), cell.y.toDouble(), cell.width.toDouble(), cell.height.toDouble())
                                    .stroke()
                            }

                            if (idxLocal % per == per - 1 || globalIdx == srcEnd) {
                                runCatching { PdfEditProjection.markNupPage(currentPage, editCells, editGeneration) }
                                editCells.clear()
                                runCatching { currentPage.flush() }
                            }

                            idxLocal++
                            globalIdx++
                            progress.advance(1 + sizeUnits)
                        }
                    }
                    outPageEnd
                }

                for (fit in fitBounds.values) fit?.close()
                fitBounds.clear()

                partFiles += partFile
                outPageStart = outPageEndInclusive + 1
                chunkIndex++
            }

            PdfDocument(
                PdfWriter(outFile.absolutePath, writerProps)
            ).use { destDoc ->
                destDoc.setFlushUnusedObjects(true)

                for (pf in partFiles) {
                    val total = PdfDocument(PdfIo.reader(pf.absolutePath)).use { it.numberOfPages }

                    var from = 1
                    while (from <= total) {
                        val to = PdfDocument(PdfIo.reader(pf.absolutePath)).use { partDoc ->
                            val end = PdfPageCost.batchEnd(partDoc, from, total, batchSize, budget)
                            partDoc.copyPagesTo(from, end, destDoc)
                            runCatching { PdfCopySupport.flushCopiedObjects(destDoc, partDoc) }
                            end
                        }

                        val count = to - from + 1
                        for (i in 0 until count) {
                            val p = destDoc.numberOfPages - i
                            if (p >= 1) runCatching { destDoc.getPage(p).flush() }
                        }
                        progress.advance(count)

                        from = to + 1
                    }
                }
                progress.idle()
            }

            partFiles.forEach { runCatching { it.delete() } }
            return pageSizes

        } finally {
            for (fit in fitBounds.values) runCatching { fit?.close() }
        }
    }

    private fun Rectangle.toNupBox(): NupBox = NupBox(x, y, width, height)
}
