package hu.reelee81.reepdf.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEditSettingsFormatTest {
    private val base = ViewerEditSettings()

    private fun roundTrip(settings: ViewerEditSettings) {
        val read = ViewerEditSettingsFormat.decode(ViewerEditSettingsFormat.encode(settings), base)

        assertTrue(read.isValid)
        assertEquals(settings, read.settings)
    }

    @Test
    fun theDefaultSettingsAreWrittenAsReadableLines() {
        assertEquals(
            "ReePDF editor settings 1\n" +
                "tool=PEN\n" +
                "drawing_tool=PEN\n" +
                "pen_color=#3D5FEC\n" +
                "pen_width=1\n" +
                "highlighter_color=#FFFF00\n" +
                "highlighter_width=24\n" +
                "underline_color=#35C369\n" +
                "strikethrough_color=#DD0000\n" +
                "underline_style=STRAIGHT\n" +
                "signature_color=#202FB0\n" +
                "signature_width=1\n" +
                "signature_slot=1\n" +
                "text_color=#000000\n" +
                "text_weight=400\n" +
                "form_mark_symbol=X\n" +
                "form_mark_color=#202FB0\n" +
                "form_mark_width=1\n",
            ViewerEditSettingsFormat.encode(base)
        )
    }

    @Test
    fun everyChoiceIsReadBackAsWritten() {
        val chosen = ViewerEditSettings(
            tool = ViewerEditTool.SIGNATURE,
            drawingTool = ViewerEditTool.SIGNATURE,
            penColor = 27,
            penWidth = 4,
            highlighterColor = 20,
            highlighterWidth = 4,
            underlineColor = 0,
            strikethroughColor = 5,
            underlineStyle = ViewerUnderlineStyle.WAVY,
            signatureColor = 3,
            signatureWidth = 4,
            signatureSlot = 1,
            textColor = 21,
            textWeight = 8,
            formSymbol = ViewerFormSymbol.DOT,
            formColor = 15,
            formWidth = 0
        )

        val text = ViewerEditSettingsFormat.encode(chosen)

        assertTrue(text.contains("signature_width=2.5\n"))
        assertTrue(text.contains("signature_slot=2\n"))
        assertTrue(text.contains("text_weight=900\n"))
        assertTrue(text.contains("form_mark_width=0.5\n"))
        roundTrip(chosen)
    }

    @Test
    fun everyPaletteColorWidthAndChoiceSurvivesTheText() {
        for (index in 0 until ViewerEditPalettes.pen.size) {
            roundTrip(
                base.copy(
                    penColor = index,
                    underlineColor = index,
                    strikethroughColor = index,
                    signatureColor = index,
                    textColor = index,
                    formColor = index
                )
            )
        }
        for (index in 0 until ViewerEditPalettes.highlighter.size) roundTrip(base.copy(highlighterColor = index))
        for (index in ViewerEditPalettes.pen.widths.indices) roundTrip(base.copy(penWidth = index))
        for (index in ViewerEditPalettes.highlighter.widths.indices) roundTrip(base.copy(highlighterWidth = index))
        for (index in ViewerEditPalettes.text.widths.indices) roundTrip(base.copy(textWeight = index))
        for (index in ViewerSignature.WIDTHS.indices) roundTrip(base.copy(signatureWidth = index, formWidth = index))
        for (slot in 0 until ViewerSignature.SLOTS) roundTrip(base.copy(signatureSlot = slot))
        for (tool in ViewerEditTool.entries) roundTrip(base.copy(tool = tool))
        for (tool in ViewerEditTool.entries.filter { it.palette != null }) roundTrip(base.copy(drawingTool = tool))
        for (style in ViewerUnderlineStyle.entries) roundTrip(base.copy(underlineStyle = style))
        for (symbol in ViewerFormSymbol.entries) roundTrip(base.copy(formSymbol = symbol))
    }

    @Test
    fun aMissingLineKeepsTheCurrentSetting() {
        val current = base.copy(penWidth = 3, textColor = 4, formSymbol = ViewerFormSymbol.CHECK)

        val read = ViewerEditSettingsFormat.decode("ReePDF editor settings 1\npen_color=#DD0000\n", current)

        assertTrue(read.isValid)
        assertEquals(current.copy(penColor = 2), read.settings)
    }

    @Test
    fun anUnknownLineIsSkipped() {
        val read = ViewerEditSettingsFormat.decode("ReePDF editor settings 1\nfuture_option=1\ntext_weight=700\n", base)

        assertTrue(read.isValid)
        assertEquals(base.copy(textWeight = 6), read.settings)
    }

    @Test
    fun aValueOutsideThePaletteIsNotLoadedButTheOthersAre() {
        val current = base.copy(highlighterColor = 3)
        val text = listOf(
            "ReePDF editor settings 1",
            "pen_color=#123456",
            "pen_width=4",
            "highlighter_color=#3D5FEC",
            "text_color=#00000",
            "text_weight=450",
            "signature_slot=3",
            "signature_width=0",
            "tool=LASSO",
            "drawing_tool=ERASER",
            "underline_style=DOTTED",
            "form_mark_symbol=CIRCLE",
            "form_mark_color=",
            "a broken line",
            "strikethrough_color=#000000"
        ).joinToString("\n")

        val read = ViewerEditSettingsFormat.decode(text, current)

        assertFalse(read.isValid)
        assertEquals(current.copy(strikethroughColor = 0), read.settings)
    }

    @Test
    fun aTextWithoutItsHeaderLoadsNothing() {
        for (text in listOf("pen_color=#DD0000\n", "ReePDF editor settings 2\npen_color=#DD0000\n", "", "\n\n")) {
            val read = ViewerEditSettingsFormat.decode(text, base)

            assertFalse(read.isValid)
            assertEquals(base, read.settings)
        }
    }

    @Test
    fun windowsLineEndsIndentationSpacesAndLowerCaseColorsAreAccepted() {
        val read = ViewerEditSettingsFormat.decode(
            "\r\n    ReePDF editor settings 1\r\n    pen_color = #dd0000 \r\n\r\n    highlighter_width=48.0\r\n",
            base
        )

        assertTrue(read.isValid)
        assertEquals(base.copy(penColor = 2, highlighterWidth = 4), read.settings)
    }

    @Test
    fun settingsOutsideTheirRangeAreWrittenAsTheirDefaults() {
        val text = ViewerEditSettingsFormat.encode(base.copy(penColor = 99, signatureSlot = 7, textWeight = -1))

        assertTrue(text.contains("pen_color=#3D5FEC\n"))
        assertTrue(text.contains("signature_slot=1\n"))
        assertTrue(text.contains("text_weight=400\n"))
    }
}
