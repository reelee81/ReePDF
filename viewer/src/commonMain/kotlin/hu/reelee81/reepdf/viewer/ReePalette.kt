package hu.reelee81.reepdf.viewer

import androidx.compose.ui.graphics.Color

class ReePalette private constructor(night: Boolean) {
    val black = Color(0xFF000000)
    val white = Color(0xFFFFFFFF)
    val transparent = Color(0x00000000)
    val appBackgroundColor = if (night) Color(0xFF000000) else Color(0xFFFFFFFF)
    val bitmapTvBackgroundColor = if (night) Color(0xFF333333) else Color(0xFF808080)
    val dialogOverlayBackgroundColor = Color(0x80000000)
    val love50 = Color(0xFFF59393)
    val cropFrameColor = Color(0xFF0062FF)
    val cropShadeColor = Color(0x80000000)
    val cropGridColor = Color(0x9903DAC5)
    val gummy150 = Color(0xFF7FB0FF)
    val gummy125 = Color(0xFF4089FF)
    val gummy100 = Color(0xFF0062FF)
    val gummy075 = Color(0xFF0049BF)
    val bubble125 = Color(0xFF406EB9)
    val bubble100 = Color(0xFF003EA2)
    val bubble050 = Color(0xFF001F51)
    val teal150 = Color(0xFF81ECE2)
    val teal100 = Color(0xFF03DAC5)
    val teal050 = Color(0xFF026D63)
    val teal025 = Color(0xFF02564D)
    val lemon100 = Color(0xFFFFFF00)
    val thumbDarkLight075 = Color(0xFFBFBFBF)
    val thumbDarkLight050 = Color(0xFF808080)
    val darkLight100 = Color(0xFFFFFFFF)
    val darkLight095 = Color(0xFFF2F2F2)
    val darkLight090 = Color(0xFFE5E5E5)
    val darkLight080 = Color(0xFFCCCCCC)
    val darkLight075 = Color(0xFFBFBFBF)
    val darkLight065 = Color(0xFFA6A6A6)
    val darkLight050 = Color(0xFF808080)
    val darkLight038 = Color(0xFF616161)
    val darkLight035 = Color(0xFF595959)
    val darkLight025 = Color(0xFF404040)
    val darkLight020 = Color(0xFF333333)
    val darkLight010 = Color(0xFF1A1A1A)
    val darkLight005 = Color(0xFF0D0D0D)
    val darkLight000 = Color(0xFF000000)

    companion object {
        val light = ReePalette(night = false)
        val dark = ReePalette(night = true)
    }
}
