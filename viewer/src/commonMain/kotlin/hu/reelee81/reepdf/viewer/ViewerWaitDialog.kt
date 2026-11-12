package hu.reelee81.reepdf.viewer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ViewerWaitDialog(
    caption: String,
    buttonLabel: String,
    colors: ReeColors,
    overlayColor: Color,
    fontFamily: FontFamily,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(overlayColor), contentAlignment = Alignment.Center) {
        val wide = maxWidth > maxHeight || minOf(maxWidth, maxHeight) >= WIDE_SCREEN
        Column(
            Modifier.padding(OUTER_PADDING)
                .then(if (wide) Modifier.widthIn(max = WIDE_PANEL) else Modifier)
                .fillMaxWidth()
                .background(colors.alertdialogBackgroundColor.default, RoundedCornerShape(PANEL_RADIUS))
                .padding(INNER_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                text = caption,
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = colors.progressInfoTextColor.default,
                    fontSize = with(density) { CAPTION_SIZE.toSp() },
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Center
                )
            )
            ViewerProgressBar(
                indicator = colors.progressIndicatorColor.default,
                track = colors.progressTrackColor.default,
                modifier = Modifier.padding(top = GAP).fillMaxWidth().height(TRACK_THICKNESS)
            )
            ViewerTextButton(buttonLabel, colors, fontFamily, onCancel, Modifier.padding(top = GAP))
        }
    }
}

@Composable
private fun ViewerTextButton(
    label: String,
    colors: ReeColors,
    fontFamily: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val textColor = if (pressed) colors.materialTextbuttonTextColor.of(ReeColorState.PRESSED) else colors.materialTextbuttonTextColor.default
    val pressedBackground = colors.materialTextbuttonRippleColor.default.copy(alpha = PRESSED_ALPHA)
    Box(
        modifier.padding(vertical = BUTTON_INSET)
            .heightIn(min = BUTTON_MIN_HEIGHT)
            .background(if (pressed) pressedBackground else Color.Transparent, RoundedCornerShape(BUTTON_RADIUS))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = BUTTON_PADDING),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = textColor,
                fontSize = with(density) { BUTTON_TEXT_SIZE.toSp() },
                lineHeight = with(density) { BUTTON_LINE_HEIGHT.toSp() },
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun ViewerProgressBar(indicator: Color, track: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = CYCLE_MS.toFloat(),
        animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart)
    )
    Canvas(modifier) {
        val gap = GAP_FRACTION_DP.toPx() / size.width
        val first = segment(time, 333f, 850f) to segment(time, 0f, 750f)
        val second = segment(time, 1267f, 533f) to segment(time, 1000f, 567f)
        val active = listOf(first, second).filter { (start, end) -> end > start }
        var trackStart = 0f
        for ((start, end) in active.sortedBy { it.first }) {
            drawBar(track, trackStart, start - gap)
            drawBar(indicator, start, end)
            trackStart = end + gap
        }
        drawBar(track, trackStart, 1f)
    }
}

private fun segment(time: Float, delay: Float, duration: Float): Float =
    FastOutSlowInEasing.transform(((time - delay) / duration).coerceIn(0f, 1f))

private fun DrawScope.drawBar(color: Color, start: Float, end: Float) {
    val from = start.coerceIn(0f, 1f) * size.width
    val to = end.coerceIn(0f, 1f) * size.width
    if (to - from <= 0f) return
    drawRoundRect(color, Offset(from, 0f), Size(to - from, size.height), CornerRadius(size.height / 2))
}

private val WIDE_SCREEN = 600.dp
private val WIDE_PANEL = 488.dp
private val OUTER_PADDING = 16.dp
private val INNER_PADDING = 16.dp
private val PANEL_RADIUS = 12.dp
private val CAPTION_SIZE = 14.dp
private val GAP = 16.dp
private val TRACK_THICKNESS = 4.dp
private val GAP_FRACTION_DP = 4.dp
private val BUTTON_INSET = 4.dp
private val BUTTON_MIN_HEIGHT = 40.dp
private val BUTTON_RADIUS = 4.dp
private val BUTTON_PADDING = 12.dp
private val BUTTON_TEXT_SIZE = 13.dp
private val BUTTON_LINE_HEIGHT = 17.dp
private const val PRESSED_ALPHA = 0.5f
private const val CYCLE_MS = 1800
