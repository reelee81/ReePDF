package hu.reelee81.reepdf.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ProgressTicker {
    suspend fun <T> run(
        tick: Duration,
        percent: () -> Int?,
        report: (Int?) -> Unit,
        onCancel: () -> Unit,
        block: suspend () -> T
    ): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val ticker = launch {
            try {
                while (true) {
                    delay(tick)
                    report(percent())
                }
            } catch (e: CancellationException) {
                if (!finished.get()) onCancel()
                throw e
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            ticker.cancel()
        }
    }
}
