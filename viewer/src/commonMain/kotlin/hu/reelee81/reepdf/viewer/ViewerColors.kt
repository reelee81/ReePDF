package hu.reelee81.reepdf.viewer

class ViewerColors private constructor(night: Boolean) {
    private val p = if (night) ReePalette.dark else ReePalette.light

    val scroller = if (night) p.darkLight020 else p.darkLight080
    val scrollerText = if (night) p.darkLight090 else p.darkLight010
    val scrollerIcon = if (night) p.darkLight080 else p.darkLight025
    val page = p.white
    val pageText = p.darkLight025
    val searchBox = if (night) p.darkLight020 else p.darkLight080
    val searchHint = p.darkLight050
    val searchText = if (night) p.darkLight100 else p.darkLight000
    val searchHit = p.lemon100
    val searchCurrentHit = p.teal100
    val selection = p.cropGridColor
    val selectionHandle = p.gummy150
    val cropFrame = p.cropFrameColor
    val cropShade = p.cropShadeColor
    val cropGrid = p.cropGridColor

    companion object {
        val light = ViewerColors(night = false)
        val dark = ViewerColors(night = true)
    }
}
