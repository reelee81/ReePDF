package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStrokeTest {
    @Test
    fun aTapIsADotAndTwoPointsAreALine() {
        val tap = ViewerStrokeBuilder(1f).apply { add(5f, 6f) }.path()
        val line = ViewerStrokeBuilder(1f).apply {
            add(0f, 0f)
            add(10f, 0f)
        }.path()

        assertArrayEquals(byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE), tap.verbs)
        assertArrayEquals(floatArrayOf(5f, 6f, 5f, 6f), tap.points, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f), line.points, 0f)
    }

    @Test
    fun longerStrokesAreSmoothedThroughTheMidpoints() {
        val builder = ViewerStrokeBuilder(1f)
        listOf(0f to 0f, 10f to 0f, 10f to 10f, 20f to 10f).forEach { (x, y) -> builder.add(x, y) }

        val path = builder.path()

        assertArrayEquals(
            byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC, ViewerMarkPath.CUBIC, ViewerMarkPath.LINE),
            path.verbs
        )
        val p = path.points
        assertArrayEquals(floatArrayOf(0f, 0f), p.copyOfRange(0, 2), 0f)
        assertArrayEquals(floatArrayOf(10f, 5f), p.copyOfRange(6, 8), 0.0001f)
        assertArrayEquals(floatArrayOf(15f, 10f), p.copyOfRange(12, 14), 0.0001f)
        assertArrayEquals(floatArrayOf(20f, 10f), p.copyOfRange(14, 16), 0f)
        assertArrayEquals(floatArrayOf(20f / 3f, 0f, 10f, 5f - 10f / 3f), p.copyOfRange(2, 6), 0.0001f)
    }

    @Test
    fun pointsCloserThanTheMinimumAreSkippedButTheLastOneIsKept() {
        val builder = ViewerStrokeBuilder(2f)

        assertTrue(builder.add(0f, 0f))
        assertFalse(builder.add(1f, 1f))
        assertTrue(builder.add(3f, 0f))
        builder.finish(3.5f, 0f)
        builder.finish(3.5f, 0f)

        assertEquals(3, builder.size)
        assertArrayEquals(floatArrayOf(3.5f, 0f), builder.path().points.copyOfRange(builder.path().points.size - 2, builder.path().points.size), 0f)
    }

    @Test
    fun aStrokeIsHitWithinHalfItsWidthAndTheTolerance() {
        val path = ViewerStrokeBuilder(0f).apply {
            add(0f, 0f)
            add(100f, 0f)
        }.path()

        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.PEN, path, 4f, 50f, 6.9f, 5f))
        assertFalse(ViewerMarkHit.hits(ViewerMarkKind.PEN, path, 4f, 50f, 7.1f, 5f))
        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.HIGHLIGHTER, path, 20f, 105f, 0f, 0f))
        assertFalse(ViewerMarkHit.hits(ViewerMarkKind.PEN, path, 4f, 120f, 0f, 5f))
    }

    @Test
    fun aCurveIsHitAlongItsBendAndADotAroundItsPoint() {
        val curve = ViewerMarkPath(
            byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.CUBIC),
            floatArrayOf(0f, 0f, 0f, 100f, 100f, 100f, 100f, 0f)
        )
        val dot = ViewerStrokeBuilder(1f).apply { add(10f, 10f) }.path()

        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.PEN, curve, 2f, 50f, 75f, 1f))
        assertFalse(ViewerMarkHit.hits(ViewerMarkKind.PEN, curve, 2f, 50f, 40f, 1f))
        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.PEN, dot, 4f, 12f, 12f, 1f))
        assertFalse(ViewerMarkHit.hits(ViewerMarkKind.PEN, dot, 4f, 16f, 16f, 1f))
    }

    @Test
    fun aTextHighlightIsHitInsideAndNearItsRectangles() {
        val path = ViewerMarkPath.rects(floatArrayOf(10f, 10f, 50f, 20f, 10f, 30f, 30f, 40f))

        assertEquals(10, path.verbs.size)
        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.TEXT_HIGHLIGHT, path, 0f, 40f, 15f, 1f))
        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.TEXT_HIGHLIGHT, path, 0f, 20f, 41f, 2f))
        assertFalse(ViewerMarkHit.hits(ViewerMarkKind.TEXT_HIGHLIGHT, path, 0f, 40f, 35f, 2f))
    }

    @Test
    fun aTextLineIsHitOnItsThinBandAndNearIt() {
        val underline = ViewerTextLines.path(floatArrayOf(10f, 50f, 60f, 50f, 0f, -1f, 10f, 0f), ViewerMarkKind.UNDERLINE, false, ViewerTextLines.MARKS)
        val strikethrough = ViewerTextLines.path(floatArrayOf(10f, 50f, 60f, 50f, 0f, -1f, 10f, 0f), ViewerMarkKind.STRIKETHROUGH, false, ViewerTextLines.MARKS)

        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.UNDERLINE, underline, 0f, 30f, 51.5f, 0f))
        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.UNDERLINE, underline, 0f, 30f, 53f, 2f))
        assertFalse(ViewerMarkHit.hits(ViewerMarkKind.UNDERLINE, underline, 0f, 30f, 55f, 2f))
        assertTrue(ViewerMarkHit.hits(ViewerMarkKind.STRIKETHROUGH, strikethrough, 0f, 30f, 47.5f, 0f))
        assertFalse(ViewerMarkHit.hits(ViewerMarkKind.STRIKETHROUGH, strikethrough, 0f, 30f, 51f, 0f))
    }

    @Test
    fun aSweepReachesTheEndInStepsNoLongerThanAsked() {
        val points = ViewerMarkHit.sweep(0f, 0f, 10f, 0f, 3f)
        assertTrue(ViewerMarkHit.within(null, -500f, 900f, 0f))
        assertTrue(ViewerMarkHit.within(floatArrayOf(10f, 20f, 30f, 40f), 31.5f, 20f, 2f))
        assertFalse(ViewerMarkHit.within(floatArrayOf(10f, 20f, 30f, 40f), 32.5f, 20f, 2f))
        assertFalse(ViewerMarkHit.within(FloatArray(4) { Float.NaN }, 0f, 0f, 5f))

        assertEquals(4, points.size)
        assertEquals(10f to 0f, points.last())
        assertTrue(points.zipWithNext().all { (a, b) -> b.first - a.first <= 3f })
        assertSweep(listOf(0f to 0f), ViewerMarkHit.sweep(0f, 0f, 0f, 0f, 3f))
    }

    private fun assertSweep(expected: List<Pair<Float, Float>>, actual: List<Pair<Float, Float>>) = assertEquals(expected, actual)
}
