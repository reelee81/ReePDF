package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEditToolsTest {
    private val pen = ViewerEditPalettes.pen
    private val highlighter = ViewerEditPalettes.highlighter

    @Test
    fun thePalettesHoldTheAndroidxColorsInTheirOrder() {
        assertEquals(
            listOf(
                0x000000, 0x202FB0, 0xDD0000, 0x00854C, 0xD9C300, 0xC2C44D, 0x7B4B19, 0x757575, 0x3D5FEC, 0xFF4365,
                0x35C369, 0xF9E100, 0xD7E871, 0xC48150, 0xC7C7C7, 0x9976FF, 0xFF8FEA, 0x9BEEC7, 0xFAB400, 0x5888B2,
                0xB8372F, 0xFFFFFF, 0xC6B9FF, 0xFFD4F8, 0xDEFFC9, 0xFFC8A0, 0xD1E5EE, 0xED9D82
            ),
            (0 until pen.size).map(pen::color)
        )
        assertEquals(
            listOf(
                0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFA500, 0xFFFF00, 0xFFC0CB, 0xADD8E6, 0x90EE90,
                0xFFED45, 0xFF8279, 0xA52A2A, 0x808000, 0x800080, 0x008000, 0xDC143C, 0x4682B4, 0x6A5ACD, 0x556B2F,
                0xDEB887
            ),
            (0 until highlighter.size).map(highlighter::color)
        )
    }

    @Test
    fun everyColorOfAPaletteHasItsOwnName() {
        for (palette in listOf(pen, highlighter)) {
            val names = (0 until palette.size).map { palette.name(it).key }
            assertEquals(names.size, names.toSet().size)
        }
        assertEquals("mark_color_lemon_yellow", highlighter.name(10).key)
    }

    @Test
    fun theWidthsAndDefaultsAreTheAgreedOnes() {
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 5f, 10f), pen.widths, 0f)
        assertArrayEquals(floatArrayOf(14f, 20f, 24f, 38f, 48f), highlighter.widths, 0f)
        val settings = ViewerEditSettings()
        assertEquals(ViewerEditTool.PEN, settings.tool)
        assertEquals(0x000000, settings.color(ViewerEditTool.PEN))
        assertEquals(2f, settings.width(ViewerEditTool.PEN), 0f)
        assertEquals(0xFFED45, settings.color(ViewerEditTool.HIGHLIGHTER))
        assertEquals(24f, settings.width(ViewerEditTool.HIGHLIGHTER), 0f)
        assertFalse(settings.undoPanelTop)
    }

    @Test
    fun onlyThePenTheHighlighterAndTheEraserCanBeChosen() {
        assertEquals(listOf(ViewerEditTool.PEN, ViewerEditTool.HIGHLIGHTER, ViewerEditTool.ERASER), ViewerEditTool.entries.filter { it.enabled })
        val settings = ViewerEditSettings()
        assertSame(settings, settings.withTool(ViewerEditTool.TEXT))
        assertSame(settings, settings.withTool(ViewerEditTool.SIGNATURE))
        assertSame(pen, ViewerEditTool.PEN.palette)
        assertSame(highlighter, ViewerEditTool.HIGHLIGHTER.palette)
        assertNull(ViewerEditTool.ERASER.palette)
    }

    @Test
    fun theEraserShowsAndKeepsTheLastDrawingToolsColor() {
        val settings = ViewerEditSettings()
            .withTool(ViewerEditTool.HIGHLIGHTER)
            .withColor(ViewerEditTool.HIGHLIGHTER, 3)
            .withTool(ViewerEditTool.ERASER)

        assertEquals(ViewerEditTool.ERASER, settings.tool)
        assertEquals(ViewerEditTool.HIGHLIGHTER, settings.swatchTool)
        assertEquals(0x00FF00, settings.color(ViewerEditTool.ERASER))
        assertEquals(3, settings.colorIndex(ViewerEditTool.ERASER))
        assertEquals(2, settings.withWidth(ViewerEditTool.ERASER, 2).widthIndex(ViewerEditTool.HIGHLIGHTER))
        assertEquals(ViewerEditTool.PEN, settings.withTool(ViewerEditTool.PEN).swatchTool)
    }

    @Test
    fun eachDrawingToolKeepsItsOwnColorAndWidth() {
        val settings = ViewerEditSettings()
            .withColor(ViewerEditTool.PEN, 2)
            .withWidth(ViewerEditTool.PEN, 4)
            .withColor(ViewerEditTool.HIGHLIGHTER, 0)
            .withWidth(ViewerEditTool.HIGHLIGHTER, 0)

        assertEquals(0xDD0000, settings.color(ViewerEditTool.PEN))
        assertEquals(10f, settings.width(ViewerEditTool.PEN), 0f)
        assertEquals(0x000000, settings.color(ViewerEditTool.HIGHLIGHTER))
        assertEquals(14f, settings.width(ViewerEditTool.HIGHLIGHTER), 0f)
        assertSame(settings, settings.withColor(ViewerEditTool.PEN, 28))
        assertSame(settings, settings.withWidth(ViewerEditTool.HIGHLIGHTER, -1))
    }

    @Test
    fun brokenStoredSettingsFallBackToTheDefaults() {
        val broken = ViewerEditSettings(
            tool = ViewerEditTool.TEXT,
            drawingTool = ViewerEditTool.ERASER,
            penColor = 99,
            penWidth = -1,
            highlighterColor = 21,
            highlighterWidth = 5,
            undoPanelTop = true
        )

        val fixed = broken.sanitized()

        assertEquals(ViewerEditSettings(undoPanelTop = true), fixed)
        assertTrue(ViewerEditSettings().sanitized() == ViewerEditSettings())
    }
}
