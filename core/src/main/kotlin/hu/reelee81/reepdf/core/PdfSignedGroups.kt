package hu.reelee81.reepdf.core

import kotlin.math.min

object PdfSignedGroups {
    fun mergedIndices(signedStarts: List<Int>, per: Int, originalTotal: Int): Set<Int> =
        signedStarts.flatMap { start -> start + 1 until min(originalTotal, start + per) }.toSet()

    fun afterMerge(
        values: IntArray,
        originalTotal: Int,
        signedStarts: List<Int>,
        merged: Set<Int>,
        signedValue: Int,
        missingValue: Int
    ): IntArray {
        val source = IntArray(originalTotal) { values.getOrElse(it) { missingValue } }
        for (start in signedStarts) {
            if (start in source.indices) source[start] = signedValue
        }
        return source.filterIndexed { index, _ -> index !in merged }.toIntArray()
    }
}
