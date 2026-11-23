package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class VectorSignatureFiles(private val dir: File) {
    fun read(slot: Int): String? {
        val file = file(slot)
        if (!file.isFile || file.length() > MAX_BYTES) return null
        return file.readText(Charsets.UTF_8)
    }

    fun write(slot: Int, text: String) {
        val target = file(slot)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.name}")
        val temporary = File(dir, target.name + TEMPORARY_SUFFIX)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
    }

    fun delete(slot: Int) {
        val file = file(slot)
        if (!file.delete() && file.exists()) throw IOException("Cannot delete ${file.name}")
    }

    private fun file(slot: Int): File {
        require(slot in 0 until SLOTS) { "Invalid signature slot $slot" }
        return File(dir, "$PREFIX${slot + 1}$SUFFIX")
    }

    companion object {
        const val SLOTS = 2
        const val MAX_BYTES = 512L * 1024
        private const val PREFIX = "vector_signature_"
        private const val SUFFIX = ".txt"
        private const val TEMPORARY_SUFFIX = ".tmp"
    }
}
