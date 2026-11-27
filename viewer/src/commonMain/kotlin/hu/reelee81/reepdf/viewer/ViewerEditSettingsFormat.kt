package hu.reelee81.reepdf.viewer

object ViewerEditSettingsFormat {
    class Read(val settings: ViewerEditSettings, val isValid: Boolean)

    private const val HEADER = "ReePDF editor settings 1"

    private class Field(
        val key: String,
        val write: (ViewerEditSettings) -> String,
        val read: (ViewerEditSettings, String) -> ViewerEditSettings?
    )

    private val fields = listOf(
        Field("tool", { it.tool.name }) { settings, value ->
            ViewerEditTool.entries.firstOrNull { it.name == value && it.enabled }?.let { settings.copy(tool = it) }
        },
        Field("drawing_tool", { it.drawingTool.name }) { settings, value ->
            ViewerEditTool.entries.firstOrNull { it.name == value && it.palette != null }?.let { settings.copy(drawingTool = it) }
        },
        color("pen_color", ViewerEditPalettes.pen, { it.penColor }) { copy(penColor = it) },
        width("pen_width", ViewerEditPalettes.pen.widths, { it.penWidth }) { copy(penWidth = it) },
        color("highlighter_color", ViewerEditPalettes.highlighter, { it.highlighterColor }) { copy(highlighterColor = it) },
        width("highlighter_width", ViewerEditPalettes.highlighter.widths, { it.highlighterWidth }) { copy(highlighterWidth = it) },
        color("underline_color", ViewerEditPalettes.underline, { it.underlineColor }) { copy(underlineColor = it) },
        color("strikethrough_color", ViewerEditPalettes.strikethrough, { it.strikethroughColor }) { copy(strikethroughColor = it) },
        Field("underline_style", { it.underlineStyle.name }) { settings, value ->
            ViewerUnderlineStyle.entries.firstOrNull { it.name == value }?.let { settings.copy(underlineStyle = it) }
        },
        color("signature_color", ViewerEditPalettes.signature, { it.signatureColor }) { copy(signatureColor = it) },
        width("signature_width", ViewerSignature.WIDTHS, { it.signatureWidth }) { copy(signatureWidth = it) },
        Field("signature_slot", { (it.signatureSlot + 1).toString() }) { settings, value ->
            value.toIntOrNull()?.takeIf { it in 1..ViewerSignature.SLOTS }?.let { settings.copy(signatureSlot = it - 1) }
        },
        color("text_color", ViewerEditPalettes.text, { it.textColor }) { copy(textColor = it) },
        width("text_weight", ViewerEditPalettes.text.widths, { it.textWeight }) { copy(textWeight = it) },
        Field("form_mark_symbol", { it.formSymbol.name }) { settings, value ->
            ViewerFormSymbol.entries.firstOrNull { it.name == value }?.let { settings.copy(formSymbol = it) }
        },
        color("form_mark_color", ViewerEditPalettes.formMark, { it.formColor }) { copy(formColor = it) },
        width("form_mark_width", ViewerEditPalettes.formMark.widths, { it.formWidth }) { copy(formWidth = it) }
    )

    private val byKey = fields.associateBy { it.key }

    fun encode(settings: ViewerEditSettings): String {
        val sanitized = settings.sanitized()
        val text = StringBuilder(HEADER).append('\n')
        for (field in fields) text.append(field.key).append('=').append(field.write(sanitized)).append('\n')
        return text.toString()
    }

    fun decode(text: String, base: ViewerEditSettings): Read {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != HEADER) return Read(base, false)
        var settings = base
        var valid = true
        for (line in lines.drop(1)) {
            val separator = line.indexOf('=')
            if (separator < 0) {
                valid = false
                continue
            }
            val field = byKey[line.substring(0, separator).trim()] ?: continue
            val read = field.read(settings, line.substring(separator + 1).trim())
            if (read == null) valid = false else settings = read
        }
        return Read(settings, valid)
    }

    private fun color(
        key: String,
        palette: ViewerEditPalette,
        get: (ViewerEditSettings) -> Int,
        set: ViewerEditSettings.(Int) -> ViewerEditSettings
    ) = Field(key, { "#" + palette.color(get(it)).toString(16).uppercase().padStart(6, '0') }) { settings, value ->
        value.takeIf { it.length == 7 && it[0] == '#' }
            ?.substring(1)?.toIntOrNull(16)
            ?.let { rgb -> (0 until palette.size).firstOrNull { palette.color(it) == rgb } }
            ?.let { settings.set(it) }
    }

    private fun width(
        key: String,
        widths: FloatArray,
        get: (ViewerEditSettings) -> Int,
        set: ViewerEditSettings.(Int) -> ViewerEditSettings
    ) = Field(key, { number(widths[get(it)]) }) { settings, value ->
        value.toFloatOrNull()
            ?.let { chosen -> widths.indices.firstOrNull { widths[it] == chosen } }
            ?.let { settings.set(it) }
    }

    private fun number(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}
