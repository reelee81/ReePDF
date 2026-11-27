package hu.reelee81.reepdf.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

internal class ViewerTooltipTip(val text: String, val anchor: IntRect)

@Stable
internal class ViewerTooltipState {
    var current: ViewerTooltipTip? by mutableStateOf(null)
        private set

    var releases by mutableIntStateOf(0)
        private set

    internal var host: LayoutCoordinates? = null

    fun show(text: String, anchor: IntRect) {
        current = ViewerTooltipTip(text, anchor)
        releases = 0
    }

    fun release() {
        if (current != null) releases++
    }

    fun dismiss() {
        current = null
    }
}

internal val LocalViewerTooltip = staticCompositionLocalOf<ViewerTooltipState?> { null }

@Composable
internal fun ViewerTooltipHost(colors: ReeColors, fontFamily: FontFamily, content: @Composable () -> Unit) {
    val tooltip = remember { ViewerTooltipState() }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(tooltip, focused) { if (!focused) tooltip.dismiss() }
    LaunchedEffect(tooltip, tooltip.current, tooltip.releases) {
        if (tooltip.current != null && tooltip.releases > 0) {
            delay(HIDE_AFTER_RELEASE_MS.milliseconds)
            tooltip.dismiss()
        }
    }
    Box(
        Modifier.fillMaxSize()
            .onPlaced { tooltip.host = it }
            .onSizeChanged { tooltip.dismiss() }
            .pointerInput(tooltip) { watchTouches(tooltip) }
    ) {
        CompositionLocalProvider(LocalViewerTooltip provides tooltip, content = content)
        TooltipBubbles(tooltip, colors, fontFamily)
    }
}

internal fun Modifier.viewerTooltip(text: String, shown: () -> Boolean = ALWAYS_SHOWN, center: Dp? = null, height: Dp? = null): Modifier =
    this then ViewerTooltipElement(text, shown, center, height)

private suspend fun PointerInputScope.watchTouches(tooltip: ViewerTooltipState) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val changes = event.changes
            if (changes.any { it.changedToDownIgnoreConsumed() } && changes.none { it.previousPressed }) tooltip.dismiss()
            if (changes.any { it.previousPressed } && changes.none { it.pressed }) tooltip.release()
        }
    }
}

@Composable
private fun TooltipBubbles(tooltip: ViewerTooltipState, colors: ReeColors, fontFamily: FontFamily) {
    var shown by remember { mutableStateOf(emptyList<ViewerTooltipTip>()) }
    val current = tooltip.current
    LaunchedEffect(current) { if (current != null && current !in shown) shown = shown + current }
    for (tip in shown) {
        key(tip) {
            val alpha = remember { Animatable(0f) }
            val leaving = tip !== current
            LaunchedEffect(leaving) {
                if (leaving) {
                    alpha.animateTo(0f, tween(FADE_MS, easing = ACCELERATE_QUAD))
                    shown = shown - tip
                } else {
                    alpha.animateTo(1f, tween(FADE_MS, easing = DECELERATE_QUAD))
                }
            }
            TooltipBubble(tip, { alpha.value }, colors, fontFamily)
        }
    }
}

@Composable
private fun TooltipBubble(tip: ViewerTooltipTip, alpha: () -> Float, colors: ReeColors, fontFamily: FontFamily) {
    val density = LocalDensity.current
    val style = TextStyle(
        color = colors.menuTextColor.default,
        fontSize = with(density) { TEXT_SIZE.toSp() },
        lineHeight = with(density) { LINE_HEIGHT.toSp() },
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.Both),
        letterSpacing = LETTER_SPACING,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center
    )
    val background = colors.menuBackgroundColor.default
    Layout(
        content = {
            Box(
                Modifier.shadow(ELEVATION, BUBBLE_SHAPE)
                    .background(background, BUBBLE_SHAPE)
                    .padding(horizontal = PADDING_HORIZONTAL, vertical = PADDING_VERTICAL)
            ) {
                BasicText(tip.text, style = style)
            }
        },
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }
    ) { measurables, constraints ->
        val edge = EDGE.roundToPx()
        val area = IntRect(edge, edge, constraints.maxWidth - edge, constraints.maxHeight - edge)
        val bubble = measurables.single().measure(Constraints(maxWidth = area.width.coerceAtLeast(0)))
        val at = ViewerTooltipPlacement.place(tip.anchor, IntSize(bubble.width, bubble.height), area, GAP.roundToPx())
        layout(constraints.maxWidth, constraints.maxHeight) { bubble.place(at) }
    }
}

private class ViewerTooltipElement(
    val text: String,
    val shown: () -> Boolean,
    val center: Dp?,
    val height: Dp?
) : ModifierNodeElement<ViewerTooltipNode>() {
    override fun create() = ViewerTooltipNode(text, shown, center, height)

    override fun update(node: ViewerTooltipNode) {
        node.text = text
        node.shown = shown
        node.center = center
        node.height = height
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "viewerTooltip"
        properties["text"] = text
        properties["center"] = center
        properties["height"] = height
    }

    override fun equals(other: Any?): Boolean =
        other is ViewerTooltipElement && other.text == text && other.shown === shown && other.center == center && other.height == height

    override fun hashCode(): Int = ((text.hashCode() * 31 + shown.hashCode()) * 31 + center.hashCode()) * 31 + height.hashCode()
}

private class ViewerTooltipNode(
    var text: String,
    var shown: () -> Boolean,
    var center: Dp?,
    var height: Dp?
) : DelegatingNode(), CompositionLocalConsumerModifierNode {
    init {
        delegate(SuspendingPointerInputModifierNode { watchLongPress() })
    }

    private suspend fun PointerInputScope.watchLongPress() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            }
            if (released != null || !shown()) return@awaitEachGesture
            val tooltip = currentValueOf(LocalViewerTooltip) ?: return@awaitEachGesture
            tooltip.show(text, anchor(tooltip))
            currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.LongPress)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

    private fun anchor(tooltip: ViewerTooltipState): IntRect {
        val coordinates = requireLayoutCoordinates()
        val host = tooltip.host?.takeIf { it.isAttached }
        val origin = host?.localPositionOf(coordinates, Offset.Zero) ?: coordinates.positionInRoot()
        val density = requireDensity()
        return ViewerTooltipPlacement.anchor(
            origin,
            coordinates.size,
            center?.let { with(density) { it.toPx() } },
            height?.let { with(density) { it.toPx() } }
        )
    }
}

private val ALWAYS_SHOWN = { true }
private val ACCELERATE_QUAD = Easing { it * it }
private val DECELERATE_QUAD = Easing { 1f - (1f - it) * (1f - it) }
private const val HIDE_AFTER_RELEASE_MS = 1500L
private const val FADE_MS = 500
private val EDGE = 16.dp
private val GAP = 8.dp
private val ELEVATION = 3.dp
private val BUBBLE_SHAPE = RoundedCornerShape(8.dp)
private val PADDING_HORIZONTAL = 12.dp
private val PADDING_VERTICAL = 8.dp
private val TEXT_SIZE = 13.dp
private val LINE_HEIGHT = 20.dp
private val LETTER_SPACING = 0.01785714.em
