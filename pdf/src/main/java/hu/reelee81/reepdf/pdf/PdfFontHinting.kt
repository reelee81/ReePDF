package hu.reelee81.reepdf.pdf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal object PdfFontHinting {
    private val HINTING_TABLES = setOf("fpgm", "prep", "cvt ", "hdmx", "LTSH", "VDMX")
    private const val SIMPLE_HEADER = 10
    private const val ARGS_ARE_WORDS = 0x0001
    private const val HAS_SCALE = 0x0008
    private const val MORE_COMPONENTS = 0x0020
    private const val HAS_X_AND_Y_SCALE = 0x0040
    private const val HAS_TWO_BY_TWO = 0x0080
    private const val HAS_INSTRUCTIONS = 0x0100
    private const val SHORT_LOCA_LIMIT = 0x20000

    fun strip(font: ByteArray): ByteArray = try {
        stripped(font) ?: font
    } catch (_: RuntimeException) {
        font
    }

    private fun stripped(font: ByteArray): ByteArray? {
        val input = ByteBuffer.wrap(font)
        if (font.size < 12) return null
        val version = input.getInt(0)
        if (version != 0x00010000) return null
        val count = input.getShort(4).toInt() and 0xFFFF
        if (12 + count * 16 > font.size) return null
        val tables = LinkedHashMap<String, ByteArray>()
        for (index in 0 until count) {
            val entry = 12 + index * 16
            val tag = String(font, entry, 4, Charsets.ISO_8859_1)
            val offset = input.getInt(entry + 8)
            val length = input.getInt(entry + 12)
            if (offset < 0 || length < 0 || offset.toLong() + length > font.size) return null
            tables[tag] = font.copyOfRange(offset, offset + length)
        }
        val head = tables["head"]?.copyOf() ?: return null
        val maxp = tables["maxp"]?.copyOf() ?: return null
        val loca = tables["loca"] ?: return null
        val glyf = tables["glyf"] ?: return null
        if (head.size < 54 || maxp.size < 6) return null
        if (tables.keys.none { it in HINTING_TABLES }) return null
        val glyphs = ByteBuffer.wrap(maxp).getShort(4).toInt() and 0xFFFF
        val longLoca = ByteBuffer.wrap(head).getShort(50).toInt() == 1
        val offsets = IntArray(glyphs + 1) { index ->
            if (longLoca) ByteBuffer.wrap(loca).getInt(index * 4) else (ByteBuffer.wrap(loca).getShort(index * 2).toInt() and 0xFFFF) * 2
        }
        val newGlyf = ByteArrayOutputStream(glyf.size)
        val newOffsets = IntArray(glyphs + 1)
        for (glyph in 0 until glyphs) {
            newOffsets[glyph] = newGlyf.size()
            val start = offsets[glyph]
            val end = offsets[glyph + 1]
            if (end < start || end > glyf.size) return null
            if (end > start) newGlyf.write(withoutInstructions(glyf.copyOfRange(start, end)) ?: return null)
            while (newGlyf.size() % 4 != 0) newGlyf.write(0)
        }
        newOffsets[glyphs] = newGlyf.size()
        val shortLoca = newGlyf.size() < SHORT_LOCA_LIMIT
        val newLoca = ByteBuffer.allocate((glyphs + 1) * if (shortLoca) 2 else 4)
        for (offset in newOffsets) if (shortLoca) newLoca.putShort((offset / 2).toShort()) else newLoca.putInt(offset)
        ByteBuffer.wrap(head).putShort(50, if (shortLoca) 0 else 1)
        ByteBuffer.wrap(head).putInt(8, 0)
        if (maxp.size >= 28) ByteBuffer.wrap(maxp).putShort(26, 0)
        tables["head"] = head
        tables["maxp"] = maxp
        tables["loca"] = newLoca.array()
        tables["glyf"] = newGlyf.toByteArray()
        for (tag in HINTING_TABLES) tables.remove(tag)
        return assemble(tables)
    }

    private fun withoutInstructions(glyph: ByteArray): ByteArray? {
        if (glyph.size < SIMPLE_HEADER) return null
        val data = ByteBuffer.wrap(glyph)
        val contours = data.getShort(0).toInt()
        if (contours >= 0) {
            val lengthAt = SIMPLE_HEADER + contours * 2
            if (lengthAt + 2 > glyph.size) return null
            val instructions = data.getShort(lengthAt).toInt() and 0xFFFF
            if (lengthAt + 2 + instructions > glyph.size) return null
            if (instructions == 0) return glyph
            val out = ByteArrayOutputStream(glyph.size - instructions)
            out.write(glyph, 0, lengthAt)
            out.write(0)
            out.write(0)
            out.write(glyph, lengthAt + 2 + instructions, glyph.size - lengthAt - 2 - instructions)
            return out.toByteArray()
        }
        var position = SIMPLE_HEADER
        var last: Int
        while (true) {
            if (position + 4 > glyph.size) return null
            last = position
            val flags = data.getShort(position).toInt() and 0xFFFF
            position += 4 + if (flags and ARGS_ARE_WORDS != 0) 4 else 2
            position += when {
                flags and HAS_SCALE != 0 -> 2
                flags and HAS_X_AND_Y_SCALE != 0 -> 4
                flags and HAS_TWO_BY_TWO != 0 -> 8
                else -> 0
            }
            if (flags and MORE_COMPONENTS == 0) break
        }
        if (position > glyph.size) return null
        val result = glyph.copyOf(position)
        val flags = ByteBuffer.wrap(result).getShort(last).toInt() and 0xFFFF
        ByteBuffer.wrap(result).putShort(last, (flags and HAS_INSTRUCTIONS.inv()).toShort())
        return result
    }

    private fun assemble(tables: Map<String, ByteArray>): ByteArray {
        val tags = tables.keys.sorted()
        var power = 1
        while (power * 2 <= tags.size) power *= 2
        val header = 12 + tags.size * 16
        val body = ByteArrayOutputStream()
        val directory = ByteBuffer.allocate(header)
        directory.putInt(0x00010000)
        directory.putShort(tags.size.toShort())
        directory.putShort((power * 16).toShort())
        directory.putShort((31 - Integer.numberOfLeadingZeros(power)).toShort())
        directory.putShort((tags.size * 16 - power * 16).toShort())
        for (tag in tags) {
            val table = tables.getValue(tag)
            val padded = table.copyOf((table.size + 3) / 4 * 4)
            directory.put(tag.toByteArray(Charsets.ISO_8859_1))
            directory.putInt(checksum(padded))
            directory.putInt(header + body.size())
            directory.putInt(table.size)
            body.write(padded)
        }
        val font = directory.array() + body.toByteArray()
        val head = header + tags.takeWhile { it != "head" }.sumOf { (tables.getValue(it).size + 3) / 4 * 4 }
        if ("head" in tables) ByteBuffer.wrap(font).putInt(head + 8, (0xB1B0AFBAL - (checksum(font).toLong() and 0xFFFFFFFFL)).toInt())
        return font
    }

    private fun checksum(bytes: ByteArray): Int {
        val data = ByteBuffer.wrap(bytes)
        var sum = 0
        var index = 0
        while (index + 4 <= bytes.size) {
            sum += data.getInt(index)
            index += 4
        }
        if (index < bytes.size) {
            val tail = ByteArray(4)
            System.arraycopy(bytes, index, tail, 0, bytes.size - index)
            sum += ByteBuffer.wrap(tail).int
        }
        return sum
    }
}
