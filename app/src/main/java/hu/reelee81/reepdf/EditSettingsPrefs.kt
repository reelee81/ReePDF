package hu.reelee81.reepdf

import android.content.Context
import android.content.SharedPreferences
import hu.reelee81.reepdf.viewer.ViewerEditSettings
import hu.reelee81.reepdf.viewer.ViewerEditTool
import hu.reelee81.reepdf.viewer.ViewerFormSymbol
import hu.reelee81.reepdf.viewer.ViewerUnderlineStyle

object EditSettingsPrefs {
    private const val EDIT_PREFS = "viewer_edit"
    private const val KEY_TOOL = "tool"
    private const val KEY_DRAWING_TOOL = "drawing_tool"
    private const val KEY_PEN_COLOR = "pen_color"
    private const val KEY_PEN_WIDTH = "pen_width"
    private const val KEY_HIGHLIGHTER_COLOR = "highlighter_color"
    private const val KEY_HIGHLIGHTER_WIDTH = "highlighter_width"
    private const val KEY_UNDERLINE_COLOR = "underline_color"
    private const val KEY_STRIKETHROUGH_COLOR = "strikethrough_color"
    private const val KEY_UNDERLINE_STYLE = "underline_style"
    private const val KEY_SIGNATURE_COLOR = "signature_color"
    private const val KEY_SIGNATURE_WIDTH = "signature_width"
    private const val KEY_SIGNATURE_SLOT = "signature_slot"
    private const val KEY_TEXT_COLOR = "text_color"
    private const val KEY_TEXT_WEIGHT = "text_weight"
    private const val KEY_FORM_MARK_SYMBOL = "form_mark_symbol"
    private const val KEY_FORM_MARK_COLOR = "form_mark_color"
    private const val KEY_FORM_MARK_WIDTH = "form_mark_width"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(EDIT_PREFS, Context.MODE_PRIVATE)

    fun read(prefs: SharedPreferences): ViewerEditSettings {
        val defaults = ViewerEditSettings()
        fun tool(key: String, fallback: ViewerEditTool) =
            prefs.getString(key, null)?.let { name -> ViewerEditTool.entries.firstOrNull { it.name == name } } ?: fallback
        return ViewerEditSettings(
            tool = tool(KEY_TOOL, defaults.tool),
            drawingTool = tool(KEY_DRAWING_TOOL, defaults.drawingTool),
            penColor = prefs.getInt(KEY_PEN_COLOR, defaults.penColor),
            penWidth = prefs.getInt(KEY_PEN_WIDTH, defaults.penWidth),
            highlighterColor = prefs.getInt(KEY_HIGHLIGHTER_COLOR, defaults.highlighterColor),
            highlighterWidth = prefs.getInt(KEY_HIGHLIGHTER_WIDTH, defaults.highlighterWidth),
            underlineColor = prefs.getInt(KEY_UNDERLINE_COLOR, defaults.underlineColor),
            strikethroughColor = prefs.getInt(KEY_STRIKETHROUGH_COLOR, defaults.strikethroughColor),
            underlineStyle = prefs.getString(KEY_UNDERLINE_STYLE, null)
                ?.let { name -> ViewerUnderlineStyle.entries.firstOrNull { it.name == name } } ?: defaults.underlineStyle,
            signatureColor = prefs.getInt(KEY_SIGNATURE_COLOR, defaults.signatureColor),
            signatureWidth = prefs.getInt(KEY_SIGNATURE_WIDTH, defaults.signatureWidth),
            signatureSlot = prefs.getInt(KEY_SIGNATURE_SLOT, defaults.signatureSlot),
            textColor = prefs.getInt(KEY_TEXT_COLOR, defaults.textColor),
            textWeight = prefs.getInt(KEY_TEXT_WEIGHT, defaults.textWeight),
            formSymbol = prefs.getString(KEY_FORM_MARK_SYMBOL, null)
                ?.let { name -> ViewerFormSymbol.entries.firstOrNull { it.name == name } } ?: defaults.formSymbol,
            formColor = prefs.getInt(KEY_FORM_MARK_COLOR, defaults.formColor),
            formWidth = prefs.getInt(KEY_FORM_MARK_WIDTH, defaults.formWidth)
        )
    }

    fun put(editor: SharedPreferences.Editor, settings: ViewerEditSettings) {
        editor.putString(KEY_TOOL, settings.tool.name)
        editor.putString(KEY_DRAWING_TOOL, settings.drawingTool.name)
        editor.putInt(KEY_PEN_COLOR, settings.penColor)
        editor.putInt(KEY_PEN_WIDTH, settings.penWidth)
        editor.putInt(KEY_HIGHLIGHTER_COLOR, settings.highlighterColor)
        editor.putInt(KEY_HIGHLIGHTER_WIDTH, settings.highlighterWidth)
        editor.putInt(KEY_UNDERLINE_COLOR, settings.underlineColor)
        editor.putInt(KEY_STRIKETHROUGH_COLOR, settings.strikethroughColor)
        editor.putString(KEY_UNDERLINE_STYLE, settings.underlineStyle.name)
        editor.putInt(KEY_SIGNATURE_COLOR, settings.signatureColor)
        editor.putInt(KEY_SIGNATURE_WIDTH, settings.signatureWidth)
        editor.putInt(KEY_SIGNATURE_SLOT, settings.signatureSlot)
        editor.putInt(KEY_TEXT_COLOR, settings.textColor)
        editor.putInt(KEY_TEXT_WEIGHT, settings.textWeight)
        editor.putString(KEY_FORM_MARK_SYMBOL, settings.formSymbol.name)
        editor.putInt(KEY_FORM_MARK_COLOR, settings.formColor)
        editor.putInt(KEY_FORM_MARK_WIDTH, settings.formWidth)
    }
}
