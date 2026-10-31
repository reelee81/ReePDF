package hu.reelee81.reepdf.core

import java.io.File
import java.io.IOException

class WorkFileBackup(
    private val dir: File,
    sourceName: String,
    nupName: String,
    sourceCopyName: String,
    nupCopyName: String
) {
    private val source = File(dir, sourceName)
    private val nup = File(dir, nupName)
    private val sourceCopy = File(dir, sourceCopyName)
    private val nupCopy = File(dir, nupCopyName)

    fun backup(ensureActive: () -> Unit = {}) {
        discardCopies()
        try {
            if (source.exists()) copy(source, sourceCopy, ensureActive)
            if (nup.exists()) copy(nup, nupCopy, ensureActive)
        } catch (e: Exception) {
            runCatching { discardCopies() }
            throw e
        }
    }

    fun restore(): Boolean {
        dir.listFiles()?.forEach { f ->
            if (f != sourceCopy && f != nupCopy && f != source && f != nup) {
                runCatching { f.delete() }
            }
        }

        val sourceBack = putBack(sourceCopy, source)
        val nupBack = putBack(nupCopy, nup)
        if (sourceBack == null || nupBack == null) {
            throw IOException("Could not restore ${if (sourceBack == null) source.name else nup.name}")
        }
        return nupBack
    }

    private fun putBack(copy: File, target: File): Boolean? {
        if (!copy.exists()) {
            runCatching { target.deleteRecursively() }
            return false
        }
        if (target.exists() && !target.deleteRecursively()) return null
        return if (copy.renameTo(target)) true else null
    }

    private fun discardCopies() {
        if (sourceCopy.exists() && !sourceCopy.deleteRecursively()) throw IOException("Could not delete ${sourceCopy.name}")
        if (nupCopy.exists() && !nupCopy.deleteRecursively()) throw IOException("Could not delete ${nupCopy.name}")
    }

    private fun copy(from: File, to: File, ensureActive: () -> Unit) {
        if (!from.isDirectory) {
            WorkFiles.copy(from, to, ensureActive)
            return
        }
        for (file in from.walkTopDown()) {
            val target = File(to, file.relativeTo(from).path)
            if (file.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) throw IOException("Could not copy ${from.name}")
            } else {
                WorkFiles.copy(file, target, ensureActive)
            }
        }
    }
}
