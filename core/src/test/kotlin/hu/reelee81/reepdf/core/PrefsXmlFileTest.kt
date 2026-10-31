package hu.reelee81.reepdf.core

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsXmlFileTest {
    @Test
    fun aFileOfExactlyTheLimitIsReadWhole() {
        val bytes = ByteArray(PrefsXmlFile.MAX_BYTES) { (it % 251).toByte() }

        assertArrayEquals(bytes, PrefsXmlFile.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun aFileOneByteOverTheLimitIsRejected() {
        assertNull(PrefsXmlFile.read(ByteArrayInputStream(ByteArray(PrefsXmlFile.MAX_BYTES + 1))))
    }

    @Test
    fun anEmptyFileIsReadAsEmpty() {
        assertArrayEquals(ByteArray(0), PrefsXmlFile.read(ByteArrayInputStream(ByteArray(0))))
    }
}
