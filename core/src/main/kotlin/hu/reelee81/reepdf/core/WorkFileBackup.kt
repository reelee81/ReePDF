package hu.reelee81.reepdf.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
        if (source.exists()) {
            runCatching { copy(source, sourceCopy) }
        }

        if (nup.exists()) {
            runCatching { copy(nup, nupCopy) }
        }
    }

    fun restore(): Boolean {
        var restoredNupBackup = false

        dir.listFiles()?.forEach { f ->
            if (f != sourceCopy && f != nupCopy) {
                runCatching { if (f == source || f == nup) f.deleteRecursively() else f.delete() }
            }
        }

        if (sourceCopy.exists()) {
            runCatching { copy(sourceCopy, source) }

            runCatching { sourceCopy.deleteRecursively() }
        }

        if (nupCopy.exists()) {
            runCatching { copy(nupCopy, nup) }.onSuccess {
                restoredNupBackup = true
            }

            runCatching { nupCopy.deleteRecursively() }
        }

        return restoredNupBackup
    }

    private fun copy(from: File, to: File) {
        if (from.isDirectory) {
            to.deleteRecursively()
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
