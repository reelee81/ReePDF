package hu.reelee81.reepdf.pdf

import com.itextpdf.io.source.PdfTokenizer
import com.itextpdf.io.source.RandomAccessFileOrArray
import com.itextpdf.io.source.RandomAccessSourceFactory
import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfIndirectReference
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfResources
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.canvas.parser.util.PdfCanvasParser
import java.io.ByteArrayOutputStream

internal class PdfOptionalContentFilter private constructor(
    private val groups: Set<Long>,
    private val switched: Set<Long>,
    private val baseOff: Boolean,
    private val printStates: Map<Long, Boolean>
) {
    private val filteredForms = HashSet<Long>()

    fun isHidden(oc: PdfDictionary?): Boolean = oc != null && !isVisible(oc)

    fun filterPage(fromPage: PdfPage, toPage: PdfPage): Boolean {
        if (!toPage.pdfObject.containsKey(PdfName.Contents)) return false
        val result = try {
            filter(fromPage.contentBytes, fromPage.resources.pdfObject, toPage.resources.pdfObject, 0)
        } catch (_: Exception) {
            null
        } ?: return false
        result.content?.let { toPage.pdfObject.put(PdfName.Contents, PdfStream(it).makeIndirect(toPage.document)) }
        return true
    }

    private class Result(val content: ByteArray?)

    private fun filter(bytes: ByteArray, source: PdfDictionary?, target: PdfDictionary?, depth: Int): Result? {
        val tokenizer = PdfTokenizer(RandomAccessFileOrArray(RandomAccessSourceFactory().createSource(bytes)))
        tokenizer.use {
            val parser = PdfCanvasParser(tokenizer, PdfResources(source ?: PdfDictionary()))
            val output = ByteArrayOutputStream(bytes.size + 64)
            val operands = ArrayList<PdfObject>()
            val marked = ArrayList<Boolean>()
            val savedModes = ArrayList<String>()
            var renderMode = "0"
            var hiddenDepth = 0
            var changed = false
            var formsChanged = false
            var start = 0
            while (parser.parse(operands).isNotEmpty()) {
                val operator = operands.last().toString()
                val hidden = hiddenDepth > 0
                var keep = true
                var neutralize = false
                when (operator) {
                    "BDC" -> {
                        val oc = if (operands.size >= 3 && operands[0] == PdfName.OC) properties(operands[1], source) else null
                        val hides = isHidden(oc)
                        marked += hides
                        if (hides) hiddenDepth++
                        neutralize = !hidden && !hides && oc != null && isMembership(oc)
                    }
                    "BMC" -> marked += false
                    "EMC" -> if (marked.isNotEmpty() && marked.removeAt(marked.lastIndex)) hiddenDepth--
                    "q" -> savedModes += renderMode
                    "Q" -> if (savedModes.isNotEmpty()) renderMode = savedModes.removeAt(savedModes.lastIndex)
                    "Tr" -> if (operands.size >= 2) renderMode = operands[0].toString()
                    "EI", "sh" -> keep = !hidden
                    "Do" -> {
                        val name = operands.getOrNull(0) as? PdfName
                        val xObject = name?.let { source?.getAsDictionary(PdfName.XObject)?.getAsStream(it) }
                        val oc = xObject?.getAsDictionary(PdfName.OC)
                        keep = !hidden && !isHidden(oc)
                        if (keep && name != null && oc != null && isMembership(oc)) {
                            target?.getAsDictionary(PdfName.XObject)?.getAsStream(name)?.let {
                                it.remove(PdfName.OC)
                                formsChanged = true
                            }
                        }
                        if (keep && name != null && xObject != null && filterForm(name, xObject, source, target, depth)) {
                            formsChanged = true
                        }
                    }
                }
                val end = tokenizer.position.toInt()
                when {
                    hidden && operator in paintingOperators -> {
                        output.write(END_PATH)
                        changed = true
                    }
                    hidden && operator in textOperators -> {
                        output.write(INVISIBLE_TEXT)
                        output.write(bytes, start, end - start)
                        output.write("\n$renderMode Tr\n".toByteArray(Charsets.ISO_8859_1))
                        changed = true
                    }
                    neutralize -> {
                        output.write(NEUTRAL_MARK)
                        changed = true
                    }
                    keep -> output.write(bytes, start, end - start)
                    else -> changed = true
                }
                start = end
            }
            if (!changed) return if (formsChanged) Result(null) else null
            output.write(bytes, start, bytes.size - start)
            return Result(output.toByteArray())
        }
    }

    private fun filterForm(
        name: PdfName,
        sourceForm: PdfStream,
        sourceResources: PdfDictionary?,
        targetResources: PdfDictionary?,
        depth: Int
    ): Boolean {
        if (depth >= MAX_DEPTH || sourceForm.getAsName(PdfName.Subtype) != PdfName.Form) return false
        val targetForm = targetResources?.getAsDictionary(PdfName.XObject)?.getAsStream(name) ?: return false
        val reference = targetForm.indirectReference ?: return false
        if (!filteredForms.add(key(reference))) return false
        val result = try {
            filter(
                sourceForm.bytes,
                sourceForm.getAsDictionary(PdfName.Resources) ?: sourceResources,
                targetForm.getAsDictionary(PdfName.Resources) ?: targetResources,
                depth + 1
            )
        } catch (_: Exception) {
            null
        } ?: return false
        result.content?.let {
            targetForm.remove(PdfName.Filter)
            targetForm.remove(PdfName.DecodeParms)
            targetForm.setData(it)
        }
        return true
    }

    private fun properties(operand: PdfObject, resources: PdfDictionary?): PdfDictionary? = when (operand) {
        is PdfDictionary -> operand
        is PdfName -> resources?.getAsDictionary(PdfName.Properties)?.getAsDictionary(operand)
        else -> null
    }

    private fun isMembership(oc: PdfDictionary): Boolean =
        oc.getAsName(PdfName.Type) == OCMD || oc.containsKey(VE) ||
            (oc.containsKey(PdfName.OCGs) && !oc.containsKey(PdfName.Name))

    private fun isVisible(oc: PdfDictionary): Boolean {
        if (!isMembership(oc)) return isOn(oc)
        oc.getAsArray(VE)?.let { return expression(it, 0) }
        val members = when (val value = oc.get(PdfName.OCGs)) {
            is PdfDictionary -> listOf(value)
            is PdfArray -> (0 until value.size()).mapNotNull { value.getAsDictionary(it) }
            else -> emptyList()
        }
        if (members.isEmpty()) return true
        val states = members.map(::isOn)
        return when (oc.getAsName(POLICY)) {
            ALL_ON -> states.all { it }
            ANY_OFF -> states.any { !it }
            ALL_OFF -> states.none { it }
            else -> states.any { it }
        }
    }

    private fun expression(ve: PdfArray, depth: Int): Boolean {
        if (depth >= MAX_DEPTH || ve.isEmpty) return true
        val values = (1 until ve.size()).mapNotNull { index ->
            when (val operand = ve.get(index)) {
                is PdfArray -> expression(operand, depth + 1)
                is PdfDictionary -> isOn(operand)
                else -> null
            }
        }
        return when (ve.getAsName(0)) {
            AND -> values.all { it }
            OR -> values.any { it }
            NOT -> values.firstOrNull()?.not() ?: true
            else -> true
        }
    }

    private fun isOn(group: PdfDictionary): Boolean {
        val key = key(group.indirectReference ?: return true)
        if (key !in groups) return true
        printStates[key]?.let { return it }
        return if (baseOff) key in switched else key !in switched
    }

    companion object {
        private const val MAX_DEPTH = 16
        private val OCMD = PdfName("OCMD")
        private val VE = PdfName("VE")
        private val POLICY = PdfName("P")
        private val ALL_ON = PdfName("AllOn")
        private val ANY_OFF = PdfName("AnyOff")
        private val ALL_OFF = PdfName("AllOff")
        private val AND = PdfName("And")
        private val OR = PdfName("Or")
        private val NOT = PdfName("Not")
        private val BASE_STATE = PdfName("BaseState")
        private val ON = PdfName("ON")
        private val AUTO_STATE = PdfName("AS")
        private val EVENT = PdfName("Event")
        private val CATEGORY = PdfName("Category")
        private val PRINT = PdfName("Print")
        private val USAGE = PdfName("Usage")
        private val PRINT_STATE = PdfName("PrintState")
        private val paintingOperators = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*")
        private val textOperators = setOf("Tj", "TJ", "'", "\"")
        private val END_PATH = "\nn\n".toByteArray(Charsets.ISO_8859_1)
        private val INVISIBLE_TEXT = "\n3 Tr".toByteArray(Charsets.ISO_8859_1)
        private val NEUTRAL_MARK = "\n/VisibleContent BMC\n".toByteArray(Charsets.ISO_8859_1)

        fun of(document: PdfDocument): PdfOptionalContentFilter? = try {
            val properties = document.catalog.pdfObject.getAsDictionary(PdfName.OCProperties)
            val all = properties?.getAsArray(PdfName.OCGs)
            if (all == null || all.isEmpty) {
                null
            } else {
                val config = properties.getAsDictionary(PdfName.D)
                val baseOff = config?.getAsName(BASE_STATE) == PdfName.OFF
                val switched = references(config?.getAsArray(if (baseOff) ON else PdfName.OFF))
                PdfOptionalContentFilter(references(all), switched, baseOff, printStates(config))
            }
        } catch (_: Exception) {
            null
        }

        private fun printStates(config: PdfDictionary?): Map<Long, Boolean> {
            val usages = config?.getAsArray(AUTO_STATE) ?: return emptyMap()
            val result = HashMap<Long, Boolean>()
            for (index in 0 until usages.size()) {
                val usage = usages.getAsDictionary(index) ?: continue
                if (usage.getAsName(EVENT) != PRINT || usage.getAsArray(CATEGORY)?.contains(PRINT) != true) continue
                val members = when (val value = usage.get(PdfName.OCGs)) {
                    is PdfDictionary -> listOf(value)
                    is PdfArray -> (0 until value.size()).mapNotNull { value.getAsDictionary(it) }
                    else -> emptyList()
                }
                for (group in members) {
                    val reference = group.indirectReference ?: continue
                    val state = group.getAsDictionary(USAGE)?.getAsDictionary(PRINT)?.getAsName(PRINT_STATE) ?: continue
                    if (state == ON || state == PdfName.OFF) result[key(reference)] = state == ON
                }
            }
            return result
        }

        private fun references(array: PdfArray?): Set<Long> {
            if (array == null) return emptySet()
            val result = HashSet<Long>()
            for (index in 0 until array.size()) {
                val reference = array.get(index, false) as? PdfIndirectReference
                    ?: array.get(index)?.indirectReference
                    ?: continue
                result += key(reference)
            }
            return result
        }

        private fun key(reference: PdfIndirectReference): Long =
            (reference.objNumber.toLong() shl 20) or reference.genNumber.toLong()
    }
}
