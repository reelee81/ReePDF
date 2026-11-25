package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerEdgeStepTest {
    @Test
    fun theFingerNearAnEdgeScrollsFasterTheDeeperItIs() {
        assertEquals(-10f, edgeStep(0f, 1000, 40f, 10f), 0.001f)
        assertEquals(-5f, edgeStep(20f, 1000, 40f, 10f), 0.001f)
        assertEquals(0f, edgeStep(500f, 1000, 40f, 10f), 0f)
        assertEquals(5f, edgeStep(980f, 1000, 40f, 10f), 0.001f)
        assertEquals(10f, edgeStep(1200f, 1000, 40f, 10f), 0.001f)
    }

    @Test
    fun theStepFollowsTheTimeSinceTheLastFrameSoTheSpeedIsTheSameAtEveryRefreshRate() {
        assertEquals(1f, autoScrollScale(16_000_000L), 1e-6f)
        assertEquals(0.5208f, autoScrollScale(8_333_333L), 1e-4f)
        assertEquals(1.0417f, autoScrollScale(16_666_667L), 1e-4f)
        assertEquals(2f * autoScrollScale(8_333_333L), autoScrollScale(16_666_666L), 1e-5f)
        assertEquals(0f, autoScrollScale(0L), 0f)
        assertEquals(0f, autoScrollScale(-5L), 0f)
        assertEquals(3f, autoScrollScale(1_000_000_000L), 0f)
    }

    @Test
    fun aDraggedFrameScrollsOnlyTowardsTheHiddenPartOfItsPage() {
        assertEquals(5f, pageEdgeStep(980f, 1000, -300f, 2000f, 40f, 10f), 0.001f)
        assertEquals(-5f, pageEdgeStep(20f, 1000, -300f, 2000f, 40f, 10f), 0.001f)
        assertEquals(0f, pageEdgeStep(980f, 1000, -300f, 1000f, 40f, 10f), 0f)
        assertEquals(0f, pageEdgeStep(980f, 1000, -300f, 800f, 40f, 10f), 0f)
        assertEquals(0f, pageEdgeStep(20f, 1000, 0f, 2000f, 40f, 10f), 0f)
        assertEquals(0f, pageEdgeStep(20f, 1000, 60f, 2000f, 40f, 10f), 0f)
        assertEquals(0f, pageEdgeStep(500f, 1000, -300f, 2000f, 40f, 10f), 0f)
    }

    @Test
    fun theLastStepStopsExactlyAtThePageEdge() {
        assertEquals(3f, pageEdgeStep(990f, 1000, -300f, 1003f, 40f, 10f), 0.001f)
        assertEquals(-2f, pageEdgeStep(10f, 1000, -2f, 2000f, 40f, 10f), 0.001f)
    }

    @Test
    fun theKeyboardTopIsTheBottomEdgeAndAFingerOnTheKeyboardScrollsAtFullSpeed() {
        assertEquals(5f, pageEdgeStep(580f, 600, -300f, 900f, 40f, 10f), 0.001f)
        assertEquals(10f, pageEdgeStep(700f, 600, -300f, 900f, 40f, 10f), 0.001f)
        assertEquals(0f, pageEdgeStep(580f, 600, -300f, 590f, 40f, 10f), 0f)
    }
}
