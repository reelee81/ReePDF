package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun Modifier.dialogFrame(mainPanelWidth: Dp): Modifier = widthIn(max = mainPanelWidth).padding(DIALOG_EDGE)

internal fun dialogWidth(availableWidth: Dp, mainPanelWidth: Dp): Dp = minOf(availableWidth, mainPanelWidth) - DIALOG_EDGE * 2

internal val DIALOG_EDGE = 16.dp
