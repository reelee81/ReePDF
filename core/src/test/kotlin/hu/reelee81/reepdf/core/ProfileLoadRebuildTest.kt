package hu.reelee81.reepdf.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLoadRebuildTest {
    private val config = NupConfig(2, 2, 2, isLandscape = false, drawFrame = false, scaleIndividually = true, fitIndividually = false)

    @Test
    fun theSameLayoutAndPositionsNeedNoRebuild() {
        assertFalse(ProfileLoadRebuild.isNeeded(config, config.copy(), intArrayOf(5, 5, 5), 5, 5))
        assertFalse(ProfileLoadRebuild.isNeeded(config, config, IntArray(0), 5, 5))
    }

    @Test
    fun anyChangedLayoutValueNeedsARebuild() {
        listOf(
            config.copy(rows = 1),
            config.copy(cols = 3),
            config.copy(marginMm = 0),
            config.copy(isLandscape = true),
            config.copy(drawFrame = true),
            config.copy(scaleIndividually = false),
            config.copy(fitIndividually = true)
        ).forEach { loaded ->
            assertTrue(loaded.toString(), ProfileLoadRebuild.isNeeded(config, loaded, intArrayOf(5), 5, 5))
        }
    }

    @Test
    fun withoutABuiltLayoutARebuildIsNeeded() {
        assertTrue(ProfileLoadRebuild.isNeeded(null, config, IntArray(0), 5, 5))
    }

    @Test
    fun aPageWithItsOwnPositionNeedsARebuild() {
        assertTrue(ProfileLoadRebuild.isNeeded(config, config, intArrayOf(5, 3, 5), 5, 5))
    }

    @Test
    fun aChangedDefaultPositionNeedsARebuildEvenWithoutPagePositions() {
        assertTrue(ProfileLoadRebuild.isNeeded(config, config, IntArray(0), 5, 3))
        assertTrue(ProfileLoadRebuild.isNeeded(config, config, intArrayOf(3, 3), 5, 3))
    }
}
