package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.Rectangle
import hu.reelee81.reepdf.core.PdfCropRect
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfCropBoxTest {
    private val crop = PdfCropRect(0.1f, 0.2f, 0.6f, 0.9f)

    @Test
    fun cropIsAppliedRelativeToTheVisibleBox() {
        val result = crop.applyTo(Rectangle(10f, 20f, 200f, 100f))
        assertEquals(30f, result.x, 0.001f)
        assertEquals(40f, result.y, 0.001f)
        assertEquals(100f, result.width, 0.001f)
        assertEquals(70f, result.height, 0.001f)
    }
}
