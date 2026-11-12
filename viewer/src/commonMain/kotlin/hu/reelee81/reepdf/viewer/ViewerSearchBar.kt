package hu.reelee81.reepdf.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.reelee81.reepdf.viewer.resources.Res
import hu.reelee81.reepdf.viewer.resources.search_match_count
import hu.reelee81.reepdf.viewer.resources.search_next
import hu.reelee81.reepdf.viewer.resources.search_no_match
import hu.reelee81.reepdf.viewer.resources.search_previous
import hu.reelee81.reepdf.viewer.resources.viewer_back
import hu.reelee81.reepdf.viewer.resources.viewer_search
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ViewerSearchBar(
    search: ViewerSearch,
    currentPage: () -> Int,
    colors: ReeColors,
    viewerColors: ViewerColors,
    fontFamily: FontFamily,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val iconTint = ColorFilter.tint(colors.toolbarNavigationIconColor.default)
    val textStyle = TextStyle(
        color = viewerColors.searchText,
        fontSize = TEXT_SIZE,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal
    )
    val backLabel = stringResource(Res.string.viewer_back)
    val hint = stringResource(Res.string.viewer_search)
    val status = when {
        search.total > 0 -> stringResource(Res.string.search_match_count, search.position, search.total)
        search.noMatch -> stringResource(Res.string.search_no_match)
        else -> null
    }
    val selectionColors = TextSelectionColors(
        handleColor = colors.appControlActivatedOtherColor.default,
        backgroundColor = colors.appControlActivatedBgColor.default
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Box(modifier.fillMaxWidth().height(BAR_HEIGHT).background(colors.pdfViewerSbBgColor.default)) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .size(NAVIGATION_SIZE)
                .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center
        ) {
            Image(rememberVectorPainter(ViewerIcons.arrowBack), null, Modifier.size(ICON_SIZE), colorFilter = iconTint)
        }
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                value = search.query,
                onValueChange = { search.setQuery(it, currentPage()) },
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = BOX_START, end = BOX_END)
                    .fillMaxWidth()
                    .height(BOX_HEIGHT)
                    .background(viewerColors.searchBox, RoundedCornerShape(BOX_RADIUS))
                    .focusRequester(focusRequester),
                textStyle = textStyle,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }),
                singleLine = true,
                cursorBrush = SolidColor(colors.appControlActivatedOtherColor.default),
                decorationBox = { field ->
                    Row(
                        Modifier.fillMaxSize().padding(start = TEXT_START, end = STATUS_END),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (search.query.isEmpty()) {
                                BasicText(hint, style = textStyle.copy(color = viewerColors.searchHint), maxLines = 1)
                            }
                            field()
                        }
                        if (status != null) {
                            Spacer(Modifier.width(STATUS_GAP))
                            BasicText(status, style = textStyle, maxLines = 1)
                        }
                    }
                }
            )
        }
        SearchArrow(
            icon = ViewerIcons.previous,
            label = stringResource(Res.string.search_previous),
            areaEnd = SPLIT,
            areaWidth = BOX_END - SPLIT,
            iconEnd = PREVIOUS_CENTER - SPLIT - ICON_SIZE / 2,
            tint = iconTint,
            onClick = search::previous
        )
        SearchArrow(
            icon = ViewerIcons.next,
            label = stringResource(Res.string.search_next),
            areaEnd = 0.dp,
            areaWidth = SPLIT,
            iconEnd = NEXT_CENTER - ICON_SIZE / 2,
            tint = iconTint,
            onClick = search::next
        )
    }
}

@Composable
private fun BoxScope.SearchArrow(
    icon: ImageVector,
    label: String,
    areaEnd: Dp,
    areaWidth: Dp,
    iconEnd: Dp,
    tint: ColorFilter,
    onClick: () -> Unit
) {
    Box(
        Modifier.align(Alignment.CenterEnd)
            .padding(end = areaEnd)
            .width(areaWidth)
            .fillMaxHeight()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Image(
            rememberVectorPainter(icon),
            null,
            Modifier.align(Alignment.CenterEnd).padding(end = iconEnd).size(ICON_SIZE),
            colorFilter = tint
        )
    }
}

private val BAR_HEIGHT = 56.dp
private val NAVIGATION_SIZE = 56.dp
private val ICON_SIZE = 24.dp
private val BOX_START = 53.5.dp
private val BOX_END = 86.dp
private val BOX_HEIGHT = 32.dp
private val BOX_RADIUS = 4.dp
private val TEXT_START = 4.dp
private val STATUS_END = 5.dp
private val STATUS_GAP = 14.5.dp
private val PREVIOUS_CENTER = 63.dp
private val NEXT_CENTER = 28.dp
private val SPLIT = (PREVIOUS_CENTER + NEXT_CENTER) / 2
private val TEXT_SIZE = 16.sp
