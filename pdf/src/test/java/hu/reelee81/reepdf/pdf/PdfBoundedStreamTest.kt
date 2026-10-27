package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfBoundedStreamTest {
    @Test
    fun unwrittenStreamUsesItsBufferedSize() {
        val bytes = "bounded data".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(bytes, PdfBoundedStream.read(PdfStream(bytes), 100))
        assertNull(PdfBoundedStream.read(PdfStream(bytes), 2))
    }

    @Test
    fun highlyCompressedStreamStopsAtDecodedLimit() {
        val encoded = ByteArrayOutputStream()
        DeflaterOutputStream(encoded).use { it.write(ByteArray(1024 * 1024)) }
        val stream = PdfStream(encoded.toByteArray()).apply { put(PdfName.Filter, PdfName.FlateDecode) }
        assertNull(PdfBoundedStream.read(stream, 4096))
    }
}
