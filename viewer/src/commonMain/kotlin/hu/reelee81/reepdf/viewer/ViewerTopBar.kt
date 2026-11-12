package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.edit_pdf
import hu.reelee81.reepdf.viewer.resources.viewer_back
import hu.reelee81.reepdf.viewer.resources.viewer_search
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerTopBar(
    title: String,
    canEdit: Boolean,
    colors: ReeColors,
    fontFamily: FontFamily,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val iconTint = ColorFilter.tint(colors.toolbarNavigationIconColor.default)
    val backLabel = stringResource(Res.string.viewer_back)
    Row(
        modifier.fillMaxWidth().height(BAR_HEIGHT).background(colors.pdfViewerSbBgColor.default),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(NAVIGATION_SIZE)
                .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center
        ) {
            Image(rememberVectorPainter(ViewerIcons.arrowBack), null, Modifier.size(ICON_SIZE), colorFilter = iconTint)
        }
        BasicText(
            text = title,
            modifier = Modifier.weight(1f).padding(start = TITLE_MARGIN, end = TITLE_MARGIN),
            style = TextStyle(
                color = colors.toolbarTitleTextColor.default,
                fontSize = with(density) { TITLE_SIZE.toSp() },
                lineHeight = with(density) { TITLE_LINE_HEIGHT.toSp() },
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                letterSpacing = TITLE_LETTER_SPACING
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val searchLabel = stringResource(Res.string.viewer_search)
        Box(
            Modifier.padding(end = if (canEdit) SEARCH_END_MARGIN else EDIT_END_MARGIN)
                .size(EDIT_SIZE)
                .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onSearch)
                .semantics { contentDescription = searchLabel },
            contentAlignment = Alignment.Center
        ) {
            Image(rememberVectorPainter(ViewerIcons.search), null, Modifier.size(ICON_SIZE), colorFilter = iconTint)
        }
        if (canEdit) {
            val editLabel = stringResource(Res.string.edit_pdf)
            Box(
                Modifier.padding(end = EDIT_END_MARGIN)
                    .size(EDIT_SIZE)
                    .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onEdit)
                    .semantics { contentDescription = editLabel },
                contentAlignment = Alignment.Center
            ) {
                Image(rememberVectorPainter(ViewerIcons.edit), null, Modifier.size(ICON_SIZE), colorFilter = iconTint)
            }
        }
    }
}

private val BAR_HEIGHT = 56.dp
private val NAVIGATION_SIZE = 56.dp
private val ICON_SIZE = 24.dp
private val TITLE_MARGIN = 4.dp
private val TITLE_SIZE = 20.dp
private val TITLE_LINE_HEIGHT = 24.dp
private val TITLE_LETTER_SPACING = 0.009375.em
private val EDIT_SIZE = 32.dp
private val EDIT_END_MARGIN = 16.dp
private val SEARCH_END_MARGIN = 4.dp
