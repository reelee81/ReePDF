package hu.reelee81.reepdf.viewer

import kotlin.math.hypot

class ViewerFramedSignature internal constructor(val mark: ViewerMark, internal val saved: Set<ViewerEditSession.SavedRef>) {
    val box: FloatArray = ViewerSignaturePlacement.box(mark.path, mark.width)
}

internal object ViewerSignaturePlacement {
    const val MIN_SIDE = 14.173229f

    fun box(path: ViewerMarkPath, width: Float): FloatArray {
        val points = path.points
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (index in points.indices step 2) {
            box[0] = minOf(box[0], points[index])
            box[1] = minOf(box[1], points[index + 1])
            box[2] = maxOf(box[2], points[index])
            box[3] = maxOf(box[3], points[index + 1])
        }
        val reach = width / 2
        return floatArrayOf(box[0] - reach, box[1] - reach, box[2] + reach, box[3] + reach)
    }

    fun framed(box: FloatArray, margin: Float): FloatArray = floatArrayOf(box[0] - margin, box[1] - margin, box[2] + margin, box[3] + margin)

    fun inside(box: FloatArray, point: ViewerTextPoint, tolerance: Float): Boolean =
        point.x >= box[0] - tolerance && point.x <= box[2] + tolerance && point.y >= box[1] - tolerance && point.y <= box[3] + tolerance

    fun onHandle(box: FloatArray, point: ViewerTextPoint, reach: Float): Boolean = hypot(point.x - box[2], point.y - box[3]) <= reach

    fun place(signature: ViewerSignature, color: Int, page: Int, point: ViewerTextPoint, pageWidth: Float, pageHeight: Float): ViewerMark {
        val bounds = signature.bounds()
        val boundsWidth = bounds[2] - bounds[0]
        val boundsHeight = bounds[3] - bounds[1]
        val fit = minOf(1f, pageWidth / (boundsWidth * ViewerSignature.PAD_WIDTH_ON_PAGE), pageHeight / (boundsHeight * ViewerSignature.PAD_WIDTH_ON_PAGE))
        val scale = ViewerSignature.PAD_WIDTH_ON_PAGE * fit
        val width = boundsWidth * scale
        val height = boundsHeight * scale
        val left = within(point.x - width / 2, 0f, pageWidth - width)
        val top = within(point.y - height / 2, 0f, pageHeight - height)
        val path = signature.path(scale, left - bounds[0] * scale, top - bounds[1] * scale)
        return ViewerMark(page, ViewerMarkKind.SIGNATURE, color, signature.width * fit, path)
    }

    fun moved(mark: ViewerMark, box: FloatArray, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float): ViewerMark {
        val x = within(dx, -box[0], pageWidth - box[2])
        val y = within(dy, -box[1], pageHeight - box[3])
        val points = mark.path.points
        val moved = FloatArray(points.size) { if (it % 2 == 0) points[it] + x else points[it] + y }
        return ViewerMark(mark.page, mark.kind, mark.color, mark.width, ViewerMarkPath(mark.path.verbs, moved))
    }

    fun resized(mark: ViewerMark, box: FloatArray, point: ViewerTextPoint, pageWidth: Float, pageHeight: Float): ViewerMark {
        val left = box[0]
        val top = box[1]
        val width = box[2] - left
        val height = box[3] - top
        if (width <= 0f || height <= 0f) return mark
        val wanted = ((point.x - left) * width + (point.y - top) * height) / (width * width + height * height)
        val factor = within(wanted, MIN_SIDE / maxOf(width, height), minOf((pageWidth - left) / width, (pageHeight - top) / height))
        val points = mark.path.points
        val scaled = FloatArray(points.size) { if (it % 2 == 0) left + (points[it] - left) * factor else top + (points[it] - top) * factor }
        return ViewerMark(mark.page, mark.kind, mark.color, mark.width * factor, ViewerMarkPath(mark.path.verbs, scaled))
    }

    private fun within(value: Float, low: Float, high: Float): Float = if (high < low) low else value.coerceIn(low, high)
}
