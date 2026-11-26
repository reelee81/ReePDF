package hu.reelee81.reepdf.viewer

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ViewerFormSymbol(val kind: ViewerMarkKind) {
    X(ViewerMarkKind.BOX_X),
    CHECK(ViewerMarkKind.BOX_CHECK),
    DASH(ViewerMarkKind.BOX_DASH),
    DOT(ViewerMarkKind.BOX_DOT);

    companion object {
        fun of(kind: ViewerMarkKind): ViewerFormSymbol? = entries.firstOrNull { it.kind == kind }
    }
}

internal class ViewerFormPlacement(val a: Float, val b: Float, val left: Float, val top: Float) {
    val side: Float
        get() = hypot(a, b)

    fun x(u: Float, v: Float): Float = a * u - b * v + left

    fun y(u: Float, v: Float): Float = b * u + a * v + top

    fun square(): FloatArray {
        val xs = floatArrayOf(x(0f, 0f), x(1f, 0f), x(0f, 1f), x(1f, 1f))
        val ys = floatArrayOf(y(0f, 0f), y(1f, 0f), y(0f, 1f), y(1f, 1f))
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

object ViewerFormMarks {
    const val DEFAULT_SIZE = 12f
    const val MIN_SIZE = 4f
    const val REFERENCE_SIZE = 12f
    private const val UNIT = 7200f
    private const val KAPPA = 0.5522848f
    private const val SAME_WIDTH = 0.02f

    private val shapes: Map<ViewerFormSymbol, ViewerMarkPath> = mapOf(
        ViewerFormSymbol.X to shape(
            byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC, ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC),
            1775f, 1066f, 2946f, 2816f, 4142f, 4551f, 5380f, 6255f,
            6100f, 1065f, 4164f, 2362f, 2643f, 4321f, 1632f, 6399f
        ),
        ViewerFormSymbol.CHECK to shape(
            byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC, ViewerMarkPath.CUBIC),
            1037f, 3759f, 1912f, 4142f, 2626f, 4825f, 2907f, 5762f,
            3501f, 3878f, 4599f, 2043f, 6169f, 801f
        ),
        ViewerFormSymbol.DASH to shape(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), 800f, 3600f, 6400f, 3600f),
        ViewerFormSymbol.DOT to dot()
    )

    internal fun shape(symbol: ViewerFormSymbol): ViewerMarkPath = shapes.getValue(symbol)

    fun place(
        symbol: ViewerFormSymbol,
        color: Int,
        width: Float,
        size: Float,
        page: Int,
        point: ViewerTextPoint,
        pageWidth: Float,
        pageHeight: Float
    ): ViewerMark {
        val side = minOf(size, pageWidth, pageHeight)
        val left = within(point.x - side / 2, 0f, pageWidth - side)
        val top = within(point.y - side / 2, 0f, pageHeight - side)
        return mark(page, symbol, color, width, ViewerFormPlacement(side, 0f, left, top))
    }

    internal fun restyled(mark: ViewerMark, symbol: ViewerFormSymbol, color: Int, width: Float): ViewerMark {
        if (symbol.kind != mark.kind) return mark(mark.page, symbol, color, width, placement(mark))
        val stroke = if (symbol.kind.fills || abs(width - width(mark)) <= width * SAME_WIDTH) {
            mark.width
        } else {
            width * placement(mark).side / REFERENCE_SIZE
        }
        return ViewerMark(mark.page, mark.kind, color, stroke, mark.path)
    }

    internal fun width(mark: ViewerMark): Float {
        val side = placement(mark).side
        return if (side > 0f) mark.width * REFERENCE_SIZE / side else 0f
    }

    internal fun size(mark: ViewerMark): Float = placement(mark).side

    internal fun box(mark: ViewerMark): FloatArray {
        val square = placement(mark).square()
        val ink = ViewerSignaturePlacement.box(mark.path, mark.width)
        return floatArrayOf(min(square[0], ink[0]), min(square[1], ink[1]), max(square[2], ink[2]), max(square[3], ink[3]))
    }

    internal fun resized(mark: ViewerMark, box: FloatArray, corner: ViewerTextPoint, pageWidth: Float, pageHeight: Float): ViewerMark {
        val left = box[0]
        val top = box[1]
        val width = box[2] - left
        val height = box[3] - top
        val side = placement(mark).side
        if (width <= 0f || height <= 0f || side <= 0f) return mark
        val wanted = ((corner.x - left) * width + (corner.y - top) * height) / (width * width + height * height)
        val factor = within(wanted, MIN_SIZE / side, minOf((pageWidth - left) / width, (pageHeight - top) / height))
        return ViewerSignaturePlacement.scaled(mark, left, top, factor)
    }

    internal fun placement(mark: ViewerMark): ViewerFormPlacement {
        val symbol = ViewerFormSymbol.of(mark.kind)
        val unit = symbol?.let(::shape)
        val points = mark.path.points
        if (unit == null || !unit.verbs.contentEquals(mark.path.verbs) || unit.points.size != points.size) return bounded(mark)
        val count = points.size / 2
        var su = 0f
        var sv = 0f
        var sx = 0f
        var sy = 0f
        for (index in 0 until count) {
            su += unit.points[index * 2]
            sv += unit.points[index * 2 + 1]
            sx += points[index * 2]
            sy += points[index * 2 + 1]
        }
        su /= count
        sv /= count
        sx /= count
        sy /= count
        var norm = 0f
        var dot = 0f
        var cross = 0f
        for (index in 0 until count) {
            val u = unit.points[index * 2] - su
            val v = unit.points[index * 2 + 1] - sv
            val x = points[index * 2] - sx
            val y = points[index * 2 + 1] - sy
            norm += u * u + v * v
            dot += u * x + v * y
            cross += u * y - v * x
        }
        if (norm <= 0f) return bounded(mark)
        val a = dot / norm
        val b = cross / norm
        return ViewerFormPlacement(a, b, sx - (a * su - b * sv), sy - (b * su + a * sv))
    }

    private fun bounded(mark: ViewerMark): ViewerFormPlacement {
        val box = ViewerSignaturePlacement.box(mark.path, 0f)
        val side = max(box[2] - box[0], box[3] - box[1])
        return ViewerFormPlacement(side, 0f, (box[0] + box[2] - side) / 2, (box[1] + box[3] - side) / 2)
    }

    private fun mark(page: Int, symbol: ViewerFormSymbol, color: Int, width: Float, placement: ViewerFormPlacement): ViewerMark {
        val unit = shape(symbol)
        val points = FloatArray(unit.points.size)
        for (index in unit.points.indices step 2) {
            val u = unit.points[index]
            val v = unit.points[index + 1]
            points[index] = placement.x(u, v)
            points[index + 1] = placement.y(u, v)
        }
        val stroke = if (symbol.kind.fills) 0f else width * placement.side / REFERENCE_SIZE
        return ViewerMark(page, symbol.kind, color, stroke, ViewerMarkPath(unit.verbs, points))
    }

    private fun shape(verbs: ByteArray, vararg coordinates: Float): ViewerMarkPath =
        ViewerMarkPath(verbs, FloatArray(coordinates.size) { coordinates[it] / UNIT })

    private fun dot(): ViewerMarkPath {
        val c = 3600f
        val r = 1800f
        val k = r * KAPPA
        return shape(
            byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC, ViewerMarkPath.CUBIC, ViewerMarkPath.CUBIC, ViewerMarkPath.CUBIC, ViewerMarkPath.CLOSE),
            c + r, c,
            c + r, c + k, c + k, c + r, c, c + r,
            c - k, c + r, c - r, c + k, c - r, c,
            c - r, c - k, c - k, c - r, c, c - r,
            c + k, c - r, c + r, c - k, c + r, c
        )
    }

    private fun within(value: Float, low: Float, high: Float): Float = if (high < low) low else value.coerceIn(low, high)
}
