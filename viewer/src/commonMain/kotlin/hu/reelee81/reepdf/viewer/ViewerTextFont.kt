package hu.reelee81.reepdf.viewer

fun interface ViewerTextFontSource {
    fun bytes(weight: Int): ByteArray
}

class ViewerTextFonts(private val source: ViewerTextFontSource) {
    private val fonts = HashMap<Int, ViewerTextFont>()

    fun font(weight: Int): ViewerTextFont = fonts.getOrPut(weight) { ViewerTextFont.parse(source.bytes(weight)) }
}

class ViewerTextFont private constructor(
    private val codes: IntArray,
    private val glyphs: IntArray,
    private val advances: IntArray,
    val unitsPerEm: Int,
    val ascender: Int,
    val descender: Int,
    val lineGap: Int
) {
    fun has(codePoint: Int): Boolean = glyph(codePoint) > 0

    fun advance(codePoint: Int): Int {
        val glyph = glyph(codePoint)
        if (glyph <= 0) return 0
        return advances[minOf(glyph, advances.lastIndex)] * THOUSAND / unitsPerEm
    }

    private fun glyph(codePoint: Int): Int {
        val at = codes.binarySearch(codePoint)
        return if (at >= 0) glyphs[at] else 0
    }

    private fun IntArray.binarySearch(value: Int): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val found = this[middle]
            when {
                found < value -> low = middle + 1
                found > value -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    companion object {
        private const val THOUSAND = 1000
        private const val UNICODE_FULL = 10
        private const val UNICODE_BMP = 1
        private const val WINDOWS = 3
        private const val UNICODE = 0

        fun parse(font: ByteArray): ViewerTextFont {
            val data = Bytes(font)
            val tables = HashMap<String, Int>()
            val count = data.u16(4)
            for (index in 0 until count) {
                val entry = 12 + index * 16
                tables[font.decodeToString(entry, entry + 4)] = data.u32(entry + 8)
            }
            val head = tables["head"] ?: throw IllegalArgumentException("The font has no head table")
            val hhea = tables["hhea"] ?: throw IllegalArgumentException("The font has no hhea table")
            val maxp = tables["maxp"] ?: throw IllegalArgumentException("The font has no maxp table")
            val hmtx = tables["hmtx"] ?: throw IllegalArgumentException("The font has no hmtx table")
            val cmap = tables["cmap"] ?: throw IllegalArgumentException("The font has no cmap table")
            val unitsPerEm = data.u16(head + 18)
            require(unitsPerEm > 0) { "The font has no units per em" }
            val glyphCount = data.u16(maxp + 4)
            val metrics = data.u16(hhea + 34).coerceIn(1, glyphCount.coerceAtLeast(1))
            val advances = IntArray(metrics) { data.u16(hmtx + it * 4) }
            val (codes, glyphs) = characterMap(data, cmap, glyphCount)
            return ViewerTextFont(codes, glyphs, advances, unitsPerEm, data.s16(hhea + 4), data.s16(hhea + 6), data.s16(hhea + 8))
        }

        private fun characterMap(data: Bytes, cmap: Int, glyphCount: Int): Pair<IntArray, IntArray> {
            val subtables = data.u16(cmap + 2)
            var best = -1
            var bestRank = -1
            for (index in 0 until subtables) {
                val entry = cmap + 4 + index * 8
                val platform = data.u16(entry)
                val encoding = data.u16(entry + 2)
                val offset = cmap + data.u32(entry + 4)
                val format = data.u16(offset)
                val rank = when (platform) {
                    WINDOWS -> when (encoding) {
                        UNICODE_FULL -> if (format == 12) 3 else -1
                        UNICODE_BMP -> if (format == 4) 2 else -1
                        else -> -1
                    }
                    UNICODE -> if (format == 4 || format == 12) 1 else -1
                    else -> -1
                }
                if (rank > bestRank) {
                    best = offset
                    bestRank = rank
                }
            }
            require(best >= 0) { "The font has no Unicode character map" }
            val pairs = ArrayList<Long>()
            fun add(code: Int, glyph: Int) {
                if (glyph in 1 until glyphCount) pairs += (code.toLong() shl 32) or glyph.toLong()
            }
            if (data.u16(best) == 12) {
                val groups = data.u32(best + 12)
                for (group in 0 until groups) {
                    val at = best + 16 + group * 12
                    val start = data.u32(at)
                    val end = data.u32(at + 4)
                    val first = data.u32(at + 8)
                    for (code in start..end) add(code, first + (code - start))
                }
            } else {
                val segments = data.u16(best + 6) / 2
                val ends = best + 14
                val starts = ends + segments * 2 + 2
                val deltas = starts + segments * 2
                val ranges = deltas + segments * 2
                for (segment in 0 until segments) {
                    val start = data.u16(starts + segment * 2)
                    val end = data.u16(ends + segment * 2)
                    val delta = data.u16(deltas + segment * 2)
                    val rangeAt = ranges + segment * 2
                    val range = data.u16(rangeAt)
                    if (start > end) continue
                    for (code in start..end) {
                        if (code == 0xFFFF) continue
                        val glyph = if (range == 0) {
                            (code + delta) and 0xFFFF
                        } else {
                            val raw = data.u16(rangeAt + range + (code - start) * 2)
                            if (raw == 0) 0 else (raw + delta) and 0xFFFF
                        }
                        add(code, glyph)
                    }
                }
            }
            pairs.sort()
            val codes = IntArray(pairs.size)
            val glyphs = IntArray(pairs.size)
            var size = 0
            for (pair in pairs) {
                val code = (pair ushr 32).toInt()
                if (size > 0 && codes[size - 1] == code) continue
                codes[size] = code
                glyphs[size] = (pair and 0xFFFFFFFFL).toInt()
                size++
            }
            return codes.copyOf(size) to glyphs.copyOf(size)
        }
    }

    private class Bytes(private val data: ByteArray) {
        fun u16(at: Int): Int {
            require(at >= 0 && at + 2 <= data.size) { "The font ends early" }
            return (data[at].toInt() and 0xFF shl 8) or (data[at + 1].toInt() and 0xFF)
        }

        fun s16(at: Int): Int = u16(at).toShort().toInt()

        fun u32(at: Int): Int {
            require(at >= 0 && at + 4 <= data.size) { "The font ends early" }
            return (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
                (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)
        }
    }
}
