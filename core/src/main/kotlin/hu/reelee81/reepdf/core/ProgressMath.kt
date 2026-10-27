package hu.reelee81.reepdf.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ProgressMath {
    fun signedPageWeight(highDpi: Boolean, scanEffect: Boolean, grayscale: Boolean): Int {
        var weight = if (highDpi) 280.0 else 100.0
        if (scanEffect) weight *= if (highDpi) 1.3 else 1.2
        if (grayscale) weight *= if (highDpi) 1.15 else 1.1
        return weight.roundToInt()
    }

    fun layoutUnits(sourcePages: Int, per: Int): Long {
        val outPages = (sourcePages + per - 1) / per
        return sourcePages.toLong() + outPages + if (per == 1) sourcePages else 0
    }

    fun measuredPercent(total: Long, done: Long, idleSince: Long, now: Long, lastPercent: Int, idleMs: Long): Int? {
        val clampedDone = min(done, total)
        val measuring = total > 0 && clampedDone > 0 && (idleSince == 0L || now - idleSince < idleMs)
        return if (measuring) max(lastPercent, (clampedDone * 100 / total).toInt()) else null
    }
}
