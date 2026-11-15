package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import hu.reelee81.reepdf.viewer.resources.edit_highlighter
import hu.reelee81.reepdf.viewer.resources.edit_pen
import hu.reelee81.reepdf.viewer.resources.edit_scroll
import hu.reelee81.reepdf.viewer.resources.edit_strikethrough
import hu.reelee81.reepdf.viewer.resources.edit_text
import hu.reelee81.reepdf.viewer.resources.edit_underline
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
        BarItem(ViewerEditBarLayout.BACK, width, Res.string.viewer_back, onClick = onBack) { BarIcon(ViewerIcons.arrowBack, iconColor) }
        val scrolling = editor.paused
        BarItem(ViewerEditBarLayout.SCROLL, width, Res.string.edit_scroll, toggled = scrolling, onClick = editor::togglePause) {
            BarIcon(if (scrolling) ViewerIcons.visibilityOn else ViewerIcons.visibilityOff, iconColor)
        }
        val swatchEnabled = settings.tool.palette != null
        BarItem(ViewerEditBarLayout.SWATCH, width, Res.string.edit_color_palette, enabled = swatchEnabled, onClick = editor::tapSwatch) {
            val color = Color(OPAQUE or settings.color(settings.swatchTool))
            val outline = viewerColors.colorOutline
            Canvas(Modifier.size(SWATCH_SIZE)) {
                drawCircle(color)
                val stroke = OUTLINE_WIDTH.toPx()
                drawCircle(outline, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            }
        }
        val groupActive = settings.tool in GROUP_TOOLS
        val (_, groupLabel, groupIcon) = TOOLS.first { it.first == (if (groupActive) settings.tool else settings.drawingTool) }
        val open = editor.rowOpen
        BarItem(ViewerEditBarLayout.GROUP, width, groupLabel, selected = groupActive, onClick = editor::tapGroup) {
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
            BarIcon(groupIcon, if (open || groupActive) viewerColors.activeToolIcon else iconColor)
        }
        val (eraser, eraserLabel, eraserIcon) = TOOLS.first { it.first == ViewerEditTool.ERASER }
        val erasing = settings.tool == eraser
        BarItem(ViewerEditBarLayout.ERASER, width, eraserLabel, selected = erasing, onClick = { editor.tapTool(eraser) }) {
            if (erasing) Box(Modifier.size(ACTIVE_SIZE).background(viewerColors.activeTool, CircleShape))
            BarIcon(eraserIcon, if (erasing) viewerColors.activeToolIcon else iconColor)
        }
        BarItem(ViewerEditBarLayout.ERASE_VISIBLE, width, Res.string.edit_erase_visible, onClick = onEraseVisible) { BarIcon(ViewerIcons.eraseOnePage, iconColor) }
        BarItem(ViewerEditBarLayout.ERASE_ALL, width, Res.string.edit_erase_all, onClick = onEraseAll) { BarIcon(ViewerIcons.eraseAllPages, iconColor) }
        BarItem(ViewerEditBarLayout.SAVE, width, Res.string.viewer_save, onClick = onSave) { BarIcon(ViewerIcons.save, iconColor) }
    }
}

@Composable
internal fun ViewerToolRow(editor: ViewerEditor, colors: ReeColors, viewerColors: ViewerColors, width: Dp) {
    val settings = editor.settings
    val step = ViewerEditBarLayout.step(width.value)
    Box(
        Modifier.fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(viewerColors.activeTool)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        val shown = if (settings.tool in GROUP_TOOLS) settings.tool else settings.drawingTool
        for ((tool, label, icon) in TOOLS) {
            if (tool !in GROUP_TOOLS) continue
            val chosen = tool == shown
            val description = stringResource(label)
            val center = ViewerEditBarLayout.rowCenter(tool, width.value)
            val press = if (tool.enabled) {
                Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) { editor.tapTool(tool) }
            } else {
                Modifier
            }
            Box(
                Modifier.offset(x = (center - step / 2).dp)
                    .width(step.dp)
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
                BarIcon(icon, if (chosen) colors.toolbarNavigationIconColor.default else viewerColors.activeToolIcon)
            }
        }
    }
}

@Composable
private fun BarItem(
    index: Int,
    width: Float,
    label: StringResource,
    enabled: Boolean = true,
    selected: Boolean? = null,
    toggled: Boolean? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val description = stringResource(label)
    val step = ViewerEditBarLayout.step(width)
    val center = ViewerEditBarLayout.center(index, width)
    val left = if (index == ViewerEditBarLayout.BACK) 0f else center - step / 2
    val right = if (index == ViewerEditBarLayout.SAVE) width else center + step / 2
    val press = when {
        !enabled -> Modifier
        toggled != null -> Modifier.toggleable(toggled, interactionSource = null, indication = null, role = Role.Switch) { onClick() }
        else -> Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
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

private val TOOLS = listOf(
    Triple(ViewerEditTool.TEXT, Res.string.edit_text, ViewerIcons.textEdit),
    Triple(ViewerEditTool.UNDERLINE, Res.string.edit_underline, ViewerIcons.underline),
    Triple(ViewerEditTool.STRIKETHROUGH, Res.string.edit_strikethrough, ViewerIcons.strikethrough),
    Triple(ViewerEditTool.PEN, Res.string.edit_pen, ViewerIcons.drawEdit),
    Triple(ViewerEditTool.HIGHLIGHTER, Res.string.edit_highlighter, ViewerIcons.inkEdit),
    Triple(ViewerEditTool.SIGNATURE, Res.string.edit_vector_signature, ViewerIcons.vectorSign),
    Triple(ViewerEditTool.ERASER, Res.string.edit_eraser, ViewerIcons.eraserEdit)
)

internal object ViewerEditBarLayout {
    const val BACK = 0
    const val SCROLL = 1
    const val SWATCH = 2
    const val GROUP = 3
    const val ERASER = 4
    const val ERASE_VISIBLE = 5
    const val ERASE_ALL = 6
    const val SAVE = 7
    const val ITEMS = 8
    const val ICON = 24f
    const val GAP = 20f
    const val EDGE = 16f
    const val ROW_HEIGHT = 56f

    fun step(width: Float): Float = ICON + ((width - EDGE * 2 - ICON * ITEMS) / (ITEMS - 1)).coerceIn(0f, GAP)

    fun center(index: Int, width: Float): Float =
        if (index == BACK) EDGE + ICON / 2 else width - EDGE - ICON / 2 - step(width) * (SAVE - index)

    fun rowCenter(tool: ViewerEditTool, width: Float): Float {
        val step = step(width)
        val first = maxOf(center(GROUP, width) - step * (GROUP_TOOLS.size - 1) / 2, center(BACK, width))
        return first + step * GROUP_TOOLS.indexOf(tool)
    }
}

private val GROUP_TOOLS = listOf(
    ViewerEditTool.TEXT,
    ViewerEditTool.UNDERLINE,
    ViewerEditTool.STRIKETHROUGH,
    ViewerEditTool.PEN,
    ViewerEditTool.HIGHLIGHTER,
    ViewerEditTool.SIGNATURE
)

internal const val OPAQUE = 0xFF000000.toInt()
internal const val DISABLED_ALPHA = 0.38f
internal val OUTLINE_WIDTH = 1.dp
private val BAR_HEIGHT = 56.dp
private val ICON_SIZE = 24.dp
private val SWATCH_SIZE = 20.dp
private val ACTIVE_SIZE = 32.dp
