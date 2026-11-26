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
    private val underline = ViewerEditPalettes.underline
    private val strikethrough = ViewerEditPalettes.strikethrough

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
        for (palette in listOf(pen, highlighter, underline, strikethrough)) {
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
        assertEquals(0x3D5FEC, settings.color(ViewerEditTool.PEN))
        assertEquals(1f, settings.width(ViewerEditTool.PEN), 0f)
        assertEquals(0xFFFF00, settings.color(ViewerEditTool.HIGHLIGHTER))
        assertEquals(24f, settings.width(ViewerEditTool.HIGHLIGHTER), 0f)
        assertFalse(settings.undoPanelTop)
        assertEquals(ViewerUnderlineStyle.STRAIGHT, settings.underlineStyle)
    }

    @Test
    fun everyToolCanBeChosenAndTheTextToolRemembersItsOwnColorAndWeight() {
        assertEquals(ViewerEditTool.entries, ViewerEditTool.entries.filter { it.enabled })
        val settings = ViewerEditSettings()
        val text = settings.withTool(ViewerEditTool.TEXT)
        assertEquals(ViewerEditTool.TEXT, text.tool)
        assertEquals(ViewerEditTool.TEXT, text.drawingTool)
        assertEquals(0x000000, text.color(ViewerEditTool.TEXT))
        assertEquals(400f, text.width(ViewerEditTool.TEXT), 0f)
        assertEquals(ViewerEditPalettes.pen.size, ViewerEditPalettes.text.size)
        val chosen = text.withColor(ViewerEditTool.TEXT, 2).withWidth(ViewerEditTool.TEXT, 6)
        assertEquals(0xDD0000, chosen.color(ViewerEditTool.TEXT))
        assertEquals(700, chosen.weight)
        assertEquals(settings.penColor, chosen.penColor)
        assertEquals(settings.penWidth, chosen.penWidth)
        assertSame(chosen, chosen.withWidth(ViewerEditTool.TEXT, 9))
        assertSame(ViewerEditPalettes.signature, ViewerEditTool.SIGNATURE.palette)
        assertSame(underline, ViewerEditTool.UNDERLINE.palette)
        assertSame(strikethrough, ViewerEditTool.STRIKETHROUGH.palette)
        assertSame(pen, ViewerEditTool.PEN.palette)
        assertSame(highlighter, ViewerEditTool.HIGHLIGHTER.palette)
        assertNull(ViewerEditTool.ERASER.palette)
        assertEquals(
            listOf(ViewerEditTool.UNDERLINE, ViewerEditTool.STRIKETHROUGH, ViewerEditTool.PEN, ViewerEditTool.HIGHLIGHTER),
            ViewerEditTool.entries.filter { it.draws }
        )
        assertEquals(listOf(ViewerEditTool.UNDERLINE, ViewerEditTool.STRIKETHROUGH), ViewerEditTool.entries.filter { it.onlyOnText })
    }

    @Test
    fun formMarksStartAsAThinXWithTheSignatureColorAndWidthsAndThePenColors() {
        val settings = ViewerEditSettings().withTool(ViewerEditTool.FORM_MARK)

        assertSame(ViewerEditPalettes.formMark, ViewerEditTool.FORM_MARK.palette)
        assertEquals((0 until pen.size).map(pen::color), (0 until ViewerEditPalettes.formMark.size).map(ViewerEditPalettes.formMark::color))
        assertArrayEquals(ViewerSignature.WIDTHS, ViewerEditPalettes.formMark.widths, 0f)
        assertEquals(ViewerSignature.DEFAULT_WIDTH, ViewerEditPalettes.formMark.defaultWidth)
        assertEquals(ViewerEditTool.FORM_MARK, settings.swatchTool)
        assertEquals(ViewerFormSymbol.X, settings.formSymbol)
        assertEquals(ViewerEditPalettes.signature.defaultColor, ViewerEditPalettes.formMark.defaultColor)
        assertEquals(0x202FB0, settings.color(ViewerEditTool.FORM_MARK))
        assertEquals(1f, settings.width(ViewerEditTool.FORM_MARK), 0f)
        val chosen = settings.withColor(ViewerEditTool.FORM_MARK, 2).withWidth(ViewerEditTool.FORM_MARK, 3)
        assertEquals(0xDD0000, chosen.color(ViewerEditTool.FORM_MARK))
        assertEquals(2f, chosen.width(ViewerEditTool.FORM_MARK), 0f)
        assertEquals(settings.penColor, chosen.penColor)
        assertEquals(settings.penWidth, chosen.penWidth)
        val broken = ViewerEditSettings(formColor = 99, formWidth = 7).sanitized()
        assertEquals(ViewerEditPalettes.formMark.defaultColor, broken.formColor)
        assertEquals(ViewerEditPalettes.formMark.defaultWidth, broken.formWidth)
    }

    @Test
    fun theTextToolOffersAllNineRobotoWeightsAndStartsAtRegular() {
        assertArrayEquals(floatArrayOf(100f, 200f, 300f, 400f, 500f, 600f, 700f, 800f, 900f), ViewerEditPalettes.text.widths, 0f)
        assertEquals(3, ViewerEditPalettes.text.defaultWidth)
        assertEquals(400, ViewerEditSettings().weight)
        assertEquals(400, ViewerEditSettings(textWeight = 9).sanitized().weight)
    }

    @Test
    fun theTextLineToolsUseThePenColorsWithoutWidths() {
        for (palette in listOf(underline, strikethrough)) {
            assertEquals((0 until pen.size).map(pen::color), (0 until palette.size).map(palette::color))
            assertEquals((0 until pen.size).map { pen.name(it) }, (0 until palette.size).map { palette.name(it) })
            assertFalse(palette.hasWidths)
        }
        assertTrue(pen.hasWidths)
        assertTrue(highlighter.hasWidths)
        val settings = ViewerEditSettings()
        assertEquals(0x35C369, settings.color(ViewerEditTool.UNDERLINE))
        assertEquals(0xDD0000, settings.color(ViewerEditTool.STRIKETHROUGH))
        assertEquals(0f, settings.width(ViewerEditTool.UNDERLINE), 0f)
        assertEquals(0f, settings.width(ViewerEditTool.STRIKETHROUGH), 0f)
        assertSame(settings, settings.withWidth(ViewerEditTool.UNDERLINE, 0))
        assertEquals(ViewerEditTool.UNDERLINE, settings.withTool(ViewerEditTool.UNDERLINE).drawingTool)
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
            .withColor(ViewerEditTool.UNDERLINE, 1)
            .withColor(ViewerEditTool.STRIKETHROUGH, 27)

        assertEquals(0x202FB0, settings.color(ViewerEditTool.UNDERLINE))
        assertEquals(0xED9D82, settings.color(ViewerEditTool.STRIKETHROUGH))
        assertEquals(0xDD0000, settings.color(ViewerEditTool.PEN))
        assertEquals(10f, settings.width(ViewerEditTool.PEN), 0f)
        assertEquals(0x000000, settings.color(ViewerEditTool.HIGHLIGHTER))
        assertEquals(14f, settings.width(ViewerEditTool.HIGHLIGHTER), 0f)
        assertSame(settings, settings.withColor(ViewerEditTool.PEN, 28))
        assertSame(settings, settings.withColor(ViewerEditTool.UNDERLINE, 28))
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
            underlineColor = 28,
            strikethroughColor = -1,
            undoPanelTop = true,
            textColor = 28,
            textWeight = 9
        )

        val fixed = broken.sanitized()

        assertEquals(ViewerEditSettings(tool = ViewerEditTool.TEXT, undoPanelTop = true), fixed)
        assertTrue(ViewerEditSettings().sanitized() == ViewerEditSettings())
    }

    @Test
    fun theSignatureHasThePenColorsItsOwnRememberedColorAndNoWidthPanel() {
        val signature = ViewerEditPalettes.signature

        assertEquals((0 until pen.size).map(pen::color), (0 until signature.size).map(signature::color))
        assertEquals(0x202FB0, signature.color(signature.defaultColor))
        assertFalse(signature.hasWidths)
        val chosen = ViewerEditSettings().withTool(ViewerEditTool.SIGNATURE).withColor(ViewerEditTool.SIGNATURE, 2)
        assertEquals(ViewerEditTool.SIGNATURE, chosen.drawingTool)
        assertEquals(2, chosen.signatureColor)
        assertEquals(ViewerEditPalettes.pen.defaultColor, chosen.penColor)
        assertEquals(0xDD0000, chosen.color(ViewerEditTool.SIGNATURE))
    }

    @Test
    fun brokenSignatureSettingsFallBackToTheirDefaults() {
        val sanitized = ViewerEditSettings(signatureColor = 99, signatureWidth = 7, signatureSlot = 2).sanitized()

        assertEquals(ViewerEditPalettes.signature.defaultColor, sanitized.signatureColor)
        assertEquals(ViewerSignature.DEFAULT_WIDTH, sanitized.signatureWidth)
        assertEquals(0, sanitized.signatureSlot)
    }
}
