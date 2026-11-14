package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.back_to_main_screen
import hu.reelee81.reepdf.viewer.resources.cancel_text
import hu.reelee81.reepdf.viewer.resources.edit_pdf
import hu.reelee81.reepdf.viewer.resources.pdf_wait_reading_edited_pages
import hu.reelee81.reepdf.viewer.resources.pdf_wait_reading_pages
import hu.reelee81.reepdf.viewer.resources.selection_too_large
import hu.reelee81.reepdf.viewer.resources.viewer_search
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun ViewerScreen(state: ViewerState, actions: ViewerActions, fontFamily: FontFamily) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) ReeColors.dark else ReeColors.light
    val palette = if (dark) ReePalette.dark else ReePalette.light
    val viewerColors = if (dark) ViewerColors.dark else ViewerColors.light
    val waiting = state.isWaiting
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val tooLarge = stringResource(Res.string.selection_too_large)
    var copying by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(colors.pdfViewerSbBgColor.default)) {
        Column(Modifier.fillMaxSize().then(if (waiting) Modifier.clearAndSetSemantics {} else Modifier)) {
            if (state.search.isActive) {
                ViewerSearchBar(
                    search = state.search,
                    currentPage = { state.currentPage },
                    colors = colors,
                    viewerColors = viewerColors,
                    fontFamily = fontFamily,
                    onBack = actions::onBack
                )
            } else {
                val search = ViewerBarAction(ViewerIcons.search, stringResource(Res.string.viewer_search), onClick = state::startSearch)
                val bar = if (state.canEdit) {
                    listOf(search, ViewerBarAction(ViewerIcons.edit, stringResource(Res.string.edit_pdf)) { if (!state.isWaiting) actions.onEdit() })
                } else {
                    listOf(search)
                }
                ViewerTopBar(
                    title = state.title,
                    actions = bar,
                    colors = colors,
                    fontFamily = fontFamily,
                    onBack = actions::onBack
                )
            }
            ViewerPages(
                state = state,
                colors = viewerColors,
                reeColors = colors,
                fontFamily = fontFamily,
                onTap = {
                    focusManager.clearFocus()
                    state.selection.clear()
                },
                onCopy = {
                    if (!copying) {
                        copying = true
                        scope.launch {
                            try {
                                val text = state.selection.copyText()
                                if (text == null) {
                                    actions.onMessage(tooLarge)
                                } else {
                                    if (text.isNotEmpty()) actions.onCopy(text)
                                    state.selection.clear()
                                }
                            } finally {
                                copying = false
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            if (state.bottomGap) Spacer(Modifier.fillMaxWidth().height(BOTTOM_SPACE))
        }
        if (waiting) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { blockTouches() })
            if (state.waitRevealed) {
                val leaves = state.leavesOnCancel
                ViewerWaitDialog(
                    caption = stringResource(if (leaves) Res.string.pdf_wait_reading_edited_pages else Res.string.pdf_wait_reading_pages),
                    buttonLabel = stringResource(if (leaves) Res.string.back_to_main_screen else Res.string.cancel_text),
                    colors = colors,
                    overlayColor = palette.dialogOverlayBackgroundColor,
                    fontFamily = fontFamily,
                    onCancel = actions::onCancelWait
                )
            }
        }
    }
}

private suspend fun PointerInputScope.blockTouches() {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

private val BOTTOM_SPACE = 32.dp
