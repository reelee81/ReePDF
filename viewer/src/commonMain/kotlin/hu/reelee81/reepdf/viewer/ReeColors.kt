package hu.reelee81.reepdf.viewer

class ReeColors private constructor(night: Boolean) {
    private val p = if (night) ReePalette.dark else ReePalette.light

    val aboutLinkColor = ReeColor(if (night) p.gummy150 else p.gummy100)
    val alertdialogBackgroundColor = ReeColor(if (night) p.darkLight025 else p.darkLight100)
    val alertdialogSignaturePositionBackgroundColor = ReeColor(if (night) p.darkLight025 else p.darkLight090)
    val appControlActivatedBgColor = ReeColor(if (night) p.teal050 else p.teal150)
    val appControlActivatedOtherColor = ReeColor(p.teal100)
    val appControlNormalColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val edittextBgColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight000,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.FOCUSED to p.teal100
    )
    val edittextHintTextColor = ReeColor(
        if (night) p.darkLight025 else p.darkLight075,
        ReeColorState.DISABLED to (if (night) p.darkLight010 else p.darkLight090)
    )
    val edittextTextColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight000,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065)
    )
    val fabButtonBgColor = ReeColor(if (night) p.bubble100 else p.gummy150)
    val fabButtonRippleColor = ReeColor(if (night) p.bubble125 else p.gummy125)
    val fabButtonTextColor = ReeColor(p.darkLight100)
    val icCheckboxAllColor = ReeColor(
        if (night) p.darkLight050 else p.darkLight090,
        ReeColorState.DISABLED to (if (night) p.darkLight010 else p.darkLight065),
        ReeColorState.CHECKED to p.darkLight100
    )
    val icCheckboxCustomColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight025,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.CHECKED to (if (night) p.darkLight100 else p.darkLight025)
    )
    val icCheckboxFilenameColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight025,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.CHECKED to p.teal100
    )
    val icCheckboxPageColor = ReeColor(
        p.thumbDarkLight050,
        ReeColorState.DISABLED to p.thumbDarkLight075
    )
    val icCheckboxPasswordColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight025,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.CHECKED to p.teal100
    )
    val icCustomIconColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight000,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065)
    )
    val icDeleteRotatePositionAllColor = ReeColor(
        p.darkLight100,
        ReeColorState.DISABLED to (if (night) p.darkLight010 else p.darkLight065)
    )
    val icDeleteRotatePositionPageCropColor = ReeColor(
        p.thumbDarkLight050,
        ReeColorState.DISABLED to p.thumbDarkLight075
    )
    val icEditColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight025,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065)
    )
    val icScrollButtonColor = ReeColor(p.darkLight100)
    val icVisibilityOffColor = ReeColor(if (night) p.darkLight075 else p.darkLight050)
    val icVisibilityOnColor = ReeColor(if (night) p.darkLight100 else p.darkLight025)
    val listDividerColor = ReeColor(p.transparent)
    val listFirstTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val listSecondaryTextColor = ReeColor(if (night) p.darkLight075 else p.darkLight025)
    val materialButtonBgColor = ReeColor(
        if (night) p.bubble100 else p.gummy100,
        ReeColorState.DISABLED to (if (night) p.bubble050 else p.gummy150)
    )
    val materialButtonRippleColor = ReeColor(if (night) p.bubble125 else p.gummy075)
    val materialButtonTextColor = ReeColor(
        p.darkLight100,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight025)
    )
    val materialTextbuttonRippleColor = ReeColor(if (night) p.darkLight050 else p.darkLight065)
    val materialTextbuttonTextColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight000,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.PRESSED to (if (night) p.darkLight075 else p.darkLight025)
    )
    val menuBackgroundColor = ReeColor(if (night) p.darkLight025 else p.darkLight100)
    val menuTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val normalTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val openWithCancelBgColor = ReeColor(if (night) p.darkLight035 else p.darkLight090)
    val openWithCancelTextColor = ReeColor(if (night) p.darkLight090 else p.darkLight020)
    val openWithDotColor = ReeColor(
        if (night) p.darkLight050 else p.darkLight080,
        ReeColorState.SELECTED to (if (night) p.darkLight090 else p.darkLight035)
    )
    val passwordErrorTextColor = ReeColor(p.love50)
    val passwordHintTextColor = ReeColor(if (night) p.darkLight050 else p.darkLight065)
    val pdfViewerSbBgColor = ReeColor(if (night) p.darkLight000 else p.darkLight090)
    val progressIndicatorColor = ReeColor(p.teal100)
    val progressInfoTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val progressTrackColor = ReeColor(if (night) p.darkLight025 else p.darkLight100)
    val radioButtonBgColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight025,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065),
        ReeColorState.CHECKED to p.teal100
    )
    val radioSwitchTextColor = ReeColor(
        if (night) p.darkLight100 else p.darkLight000,
        ReeColorState.DISABLED to (if (night) p.darkLight050 else p.darkLight065)
    )
    val signPositionBgColor = ReeColor(if (night) p.darkLight025 else p.darkLight090)
    val signatureChooserTitleTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val signatureDividerColor = ReeColor(if (night) p.white.copy(alpha = 0.12f) else p.black.copy(alpha = 0.12f))
    val signaturePositionPdfBg = ReeColor(p.darkLight100)
    val switchMarkColor = ReeColor(
        if (night) p.darkLight038 else p.darkLight065,
        ReeColorState.CHECKED to (if (night) p.teal025 else p.teal100)
    )
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
    val thumbTextColor = ReeColor(p.darkLight100)
    val toolbarNavigationIconColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)
    val toolbarSubtitleTextColor = ReeColor(if (night) p.darkLight075 else p.darkLight025)
    val toolbarTitleTextColor = ReeColor(if (night) p.darkLight100 else p.darkLight000)

    companion object {
        val light = ReeColors(night = false)
        val dark = ReeColors(night = true)
    }
}
