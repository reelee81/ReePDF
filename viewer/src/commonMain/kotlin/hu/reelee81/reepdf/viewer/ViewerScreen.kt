package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.back_to_main_screen
import hu.reelee81.reepdf.viewer.resources.cancel_text
import hu.reelee81.reepdf.viewer.resources.edit_erase_all_question
import hu.reelee81.reepdf.viewer.resources.edit_pdf
import hu.reelee81.reepdf.viewer.resources.edit_question
import hu.reelee81.reepdf.viewer.resources.edit_signature_1_delete_question
import hu.reelee81.reepdf.viewer.resources.edit_signature_2_delete_question
import hu.reelee81.reepdf.viewer.resources.no_pdf_edits_to_save
import hu.reelee81.reepdf.viewer.resources.ok_text
import hu.reelee81.reepdf.viewer.resources.pdf_edits_erase_failed
import hu.reelee81.reepdf.viewer.resources.pdf_edits_restore_incomplete
import hu.reelee81.reepdf.viewer.resources.pdf_edits_save_failed
import hu.reelee81.reepdf.viewer.resources.pdf_edits_save_interrupted
import hu.reelee81.reepdf.viewer.resources.pdf_edits_saving
import hu.reelee81.reepdf.viewer.resources.pdf_wait_reading_edited_pages
import hu.reelee81.reepdf.viewer.resources.pdf_wait_reading_pages
import hu.reelee81.reepdf.viewer.resources.selection_too_large
import hu.reelee81.reepdf.viewer.resources.signature_store_failed
import hu.reelee81.reepdf.viewer.resources.viewer_search
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun ViewerScreen(state: ViewerState, actions: ViewerActions, fontFamily: FontFamily, mainPanelWidth: Dp) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) ReeColors.dark else ReeColors.light
    val palette = if (dark) ReePalette.dark else ReePalette.light
    val viewerColors = if (dark) ViewerColors.dark else ViewerColors.light
    val waiting = state.isWaiting
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val tooLarge = stringResource(Res.string.selection_too_large)
    var copying by remember { mutableStateOf(false) }
    val editBarScroll = remember(state.editor.isActive) { ScrollState(0) }
    ViewerTooltipHost(colors, fontFamily) {
        Box(Modifier.fillMaxSize().background(colors.pdfViewerSbBgColor.default)) {
            Column(Modifier.fillMaxSize().then(if (waiting) Modifier.clearAndSetSemantics {} else Modifier)) {
                if (state.editor.isActive) {
                    ViewerEditBar(
                        editor = state.editor,
                        colors = colors,
                        viewerColors = viewerColors,
                        scroll = editBarScroll,
                        onBack = {
                            state.editor.commitTyping()
                            actions.onBack()
                        },
                        onEraseVisible = {
                            state.editor.closePanel()
                            state.editor.eraseVisible(state.labeledPages())
                        },
                        onEraseAll = {
                            state.editor.closePanel()
                            state.editor.question = ViewerEditQuestion.ERASE_ALL
                        },
                        onSave = state::saveEdits
                    )
                } else if (state.search.isActive) {
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
                        listOf(search, ViewerBarAction(ViewerIcons.edit, stringResource(Res.string.edit_pdf), onClick = state::startEditing))
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
                Box(Modifier.weight(1f).fillMaxWidth()) {
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
                        modifier = Modifier.fillMaxSize()
                    )
                    if (state.editor.isActive) {
                        ViewerEditPanels(state, colors, viewerColors, fontFamily, editBarScroll)
                    }
                }
                if (state.bottomGap) Spacer(Modifier.fillMaxWidth().height(BOTTOM_SPACE))
            }
            when (state.editor.question) {
                ViewerEditQuestion.EXIT -> ViewerSaveQuestion(
                    text = stringResource(Res.string.edit_question),
                    colors = colors,
                    overlayColor = palette.dialogOverlayBackgroundColor,
                    fontFamily = fontFamily,
                    mainPanelWidth = mainPanelWidth,
                    onDismiss = { state.editor.question = null },
                    onDiscard = state.editor::discard,
                    onSave = state::saveEdits
                )
                ViewerEditQuestion.ERASE_ALL -> ViewerSaveQuestion(
                    text = stringResource(Res.string.edit_erase_all_question),
                    colors = colors,
                    overlayColor = palette.dialogOverlayBackgroundColor,
                    fontFamily = fontFamily,
                    mainPanelWidth = mainPanelWidth,
                    onDismiss = { state.editor.question = null },
                    onDiscard = { state.editor.question = null },
                    onSave = {
                        state.editor.question = null
                        state.editor.eraseAll()
                    },
                    firstButton = Res.string.cancel_text,
                    secondButton = Res.string.ok_text
                )
                null -> Unit
            }
            state.editor.signatures.pad?.let { pad ->
                ViewerSignaturePadDialog(state.editor, pad, colors, viewerColors, palette.dialogOverlayBackgroundColor, fontFamily, mainPanelWidth)
            }
            state.editor.signatures.deleting?.let { slot ->
                ViewerSaveQuestion(
                    text = stringResource(if (slot == 0) Res.string.edit_signature_1_delete_question else Res.string.edit_signature_2_delete_question),
                    colors = colors,
                    overlayColor = palette.dialogOverlayBackgroundColor,
                    fontFamily = fontFamily,
                    mainPanelWidth = mainPanelWidth,
                    onDismiss = state.editor.signatures::dismissDelete,
                    onDiscard = state.editor.signatures::dismissDelete,
                    onSave = state.editor.signatures::confirmDelete,
                    firstButton = Res.string.cancel_text,
                    secondButton = Res.string.ok_text
                )
            }
            if (waiting) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { blockTouches() })
                if (state.waitRevealed) {
                    val reason = state.waitReason
                    val saving = reason == ViewerWait.Reason.SAVING_EDITS
                    val leaves = reason == ViewerWait.Reason.OPENING_EDITED
                    ViewerWaitDialog(
                        caption = stringResource(
                            when {
                                saving -> Res.string.pdf_edits_saving
                                leaves -> Res.string.pdf_wait_reading_edited_pages
                                else -> Res.string.pdf_wait_reading_pages
                            }
                        ),
                        buttonLabel = stringResource(if (leaves) Res.string.back_to_main_screen else Res.string.cancel_text),
                        colors = colors,
                        overlayColor = palette.dialogOverlayBackgroundColor,
                        fontFamily = fontFamily,
                        mainPanelWidth = mainPanelWidth,
                        onCancel = if (saving) state::cancelSave else actions::onCancelWait
                    )
                }
            }
            val message = when (val shown = state.editMessage) {
                null -> null
                ViewerEditMessage.NothingToSave -> stringResource(Res.string.no_pdf_edits_to_save)
                is ViewerEditMessage.SaveFailed -> stringResource(Res.string.pdf_edits_save_failed, shown.text)
                is ViewerEditMessage.EraseFailed -> stringResource(Res.string.pdf_edits_erase_failed, shown.text)
                is ViewerEditMessage.SignatureFailed -> stringResource(Res.string.signature_store_failed, shown.text)
                ViewerEditMessage.RestoreIncomplete -> stringResource(Res.string.pdf_edits_restore_incomplete)
                ViewerEditMessage.SaveInterrupted -> stringResource(Res.string.pdf_edits_save_interrupted)
            }
            LaunchedEffect(message) {
                if (message != null) {
                    actions.onMessage(message)
                    state.messageShown()
                }
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
