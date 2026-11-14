package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.crop_all_pages
import hu.reelee81.reepdf.viewer.resources.crop_question
import hu.reelee81.reepdf.viewer.resources.crop_reset
import hu.reelee81.reepdf.viewer.resources.crop_title
import hu.reelee81.reepdf.viewer.resources.viewer_save
import org.jetbrains.compose.resources.stringResource

interface PageCropperActions {
    fun onBack()

    fun onSave()

    fun onDiscard()
}

@Composable
fun PageCropperScreen(
    state: PageCropperState,
    actions: PageCropperActions,
    fontFamily: FontFamily,
    excludeGestures: (List<Rect>) -> Unit = {}
) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) ReeColors.dark else ReeColors.light
    val palette = if (dark) ReePalette.dark else ReePalette.light
    val viewerColors = if (dark) ViewerColors.dark else ViewerColors.light
    val density = LocalDensity.current
    val markLength = with(density) { MARK_LENGTH.toPx() }
    val markSlop = with(density) { MARK_SLOP.toPx() }
    val lineSlop = with(density) { LINE_SLOP.toPx() }
    val edge = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val edgeStep = with(density) { AUTO_SCROLL_STEP.toPx() }
    val motion = remember { ViewerMotion() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentExclude by rememberUpdatedState(excludeGestures)
    LaunchedEffect(state, markSlop) {
        snapshotFlow { if (state.asking) emptyList() else state.sideMarkZones(markSlop).map { it.translate(origin) } }
            .collect { currentExclude(it) }
    }
    Box(Modifier.fillMaxSize().background(colors.pdfViewerSbBgColor.default)) {
        Column(Modifier.fillMaxSize()) {
            val allPages = ViewerBarAction(
                icon = if (state.allPages) ViewerIcons.allPagesOn else ViewerIcons.allPagesOff,
                label = stringResource(Res.string.crop_all_pages),
                tint = if (state.allPages) palette.teal100 else null,
                checked = state.allPages,
                onClick = state::toggleAllPages
            )
            val reset = ViewerBarAction(ViewerIcons.restoreCrop, stringResource(Res.string.crop_reset)) {
                if (state.isReady) state.reset()
            }
            val save = ViewerBarAction(ViewerIcons.save, stringResource(Res.string.viewer_save)) {
                if (state.isReady) actions.onSave()
            }
            ViewerTopBar(
                title = stringResource(Res.string.crop_title),
                actions = listOf(allPages, reset, save),
                colors = colors,
                fontFamily = fontFamily,
                onBack = actions::onBack
            )
            ViewerSurfaceView(
                surface = state.surface,
                colors = viewerColors,
                fontFamily = fontFamily,
                motion = motion,
                modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() },
                gutter = PAGE_GUTTER,
                grab = { position ->
                    if (state.grab(position.x, position.y, markLength, markSlop, lineSlop)) {
                        autoScrollDrag(state.surface, edge, edgeStep, state::dragTo, state::endDrag)
                    } else {
                        null
                    }
                },
                drawOver = { drawCropFrame(state, viewerColors, markLength) }
            )
        }
        if (state.asking) {
            ViewerSaveQuestion(
                text = stringResource(Res.string.crop_question),
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

private fun DrawScope.drawCropFrame(state: PageCropperState, colors: ViewerColors, maxMarkLength: Float) {
    val page = state.pageOnScreen() ?: return
    val shown = state.frameOnScreen() ?: return
    clipRect(shown.left, shown.top, shown.right, shown.bottom, ClipOp.Difference) {
        drawRect(colors.cropShade, page.topLeft, page.size)
    }
    val grid = GRID_WIDTH.toPx()
    for (step in 1..2) {
        val x = shown.left + shown.width * step / 3f
        val y = shown.top + shown.height * step / 3f
        drawLine(colors.cropGrid, Offset(x, shown.top), Offset(x, shown.bottom), grid)
        drawLine(colors.cropGrid, Offset(shown.left, y), Offset(shown.right, y), grid)
    }
    drawRect(colors.cropFrame, shown.topLeft, shown.size, style = Stroke(FRAME_WIDTH.toPx()))
    val length = PageCropperState.markLength(shown, maxMarkLength)
    val half = length / 2f
    val width = MARK_WIDTH.toPx()
    val l = shown.left
    val t = shown.top
    val r = shown.right
    val b = shown.bottom
    val centerX = shown.center.x
    val centerY = shown.center.y
    val marks = listOf(
        Offset(l, t) to Offset(l + length, t),
        Offset(l, t) to Offset(l, t + length),
        Offset(r, t) to Offset(r - length, t),
        Offset(r, t) to Offset(r, t + length),
        Offset(l, b) to Offset(l + length, b),
        Offset(l, b) to Offset(l, b - length),
        Offset(r, b) to Offset(r - length, b),
        Offset(r, b) to Offset(r, b - length),
        Offset(centerX - half, t) to Offset(centerX + half, t),
        Offset(centerX - half, b) to Offset(centerX + half, b),
        Offset(l, centerY - half) to Offset(l, centerY + half),
        Offset(r, centerY - half) to Offset(r, centerY + half)
    )
    for ((start, end) in marks) drawLine(colors.cropFrame, start, end, width, StrokeCap.Square)
}

private val PAGE_GUTTER = 24.dp
private val MARK_LENGTH = 22.dp
private val MARK_SLOP = 24.dp
private val LINE_SLOP = 16.dp
private val FRAME_WIDTH = 1.5.dp
private val GRID_WIDTH = 1.dp
private val MARK_WIDTH = 4.dp
