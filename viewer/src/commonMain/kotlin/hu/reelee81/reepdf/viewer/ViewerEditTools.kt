package hu.reelee81.reepdf.viewer

import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.mark_color_amber
import hu.reelee81.reepdf.viewer.resources.mark_color_black
import hu.reelee81.reepdf.viewer.resources.mark_color_blue
import hu.reelee81.reepdf.viewer.resources.mark_color_brick_red
import hu.reelee81.reepdf.viewer.resources.mark_color_bright_green
import hu.reelee81.reepdf.viewer.resources.mark_color_brown
import hu.reelee81.reepdf.viewer.resources.mark_color_coral
import hu.reelee81.reepdf.viewer.resources.mark_color_crimson
import hu.reelee81.reepdf.viewer.resources.mark_color_dark_blue
import hu.reelee81.reepdf.viewer.resources.mark_color_dark_green
import hu.reelee81.reepdf.viewer.resources.mark_color_dark_olive_green
import hu.reelee81.reepdf.viewer.resources.mark_color_gray
import hu.reelee81.reepdf.viewer.resources.mark_color_green
import hu.reelee81.reepdf.viewer.resources.mark_color_lavender
import hu.reelee81.reepdf.viewer.resources.mark_color_lemon_yellow
import hu.reelee81.reepdf.viewer.resources.mark_color_light_blue
import hu.reelee81.reepdf.viewer.resources.mark_color_light_brown
import hu.reelee81.reepdf.viewer.resources.mark_color_light_gray
import hu.reelee81.reepdf.viewer.resources.mark_color_light_green
import hu.reelee81.reepdf.viewer.resources.mark_color_light_olive
import hu.reelee81.reepdf.viewer.resources.mark_color_mint
import hu.reelee81.reepdf.viewer.resources.mark_color_mustard
import hu.reelee81.reepdf.viewer.resources.mark_color_olive
import hu.reelee81.reepdf.viewer.resources.mark_color_orange
import hu.reelee81.reepdf.viewer.resources.mark_color_pale_blue
import hu.reelee81.reepdf.viewer.resources.mark_color_pale_green
import hu.reelee81.reepdf.viewer.resources.mark_color_pale_lime
import hu.reelee81.reepdf.viewer.resources.mark_color_pale_pink
import hu.reelee81.reepdf.viewer.resources.mark_color_peach
import hu.reelee81.reepdf.viewer.resources.mark_color_pink
import hu.reelee81.reepdf.viewer.resources.mark_color_purple
import hu.reelee81.reepdf.viewer.resources.mark_color_red
import hu.reelee81.reepdf.viewer.resources.mark_color_rose_red
import hu.reelee81.reepdf.viewer.resources.mark_color_salmon
import hu.reelee81.reepdf.viewer.resources.mark_color_slate_blue
import hu.reelee81.reepdf.viewer.resources.mark_color_steel_blue
import hu.reelee81.reepdf.viewer.resources.mark_color_tan
import hu.reelee81.reepdf.viewer.resources.mark_color_white
import hu.reelee81.reepdf.viewer.resources.mark_color_yellow
import org.jetbrains.compose.resources.StringResource

class ViewerEditPalette internal constructor(
    private val entries: List<Pair<Int, StringResource>>,
    val widths: FloatArray,
    val defaultColor: Int,
    val defaultWidth: Int
) {
    val size: Int
        get() = entries.size

    fun color(index: Int): Int = entries[index].first

    fun name(index: Int): StringResource = entries[index].second
}

object ViewerEditPalettes {
    val pen = ViewerEditPalette(
        listOf(
            0x000000 to Res.string.mark_color_black,
            0x202FB0 to Res.string.mark_color_dark_blue,
            0xDD0000 to Res.string.mark_color_red,
            0x00854C to Res.string.mark_color_dark_green,
            0xD9C300 to Res.string.mark_color_mustard,
            0xC2C44D to Res.string.mark_color_light_olive,
            0x7B4B19 to Res.string.mark_color_brown,
            0x757575 to Res.string.mark_color_gray,
            0x3D5FEC to Res.string.mark_color_blue,
            0xFF4365 to Res.string.mark_color_rose_red,
            0x35C369 to Res.string.mark_color_green,
            0xF9E100 to Res.string.mark_color_yellow,
            0xD7E871 to Res.string.mark_color_pale_lime,
            0xC48150 to Res.string.mark_color_light_brown,
            0xC7C7C7 to Res.string.mark_color_light_gray,
            0x9976FF to Res.string.mark_color_purple,
            0xFF8FEA to Res.string.mark_color_pink,
            0x9BEEC7 to Res.string.mark_color_mint,
            0xFAB400 to Res.string.mark_color_amber,
            0x5888B2 to Res.string.mark_color_steel_blue,
            0xB8372F to Res.string.mark_color_brick_red,
            0xFFFFFF to Res.string.mark_color_white,
            0xC6B9FF to Res.string.mark_color_lavender,
            0xFFD4F8 to Res.string.mark_color_pale_pink,
            0xDEFFC9 to Res.string.mark_color_pale_green,
            0xFFC8A0 to Res.string.mark_color_peach,
            0xD1E5EE to Res.string.mark_color_pale_blue,
            0xED9D82 to Res.string.mark_color_salmon
        ),
        floatArrayOf(1f, 2f, 3f, 5f, 10f),
        defaultColor = 0,
        defaultWidth = 1
    )

