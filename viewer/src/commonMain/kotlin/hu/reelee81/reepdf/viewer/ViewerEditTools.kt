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

    val hasWidths: Boolean
        get() = widths.isNotEmpty()

    fun color(index: Int): Int = entries[index].first

    fun hex(index: Int): String = "#" + color(index).toString(16).uppercase().padStart(6, '0')

    fun name(index: Int): StringResource = entries[index].second
}

object ViewerEditPalettes {
    private val penColors = listOf(
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
    )

    val pen = ViewerEditPalette(penColors, floatArrayOf(1f, 2f, 3f, 5f, 10f), defaultColor = 8, defaultWidth = 0)

    val underline = ViewerEditPalette(penColors, FloatArray(0), defaultColor = 10, defaultWidth = 0)

    val strikethrough = ViewerEditPalette(penColors, FloatArray(0), defaultColor = 2, defaultWidth = 0)

    val signature = ViewerEditPalette(penColors, FloatArray(0), defaultColor = 1, defaultWidth = 0)

    val formMark = ViewerEditPalette(penColors, ViewerSignature.WIDTHS, defaultColor = signature.defaultColor, defaultWidth = ViewerSignature.DEFAULT_WIDTH)

    val text = ViewerEditPalette(
        penColors,
        ViewerMarkText.WEIGHTS.map { it.toFloat() }.toFloatArray(),
        defaultColor = 0,
        defaultWidth = ViewerMarkText.WEIGHTS.indexOf(ViewerMarkText.DEFAULT_WEIGHT)
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
        defaultColor = 6,
        defaultWidth = 2
    )
}

enum class ViewerEditTool(val enabled: Boolean) {
    TEXT(true),
    UNDERLINE(true),
    STRIKETHROUGH(true),
    PEN(true),
    HIGHLIGHTER(true),
    FORM_MARK(true),
    SIGNATURE(true),
    ERASER(true);

    val palette: ViewerEditPalette?
        get() = when (this) {
            UNDERLINE -> ViewerEditPalettes.underline
            STRIKETHROUGH -> ViewerEditPalettes.strikethrough
            PEN -> ViewerEditPalettes.pen
            HIGHLIGHTER -> ViewerEditPalettes.highlighter
            SIGNATURE -> ViewerEditPalettes.signature
            TEXT -> ViewerEditPalettes.text
            FORM_MARK -> ViewerEditPalettes.formMark
            else -> null
        }

    val draws: Boolean
        get() = this == UNDERLINE || this == STRIKETHROUGH || this == PEN || this == HIGHLIGHTER

    val onlyOnText: Boolean
        get() = this == UNDERLINE || this == STRIKETHROUGH
}

enum class ViewerUnderlineStyle { STRAIGHT, WAVY }

