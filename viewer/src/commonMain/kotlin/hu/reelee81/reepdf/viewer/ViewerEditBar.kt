package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.edit_color_palette
import hu.reelee81.reepdf.viewer.resources.edit_erase_all
import hu.reelee81.reepdf.viewer.resources.edit_erase_visible
import hu.reelee81.reepdf.viewer.resources.edit_eraser
import hu.reelee81.reepdf.viewer.resources.edit_form_marks
import hu.reelee81.reepdf.viewer.resources.edit_highlighter
import hu.reelee81.reepdf.viewer.resources.edit_pen
import hu.reelee81.reepdf.viewer.resources.edit_redo
import hu.reelee81.reepdf.viewer.resources.edit_scroll
import hu.reelee81.reepdf.viewer.resources.edit_strikethrough
import hu.reelee81.reepdf.viewer.resources.edit_text
import hu.reelee81.reepdf.viewer.resources.edit_underline
import hu.reelee81.reepdf.viewer.resources.edit_undo
import hu.reelee81.reepdf.viewer.resources.edit_vector_signature
import hu.reelee81.reepdf.viewer.resources.viewer_back
import hu.reelee81.reepdf.viewer.resources.viewer_save
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerEditBar(
    editor: ViewerEditor,
    colors: ReeColors,
    viewerColors: ViewerColors,
    scroll: ScrollState,
    onBack: () -> Unit,
    onEraseVisible: () -> Unit,
    onEraseAll: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = editor.settings
    val iconColor = colors.toolbarNavigationIconColor.default
    BoxWithConstraints(modifier.fillMaxWidth().height(BAR_HEIGHT).background(colors.pdfViewerSbBgColor.default)) {
        val width = maxWidth.value
        LaunchedEffect(width) { scroll.scrollTo(0) }
        BarItem(ViewerEditBarLayout.BACK, width, null, Res.string.viewer_back, onClick = onBack) { BarIcon(ViewerIcons.arrowBack, iconColor) }
        val strip = ViewerEditBarLayout.scrolls(width)
        ScrollStrip(strip, ViewerEditBarLayout.STRIP_START, ViewerEditBarLayout.stripEnd(width), ViewerEditBarLayout.length(ViewerEditBarLayout.ITEMS - 1), 0f, scroll) {
            BarItem(ViewerEditBarLayout.SAVE, width, scroll, Res.string.viewer_save, onClick = onSave) { BarIcon(ViewerIcons.save, iconColor) }
            val scrolling = editor.paused
            BarItem(ViewerEditBarLayout.SCROLL, width, scroll, Res.string.edit_scroll, toggled = scrolling, onClick = editor::togglePause) {
                BarIcon(if (scrolling) ViewerIcons.visibilityOn else ViewerIcons.visibilityOff, iconColor)
            }
            val swatchEnabled = settings.tool.palette != null
            BarItem(ViewerEditBarLayout.SWATCH, width, scroll, Res.string.edit_color_palette, enabled = swatchEnabled, onClick = editor::tapSwatch) {
                val color = Color(OPAQUE or editor.shownColor(settings.swatchTool))
                val outline = viewerColors.colorOutline
                Canvas(Modifier.size(SWATCH_SIZE)) {
                    drawCircle(color)
                    val stroke = OUTLINE_WIDTH.toPx()
                    drawCircle(outline, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                }
            }
            val groupActive = settings.tool in GROUP_TOOLS
            val groupTool = if (groupActive) settings.tool else settings.drawingTool
            val open = editor.rowOpen
            BarItem(ViewerEditBarLayout.GROUP, width, scroll, toolLabel(groupTool), selected = groupActive, onClick = editor::tapGroup) {
                if (open) {
                    val tab = viewerColors.activeTool
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = ACTIVE_SIZE.toPx() / 2
                        val x = size.width / 2
                        val top = size.height / 2 - radius
                        drawPath(
                            Path().apply {
                                addRoundRect(RoundRect(x - radius, top, x + radius, size.height, CornerRadius(radius), CornerRadius(radius), CornerRadius.Zero, CornerRadius.Zero))
                            },
                            tab
                        )
                    }
                } else if (groupActive) {
                    Box(Modifier.size(ACTIVE_SIZE).background(viewerColors.activeTool, CircleShape))
                }
                BarIcon(toolIcon(groupTool, editor), if (open || groupActive) viewerColors.activeToolIcon else iconColor)
            }
            val eraser = ViewerEditTool.ERASER
            val erasing = settings.tool == eraser
            BarItem(ViewerEditBarLayout.ERASER, width, scroll, toolLabel(eraser), selected = erasing, onClick = { editor.tapTool(eraser) }) {
                if (erasing) Box(Modifier.size(ACTIVE_SIZE).background(viewerColors.activeTool, CircleShape))
                BarIcon(toolIcon(eraser, editor), if (erasing) viewerColors.activeToolIcon else iconColor)
            }
            BarItem(ViewerEditBarLayout.UNDO, width, scroll, Res.string.edit_undo, enabled = editor.canUndo, onClick = {
                editor.closePanel()
                editor.undo()
            }) { BarIcon(ViewerIcons.undo, iconColor) }
            BarItem(ViewerEditBarLayout.REDO, width, scroll, Res.string.edit_redo, enabled = editor.canRedo, onClick = {
                editor.closePanel()
                editor.redo()
            }) { BarIcon(ViewerIcons.redo, iconColor) }
            BarItem(ViewerEditBarLayout.ERASE_VISIBLE, width, scroll, Res.string.edit_erase_visible, onClick = onEraseVisible) { BarIcon(ViewerIcons.eraseOnePage, iconColor) }
            BarItem(ViewerEditBarLayout.ERASE_ALL, width, scroll, Res.string.edit_erase_all, onClick = onEraseAll) { BarIcon(ViewerIcons.eraseAllPages, iconColor) }
        }
    }
}

