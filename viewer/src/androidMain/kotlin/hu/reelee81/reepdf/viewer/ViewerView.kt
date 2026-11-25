package hu.reelee81.reepdf.viewer

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.ceil
import kotlin.math.floor

fun viewerView(
    context: Context,
    state: ViewerState,
    actions: ViewerActions,
    thinFont: Int,
    extraLightFont: Int,
    lightFont: Int,
    regularFont: Int,
    mediumFont: Int,
    semiBoldFont: Int,
    boldFont: Int,
    extraBoldFont: Int,
    blackFont: Int,
    mainPanelWidth: Int
): View {
    val fontFamily = FontFamily(
        Font(thinFont, FontWeight.Thin),
        Font(extraLightFont, FontWeight.ExtraLight),
        Font(lightFont, FontWeight.Light),
        Font(regularFont, FontWeight.Normal),
        Font(mediumFont, FontWeight.Medium),
        Font(semiBoldFont, FontWeight.SemiBold),
        Font(boldFont, FontWeight.Bold),
        Font(extraBoldFont, FontWeight.ExtraBold),
        Font(blackFont, FontWeight.Black)
    )
    return ComposeView(context).apply {
        setContent { ViewerScreen(state, actions, fontFamily, dimensionResource(mainPanelWidth)) }
    }
}

fun signaturePlacerView(
    context: Context,
    state: SignaturePlacerState,
    actions: SignaturePlacerActions,
    regularFont: Int,
    mediumFont: Int,
    mainPanelWidth: Int
): View {
    val fontFamily = fontFamily(regularFont, mediumFont)
    return ComposeView(context).apply {
        setContent { SignaturePlacerScreen(state, actions, fontFamily, dimensionResource(mainPanelWidth)) }
    }
}

fun pageCropperView(
    context: Context,
    state: PageCropperState,
    actions: PageCropperActions,
    regularFont: Int,
    mediumFont: Int,
    mainPanelWidth: Int
): View {
    val fontFamily = fontFamily(regularFont, mediumFont)
    return ComposeView(context).apply {
        val view = this
        setContent {
            PageCropperScreen(state, actions, fontFamily, dimensionResource(mainPanelWidth)) { zones ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    view.systemGestureExclusionRects = zones.map { zone ->
                        Rect(floor(zone.left).toInt(), floor(zone.top).toInt(), ceil(zone.right).toInt(), ceil(zone.bottom).toInt())
                    }
                }
            }
        }
    }
}

private fun fontFamily(regularFont: Int, mediumFont: Int) =
    FontFamily(Font(regularFont, FontWeight.Normal), Font(mediumFont, FontWeight.Medium))
