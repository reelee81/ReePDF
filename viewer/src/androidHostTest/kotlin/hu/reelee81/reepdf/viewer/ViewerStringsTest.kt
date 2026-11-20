package hu.reelee81.reepdf.viewer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ViewerStringsTest {
    @Test
    fun englishStringsMatchTheApp() = assertMatchesApp("values")

    @Test
    fun hungarianStringsMatchTheApp() = assertMatchesApp("values-hu")

    @Test
    fun viewerOnlyStringsAreTranslatedAndNotInTheApp() {
        for (folder in listOf("values", "values-hu")) {
            val viewer = viewerStrings(folder)
            val app = appStrings(folder)
            assertEquals(VIEWER_ONLY, VIEWER_ONLY.filter { it in viewer }.toSet())
            assertEquals(emptySet<String>(), VIEWER_ONLY.filter { it in app }.toSet())
        }
    }

    private fun assertMatchesApp(folder: String) {
        val viewer = viewerStrings(folder) - VIEWER_ONLY
        val app = appStrings(folder)
        assertTrue(viewer.isNotEmpty())
        assertEquals(viewer.keys.associateWith { app[it]?.let(::asComposeText) }, viewer)
    }

    private fun asComposeText(androidText: String): String =
        if (FORMAT_ARGUMENT.containsMatchIn(androidText)) androidText.replace("%%", "%") else androidText

    private fun viewerStrings(folder: String): Map<String, String> =
        strings(File("src/commonMain/composeResources/$folder/strings.xml"))

    private fun appStrings(folder: String): Map<String, String> =
        strings(File("../app/src/main/res/$folder/strings.xml"))

    private fun strings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private companion object {
        val FORMAT_ARGUMENT = Regex("""%\d+\$[ds]""")
        val VIEWER_ONLY = setOf(
            "edit_scroll",
            "edit_pdf",
            "discard_text",
            "back_to_main_screen",
            "no_pdf_edits_to_save",
            "pdf_edits_erase_failed",
            "viewer_back",
            "fast_scroller_thumb",
            "page_indicator_single",
            "page_indicator_range",
            "page_render_failed",
            "viewer_search",
            "search_match_count",
            "search_no_match",
            "search_previous",
            "search_next",
            "selection_copy",
            "selection_select_all",
            "selection_too_large",
            "page_description_text",
            "page_description",
            "signature_position",
            "viewer_save",
            "signature_position_question",
            "save_text",
            "crop_title",
            "crop_all_pages",
            "crop_reset",
            "crop_question",
            "edit_color_palette",
            "edit_text",
            "edit_underline",
            "edit_strikethrough",
            "edit_underline_straight",
            "edit_underline_wavy",
            "edit_pen",
            "edit_highlighter",
            "edit_vector_signature",
            "edit_eraser",
            "edit_erase_visible",
            "edit_erase_all",
            "edit_erase_all_question",
            "edit_undo",
            "edit_redo",
            "edit_line_width",
            "edit_question",
            "mark_color_black",
            "mark_color_white",
            "mark_color_red",
            "mark_color_dark_blue",
            "mark_color_dark_green",
            "mark_color_mustard",
            "mark_color_light_olive",
            "mark_color_brown",
            "mark_color_gray",
            "mark_color_blue",
            "mark_color_rose_red",
            "mark_color_green",
            "mark_color_yellow",
            "mark_color_pale_lime",
            "mark_color_light_brown",
            "mark_color_light_gray",
            "mark_color_purple",
            "mark_color_pink",
            "mark_color_mint",
            "mark_color_amber",
            "mark_color_steel_blue",
            "mark_color_brick_red",
            "mark_color_lavender",
            "mark_color_pale_pink",
            "mark_color_pale_green",
            "mark_color_peach",
            "mark_color_pale_blue",
            "mark_color_salmon",
            "mark_color_bright_green",
            "mark_color_orange",
            "mark_color_light_blue",
            "mark_color_light_green",
            "mark_color_lemon_yellow",
            "mark_color_coral",
            "mark_color_olive",
            "mark_color_crimson",
            "mark_color_slate_blue",
            "mark_color_dark_olive_green",
            "mark_color_tan"
        )
    }
}
