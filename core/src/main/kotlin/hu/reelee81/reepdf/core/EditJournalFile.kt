package hu.reelee81.reepdf.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class EditJournalFile(scope: CoroutineScope, private val file: () -> File) {
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var header: ByteArray? = null
    private var starting = true
    private var broken = false

    init {
        scope.launch {
            for (task in tasks) {
                try {
                    task()
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun begin(header: ByteArray) {
        submit {
            this.header = header
            starting = true
            broken = false
        }
    }

    fun append(record: () -> ByteArray) {
        submit {
            val head = header ?: return@submit
            if (broken) return@submit
            guarded {
                val bytes = record()
                FileOutputStream(file(), !starting).use { output ->
                    if (starting) output.write(head)
                    output.write(bytes)
                }
                starting = false
            }
        }
    }

    fun rewrite(records: () -> Sequence<ByteArray>) {
        submit {
            val head = header ?: return@submit
            guarded {
                val target = file()
                val temporary = File(target.parentFile, target.name + TEMPORARY_SUFFIX)
                try {
                    BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                        output.write(head)
                        for (record in records()) output.write(record)
                    }
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    temporary.delete()
                    throw e
                }
                starting = false
                broken = false
            }
        }
    }

    fun discard() {
        submit {
            starting = true
            broken = false
            remove()
        }
    }

    suspend fun <T> read(reader: (InputStream) -> T): T? {
        val result = CompletableDeferred<T?>()
        submit {
            try {
                val target = file()
                result.complete(if (target.isFile) target.inputStream().buffered().use(reader) else null)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }

    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        submit { done.complete(Unit) }
        done.await()
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            broken = true
            starting = true
            try {
                remove()
            } catch (_: IOException) {
            }
        }
    }

    private fun remove() {
        val target = file()
        if (!target.delete() && target.exists()) throw IOException("Cannot delete ${target.name}")
    }

    private fun submit(task: suspend () -> Unit) {
        tasks.trySend(task)
    }

    private companion object {
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}
