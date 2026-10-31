package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException

object WorkFiles {
    fun replace(work: File, target: File) {
        if (work.renameTo(target)) return
        if (!work.isFile) throw IOException("Could not rename ${work.name}")
        if (target.exists() && !target.deleteRecursively()) throw IOException("Could not replace ${target.name}")
        if (!work.renameTo(target)) throw IOException("Could not rename ${work.name}")
    }
}