data class ViewerEditSettings(
    val tool: ViewerEditTool = ViewerEditTool.PEN,
    val drawingTool: ViewerEditTool = ViewerEditTool.PEN,
    val penColor: Int = ViewerEditPalettes.pen.defaultColor,
    val penWidth: Int = ViewerEditPalettes.pen.defaultWidth,
    val highlighterColor: Int = ViewerEditPalettes.highlighter.defaultColor,
    val highlighterWidth: Int = ViewerEditPalettes.highlighter.defaultWidth,
    val underlineColor: Int = ViewerEditPalettes.underline.defaultColor,
    val strikethroughColor: Int = ViewerEditPalettes.strikethrough.defaultColor,
    val underlineStyle: ViewerUnderlineStyle = ViewerUnderlineStyle.STRAIGHT,
    val signatureColor: Int = ViewerEditPalettes.signature.defaultColor,
    val signatureWidth: Int = ViewerSignature.DEFAULT_WIDTH,
    val signatureSlot: Int = 0,
    val textColor: Int = ViewerEditPalettes.text.defaultColor,
    val textWeight: Int = ViewerEditPalettes.text.defaultWidth,
    val formSymbol: ViewerFormSymbol = ViewerFormSymbol.X,
    val formColor: Int = ViewerEditPalettes.formMark.defaultColor,
    val formWidth: Int = ViewerEditPalettes.formMark.defaultWidth
) {
    val swatchTool: ViewerEditTool
        get() = if (tool.palette != null) tool else drawingTool

    fun colorIndex(tool: ViewerEditTool): Int = when (drawing(tool)) {
        ViewerEditTool.HIGHLIGHTER -> highlighterColor
        ViewerEditTool.UNDERLINE -> underlineColor
        ViewerEditTool.STRIKETHROUGH -> strikethroughColor
        ViewerEditTool.SIGNATURE -> signatureColor
        ViewerEditTool.TEXT -> textColor
        ViewerEditTool.FORM_MARK -> formColor
        else -> penColor
    }

    fun widthIndex(tool: ViewerEditTool): Int = when (drawing(tool)) {
        ViewerEditTool.HIGHLIGHTER -> highlighterWidth
        ViewerEditTool.PEN -> penWidth
        ViewerEditTool.TEXT -> textWeight
        ViewerEditTool.FORM_MARK -> formWidth
        else -> 0
    }

    val weight: Int
        get() = ViewerMarkText.WEIGHTS.getOrElse(textWeight) { ViewerMarkText.DEFAULT_WEIGHT }

    fun color(tool: ViewerEditTool): Int = paletteOf(tool).color(colorIndex(tool))

    fun width(tool: ViewerEditTool): Float = paletteOf(tool).widths.getOrElse(widthIndex(tool)) { 0f }

    fun withTool(chosen: ViewerEditTool): ViewerEditSettings {
        if (!chosen.enabled) return this
        return copy(tool = chosen, drawingTool = if (chosen.palette != null) chosen else drawingTool)
    }

    fun withColor(tool: ViewerEditTool, index: Int): ViewerEditSettings {
        if (index !in 0 until paletteOf(tool).size) return this
        return when (drawing(tool)) {
            ViewerEditTool.HIGHLIGHTER -> copy(highlighterColor = index)
            ViewerEditTool.UNDERLINE -> copy(underlineColor = index)
            ViewerEditTool.STRIKETHROUGH -> copy(strikethroughColor = index)
            ViewerEditTool.SIGNATURE -> copy(signatureColor = index)
            ViewerEditTool.TEXT -> copy(textColor = index)
            ViewerEditTool.FORM_MARK -> copy(formColor = index)
            else -> copy(penColor = index)
        }
    }

    fun withWidth(tool: ViewerEditTool, index: Int): ViewerEditSettings {
        if (index !in paletteOf(tool).widths.indices) return this
        return when (drawing(tool)) {
            ViewerEditTool.HIGHLIGHTER -> copy(highlighterWidth = index)
            ViewerEditTool.TEXT -> copy(textWeight = index)
            ViewerEditTool.FORM_MARK -> copy(formWidth = index)
            else -> copy(penWidth = index)
        }
    }

    fun sanitized(): ViewerEditSettings {
        val pen = ViewerEditPalettes.pen
        val highlighter = ViewerEditPalettes.highlighter
        val underline = ViewerEditPalettes.underline
        val strikethrough = ViewerEditPalettes.strikethrough
        val signature = ViewerEditPalettes.signature
        val text = ViewerEditPalettes.text
        val formMark = ViewerEditPalettes.formMark
        return copy(
            tool = tool.takeIf { it.enabled } ?: ViewerEditTool.PEN,
            drawingTool = drawingTool.takeIf { it.palette != null } ?: ViewerEditTool.PEN,
            penColor = penColor.takeIf { it in 0 until pen.size } ?: pen.defaultColor,
            penWidth = penWidth.takeIf { it in pen.widths.indices } ?: pen.defaultWidth,
            highlighterColor = highlighterColor.takeIf { it in 0 until highlighter.size } ?: highlighter.defaultColor,
            highlighterWidth = highlighterWidth.takeIf { it in highlighter.widths.indices } ?: highlighter.defaultWidth,
            underlineColor = underlineColor.takeIf { it in 0 until underline.size } ?: underline.defaultColor,
            strikethroughColor = strikethroughColor.takeIf { it in 0 until strikethrough.size } ?: strikethrough.defaultColor,
            signatureColor = signatureColor.takeIf { it in 0 until signature.size } ?: signature.defaultColor,
            signatureWidth = signatureWidth.takeIf { it in ViewerSignature.WIDTHS.indices } ?: ViewerSignature.DEFAULT_WIDTH,
            signatureSlot = signatureSlot.takeIf { it in 0 until ViewerSignature.SLOTS } ?: 0,
            textColor = textColor.takeIf { it in 0 until text.size } ?: text.defaultColor,
            textWeight = textWeight.takeIf { it in text.widths.indices } ?: text.defaultWidth,
            formColor = formColor.takeIf { it in 0 until formMark.size } ?: formMark.defaultColor,
            formWidth = formWidth.takeIf { it in formMark.widths.indices } ?: formMark.defaultWidth
        )
    }

    private fun drawing(tool: ViewerEditTool): ViewerEditTool =
        if (tool.palette != null) tool else drawingTool.takeIf { it.palette != null } ?: ViewerEditTool.PEN

    private fun paletteOf(tool: ViewerEditTool): ViewerEditPalette = drawing(tool).palette ?: ViewerEditPalettes.pen
}
