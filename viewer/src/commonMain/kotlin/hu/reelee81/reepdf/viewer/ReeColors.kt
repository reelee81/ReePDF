package hu.reelee81.reepdf.viewer

class ReeColors private constructor(night: Boolean) {
    private val p = if (night) ReePalette.dark else ReePalette.light

    val alertdialogBackgroundColor = ReeColor(if (night) p.darkLight025 else p.darkLight100)
    val appControlActivatedBgColor = ReeColor(if (night) p.teal050 else p.teal150)
    val appControlActivatedOtherColor = ReeColor(p.teal100)
    val icCheckboxPasswordColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight025,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.CHECKED to p.teal100
    )
    val materialTextbuttonRippleColor = ReeColor(if (night) p.darkLight050 else p.darkLight065)
    val materialTextbuttonTextColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight000,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.PRESSED to (if (night) p.darkLight075 else p.darkLight025)
    )
    val normalTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val pdfViewerSbBgColor = ReeColor(if (night) p.darkLight000 else p.darkLight090)
    val progressIndicatorColor = ReeColor(p.teal100)
    val progressInfoTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val progressTrackColor = ReeColor(if (night) p.darkLight025 else p.darkLight100)
    val switchThumbColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight095,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight080),
        ReeColorState.CHECKED to p.teal100
    )
    val switchTrackColor = ReeColor(
        if (night) p.darkLight050 else p.darkLight075,
        ReeColorState.DISABLED to (if (night) p.darkLight025 else p.darkLight065),
        ReeColorState.CHECKED to (if (night) p.teal050 else p.teal150)
    )
    val toolbarNavigationIconColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val toolbarTitleTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)

    companion object {
        val light = ReeColors(night = false)
        val dark = ReeColors(night = true)
    }
}
