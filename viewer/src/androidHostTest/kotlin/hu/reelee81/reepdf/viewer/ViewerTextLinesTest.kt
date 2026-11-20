package hu.reelee81.reepdf.viewer

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTextLinesTest {
    @Test
    fun anUnderlineSitsBelowAndAStrikethroughAcrossTheBaselineScaledToTheLetters() {
        val run = floatArrayOf(0f, 100f, 50f, 100f, 0f, -1f, 20f, 0f)

        val underline = ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, false, ViewerTextLines.MARKS)
        val strikethrough = ViewerTextLines.path(run, ViewerMarkKind.STRIKETHROUGH, false, ViewerTextLines.MARKS)

        assertArrayEquals(floatArrayOf(0f, 103.7f, 50f, 103.7f, 50f, 101.9f, 0f, 101.9f), underline.points, 0.001f)
        assertArrayEquals(floatArrayOf(0f, 95.7f, 50f, 95.7f, 50f, 93.9f, 0f, 93.9f), strikethrough.points, 0.001f)
        assertArrayEquals(
            byteArrayOf(ViewerMarkPath.MOVE, ViewerMarkPath.LINE, ViewerMarkPath.LINE, ViewerMarkPath.LINE, ViewerMarkPath.CLOSE),
            underline.verbs
        )
    }

    @Test
    fun aTurnedLineFollowsItsTextAndTinyLettersKeepAVisibleLine() {
        val run = floatArrayOf(100f, 10f, 100f, 30f, 1f, 0f, 4f, 0f)

        val underline = ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, false, ViewerTextLines.MARKS)

        assertArrayEquals(floatArrayOf(99.19f, 10f, 99.19f, 30f, 99.69f, 30f, 99.69f, 10f), underline.points, 0.001f)
    }

    @Test
    fun anUprightColumnIsUnderlinedOnItsRightSideAndStruckThroughItsMiddle() {
        val run = floatArrayOf(105f, 30f, 105f, 20f, 1f, 0f, 10f, 1f)

        val underline = ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, false, ViewerTextLines.MARKS)
        val strikethrough = ViewerTextLines.path(run, ViewerMarkKind.STRIKETHROUGH, false, ViewerTextLines.MARKS)

        assertArrayEquals(floatArrayOf(110.95f, 30f, 110.95f, 20f, 111.85f, 20f, 111.85f, 30f), underline.points, 0.001f)
        assertArrayEquals(floatArrayOf(104.55f, 30f, 104.55f, 20f, 105.45f, 20f, 105.45f, 30f), strikethrough.points, 0.001f)
    }

    @Test
    fun aWavyUnderlineZigzagsBelowTheBaselineWithAnEvenThickness() {
        val run = floatArrayOf(0f, 100f, 3f, 100f, 0f, -1f, 10f, 0f)

        val wavy = ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, true, ViewerTextLines.MARKS)

        assertArrayEquals(
            byteArrayOf(
                ViewerMarkPath.MOVE, ViewerMarkPath.LINE, ViewerMarkPath.LINE, ViewerMarkPath.LINE,
                ViewerMarkPath.LINE, ViewerMarkPath.LINE, ViewerMarkPath.CLOSE
            ),
            wavy.verbs
        )
        assertArrayEquals(
            floatArrayOf(0f, 101.8903f, 1.75f, 100.2903f, 3f, 101.4331f, 3f, 102.6526f, 1.75f, 101.5097f, 0f, 103.1097f),
            wavy.points,
            0.001f
        )
    }

    @Test
    fun wavyPiecesLyingInLineContinueEachOthersWave() {
        val first = ViewerTextLines.path(floatArrayOf(0f, 100f, 4f, 100f, 0f, -1f, 10f, 0f), ViewerMarkKind.UNDERLINE, true, ViewerTextLines.MARKS).points
        val second = ViewerTextLines.path(floatArrayOf(4f, 100f, 9f, 100f, 0f, -1f, 10f, 0f), ViewerMarkKind.UNDERLINE, true, ViewerTextLines.MARKS).points
        val joint = (0 until first.size / 2).filter { abs(first[it * 2] - 4f) < 0.001f }

        assertEquals(2, joint.size)
        assertEquals(4f, second[0], 0.001f)
        assertEquals(first[joint[0] * 2 + 1], second[1], 0.001f)
        assertEquals(4f, second[second.size - 2], 0.001f)
        assertEquals(first[joint[1] * 2 + 1], second[second.size - 1], 0.001f)
    }

    @Test
    fun anUprightColumnGetsItsWavyUnderlineOnItsRightSide() {
        val run = floatArrayOf(105f, 30f, 105f, 20f, 1f, 0f, 10f, 1f)

        val points = ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, true, ViewerTextLines.MARKS).points

        assertEquals(32, points.size)
        assertArrayEquals(floatArrayOf(112.8812f, 30f, 113.1097f, 29.75f), points.copyOfRange(0, 4), 0.001f)
        assertArrayEquals(floatArrayOf(112.424f, 20f, 111.2046f, 20f), points.copyOfRange(14, 18), 0.001f)
        for (index in points.indices step 2) assertTrue(points[index] in 110.2f..113.2f)
    }

    @Test
    fun aStrikethroughStaysStraightEvenWhenTheUnderlineIsWavy() {
        val run = floatArrayOf(0f, 100f, 50f, 100f, 0f, -1f, 20f, 0f)

        val strikethrough = ViewerTextLines.path(run, ViewerMarkKind.STRIKETHROUGH, true, ViewerTextLines.MARKS)

        assertArrayEquals(ViewerTextLines.path(run, ViewerMarkKind.STRIKETHROUGH, false, ViewerTextLines.MARKS).points, strikethrough.points, 0f)
    }

    @Test
    fun thePanelSamplesKeepTheirOwnSlimmerShape() {
        val run = floatArrayOf(0f, 100f, 3f, 100f, 0f, -1f, 10f, 0f)

        val straight = ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, false, ViewerTextLines.SAMPLES)
        val wavy = ViewerTextLines.path(run, ViewerMarkKind.UNDERLINE, true, ViewerTextLines.SAMPLES)

        assertArrayEquals(floatArrayOf(0f, 101.35f, 3f, 101.35f, 3f, 100.65f, 0f, 100.65f), straight.points, 0.001f)
        assertArrayEquals(
            floatArrayOf(0f, 101.452f, 1.5f, 100.252f, 3f, 101.452f, 3f, 102.348f, 1.5f, 101.148f, 0f, 102.348f),
            wavy.points,
            0.001f
        )
    }

    @Test
    fun everyRunBecomesItsOwnPieceWithItsOwnThickness() {
        val runs = floatArrayOf(
            0f, 100f, 50f, 100f, 0f, -1f, 10f, 0f,
            0f, 200f, 80f, 200f, 0f, -1f, 30f, 0f
        )

        val path = ViewerTextLines.path(runs, ViewerMarkKind.UNDERLINE, false, ViewerTextLines.MARKS)

        assertEquals(10, path.verbs.size)
        assertArrayEquals(floatArrayOf(0f, 101.85f, 50f, 101.85f, 50f, 100.95f, 0f, 100.95f), path.points.copyOfRange(0, 8), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 205.55f, 80f, 205.55f, 80f, 202.85f, 0f, 202.85f), path.points.copyOfRange(8, 16), 0.001f)
        assertEquals(0, ViewerTextLines.path(FloatArray(0), ViewerMarkKind.STRIKETHROUGH, false, ViewerTextLines.MARKS).verbs.size)
    }
}