@Composable
internal fun ViewerToolRow(editor: ViewerEditor, colors: ReeColors, viewerColors: ViewerColors, width: Dp, scroll: ScrollState) {
    val settings = editor.settings
    val density = LocalDensity.current
    val rowWidth = width.value
    val strip = ViewerEditBarLayout.rowScrolls(rowWidth)
    val origin = if (strip) ViewerEditBarLayout.EDGE + ViewerEditBarLayout.ROW_PAD else 0f
    Box(
        Modifier.fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(viewerColors.activeTool)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        ScrollStrip(strip, ViewerEditBarLayout.EDGE, ViewerEditBarLayout.stripEnd(rowWidth), ViewerEditBarLayout.length(GROUP_TOOLS.size), ViewerEditBarLayout.ROW_PAD, scroll) {
            val shown = if (settings.tool in GROUP_TOOLS) settings.tool else settings.drawingTool
            for (tool in GROUP_TOOLS) {
                val chosen = tool == shown
                val description = stringResource(toolLabel(tool))
                val center = ViewerEditBarLayout.rowCenter(tool, rowWidth) - origin
                val press = if (tool.enabled) {
                    Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) {
                        if (ViewerEditBarLayout.rowIconShown(tool, rowWidth, with(density) { scroll.value.toDp().value })) editor.tapTool(tool)
                    }
                } else {
                    Modifier
                }
                Box(
                    Modifier.offset(x = (center - ViewerEditBarLayout.STEP / 2).dp)
                        .width(ViewerEditBarLayout.STEP.dp)
                        .fillMaxHeight()
                        .then(press)
                        .semantics {
                            contentDescription = description
                            selected = chosen
                            if (!tool.enabled) disabled()
                        }
                        .alpha(if (tool.enabled) 1f else DISABLED_ALPHA),
                    contentAlignment = Alignment.Center
                ) {
                    if (chosen) Box(Modifier.size(ACTIVE_SIZE).background(colors.pdfViewerSbBgColor.default, CircleShape))
                    BarIcon(toolIcon(tool, editor), if (chosen) colors.toolbarNavigationIconColor.default else viewerColors.activeToolIcon)
                }
            }
        }
    }
}

