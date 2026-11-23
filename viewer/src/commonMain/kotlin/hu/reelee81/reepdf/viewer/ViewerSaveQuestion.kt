package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.discard_text
import hu.reelee81.reepdf.viewer.resources.save_text
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerSaveQuestion(
    text: String,
    colors: ReeColors,
    overlayColor: Color,
    fontFamily: FontFamily,
    mainPanelWidth: Dp,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    firstButton: StringResource = Res.string.discard_text,
    secondButton: StringResource = Res.string.save_text
) {
    val density = LocalDensity.current
    Box(
        modifier.fillMaxSize()
            .background(overlayColor)
            .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.dialogFrame(mainPanelWidth)
                .fillMaxWidth()
                .background(colors.alertdialogBackgroundColor.default, RoundedCornerShape(PANEL_RADIUS))
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            BasicText(
                text = text,
                modifier = Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .padding(start = TEXT_SIDE, top = TEXT_TOP, end = TEXT_SIDE, bottom = TEXT_BOTTOM),
                style = TextStyle(
                    color = colors.normalTextColor.default,
                    fontSize = with(density) { TEXT_SIZE.toSp() },
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center
                )
            )
            Row(Modifier.fillMaxWidth()) {
                ViewerTextButton(stringResource(firstButton), colors, fontFamily, onDiscard, Modifier.weight(1f))
                ViewerTextButton(stringResource(secondButton), colors, fontFamily, onSave, Modifier.weight(1f))
            }
        }
    }
}

private val PANEL_RADIUS = 12.dp
private val TEXT_SIDE = 16.dp
private val TEXT_TOP = 20.dp
private val TEXT_BOTTOM = 12.dp
private val TEXT_SIZE = 18.dp
