package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.signature_position
import hu.reelee81.reepdf.viewer.resources.signature_position_question
import hu.reelee81.reepdf.viewer.resources.viewer_save
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

interface SignaturePlacerActions {
    fun onBack()

    fun onSave()

    fun onDiscard()
}

@Composable
fun SignaturePlacerScreen(state: SignaturePlacerState, actions: SignaturePlacerActions, fontFamily: FontFamily) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) ReeColors.dark else ReeColors.light
    val palette = if (dark) ReePalette.dark else ReePalette.light
    val viewerColors = if (dark) ViewerColors.dark else ViewerColors.light
    val density = LocalDensity.current
    val slop = with(density) { ViewerSelectionHandles.TOUCH_SLOP.toPx() }
    val edge = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val edgeStep = with(density) { AUTO_SCROLL_STEP.toPx() }
    val motion = remember { ViewerMotion() }
    Box(Modifier.fillMaxSize().background(colors.pdfViewerSbBgColor.default)) {
        Column(Modifier.fillMaxSize()) {
            val save = ViewerBarAction(ViewerIcons.save, stringResource(Res.string.viewer_save)) {
                if (state.isReady) actions.onSave()
            }
            ViewerTopBar(
                title = stringResource(Res.string.signature_position),
                actions = listOf(save),
                colors = colors,
                fontFamily = fontFamily,
                onBack = actions::onBack
            )
            ViewerSurfaceView(
                surface = state.surface,
                colors = viewerColors,
                fontFamily = fontFamily,
                motion = motion,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                grab = { position ->
                    if (state.grab(position.x, position.y, slop)) {
                        autoScrollDrag(state.surface, edge, edgeStep, state::dragTo, state::endDrag)
                    } else {
                        null
                    }
                },
                drawOver = { drawSignature(state) }
            )
        }
        if (state.asking) {
            ViewerSaveQuestion(
                text = stringResource(Res.string.signature_position_question),
                colors = colors,
                overlayColor = palette.dialogOverlayBackgroundColor,
                fontFamily = fontFamily,
                onDismiss = state::dismissQuestion,
                onDiscard = actions::onDiscard,
                onSave = actions::onSave
            )
        }
    }
}

private fun DrawScope.drawSignature(state: SignaturePlacerState) {
    val image = state.signature ?: return
    val shown = state.signatureOnScreen() ?: return
    val left = shown.left.roundToInt()
    val top = shown.top.roundToInt()
    drawImage(
        image = image,
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(shown.right.roundToInt() - left, shown.bottom.roundToInt() - top)
    )
}
