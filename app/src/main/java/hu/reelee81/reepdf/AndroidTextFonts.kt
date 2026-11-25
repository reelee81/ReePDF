package hu.reelee81.reepdf

import android.content.res.Resources
import hu.reelee81.reepdf.pdf.PdfTextFonts
import hu.reelee81.reepdf.viewer.ViewerMarkText
import hu.reelee81.reepdf.viewer.ViewerTextFontSource
import hu.reelee81.reepdf.viewer.ViewerTextFonts

class AndroidTextFonts(private val resources: Resources) : PdfTextFonts, ViewerTextFontSource {
    override fun bytes(weight: Int): ByteArray = resources.openRawResource(resource(weight)).use { it.readBytes() }

    fun preloaded(): ViewerTextFonts = ViewerTextFonts(this).also { fonts -> ViewerMarkText.WEIGHTS.forEach { fonts.font(it) } }

    private fun resource(weight: Int): Int = when (weight) {
        400 -> R.font.roboto_regular
        500 -> R.font.roboto_medium
        600 -> R.font.roboto_semibold
        700 -> R.font.roboto_bold
        900 -> R.font.roboto_black
        else -> throw IllegalArgumentException("No Roboto file for weight $weight")
    }
}
