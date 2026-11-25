package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
internal fun ViewerTypingField(editor: ViewerEditor, focusRequests: Int, fontFamily: FontFamily) {
    val draft = editor.typing ?: return
    val mark = draft.mark.text ?: return
    val text = mark.text
    val cursor = draft.cursor
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(cursor))) }

    LaunchedEffect(text, cursor) {
        if (field.text != text || field.selection.end != cursor) field = TextFieldValue(text, TextRange(cursor))
    }
    LaunchedEffect(focusRequests) {
        focus.requestFocus()
        keyboard?.show()
    }
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    Box(Modifier.offset(-OFFSCREEN, -OFFSCREEN).size(0.dp)) {
        BasicTextField(
            value = field,
            onValueChange = { next ->
                if (next.text == field.text) editor.moveCaret(next.selection.end) else editor.typeText(next.text, next.selection.end)
                val now = editor.typing ?: return@BasicTextField
                val nowText = now.mark.text?.text.orEmpty()
                field = if (nowText == next.text && now.cursor == next.selection.end) next else TextFieldValue(nowText, TextRange(now.cursor))
            },
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).focusRequester(focus),
            textStyle = TextStyle(color = Color.Transparent, fontFamily = fontFamily, fontWeight = FontWeight(mark.weight)),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            cursorBrush = SolidColor(Color.Transparent)
        )
    }
}

private val OFFSCREEN = 4000.dp
