package hu.reelee81.reepdf.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class WorkFileCleaner(scope: CoroutineScope) {
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)

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

    fun clear(
        dir: () -> File,
        keep: (File) -> Boolean = { false },
        ready: suspend () -> Boolean = { true },
        done: () -> Unit = {}
    ) {
        submit {
            try {
                if (!ready()) return@submit
                dir().listFiles()?.forEach { file ->
                    if (!keep(file)) file.deleteRecursively()
                }
            } finally {
                done()
            }
        }
    }

    fun delete(dir: () -> File, names: List<String>) {
        submit {
            val root = dir()
            for (name in names) File(root, name).deleteRecursively()
        }
    }

    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        submit { done.complete(Unit) }
        done.await()
    }

    private fun submit(task: suspend () -> Unit) {
        tasks.trySend(task)
    }
}
