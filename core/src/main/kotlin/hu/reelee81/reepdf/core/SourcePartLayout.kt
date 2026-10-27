package hu.reelee81.reepdf.core

import kotlin.math.max
import kotlin.math.min

class SourcePartLayout(pageCounts: List<Int>) {
    data class Run(val part: Int, val first: Int, val last: Int)

    val pageCounts: List<Int> = pageCounts.toList()
    private val starts = IntArray(this.pageCounts.size)

    val parts: Int get() = pageCounts.size
    val total: Int

    init {
        require(this.pageCounts.all { it > 0 }) { "Every source part needs at least one page" }
        var sum = 0
        for (part in this.pageCounts.indices) {
            starts[part] = sum
            sum += this.pageCounts[part]
        }
        total = sum
    }

    fun start(part: Int): Int = starts[part]

    fun end(part: Int): Int = starts[part] + pageCounts[part] - 1

    fun partOf(page: Int): Int {
        require(page in 0 until total) { "Page $page is outside the source (0 until $total)" }
        var low = 0
        var high = parts - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (starts[middle] <= page) low = middle else high = middle - 1
        }
        return low
    }

    fun local(page: Int): Int = page - starts[partOf(page)]

    fun runs(first: Int, last: Int): List<Run> {
        if (first > last) return emptyList()
        val runs = ArrayList<Run>()
        var part = partOf(first)
        val lastPart = partOf(last)
        while (part <= lastPart) {
            val from = max(first, start(part))
            val to = min(last, end(part))
            runs += Run(part, from - start(part), to - start(part))
            part++
        }
        return runs
    }
}
