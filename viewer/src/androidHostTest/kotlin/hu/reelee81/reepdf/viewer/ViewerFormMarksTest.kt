package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerFormMarksTest {
    private fun place(symbol: ViewerFormSymbol, width: Float = 1f, size: Float = 12f, at: ViewerTextPoint = ViewerTextPoint(50f, 50f)) =
        ViewerFormMarks.place(symbol, 0x202FB0, width, size, 3, at, 100f, 100f)

    private fun turned(mark: ViewerMark): ViewerMark {
        val points = mark.path.points
        val turned = FloatArray(points.size) { index -> if (index % 2 == 0) 100f - points[index + 1] else points[index - 1] }
        return ViewerMark(mark.page, mark.kind, mark.color, mark.width, ViewerMarkPath(mark.path.verbs, turned))
    }

    @Test
    fun everySymbolIsDrawnInsideItsUnitBoxAndTheDotIsAClosedCircleOfHalfTheBox() {
        for (symbol in ViewerFormSymbol.entries) {
            val points = ViewerFormMarks.shape(symbol).points
            assertTrue(symbol.name, points.all { it in 0.1f..0.9f })
        }
        assertEquals(
            listOf(ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC, ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC),
            ViewerFormMarks.shape(ViewerFormSymbol.X).verbs.toList()
        )
        val dot = ViewerFormMarks.shape(ViewerFormSymbol.DOT)
        assertEquals(ViewerMarkPath.CLOSE, dot.verbs.last())
        val box = ViewerSignaturePlacement.box(dot, 0f)
        assertArrayEquals(floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f), box, 0.0001f)
        assertEquals(listOf(ViewerMarkKind.BOX_X, ViewerMarkKind.BOX_CHECK, ViewerMarkKind.BOX_DASH, ViewerMarkKind.BOX_DOT), ViewerFormSymbol.entries.map { it.kind })
    }

    @Test
    fun everySymbolHasItsWeightInTheMiddleAndStaysInsideTheOldMargins() {
        for (symbol in ViewerFormSymbol.entries) {
            val path = ViewerFormMarks.shape(symbol)
            val p = path.points
            var length = 0.0
            var sumX = 0.0
            var sumY = 0.0
            var index = 0
            var lastX = 0f
            var lastY = 0f
            for (verb in path.verbs) {
                when (verb) {
                    ViewerMarkPath.MOVE -> {
                        lastX = p[index]
                        lastY = p[index + 1]
                    }
                    ViewerMarkPath.LINE, ViewerMarkPath.CUBIC -> {
                        val cubic = verb == ViewerMarkPath.CUBIC
                        val endX = if (cubic) p[index + 4] else p[index]
                        val endY = if (cubic) p[index + 5] else p[index + 1]
                        var x0 = lastX.toDouble()
                        var y0 = lastY.toDouble()
                        for (step in 1..2000) {
                            val s = step / 2000.0
                            val m = 1 - s
                            val x = if (cubic) m * m * m * lastX + 3 * m * m * s * p[index] + 3 * m * s * s * p[index + 2] + s * s * s * endX else lastX + (endX - lastX) * s
                            val y = if (cubic) m * m * m * lastY + 3 * m * m * s * p[index + 1] + 3 * m * s * s * p[index + 3] + s * s * s * endY else lastY + (endY - lastY) * s
                            val piece = hypot(x - x0, y - y0)
                            length += piece
                            sumX += piece * (x + x0) / 2
                            sumY += piece * (y + y0) / 2
                            x0 = x
                            y0 = y
                        }
                        lastX = endX
                        lastY = endY
                    }
                }
                index += ViewerMarkPath.pointsOf(verb)
            }
            assertEquals(symbol.name, 0.5, sumX / length, 0.001)
            assertEquals(symbol.name, 0.5, sumY / length, 0.001)
            assertTrue(symbol.name, p.all { it in 800f / 7200f..6400f / 7200f })
        }
    }

    @Test
    fun aMarkIsPlacedCenteredOnTheTapInsideThePageWithItsWidthMeantForTwelvePoints() {
        val mark = place(ViewerFormSymbol.CHECK, width = 2f)

        assertEquals(ViewerMarkKind.BOX_CHECK, mark.kind)
        assertEquals(3, mark.page)
        assertEquals(0x202FB0, mark.color)
        assertEquals(2f, mark.width, 0.0001f)
        assertArrayEquals(floatArrayOf(44f, 44f, 56f, 56f), ViewerFormMarks.box(mark), 0.001f)
        assertEquals(4f, place(ViewerFormSymbol.CHECK, width = 2f, size = 24f).width, 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 12f, 12f), ViewerFormMarks.box(place(ViewerFormSymbol.X, at = ViewerTextPoint(2f, 3f))), 0.001f)
        assertArrayEquals(floatArrayOf(88f, 88f, 100f, 100f), ViewerFormMarks.box(place(ViewerFormSymbol.X, at = ViewerTextPoint(99f, 98f))), 0.001f)
        val tooBig = ViewerFormMarks.place(ViewerFormSymbol.DASH, 0, 1f, 500f, 0, ViewerTextPoint(10f, 10f), 100f, 60f)
        assertArrayEquals(floatArrayOf(0f, 0f, 60f, 60f), ViewerFormMarks.box(tooBig), 0.001f)
    }

    @Test
    fun theDotIsAFillWithoutAStrokeWidth() {
        val dot = place(ViewerFormSymbol.DOT, width = 5f)

        assertEquals(ViewerMarkKind.BOX_DOT, dot.kind)
        assertTrue(dot.kind.fills)
        assertEquals(0f, dot.width, 0f)
        assertArrayEquals(floatArrayOf(47f, 47f, 53f, 53f), ViewerSignaturePlacement.box(dot.path, 0f), 0.001f)
        assertArrayEquals(floatArrayOf(44f, 44f, 56f, 56f), ViewerFormMarks.box(dot), 0.001f)
    }

    @Test
    fun theSizeTheWidthAndATurnAreReadBackFromTheMarkSoAnotherSymbolTakesItsPlaceAsItIs() {
        val mark = turned(place(ViewerFormSymbol.X, width = 3f))

        assertEquals(12f, ViewerFormMarks.size(mark), 0.001f)
        assertEquals(3f, ViewerFormMarks.width(mark), 0.001f)
        assertArrayEquals(floatArrayOf(44f, 44f, 56f, 56f), ViewerFormMarks.placement(mark).square(), 0.001f)
        val check = ViewerFormMarks.restyled(mark, ViewerFormSymbol.CHECK, 0xDD0000, 3f)
        val expected = turned(place(ViewerFormSymbol.CHECK, width = 3f))
        assertEquals(ViewerMarkKind.BOX_CHECK, check.kind)
        assertEquals(0xDD0000, check.color)
        assertEquals(3f, check.width, 0.001f)
        assertArrayEquals(expected.path.points, check.path.points, 0.001f)
    }

    @Test
    fun resizingKeepsTheTopLeftCornerAndTheProportionsAndStopsAtFourPointsAndThePageEdge() {
        val mark = place(ViewerFormSymbol.X, width = 1f)
        val box = ViewerFormMarks.box(mark)

        val doubled = ViewerFormMarks.resized(mark, box, ViewerTextPoint(68f, 68f), 100f, 100f)
        assertArrayEquals(floatArrayOf(44f, 44f, 68f, 68f), ViewerFormMarks.box(doubled), 0.001f)
        assertEquals(2f, doubled.width, 0.0001f)
        assertEquals(1f, ViewerFormMarks.width(doubled), 0.0001f)
        val smallest = ViewerFormMarks.resized(mark, box, ViewerTextPoint(44.5f, 44.5f), 100f, 100f)
        assertEquals(4f, ViewerFormMarks.size(smallest), 0.001f)
        val largest = ViewerFormMarks.resized(mark, box, ViewerTextPoint(1000f, 1000f), 100f, 100f)
        assertArrayEquals(floatArrayOf(44f, 44f, 100f, 100f), ViewerFormMarks.box(largest), 0.001f)
    }

    @Test
    fun theBoxCoversTheInkOfAThickMark() {
        val thick = place(ViewerFormSymbol.DASH, width = 10f)

        val box = ViewerFormMarks.box(thick)

        assertArrayEquals(floatArrayOf(44f, 44f, 56f, 56f), ViewerFormMarks.placement(thick).square(), 0.001f)
        assertEquals(44f + 800f / 7200f * 12f - 5f, box[0], 0.001f)
        assertEquals(44f, box[1], 0.001f)
        assertEquals(56f + 5f - 800f / 7200f * 12f, box[2], 0.001f)
    }

    @Test
    fun everyLineIconIsTwentyUnitsOnItsLongerSideTwoUnitsWideAndTheDotIsHalfTheDrawing() {
        for (symbol in ViewerFormSymbol.entries) {
            val icon = ViewerFormIcons.of(symbol)
            val path = icon.root[0] as VectorPath

            assertEquals(24f, icon.viewportWidth, 0f)
            assertEquals(24f, icon.viewportHeight, 0f)
            assertEquals(ViewerFormIcons.nodes(symbol), path.pathData)
            if (symbol == ViewerFormSymbol.DOT) {
                assertNotNull(path.fill)
                assertNull(path.stroke)
            } else {
                assertNull(path.fill)
                assertNotNull(path.stroke)
                assertEquals(2f, path.strokeLineWidth, 0f)
                assertEquals(StrokeCap.Round, path.strokeLineCap)
                assertEquals(StrokeJoin.Round, path.strokeLineJoin)
            }
        }
        for (symbol in ViewerFormSymbol.entries.filter { it != ViewerFormSymbol.DOT }) {
            val ink = iconInk(symbol)
            val width = ink.maxOf { it[0] } - ink.minOf { it[0] } + 2f
            val height = ink.maxOf { it[1] } - ink.minOf { it[1] } + 2f
            assertEquals(symbol.name, 20f, maxOf(width, height), 0.001f)
            assertTrue(symbol.name, ink.all { abs(it[0] - 12f) + 1f <= 12f && abs(it[1] - 12f) + 1f <= 12f })
        }
        val dot = ViewerFormIcons.nodes(ViewerFormSymbol.DOT)
        assertEquals(PathNode.Close, dot.last())
        assertEquals(17f, (dot.first() as PathNode.MoveTo).x, 0.001f)
        assertEquals(12f, (dot.first() as PathNode.MoveTo).y, 0.001f)
    }

    @Test
    fun everyIconSitsInTheMiddleOfItsRoundFrameWithItsFarthestEndsEquallyFarFromIt() {
        for (symbol in ViewerFormSymbol.entries) {
            val ink = iconInk(symbol)
            fun farthest(x: Float, y: Float) = ink.maxOf { hypot(it[0] - x, it[1] - y) }
            val middle = farthest(12f, 12f)
            for (step in 0 until 32) {
                val angle = step * PI / 16
                assertTrue(symbol.name, middle <= farthest(12f + 0.05f * cos(angle).toFloat(), 12f + 0.05f * sin(angle).toFloat()))
            }
        }
    }

    private fun iconInk(symbol: ViewerFormSymbol): List<FloatArray> {
        val ink = ArrayList<FloatArray>()
        var x = 0f
        var y = 0f
        for (node in ViewerFormIcons.nodes(symbol)) {
            when (node) {
                is PathNode.MoveTo -> {
                    x = node.x
                    y = node.y
                    ink += floatArrayOf(x, y)
                }
                is PathNode.LineTo -> {
                    x = node.x
                    y = node.y
                    ink += floatArrayOf(x, y)
                }
                is PathNode.CurveTo -> {
                    for (step in 1..200) {
                        val s = step / 200f
                        val m = 1 - s
                        ink += floatArrayOf(
                            m * m * m * x + 3 * m * m * s * node.x1 + 3 * m * s * s * node.x2 + s * s * s * node.x3,
                            m * m * m * y + 3 * m * m * s * node.y1 + 3 * m * s * s * node.y2 + s * s * s * node.y3
                        )
                    }
                    x = node.x3
                    y = node.y3
                }
                else -> Unit
            }
        }
        return ink
    }

    @Test
    fun aFormMarkOfAnUnknownShapeIsBoxedByItsBounds() {
        val odd = ViewerMark(0, ViewerMarkKind.BOX_X, 0, 1f, ViewerMarkPath(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), floatArrayOf(10f, 10f, 30f, 20f)))

        assertArrayEquals(floatArrayOf(10f, 5f, 30f, 25f), ViewerFormMarks.placement(odd).square(), 0.001f)
        assertEquals(20f, ViewerFormMarks.size(odd), 0.001f)
    }
}
