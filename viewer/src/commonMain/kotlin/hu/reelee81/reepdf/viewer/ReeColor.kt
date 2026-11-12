package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.Color

enum class ReeColorState { DISABLED, CHECKED, FOCUSED, PRESSED, SELECTED }

class ReeColor(val default: Color, vararg rules: Pair<ReeColorState, Color>) {
    val rules: List<Pair<ReeColorState, Color>> = rules.toList()

    fun of(vararg states: ReeColorState): Color =
        rules.firstOrNull { (state, _) -> state in states }?.second ?: default
}
