package hu.reelee81.reepdf.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerFlingTrackTest {
    @Test
    fun aFastFlickFlingsTheWayTheFingerWentEvenWhenAnEditToolHeldThePageUntilJustBeforeTheLift() {
        val track = ViewerFlingTrack()

        flick(track)

        val velocity = track.velocity(LIMIT)
        assertTrue("the fling must go the finger's way, got ${velocity.y}", velocity.y > 4000f)
        assertEquals(0f, velocity.x, 1f)
    }

    @Test
    fun aNewPressForgetsTheLastFlick() {
        val track = ViewerFlingTrack()
        flick(track)

        track.start(70L, Offset(40f, 500f))
        for (step in 1..8) track.add(70L + step * 8L, Offset(40f, 500f - step * 4f))

        val velocity = track.velocity(LIMIT)
        assertEquals(-500f, velocity.y, 50f)
    }

    private fun flick(track: ViewerFlingTrack) {
        track.start(0L, FLICK_START)
        for ((time, moved) in FLICK) track.add(time, FLICK_START + Offset(0f, moved))
    }

    private companion object {
        const val LIMIT = 20000f

        val FLICK_START = Offset(40f, 1000f)

        // A finger that speeds up to 5 px per millisecond. The last move is resampled 16 px ahead of
        // the finger, and the lift comes 2 ms later, 12 px behind that move. When only the moves after
        // a late Underline/Strikethrough word decision reached the tracker, this flick flung backwards.
        val FLICK = listOf(
            8L to 5.3f,
            16L to 21.2f,
            24L to 47.8f,
            32L to 84.8f,
            40L to 124.8f,
            48L to 164.8f,
            56L to 220.8f,
            58L to 208.8f
        )
    }
}
