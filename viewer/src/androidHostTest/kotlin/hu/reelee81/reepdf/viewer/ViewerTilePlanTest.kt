package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTilePlanTest {
    private val plan = ViewerTilePlan(tileSize = 512, maxPagePixels = 2_000_000L)

    @Test
    fun pageImageUsesTheZoomWhileItFits() {
        assertEquals(ViewerPageImage(3, 1.8f, 1071, 1516), plan.pageImage(3, 595.0, 842.0, 1.8f))
    }

    @Test
    fun pageImageIsCappedAtTheSize() {
        val image = plan.pageImage(0, 595.0, 842.0, 4f)
        assertTrue(image.scale < 4f)
        assertTrue(image.width.toLong() * image.height <= 2_000_000L + image.width + image.height)
    }

    @Test
    fun noTilesWhileThePageImageIsSharpEnough() {
        assertTrue(plan.tiles(0, 595.0, 842.0, 1.8f, 0.0, 0.0, 595.0, 842.0).isEmpty())
    }

    @Test
    fun tilesCoverOnlyTheVisiblePart() {
        val tiles = plan.tiles(0, 595.0, 842.0, 4f, 0.0, 0.0, 270.0, 480.0)
        assertEquals(12, tiles.size)
        assertEquals(setOf(0, 512, 1024), tiles.map { it.left }.toSet())
        assertEquals(setOf(0, 512, 1024, 1536), tiles.map { it.top }.toSet())
        assertTrue(tiles.all { it.width == 512 && it.height == 512 && it.zoom == 4f && it.page == 0 })
    }

    @Test
    fun aBatchIsTheLongestRunOfTilesThatFitsOneScreenOfPixels() {
        val tiles = plan.tiles(0, 595.0, 842.0, 8f, 0.0, 0.0, 595.0, 842.0)
        val batch = plan.batch(tiles)

        assertEquals(tiles.take(batch.size), batch)
        assertTrue(area(batch) <= 2_000_000L)
        assertTrue(area(tiles.take(batch.size + 1)) > 2_000_000L)
    }

    @Test
    fun aBatchAlwaysTakesTheFirstTile() {
        val tile = ViewerTile(0, 4f, 512, 1024, 512, 512)

        assertEquals(listOf(tile), ViewerTilePlan(tileSize = 512, maxPagePixels = 1_000L).batch(listOf(tile)))
    }

    private fun area(tiles: List<ViewerTile>): Long =
        (tiles.maxOf { it.left + it.width } - tiles.minOf { it.left }).toLong() *
            (tiles.maxOf { it.top + it.height } - tiles.minOf { it.top })

    @Test
    fun edgeTilesAreCutToThePage() {
        assertEquals(
            listOf(ViewerTile(0, 4f, 1536, 3072, 512, 296), ViewerTile(0, 4f, 2048, 3072, 332, 296)),
            plan.tiles(0, 595.0, 842.0, 4f, 500.0, 800.0, 595.0, 842.0)
        )
    }
}
