package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object ViewerIcons {
    internal const val VIEWPORT = 960f
    internal const val STEP_VIEWPORT = 2400f
    internal const val ARROW_BACK_PATH = "m333,534 l222,222 -76,76 -351,-351 353,-353 76,76 -224,224h499v106L333,534Z"
    internal const val EDIT_PATH = "m168.89,791.11h63.33l434.44,-434.44 -63.33,-63.33 -434.44,434.44zM80,880v-188.89l586.67,-585.56q13.33,-12.22 29.44,-18.89 16.11,-6.67 33.89,-6.67t34.44,6.67q16.67,6.67 28.89,20l61.11,62.22q13.33,12.22 19.44,28.89 6.11,16.67 6.11,33.33 0,17.78 -6.11,33.89t-19.44,29.44l-585.56,585.56zM791.11,231.11 L728.89,168.89zM634.44,325.55 L603.33,293.33 666.66,356.66z"
    internal const val FAST_SCROLL_PATH = "M480,880L240,640L297,583L480,766L663,583L720,640L480,880ZM298,376L240,320L480,80L720,320L662,376L480,194L298,376Z"
    internal const val SEARCH_PATH = "M784,840 L532,588q-30,24 -69,38t-83,14q-109,0 -184.5,-75.5T120,380q0,-109 75.5,-184.5T380,120q109,0 184.5,75.5T640,380q0,44 -14,83t-38,69l252,252 -56,56ZM380,560q75,0 127.5,-52.5T560,380q0,-75 -52.5,-127.5T380,200q-75,0 -127.5,52.5T200,380q0,75 52.5,127.5T380,560Z"
    internal const val PREVIOUS_PATH = "M1611,2200L611,1200L1611,200L1789,377L966,1200L1789,2023Z"
    internal const val NEXT_PATH = "M789,2200L611,2023L1434,1200L611,377L789,200L1789,1200Z"

    val arrowBack: ImageVector by lazy { icon("ic_arrow_back_24", ARROW_BACK_PATH, VIEWPORT) }
    val edit: ImageVector by lazy { icon("ic_edit_24", EDIT_PATH, VIEWPORT) }
    val fastScroll: ImageVector by lazy { icon("ic_fast_scroll_24", FAST_SCROLL_PATH, VIEWPORT) }
    val search: ImageVector by lazy { icon("ic_search_24", SEARCH_PATH, VIEWPORT) }
    val previous: ImageVector by lazy { icon("ic_previous_24", PREVIOUS_PATH, STEP_VIEWPORT) }
    val next: ImageVector by lazy { icon("ic_next_24", NEXT_PATH, STEP_VIEWPORT) }

    private fun icon(name: String, path: String, viewport: Float): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = viewport,
            viewportHeight = viewport
        ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
