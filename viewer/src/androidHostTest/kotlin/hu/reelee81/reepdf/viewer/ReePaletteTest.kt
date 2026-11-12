package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class ReePaletteTest {
    @Test
    fun lightPaletteMatchesTheAppColors() {
        assertEquals(appColors("values"), hex(ReePalette.light))
    }

    @Test
    fun darkPaletteMatchesTheAppNightColors() {
        assertEquals(appColors("values-night"), hex(ReePalette.dark))
    }

    private fun appColors(folder: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("../app/src/main/res/$folder/colors.xml"))
            .getElementsByTagName("color")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim().uppercase()
        }
    }

    private fun hex(palette: ReePalette): Map<String, String> =
        named(palette).mapValues { (_, color) -> "#%08X".format(color.toArgb()) }

    private fun named(p: ReePalette): Map<String, Color> = mapOf(
        "black" to p.black,
        "white" to p.white,
        "transparent" to p.transparent,
        "app_background_color" to p.appBackgroundColor,
        "bitmap_tv_background_color" to p.bitmapTvBackgroundColor,
        "dialog_overlay_background_color" to p.dialogOverlayBackgroundColor,
        "love_50" to p.love50,
        "crop_frame_color" to p.cropFrameColor,
        "crop_shade_color" to p.cropShadeColor,
        "crop_grid_color" to p.cropGridColor,
        "gummy_150" to p.gummy150,
        "gummy_125" to p.gummy125,
        "gummy_100" to p.gummy100,
        "gummy_075" to p.gummy075,
        "bubble_125" to p.bubble125,
        "bubble_100" to p.bubble100,
        "bubble_050" to p.bubble050,
        "teal_150" to p.teal150,
        "teal_100" to p.teal100,
        "teal_050" to p.teal050,
        "teal_025" to p.teal025,
        "lemon_100" to p.lemon100,
        "thumb_dark_light_075" to p.thumbDarkLight075,
        "thumb_dark_light_050" to p.thumbDarkLight050,
        "dark_light_100" to p.darkLight100,
        "dark_light_095" to p.darkLight095,
        "dark_light_090" to p.darkLight090,
        "dark_light_080" to p.darkLight080,
        "dark_light_075" to p.darkLight075,
        "dark_light_065" to p.darkLight065,
        "dark_light_050" to p.darkLight050,
        "dark_light_038" to p.darkLight038,
        "dark_light_035" to p.darkLight035,
        "dark_light_025" to p.darkLight025,
        "dark_light_020" to p.darkLight020,
        "dark_light_010" to p.darkLight010,
        "dark_light_005" to p.darkLight005,
        "dark_light_000" to p.darkLight000
    )
}
