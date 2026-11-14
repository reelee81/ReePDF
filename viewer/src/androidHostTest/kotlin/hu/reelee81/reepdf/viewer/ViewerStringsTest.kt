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
            "crop_question"
        )
    }
}
