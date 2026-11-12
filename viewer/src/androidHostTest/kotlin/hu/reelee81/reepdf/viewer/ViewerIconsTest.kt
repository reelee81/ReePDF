package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.vector.addPathNodes
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ViewerIconsTest {
    @Test
    fun arrowBackMatchesTheAppIcon() = assertMatchesApp("ic_arrow_back_24", ViewerIcons.ARROW_BACK_PATH)

    @Test
    fun editMatchesTheAppIcon() = assertMatchesApp("ic_edit_24", ViewerIcons.EDIT_PATH)

    @Test
    fun searchMatchesItsDesign() = assertMatchesDesign("ic_search_24", ViewerIcons.SEARCH_PATH, ViewerIcons.VIEWPORT)

    @Test
    fun previousMatchesItsDesign() = assertMatchesDesign("ic_previous_24", ViewerIcons.PREVIOUS_PATH, ViewerIcons.STEP_VIEWPORT)

    @Test
    fun nextMatchesItsDesign() = assertMatchesDesign("ic_next_24", ViewerIcons.NEXT_PATH, ViewerIcons.STEP_VIEWPORT)

    @Test
    fun everyPathParses() {
        val paths = listOf(
            ViewerIcons.ARROW_BACK_PATH,
            ViewerIcons.EDIT_PATH,
            ViewerIcons.FAST_SCROLL_PATH,
            ViewerIcons.SEARCH_PATH,
            ViewerIcons.PREVIOUS_PATH,
            ViewerIcons.NEXT_PATH
        )
        for (path in paths) {
            assertTrue(addPathNodes(path).isNotEmpty())
        }
    }

    private fun assertMatchesApp(name: String, path: String) =
        assertMatches(File("../app/src/main/res/drawable/$name.xml"), path, ViewerIcons.VIEWPORT)

    private fun assertMatchesDesign(name: String, path: String, viewport: Float) =
        assertMatches(File("src/androidHostTest/resources/icons/$name.xml"), path, viewport)

    private fun assertMatches(file: File, path: String, viewport: Float) {
        val vector = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file)
            .documentElement
        val paths = vector.getElementsByTagName("path")
        assertEquals(
            listOf("24dp", "24dp", "${viewport.toInt()}", "${viewport.toInt()}", "1", path),
            listOf(
                vector.getAttribute("android:width"),
                vector.getAttribute("android:height"),
                vector.getAttribute("android:viewportWidth"),
                vector.getAttribute("android:viewportHeight"),
                paths.length.toString(),
                (paths.item(0) as Element).getAttribute("android:pathData")
            )
        )
    }
}
