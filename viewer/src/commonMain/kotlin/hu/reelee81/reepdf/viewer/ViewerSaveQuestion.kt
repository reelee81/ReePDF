package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.discard_text
import hu.reelee81.reepdf.viewer.resources.save_text
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerSaveQuestion(
    text: String,
    colors: ReeColors,
    overlayColor: Color,
    fontFamily: FontFamily,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(overlayColor)
            .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center
    ) {
        val wide = maxWidth > maxHeight || minOf(maxWidth, maxHeight) >= WIDE_SCREEN
        Column(
            Modifier.padding(OUTER_PADDING)
                .then(if (wide) Modifier.widthIn(max = WIDE_PANEL) else Modifier)
                .fillMaxWidth()
                .background(colors.alertdialogBackgroundColor.default, RoundedCornerShape(PANEL_RADIUS))
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            BasicText(
                text = text,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = TEXT_SIDE, top = TEXT_TOP, end = TEXT_SIDE, bottom = TEXT_BOTTOM),
                style = TextStyle(
                    color = colors.normalTextColor.default,
                    fontSize = with(density) { TEXT_SIZE.toSp() },
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center
                )
            )
            Row(Modifier.fillMaxWidth()) {
                ViewerTextButton(stringResource(Res.string.discard_text), colors, fontFamily, onDiscard, Modifier.weight(1f))
                ViewerTextButton(stringResource(Res.string.save_text), colors, fontFamily, onSave, Modifier.weight(1f))
            }
        }
    }
}

private val WIDE_SCREEN = 600.dp
private val WIDE_PANEL = 488.dp
private val OUTER_PADDING = 16.dp
private val PANEL_RADIUS = 12.dp
private val TEXT_SIDE = 16.dp
private val TEXT_TOP = 20.dp
private val TEXT_BOTTOM = 12.dp
private val TEXT_SIZE = 18.dp
