package hu.reelee81.reepdf.core

import hu.reelee81.reepdf.core.NupGeometry.A4_HEIGHT_PT
import hu.reelee81.reepdf.core.NupGeometry.A4_WIDTH_PT
import hu.reelee81.reepdf.core.NupGeometry.horizontalPositionFactor
import hu.reelee81.reepdf.core.NupGeometry.validPosition
import hu.reelee81.reepdf.core.NupGeometry.verticalPositionFactor
import kotlin.math.min

data class NupBox(val x: Float, val y: Float, val width: Float, val height: Float)

class NupCell(val x: Float, val y: Float, val width: Float, val height: Float, val matrix: DoubleArray)

object NupCellLayout {
    fun place(
        config: NupConfig,
        pageWidth: Float,
        pageHeight: Float,
        slot: Int,
        pageBox: NupBox,
        cropBox: NupBox,
        contentBox: NupBox?,
        quarterTurns: Int,
        position: Int
    ): NupCell {
        val rows = config.rows
        val cols = config.cols
        val land = config.isLandscape
        val scaleIndRequested = config.scaleIndividually
        val fitIndRequested = config.fitIndividually

        val marginPt = config.marginMm * 72f / 25.4f
        val per      = (rows * cols).coerceAtLeast(1)
        val isPortraitMode = !land
        val isSingleUp     = (per == 1)

        val measured = if (scaleIndRequested && fitIndRequested) contentBox else null
        val contentWidth  = measured?.width ?: cropBox.width
        val contentHeight = measured?.height ?: cropBox.height
        val contentOriginX = measured?.x ?: cropBox.x
        val contentOriginY = measured?.y ?: cropBox.y
        val hasBoundingBox = measured != null

        val isSideways = quarterTurns == 1 || quarterTurns == 3

        val scaleBox = if (scaleIndRequested) cropBox else pageBox
        val srcPageW = if (isSideways) scaleBox.height else scaleBox.width
        val srcPageH = if (isSideways) scaleBox.width else scaleBox.height

        val cellW = (pageWidth  - marginPt * (cols + 1)) / cols
        val cellH = (pageHeight - marginPt * (rows + 1)) / rows

        val scale: Float
        if (scaleIndRequested) {
            val fitContentW = if (isSideways) contentHeight else contentWidth
            val fitContentH = if (isSideways) contentWidth else contentHeight
            scale = min(cellW / fitContentW, cellH / fitContentH)
        } else {
            val srcIsPortrait = srcPageH >= srcPageW
            val refW = if (srcIsPortrait) A4_WIDTH_PT else A4_HEIGHT_PT
            val refH = if (srcIsPortrait) A4_HEIGHT_PT else A4_WIDTH_PT
            scale = min(min(cellW / refW, cellH / refH), 1f)
        }

        val col     = slot % cols
        val row     = (slot / cols) % rows
        val cellX   = marginPt + col * (cellW + marginPt)
        val cellY   = marginPt + (rows - 1 - row) * (cellH + marginPt)

        val finalScale: Float
        if (isPortraitMode && isSingleUp) {
            val fit =
                if (scaleIndRequested && fitIndRequested) {
                    val fitContentW = if (isSideways) contentHeight else contentWidth
                    val fitContentH = if (isSideways) contentWidth else contentHeight
                    min(cellW / fitContentW, cellH / fitContentH)
                } else {
                    min(cellW / srcPageW, cellH / srcPageH)
                }
            val s = if (scaleIndRequested) fit else min(fit, 1f)
            finalScale = if (!scaleIndRequested) {
                if (s < 1f) s else scale
            } else {
                s
            }
        } else {
            val fitToCell = min(cellW / srcPageW, cellH / srcPageH)
            finalScale =
                if (!scaleIndRequested) {
                    val fitNoUpscale = min(fitToCell, 1f)
                    if (isSingleUp) {
                        if (fitNoUpscale < 1f) fitNoUpscale else scale
                    } else {
                        val srcIsPortrait = srcPageH >= srcPageW
                        val orientationMismatch =
                            (land && srcIsPortrait) || (!land && !srcIsPortrait)

                        if (!orientationMismatch) {
                            min(scale, fitNoUpscale)
                        } else {
                            val fitW = cellW / srcPageW
                            val fitH = cellH / srcPageH
                            val limitIsWidth = fitW <= fitH
                            val refW = if (srcIsPortrait) A4_WIDTH_PT else A4_HEIGHT_PT
                            val refH = if (srcIsPortrait) A4_HEIGHT_PT else A4_WIDTH_PT
                            val srcRel = if (limitIsWidth) (srcPageW / refW) else (srcPageH / refH)
                            val threshold = 0.5f
                            val preferFit = srcRel >= threshold
                            val candidate = if (preferFit) fitNoUpscale else scale
                            min(candidate, fitNoUpscale)
                        }
                    }
                } else {
                    scale
                }
        }

        val useBoundingBox = scaleIndRequested && fitIndRequested && hasBoundingBox

        val scaledW: Float
        val scaledH: Float
        val originX: Float
        val originY: Float

        if (useBoundingBox) {
            scaledW = contentWidth * finalScale
            scaledH = contentHeight * finalScale
            originX = contentOriginX
            originY = contentOriginY
        } else {
            scaledW = cropBox.width * finalScale
            scaledH = cropBox.height * finalScale
            originX = cropBox.x
            originY = cropBox.y
        }

        val rotatedScaledW = if (isSideways) scaledH else scaledW
        val rotatedScaledH = if (isSideways) scaledW else scaledH

        val cellPosition = validPosition(position)
        val offsetX = cellX + (cellW - rotatedScaledW) * horizontalPositionFactor(cellPosition)
        val offsetY = cellY + (cellH - rotatedScaledH) * verticalPositionFactor(cellPosition)

        val a: Double
        val b: Double
        val c: Double
        val d: Double
        val e: Double
        val f: Double

        when (quarterTurns) {
            1 -> {
                a = 0.0
                b = finalScale.toDouble()
                c = -finalScale.toDouble()
                d = 0.0
                e = offsetX.toDouble() + scaledH + originY * finalScale
                f = offsetY.toDouble() - originX * finalScale
            }
            2 -> {
                a = -finalScale.toDouble()
                b = 0.0
                c = 0.0
                d = -finalScale.toDouble()
                e = offsetX.toDouble() + scaledW + originX * finalScale
                f = offsetY.toDouble() + scaledH + originY * finalScale
            }
            3 -> {
                a = 0.0
                b = -finalScale.toDouble()
                c = finalScale.toDouble()
                d = 0.0
                e = offsetX.toDouble() - originY * finalScale
                f = offsetY.toDouble() + scaledW + originX * finalScale
            }
            else -> {
                a = finalScale.toDouble()
                b = 0.0
                c = 0.0
                d = finalScale.toDouble()
                e = offsetX.toDouble() - originX * finalScale
                f = offsetY.toDouble() - originY * finalScale
            }
        }

        return NupCell(cellX, cellY, cellW, cellH, doubleArrayOf(a, b, c, d, e, f))
    }
}
