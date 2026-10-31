package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException

object WorkFiles {
    fun replace(work: File, target: File) = replace(work, target, File::renameTo)

    internal fun replace(work: File, target: File, rename: (File, File) -> Boolean) {
        if (rename(work, target)) return
        if (!work.isFile) throw IOException("Could not rename ${work.name}")
        if (!target.exists()) {
            if (!rename(work, target)) throw IOException("Could not rename ${work.name}")
            return
        }
        val old = File(target.path + OLD_SUFFIX)
        if (old.exists() && !old.deleteRecursively()) throw IOException("Could not replace ${target.name}")
        if (!rename(target, old)) throw IOException("Could not replace ${target.name}")
        if (!rename(work, target)) {
            rename(old, target)
            throw IOException("Could not rename ${work.name}")
        }
        old.deleteRecursively()
    }

    fun copy(from: File, to: File, ensureActive: () -> Unit) {
        val buffer = ByteArray(COPY_BUFFER)
        from.inputStream().use { input ->
            to.outputStream().use { output ->
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private const val OLD_SUFFIX = ".old"
    private const val COPY_BUFFER = 64 * 1024
}
