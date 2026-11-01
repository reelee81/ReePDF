package hu.reelee81.reepdf.core

import hu.reelee81.reepdf.core.NupGeometry.DEFAULT_POSITION
import hu.reelee81.reepdf.core.NupGeometry.validPosition
import kotlin.math.min

object SourcePageArrays {
    fun rotationsSized(rotation: IntArray, total: Int): IntArray =
        if (rotation.size != total) IntArray(total) else rotation

    fun rotationsGrown(rotation: IntArray, total: Int): IntArray =
        if (rotation.size < total) rotation.copyOf(total) else rotation

    fun quarterTurnsOfPageRotate(degrees: Int): Int? =
        if (degrees % 90 != 0) null else (-(degrees / 90)).mod(4)

    fun withOwnRotations(rotation: IntArray, first: Int, quarterTurns: IntArray): IntArray {
        val result = rotation.copyOf()
        for ((index, turns) in quarterTurns.withIndex()) {
            val page = first + index
            if (turns != 0 && page in result.indices) result[page] = turns
        }
        return result
    }

    fun positionsSized(positions: IntArray, total: Int, defaultPosition: Int): IntArray {
        if (positions.size == total) return positions
        return IntArray(total) { index -> validPosition(positions.getOrElse(index) { defaultPosition }) }
    }

    fun withRotatedGroups(rotation: IntArray, groups: Iterable<Int>, per: Int, total: Int): IntArray {
        val result = rotation.copyOf()
        for (group in groups) {
            val groupStart = group * per
            val groupEnd = min(total - 1, groupStart + per - 1)
            if (groupStart <= groupEnd) {
                for (i in groupStart..groupEnd) {
                    result[i] = (result[i] + 1) % 4
                }
            }
        }
        return result
    }

    fun withGroupPosition(positions: IntArray, groups: Iterable<Int>, per: Int, total: Int, position: Int): IntArray {
        val result = positions.copyOf()
        for (group in groups) {
            val groupStart = group * per
            val groupEnd = min(total - 1, groupStart + per - 1)
            if (groupStart <= groupEnd) {
                for (i in groupStart..groupEnd) {
                    result[i] = position
                }
            }
        }
        return result
    }

    fun afterRemoval(values: IntArray, removed: Set<Int>): IntArray =
        values.filterIndexed { index, _ -> index !in removed }.toIntArray()

    fun reorderedIndices(groupOrder: Iterable<Int>, per: Int, total: Int): IntArray {
        val newToOld = IntArray(total) { -1 }
        var destIndex = 0
        for (group in groupOrder) {
            val start = group * per
            val end = min(total - 1, start + per - 1)
            if (start < total) {
                for (i in start..end) {
                    if (destIndex < total) newToOld[destIndex++] = i
                }
            }
        }
        return newToOld
    }

    fun rotationsAfterReorder(rotation: IntArray, newToOld: IntArray): IntArray =
        IntArray(newToOld.size) { newIndex ->
            val oldIndex = newToOld[newIndex]
            if (oldIndex < 0) 0 else rotation.getOrElse(oldIndex) { 0 }
        }

    fun positionsAfterReorder(positions: IntArray, newToOld: IntArray, defaultPosition: Int): IntArray =
        IntArray(newToOld.size) { newIndex ->
            val oldIndex = newToOld[newIndex]
            if (oldIndex < 0) DEFAULT_POSITION else validPosition(positions.getOrElse(oldIndex) { defaultPosition })
        }
}
