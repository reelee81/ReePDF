package hu.reelee81.reepdf.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    fun backup() {
        discardCopies()
        try {
            if (source.exists()) copy(source, sourceCopy)
            if (nup.exists()) copy(nup, nupCopy)
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

        putBack(sourceCopy, source)
        return putBack(nupCopy, nup)
    }

    private fun putBack(copy: File, target: File): Boolean {
        if (!copy.exists()) {
            runCatching { target.deleteRecursively() }
            return false
        }
        if (target.exists() && !target.deleteRecursively()) return false
        return copy.renameTo(target)
    }

    private fun discardCopies() {
        if (sourceCopy.exists() && !sourceCopy.deleteRecursively()) throw IOException("Could not delete ${sourceCopy.name}")
        if (nupCopy.exists() && !nupCopy.deleteRecursively()) throw IOException("Could not delete ${nupCopy.name}")
    }

    private fun copy(from: File, to: File) {
        if (from.isDirectory) {
            check(from.copyRecursively(to)) { "Could not copy ${from.name}" }
            return
        }
        FileInputStream(from).use { input ->
            FileOutputStream(to, false).use { output ->
                input.copyTo(output)
            }
        }
    }
}