@Composable
private fun ScrollStrip(scrolls: Boolean, start: Float, end: Float, length: Float, pad: Float, scroll: ScrollState, content: @Composable BoxScope.() -> Unit) {
    if (scrolls) {
        Box(Modifier.offset(x = start.dp).width((end - start).coerceAtLeast(0f).dp).fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.padding(horizontal = pad.dp).width(length.dp).fillMaxHeight(), content = content)
        }
    } else {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun BarItem(
    index: Int,
    width: Float,
    scroll: ScrollState?,
    label: StringResource,
    enabled: Boolean = true,
    selected: Boolean? = null,
    toggled: Boolean? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val description = stringResource(label)
    val density = LocalDensity.current
    val step = ViewerEditBarLayout.STEP
    val reach = if (ViewerEditBarLayout.scrolls(width)) scroll else null
    val center = ViewerEditBarLayout.center(index, width) - if (reach != null) ViewerEditBarLayout.STRIP_START else 0f
    val left = if (index == ViewerEditBarLayout.BACK) 0f else center - step / 2
    val right = if (index == ViewerEditBarLayout.ITEMS - 1 && reach == null) width else center + step / 2
    val tap = {
        if (reach == null || ViewerEditBarLayout.iconShown(index, width, with(density) { reach.value.toDp().value })) onClick()
    }
    val press = when {
        !enabled -> Modifier
        toggled != null -> Modifier.toggleable(toggled, interactionSource = null, indication = null, role = Role.Switch) { tap() }
        else -> Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = tap)
    }
    Box(
        Modifier.offset(x = left.dp)
            .width((right - left).dp)
            .fillMaxHeight()
            .then(press)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
                if (!enabled) disabled()
            }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
    ) {
        Box(Modifier.offset(x = (center - step / 2 - left).dp).width(step.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, tint: Color) {
    Image(rememberVectorPainter(icon), null, Modifier.size(ICON_SIZE), colorFilter = ColorFilter.tint(tint))
}

private fun toolLabel(tool: ViewerEditTool): StringResource = when (tool) {
    ViewerEditTool.TEXT -> Res.string.edit_text
    ViewerEditTool.UNDERLINE -> Res.string.edit_underline
    ViewerEditTool.STRIKETHROUGH -> Res.string.edit_strikethrough
    ViewerEditTool.PEN -> Res.string.edit_pen
    ViewerEditTool.HIGHLIGHTER -> Res.string.edit_highlighter
    ViewerEditTool.FORM_MARK -> Res.string.edit_form_marks
    ViewerEditTool.SIGNATURE -> Res.string.edit_vector_signature
    ViewerEditTool.ERASER -> Res.string.edit_eraser
}

private fun toolIcon(tool: ViewerEditTool, editor: ViewerEditor): ImageVector = when (tool) {
    ViewerEditTool.TEXT -> ViewerIcons.textEdit
    ViewerEditTool.UNDERLINE -> ViewerIcons.underline
    ViewerEditTool.STRIKETHROUGH -> ViewerIcons.strikethrough
    ViewerEditTool.PEN -> ViewerIcons.drawEdit
    ViewerEditTool.HIGHLIGHTER -> ViewerIcons.inkEdit
    ViewerEditTool.FORM_MARK -> ViewerFormIcons.of(editor.shownFormSymbol)
    ViewerEditTool.SIGNATURE -> ViewerIcons.vectorSign
    ViewerEditTool.ERASER -> ViewerIcons.eraserEdit
}

internal object ViewerEditBarLayout {
    const val BACK = 0
    const val SAVE = 1
    const val SCROLL = 2
    const val SWATCH = 3
    const val GROUP = 4
    const val ERASER = 5
    const val UNDO = 6
    const val REDO = 7
    const val ERASE_VISIBLE = 8
    const val ERASE_ALL = 9
    const val ITEMS = 10
    const val ICON = 24f
    const val INK = 20f
    const val ACTIVE = 32f
    const val ROW_PAD = (ACTIVE - ICON) / 2
    const val GAP = 20f
    const val STEP = ICON + GAP
    const val EDGE = 16f
    const val STRIP_START = EDGE + STEP
    const val ROW_HEIGHT = 56f

    fun length(count: Int): Float = STEP * count - GAP

    fun stripEnd(width: Float): Float = width - EDGE

    fun scrolls(width: Float): Boolean = STRIP_START + length(ITEMS - 1) > stripEnd(width)

    fun center(index: Int, width: Float, scroll: Float = 0f): Float = when {
        index == BACK -> EDGE + ICON / 2
        scrolls(width) -> STRIP_START + ICON / 2 + STEP * (index - 1) - scroll
        else -> width - EDGE - ICON / 2 - STEP * (ITEMS - 1 - index)
    }

    fun shown(center: Float, scroll: Float, viewport: Float): Boolean =
        minOf(center + ICON / 2, scroll + viewport) - maxOf(center - ICON / 2, scroll) > (ICON - INK) / 2

    fun iconShown(index: Int, width: Float, scroll: Float): Boolean =
        !scrolls(width) || shown(center(index, width) - STRIP_START, scroll, stripEnd(width) - STRIP_START)

    fun rowIconShown(tool: ViewerEditTool, width: Float, scroll: Float): Boolean =
        !rowScrolls(width) || shown(rowCenter(tool, width) - EDGE, scroll, stripEnd(width) - EDGE)

    fun rowScrolls(width: Float): Boolean = EDGE + ROW_PAD * 2 + length(GROUP_TOOLS.size) > stripEnd(width)

    fun rowCenter(tool: ViewerEditTool, width: Float, scroll: Float = 0f): Float {
        val length = length(GROUP_TOOLS.size)
        val start = if (rowScrolls(width)) EDGE + ROW_PAD else (center(GROUP, width) - length / 2).coerceIn(EDGE + ROW_PAD, stripEnd(width) - ROW_PAD - length)
        return start + ICON / 2 + STEP * GROUP_TOOLS.indexOf(tool) - scroll
    }
}

private val GROUP_TOOLS = listOf(
    ViewerEditTool.TEXT,
    ViewerEditTool.UNDERLINE,
    ViewerEditTool.STRIKETHROUGH,
    ViewerEditTool.PEN,
    ViewerEditTool.HIGHLIGHTER,
    ViewerEditTool.FORM_MARK,
    ViewerEditTool.SIGNATURE
)

internal const val OPAQUE = 0xFF000000.toInt()
internal const val DISABLED_ALPHA = 0.38f
internal val OUTLINE_WIDTH = 1.dp
private val BAR_HEIGHT = 56.dp
private val ICON_SIZE = 24.dp
private val SWATCH_SIZE = 20.dp
private val ACTIVE_SIZE = ViewerEditBarLayout.ACTIVE.dp
