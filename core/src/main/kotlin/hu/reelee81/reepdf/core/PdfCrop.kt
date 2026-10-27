package hu.reelee81.reepdf.core

data class PdfCropRect(val left: Float, val bottom: Float, val right: Float, val top: Float) {
    val isFullPage: Boolean
        get() = left <= FULL_TOLERANCE && bottom <= FULL_TOLERANCE &&
            right >= 1f - FULL_TOLERANCE && top >= 1f - FULL_TOLERANCE

    companion object {
        const val MIN_SIZE = 0.02f
        private const val FULL_TOLERANCE = 0.002f

        fun of(left: Float, bottom: Float, right: Float, top: Float): PdfCropRect? {
            if (!left.isFinite() || !bottom.isFinite() || !right.isFinite() || !top.isFinite()) return null
            val l = left.coerceIn(0f, 1f)
            val b = bottom.coerceIn(0f, 1f)
            val r = right.coerceIn(0f, 1f)
            val t = top.coerceIn(0f, 1f)
            if (r - l < MIN_SIZE || t - b < MIN_SIZE) return null
            return PdfCropRect(l, b, r, t)
        }
    }
}

object PdfCrops {
    fun encode(crops: Map<Int, PdfCropRect>): String =
        crops.entries.sortedBy { it.key }.joinToString(";") { (index, crop) ->
            "$index:${crop.left},${crop.bottom},${crop.right},${crop.top}"
        }

    fun decode(text: String): Map<Int, PdfCropRect> {
        val result = HashMap<Int, PdfCropRect>()
        for (entry in text.split(';')) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val index = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: continue
            val values = parts[1].split(',').mapNotNull { it.toFloatOrNull() }
            if (values.size != 4) continue
            val crop = PdfCropRect.of(values[0], values[1], values[2], values[3]) ?: continue
            if (!crop.isFullPage) result[index] = crop
        }
        return result
    }

    fun withCrop(crops: Map<Int, PdfCropRect>, indices: Iterable<Int>, crop: PdfCropRect?): Map<Int, PdfCropRect> {
        val result = HashMap(crops)
        for (index in indices) {
            if (crop == null || crop.isFullPage) result.remove(index) else result[index] = crop
        }
        return result
    }

    fun afterRemoval(crops: Map<Int, PdfCropRect>, removed: Set<Int>): Map<Int, PdfCropRect> {
        if (removed.isEmpty() || crops.isEmpty()) return crops
        val sortedRemoved = removed.sorted()
        val result = HashMap<Int, PdfCropRect>()
        for ((index, crop) in crops) {
            if (index in removed) continue
            val shift = countBelow(sortedRemoved, index)
            result[index - shift] = crop
        }
        return result
    }

    fun toDisplay(crop: PdfCropRect?, quarterTurns: Int): FloatArray {
        val source = crop ?: PdfCropRect(0f, 0f, 1f, 1f)
        val first = rotate(source.left, source.bottom, quarterTurns)
        val second = rotate(source.right, source.top, quarterTurns)
        return floatArrayOf(
            minOf(first[0], second[0]),
            1f - maxOf(first[1], second[1]),
            maxOf(first[0], second[0]),
            1f - minOf(first[1], second[1])
        )
    }

    fun fromDisplay(left: Float, top: Float, right: Float, bottom: Float, quarterTurns: Int): PdfCropRect? {
        val inverse = (4 - Math.floorMod(quarterTurns, 4)) % 4
        val first = rotate(left, 1f - bottom, inverse)
        val second = rotate(right, 1f - top, inverse)
        return PdfCropRect.of(
            minOf(first[0], second[0]),
            minOf(first[1], second[1]),
            maxOf(first[0], second[0]),
            maxOf(first[1], second[1])
        )
    }

    private fun rotate(x: Float, y: Float, quarterTurns: Int): FloatArray {
        var u = x
        var v = y
        repeat(Math.floorMod(quarterTurns, 4)) {
            val next = 1f - v
            v = u
            u = next
        }
        return floatArrayOf(u, v)
    }

    fun afterReorder(crops: Map<Int, PdfCropRect>, newToOld: IntArray): Map<Int, PdfCropRect> {
        if (crops.isEmpty()) return crops
        val result = HashMap<Int, PdfCropRect>()
        newToOld.forEachIndexed { newIndex, oldIndex -> crops[oldIndex]?.let { result[newIndex] = it } }
        return result
    }

    private fun countBelow(sorted: List<Int>, value: Int): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sorted[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }
}
