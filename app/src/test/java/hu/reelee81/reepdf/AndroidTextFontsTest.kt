package hu.reelee81.reepdf

import hu.reelee81.reepdf.pdf.PdfMarkText
import hu.reelee81.reepdf.viewer.ViewerMarkText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTextFontsTest {
    @Test
    fun everyTextWeightHasItsOwnRobotoFile() {
        val files = listOf(
            R.font.roboto_thin,
            R.font.roboto_extralight,
            R.font.roboto_light,
            R.font.roboto_regular,
            R.font.roboto_medium,
            R.font.roboto_semibold,
            R.font.roboto_bold,
            R.font.roboto_extrabold,
            R.font.roboto_black
        )

        assertEquals(files, ViewerMarkText.WEIGHTS.map(AndroidTextFonts::resource))
        assertArrayEquals(ViewerMarkText.WEIGHTS, PdfMarkText.WEIGHTS)
    }
}
