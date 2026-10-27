package hu.reelee81.reepdf.pdf

import com.itextpdf.io.source.PdfTokenizer
import com.itextpdf.io.source.RandomAccessFileOrArray
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.pdf.PdfLiteral
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.canvas.parser.util.PdfCanvasParser

internal object PdfIdentityCMapRepair {
    private class CorrectedMaps(val encoding: ByteArray, val unicode: ByteArray)

    fun repair(page: PdfPage) {
        val fonts = page.resources.pdfObject.getAsDictionary(PdfName.Font) ?: return
        for (key in fonts.keySet()) {
            val font = fonts.getAsDictionary(key) ?: continue
            if (font.getAsName(PdfName.Subtype) != PdfName.Type0 ||
                font.getAsName(PdfName.ToUnicode) != PdfName("Identity-H")
            ) continue
            val descendant = font.getAsArray(PdfName.DescendantFonts)?.getAsDictionary(0) ?: continue
            if (descendant.getAsName(PdfName.Subtype) != PdfName.CIDFontType2 ||
                descendant.getAsDictionary(PdfName.CIDSystemInfo)?.getAsString(PdfName.Ordering)?.toUnicodeString() != "Identity"
            ) continue
            val encoding = font.getAsStream(PdfName.Encoding) ?: continue
            val bytes = PdfBoundedStream.read(encoding, 64 * 1024) ?: continue
            val corrected = try {
                correctedEncoding(bytes)
            } catch (_: Exception) {
                null
            } ?: continue
            font.put(PdfName.Encoding, PdfStream(corrected.encoding).apply {
                put(PdfName.CMapName, PdfName("PdfLabelIdentityEncoding"))
            })
            font.put(PdfName.ToUnicode, PdfStream(corrected.unicode).apply {
                put(PdfName.CMapName, PdfName("PdfLabelIdentityUnicode"))
            })
        }
    }

    private fun correctedEncoding(bytes: ByteArray): CorrectedMaps? {
        if (!boundedNesting(bytes)) return null
        var type = 0
        var vertical = false
        var fullTwoByteSpace = false
        val mappings = ArrayList<Pair<Int, Int>>()
        PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(bytes))).use { tokenizer ->
            val parser = PdfCanvasParser(tokenizer)
            val operands = ArrayList<PdfObject>()
            while (parser.parse(operands).isNotEmpty()) {
                if (operands.size > 8193) return null
                val operator = operands.last() as? PdfLiteral ?: return null
                when (operator.toString()) {
                    "def" -> {
                        if (operands.firstOrNull() == PdfName("CMapType")) type = (operands.getOrNull(1) as? PdfNumber)?.intValue() ?: 0
                        if (operands.firstOrNull() == PdfName("WMode")) vertical = (operands.getOrNull(1) as? PdfNumber)?.intValue() != 0
                    }
                    "endcodespacerange" -> {
                        if (operands.size != 3) return null
                        fullTwoByteSpace = code(operands[0]) == 0 && code(operands[1]) == 65535
                    }
                    "endbfchar" -> {
                        if ((operands.size - 1) % 2 != 0) return null
                        for (index in 0 until operands.size - 1 step 2) {
                            if (mappings.size >= 4096) return null
                            val source = code(operands[index]) ?: return null
                            val target = code(operands[index + 1]) ?: return null
                            mappings.add(source to target)
                        }
                    }
                    "begincidchar", "begincidrange", "beginbfrange" -> return null
                }
            }
        }
        if (type != 1 || vertical || !fullTwoByteSpace || mappings.isEmpty()) return null
        val result = StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin begincmap\n")
            .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def\n")
            .append("/CMapName /PdfLabelIdentityEncoding def /CMapType 1 def /WMode 0 def\n")
            .append("1 begincodespacerange <0000> <FFFF> endcodespacerange\n")
        val unicode = StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin begincmap\n")
            .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
            .append("/CMapName /PdfLabelIdentityUnicode def /CMapType 2 def\n")
            .append("1 begincodespacerange <0000> <FFFF> endcodespacerange\n")
        for (start in mappings.indices step 100) {
            val end = minOf(start + 100, mappings.size)
            result.append(end - start).append(" begincidchar\n")
            unicode.append(end - start).append(" beginbfchar\n")
            for (index in start until end) {
                val (source, target) = mappings[index]
                val hex = source.toString(16).padStart(4, '0')
                result.append('<').append(hex).append("> ").append(target).append('\n')
                unicode.append('<').append(hex).append("> <").append(hex).append(">\n")
            }
            result.append("endcidchar\n")
            unicode.append("endbfchar\n")
        }
        result.append("endcmap CMapName currentdict /CMap defineresource pop end end\n")
        unicode.append("endcmap CMapName currentdict /CMap defineresource pop end end\n")
        return CorrectedMaps(
            result.toString().toByteArray(Charsets.US_ASCII),
            unicode.toString().toByteArray(Charsets.US_ASCII)
        )
    }

    private fun boundedNesting(bytes: ByteArray): Boolean {
        PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(bytes))).use { tokenizer ->
            var depth = 0
            while (tokenizer.nextToken()) {
                when (tokenizer.tokenType) {
                    PdfTokenizer.TokenType.StartArray, PdfTokenizer.TokenType.StartDic -> if (++depth > 32) return false
                    PdfTokenizer.TokenType.EndArray, PdfTokenizer.TokenType.EndDic -> if (--depth < 0) return false
                    else -> Unit
                }
            }
            return depth == 0
        }
    }

    private fun code(value: PdfObject): Int? {
        val bytes = (value as? PdfString)?.valueBytes ?: return null
        if (bytes.size != 2) return null
        return ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
    }
}