    val highlighter = ViewerEditPalette(
        listOf(
            0x000000 to Res.string.mark_color_black,
            0xFFFFFF to Res.string.mark_color_white,
            0xFF0000 to Res.string.mark_color_red,
            0x00FF00 to Res.string.mark_color_bright_green,
            0x0000FF to Res.string.mark_color_blue,
            0xFFA500 to Res.string.mark_color_orange,
            0xFFFF00 to Res.string.mark_color_yellow,
            0xFFC0CB to Res.string.mark_color_pink,
            0xADD8E6 to Res.string.mark_color_light_blue,
            0x90EE90 to Res.string.mark_color_light_green,
            0xFFED45 to Res.string.mark_color_lemon_yellow,
            0xFF8279 to Res.string.mark_color_coral,
            0xA52A2A to Res.string.mark_color_brown,
            0x808000 to Res.string.mark_color_olive,
            0x800080 to Res.string.mark_color_purple,
            0x008000 to Res.string.mark_color_green,
            0xDC143C to Res.string.mark_color_crimson,
            0x4682B4 to Res.string.mark_color_steel_blue,
            0x6A5ACD to Res.string.mark_color_slate_blue,
            0x556B2F to Res.string.mark_color_dark_olive_green,
            0xDEB887 to Res.string.mark_color_tan
        ),
        floatArrayOf(14f, 20f, 24f, 38f, 48f),
        defaultColor = 10,
        defaultWidth = 2
    )
}

enum class ViewerEditTool(val enabled: Boolean) {
    TEXT(false),
    UNDERLINE(false),
    STRIKETHROUGH(false),
    PEN(true),
    HIGHLIGHTER(true),
    SIGNATURE(false),
    ERASER(true);

    val palette: ViewerEditPalette?
        get() = when (this) {
            PEN -> ViewerEditPalettes.pen
            HIGHLIGHTER -> ViewerEditPalettes.highlighter
            else -> null
        }
}

data class ViewerEditSettings(
    val tool: ViewerEditTool = ViewerEditTool.PEN,
    val drawingTool: ViewerEditTool = ViewerEditTool.PEN,
    val penColor: Int = ViewerEditPalettes.pen.defaultColor,
    val penWidth: Int = ViewerEditPalettes.pen.defaultWidth,
    val highlighterColor: Int = ViewerEditPalettes.highlighter.defaultColor,
    val highlighterWidth: Int = ViewerEditPalettes.highlighter.defaultWidth,
    val undoPanelTop: Boolean = false
) {
    val swatchTool: ViewerEditTool
        get() = if (tool.palette != null) tool else drawingTool

    fun colorIndex(tool: ViewerEditTool): Int = if (drawing(tool) == ViewerEditTool.HIGHLIGHTER) highlighterColor else penColor

    fun widthIndex(tool: ViewerEditTool): Int = if (drawing(tool) == ViewerEditTool.HIGHLIGHTER) highlighterWidth else penWidth

    fun color(tool: ViewerEditTool): Int = paletteOf(tool).color(colorIndex(tool))

    fun width(tool: ViewerEditTool): Float = paletteOf(tool).widths[widthIndex(tool)]

    fun withTool(chosen: ViewerEditTool): ViewerEditSettings {
        if (!chosen.enabled) return this
        return copy(tool = chosen, drawingTool = if (chosen.palette != null) chosen else drawingTool)
    }

    fun withColor(tool: ViewerEditTool, index: Int): ViewerEditSettings {
        if (index !in 0 until paletteOf(tool).size) return this
        return if (drawing(tool) == ViewerEditTool.HIGHLIGHTER) copy(highlighterColor = index) else copy(penColor = index)
    }

    fun withWidth(tool: ViewerEditTool, index: Int): ViewerEditSettings {
        if (index !in paletteOf(tool).widths.indices) return this
        return if (drawing(tool) == ViewerEditTool.HIGHLIGHTER) copy(highlighterWidth = index) else copy(penWidth = index)
    }

    fun sanitized(): ViewerEditSettings {
        val pen = ViewerEditPalettes.pen
        val highlighter = ViewerEditPalettes.highlighter
        return copy(
            tool = tool.takeIf { it.enabled } ?: ViewerEditTool.PEN,
            drawingTool = drawingTool.takeIf { it.palette != null } ?: ViewerEditTool.PEN,
            penColor = penColor.takeIf { it in 0 until pen.size } ?: pen.defaultColor,
            penWidth = penWidth.takeIf { it in pen.widths.indices } ?: pen.defaultWidth,
            highlighterColor = highlighterColor.takeIf { it in 0 until highlighter.size } ?: highlighter.defaultColor,
            highlighterWidth = highlighterWidth.takeIf { it in highlighter.widths.indices } ?: highlighter.defaultWidth
        )
    }

    private fun drawing(tool: ViewerEditTool): ViewerEditTool =
        if (tool.palette != null) tool else drawingTool.takeIf { it.palette != null } ?: ViewerEditTool.PEN

    private fun paletteOf(tool: ViewerEditTool): ViewerEditPalette = drawing(tool).palette ?: ViewerEditPalettes.pen
}
