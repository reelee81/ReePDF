package hu.reelee81.reepdf.viewer

import android.content.Context
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

fun viewerView(context: Context, state: ViewerState, actions: ViewerActions, regularFont: Int, mediumFont: Int): View {
    val fontFamily = FontFamily(Font(regularFont, FontWeight.Normal), Font(mediumFont, FontWeight.Medium))
    return ComposeView(context).apply {
        setContent { ViewerScreen(state, actions, fontFamily) }
    }
}
