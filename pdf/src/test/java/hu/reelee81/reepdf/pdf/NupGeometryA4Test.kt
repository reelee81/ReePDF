package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.geom.PageSize
import hu.reelee81.reepdf.core.NupGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class NupGeometryA4Test {
    @Test
    fun theCoreA4SizeMatchesITextA4() {
        assertEquals(NupGeometry.A4_WIDTH_PT, PageSize.A4.width, 0f)
        assertEquals(NupGeometry.A4_HEIGHT_PT, PageSize.A4.height, 0f)
        assertEquals(NupGeometry.A4_HEIGHT_PT, PageSize.A4.rotate().width, 0f)
        assertEquals(NupGeometry.A4_WIDTH_PT, PageSize.A4.rotate().height, 0f)
    }
}
