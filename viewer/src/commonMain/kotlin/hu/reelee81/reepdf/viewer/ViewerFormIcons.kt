package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

internal object ViewerFormIcons {
    const val VIEWPORT = 24f
    const val DRAWING = 20f
    const val STROKE = 2f
    private const val EPSILON = 1e-9

    private val icons: Map<ViewerFormSymbol, ImageVector> by lazy { ViewerFormSymbol.entries.associateWith(::icon) }

    private val circles: Map<ViewerFormSymbol, DoubleArray> by lazy { ViewerFormSymbol.entries.associateWith { enclosing(outline(it)) } }

    private val scales: Map<ViewerFormSymbol, Double> by lazy { ViewerFormSymbol.entries.associateWith(::scaleOf) }

    fun of(symbol: ViewerFormSymbol): ImageVector = icons.getValue(symbol)

    fun nodes(symbol: ViewerFormSymbol): List<PathNode> {
        val path = ViewerFormMarks.shape(symbol)
        val circle = circles.getValue(symbol)
        val scale = scales.getValue(symbol)
        val p = FloatArray(path.points.size) { (VIEWPORT / 2 + (path.points[it] - circle[it % 2]) * scale).toFloat() }
        val nodes = ArrayList<PathNode>(path.verbs.size)
        var index = 0
        for (verb in path.verbs) {
            nodes += when (verb) {
                ViewerMarkPath.MOVE -> PathNode.MoveTo(p[index], p[index + 1])
                ViewerMarkPath.LINE -> PathNode.LineTo(p[index], p[index + 1])
                ViewerMarkPath.CUBIC -> PathNode.CurveTo(p[index], p[index + 1], p[index + 2], p[index + 3], p[index + 4], p[index + 5])
                else -> PathNode.Close
            }
            index += ViewerMarkPath.pointsOf(verb)
        }
        return nodes
    }

    private fun scaleOf(symbol: ViewerFormSymbol): Double {
        if (symbol.kind.fills) return DRAWING.toDouble()
        val circle = circles.getValue(symbol)
        val points = outline(symbol)
        val xs = points.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = points.filterIndexed { index, _ -> index % 2 == 1 }
        val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
        val reach = maxOf(xs.maxOf { abs(it - circle[0]) }, ys.maxOf { abs(it - circle[1]) })
        return minOf((DRAWING - STROKE) / span, (VIEWPORT - STROKE) / 2.0 / reach)
    }

    private fun outline(symbol: ViewerFormSymbol): DoubleArray {
        val polygons = ViewerMarkHit.flatten(ViewerFormMarks.shape(symbol))
        return polygons.flatMap { polygon -> polygon.map(Float::toDouble) }.toDoubleArray()
    }

    private fun enclosing(points: DoubleArray): DoubleArray {
        var circle = doubleArrayOf(points[0], points[1], 0.0)
        for (i in 1 until points.size / 2) {
            if (covers(circle, points, i)) continue
            circle = doubleArrayOf(points[i * 2], points[i * 2 + 1], 0.0)
            for (j in 0 until i) {
                if (covers(circle, points, j)) continue
                circle = across(points, i, j)
                for (k in 0 until j) {
                    if (!covers(circle, points, k)) circle = around(points, i, j, k)
                }
            }
        }
        return circle
    }

    private fun covers(circle: DoubleArray, points: DoubleArray, index: Int): Boolean =
        hypot(points[index * 2] - circle[0], points[index * 2 + 1] - circle[1]) <= circle[2] + EPSILON

    private fun across(points: DoubleArray, i: Int, j: Int): DoubleArray {
        val ax = points[i * 2]
        val ay = points[i * 2 + 1]
        val bx = points[j * 2]
        val by = points[j * 2 + 1]
        return doubleArrayOf((ax + bx) / 2, (ay + by) / 2, hypot(ax - bx, ay - by) / 2)
    }

    private fun around(points: DoubleArray, i: Int, j: Int, k: Int): DoubleArray {
        val ax = points[i * 2]
        val ay = points[i * 2 + 1]
        val bx = points[j * 2]
        val by = points[j * 2 + 1]
        val cx = points[k * 2]
        val cy = points[k * 2 + 1]
        val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < EPSILON) return listOf(across(points, i, j), across(points, i, k), across(points, j, k)).maxBy { it[2] }
        val a = ax * ax + ay * ay
        val b = bx * bx + by * by
        val c = cx * cx + cy * cy
        val x = (a * (by - cy) + b * (cy - ay) + c * (ay - by)) / d
        val y = (a * (cx - bx) + b * (ax - cx) + c * (bx - ax)) / d
        return doubleArrayOf(x, y, hypot(ax - x, ay - y))
    }

    private fun icon(symbol: ViewerFormSymbol): ImageVector {
        val builder = ImageVector.Builder(
            name = "ic_form_${symbol.name.lowercase()}_24",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT
        )
        val paint = SolidColor(Color.Black)
        return if (symbol.kind.fills) {
            builder.addPath(pathData = nodes(symbol), fill = paint).build()
        } else {
            builder.addPath(
                pathData = nodes(symbol),
                stroke = paint,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ).build()
        }
    }
}
