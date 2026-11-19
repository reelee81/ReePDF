package hu.reelee81.reepdf.viewer

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ViewerStrokeBuilder(private val minDistance: Float) {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()

    val size: Int
        get() = xs.size

    fun add(x: Float, y: Float): Boolean {
        if (xs.isNotEmpty() && hypot(x - xs.last(), y - ys.last()) < minDistance) return false
        xs += x
        ys += y
        return true
    }

    fun finish(x: Float, y: Float) {
        if (xs.isEmpty() || x != xs.last() || y != ys.last()) {
            xs += x
            ys += y
        }
    }

    fun path(): ViewerMarkPath {
        val count = xs.size
        require(count > 0) { "The stroke has no points" }
        if (count <= 2) {
            val last = count - 1
            return ViewerMarkPath(
                byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE),
                floatArrayOf(xs[0], ys[0], xs[last], ys[last])
            )
        }
        val verbs = ByteArray(count)
        val points = FloatArray(2 + (count - 2) * 6 + 2)
        verbs[0] = ViewerMarkPath.MOVE
        points[0] = xs[0]
        points[1] = ys[0]
        var startX = xs[0]
        var startY = ys[0]
        var offset = 2
        for (index in 1 until count - 1) {
            val controlX = xs[index]
            val controlY = ys[index]
            val endX = (xs[index] + xs[index + 1]) / 2
            val endY = (ys[index] + ys[index + 1]) / 2
            verbs[index] = ViewerMarkPath.CUBIC
            points[offset] = startX + 2f / 3f * (controlX - startX)
            points[offset + 1] = startY + 2f / 3f * (controlY - startY)
            points[offset + 2] = endX + 2f / 3f * (controlX - endX)
            points[offset + 3] = endY + 2f / 3f * (controlY - endY)
            points[offset + 4] = endX
            points[offset + 5] = endY
            offset += 6
            startX = endX
            startY = endY
        }
        verbs[count - 1] = ViewerMarkPath.LINE
        points[offset] = xs[count - 1]
        points[offset + 1] = ys[count - 1]
        return ViewerMarkPath(verbs, points)
    }
}

internal object ViewerMarkHit {
    private const val CURVE_STEPS = 8

    fun hits(kind: ViewerMarkKind, path: ViewerMarkPath, width: Float, x: Float, y: Float, tolerance: Float): Boolean {
        val polygons = flatten(path)
        if (kind == ViewerMarkKind.TEXT_HIGHLIGHT) {
            return polygons.any { inside(it, x, y) || distance(it, x, y, closed = true) <= tolerance }
        }
        val reach = width / 2 + tolerance
        return polygons.any { distance(it, x, y, closed = false) <= reach }
    }

    fun within(clip: FloatArray?, x: Float, y: Float, tolerance: Float): Boolean =
        clip == null || (x >= clip[0] - tolerance && x <= clip[2] + tolerance && y >= clip[1] - tolerance && y <= clip[3] + tolerance)

    fun sweep(fromX: Float, fromY: Float, toX: Float, toY: Float, step: Float): List<Pair<Float, Float>> {
        val length = hypot(toX - fromX, toY - fromY)
        val count = max(1, (length / max(step, 0.001f)).toInt() + 1)
        return (1..count).map { index ->
            val t = index.toFloat() / count
            (fromX + (toX - fromX) * t) to (fromY + (toY - fromY) * t)
        }
    }

    private fun flatten(path: ViewerMarkPath): List<FloatArray> {
        val polygons = ArrayList<FloatArray>()
        var current = ArrayList<Float>()
        var index = 0
        fun finish() {
            if (current.isNotEmpty()) polygons += current.toFloatArray()
            current = ArrayList()
        }
        val p = path.points
        for (verb in path.verbs) {
            when (verb) {
                ViewerMarkPath.MOVE -> {
                    finish()
                    current += p[index]
                    current += p[index + 1]
                }
                ViewerMarkPath.LINE -> {
                    current += p[index]
                    current += p[index + 1]
                }
                ViewerMarkPath.CUBIC -> {
                    val x0 = if (current.size >= 2) current[current.size - 2] else p[index]
                    val y0 = if (current.size >= 2) current[current.size - 1] else p[index + 1]
                    for (step in 1..CURVE_STEPS) {
                        val t = step.toFloat() / CURVE_STEPS
                        val u = 1 - t
                        val a = u * u * u
                        val b = 3 * u * u * t
                        val c = 3 * u * t * t
                        val d = t * t * t
                        current += a * x0 + b * p[index] + c * p[index + 2] + d * p[index + 4]
                        current += a * y0 + b * p[index + 1] + c * p[index + 3] + d * p[index + 5]
                    }
                }
                ViewerMarkPath.CLOSE -> finish()
            }
            index += ViewerMarkPath.pointsOf(verb)
        }
        finish()
        return polygons
    }

    private fun distance(points: FloatArray, x: Float, y: Float, closed: Boolean): Float {
        val count = points.size / 2
        if (count == 1) return hypot(points[0] - x, points[1] - y)
        var best = Float.MAX_VALUE
        val segments = if (closed) count else count - 1
        for (index in 0 until segments) {
            val next = (index + 1) % count
            best = min(best, segment(points[index * 2], points[index * 2 + 1], points[next * 2], points[next * 2 + 1], x, y))
        }
        return best
    }

    private fun segment(ax: Float, ay: Float, bx: Float, by: Float, x: Float, y: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
        return hypot(ax + t * dx - x, ay + t * dy - y)
    }

    private fun inside(points: FloatArray, x: Float, y: Float): Boolean {
        val count = points.size / 2
        if (count < 3) return false
        var inside = false
        var previous = count - 1
        for (index in 0 until count) {
            val xi = points[index * 2]
            val yi = points[index * 2 + 1]
            val xj = points[previous * 2]
            val yj = points[previous * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            previous = index
        }
        return inside
    }
}
