package hu.reelee81.reepdf.pdf

import hu.reelee81.reepdf.core.ProgressMath
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

class ProgressTracker(private val clock: () -> Long) {
    @Volatile private var total = 0L
    @Volatile private var idleSince = 0L
    @Volatile private var generation = 0L
    @Volatile var accepting = true

    private val done = AtomicLong(0L)
    private var lastPercent = -1

    var startedAt = 0L
        private set

    private inner class Stage(private val generation: Long) : PdfProgress {
        override fun advance(units: Int) {
            if (generation != this@ProgressTracker.generation) throw CancellationException()
            done.addAndGet(units.toLong())
            idleSince = 0L
        }

        override fun idle() {
            if (generation == this@ProgressTracker.generation && idleSince == 0L) {
                idleSince = clock()
            }
        }
    }

    fun reset() {
        done.set(0L)
        total = 0L
        startedAt = clock()
        idleSince = startedAt
        lastPercent = -1
    }

    fun stage(remaining: Long): PdfProgress {
        if (!accepting) return Stage(-1L)
        val stage = Stage(generation)
        total = done.get() + remaining
        return stage
    }

    fun cancel() {
        generation++
        accepting = false
    }

    fun percent(now: Long, idleMs: Long): Int? =
        ProgressMath.measuredPercent(total, done.get(), idleSince, now, lastPercent, idleMs)
            ?.also { lastPercent = it }

    fun doneUnits(): Long = done.get()

    fun resetDoneUnits(units: Long) {
        done.set(units)
    }

    class Snapshot(val done: Long, val total: Long, val idleSince: Long, val lastPercent: Int)

    fun snapshot(): Snapshot = Snapshot(done.get(), total, idleSince, lastPercent)

    fun continueFrom(snapshot: Snapshot) {
        done.set(snapshot.done)
        total = snapshot.total
        idleSince = snapshot.idleSince
        lastPercent = snapshot.lastPercent
    }
}
