package hu.reelee81.reepdf.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

object PrefsXmlFile {
    const val MAX_BYTES = 1024 * 1024

    fun read(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
    }

    private const val BUFFER_SIZE = 8 * 1024
}
