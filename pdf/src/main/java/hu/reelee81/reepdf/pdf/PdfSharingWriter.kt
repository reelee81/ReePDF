package hu.reelee81.reepdf.pdf

import com.itextpdf.kernel.pdf.PdfArray
import com.itextpdf.kernel.pdf.PdfBoolean
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfIndirectReference
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.utils.ICopyFilter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.WeakHashMap

internal class PdfSharingWriter(
    path: String,
    properties: WriterProperties,
    private val direct: Boolean,
    private val readableCopies: Boolean,
    private val sameSource: Boolean = false,
    private val maxKeys: Int = MAX_KEYS,
    private val maxHashedBytes: Int = MAX_HASHED_BYTES
) : PdfWriter(path, properties) {
    var sharing = true

    val keys: Int get() = shared.size

    private val shared = HashMap<Key, PdfIndirectReference>()
    private var reused = arrayOfNulls<PdfIndirectReference>(0)
    private var eligibility = ByteArray(0)
    private val seenStreams = HashSet<Long>()
    private val known = WeakHashMap<PdfIndirectReference, PdfIndirectReference>()
    private val active = HashSet<PdfIndirectReference>()
    private val digests = ArrayDeque<MessageDigest>()
    private val buffers = ArrayDeque<KeyBuffer>()
    private val names = HashMap<PdfName, ByteArray>()

    override fun flushObject(pdfObject: PdfObject, canBeInObjStm: Boolean) =
        super.flushObject(pdfObject, canBeInObjStm && !direct)

    override fun copyObject(
        obj: PdfObject,
        documentTo: PdfDocument,
        allowDuplicating: Boolean,
        copyFilter: ICopyFilter
    ): PdfObject {
        val target = if (obj is PdfIndirectReference) obj.refersTo else obj
        val reference = target?.indirectReference
        if (!sharing || allowDuplicating || reference == null || !shareable(target)) {
            return super.copyObject(obj, documentTo, allowDuplicating, copyFilter)
        }
        if (sameSource) return copyFromSameSource(obj, target, reference, documentTo, copyFilter)
        known[reference]?.let { existing -> usable(existing)?.let { return it } }
        if (!active.add(reference)) return super.copyObject(obj, documentTo, false, copyFilter)
        val outline = buffers.removeLastOrNull() ?: KeyBuffer()
        try {
            if (!outline.describe(target, documentTo, copyFilter)) return super.copyObject(obj, documentTo, false, copyFilter)
            val key = if (target is PdfStream) {
                if (outline.hash() !in seenStreams) return copyNewStream(obj, outline, reference, documentTo, copyFilter)
                keyOf(outline) { digest -> digest.update(target.getBytes(false)) }
            } else {
                keyOf(outline)
            }
            shared[key]?.let { existing ->
                usable(existing)?.let { found ->
                    known[reference] = existing
                    return found
                }
            }
            return copyAndRemember(obj, key, reference, documentTo, copyFilter)
        } finally {
            active.remove(reference)
            outline.clear()
            buffers.addLast(outline)
        }
    }

    private fun copyFromSameSource(
        obj: PdfObject,
        target: PdfObject,
        reference: PdfIndirectReference,
        documentTo: PdfDocument,
        copyFilter: ICopyFilter
    ): PdfObject {
        val number = reference.objNumber
        if (reference.genNumber != 0 || !eligible(target, number)) return super.copyObject(obj, documentTo, false, copyFilter)
        reused.getOrNull(number)?.let { existing -> usable(existing)?.let { return it } }
        val copy = super.copyObject(obj, documentTo, false, copyFilter)
        copy.indirectReference?.let { copied ->
            if (number >= reused.size) reused = reused.copyOf(maxOf(number + 1, reused.size * 2))
            reused[number] = copied
        }
        return copy
    }

    private fun eligible(target: PdfObject, number: Int): Boolean {
        when (eligibility.getOrElse(number) { UNKNOWN }) {
            ELIGIBLE -> return true
            INELIGIBLE, VISITING -> return false
        }
        if (number >= eligibility.size) eligibility = eligibility.copyOf(maxOf(number + 1, eligibility.size * 2))
        eligibility[number] = VISITING
        val result = shareable(target) && entriesEligible(target, 0)
        eligibility[number] = if (result) ELIGIBLE else INELIGIBLE
        return result
    }

    private fun entriesEligible(value: PdfObject, depth: Int): Boolean = when (value) {
        is PdfDictionary -> value.values(false).all { item -> valueEligible(item, depth + 1) }
        is PdfArray -> (0 until value.size()).all { index -> valueEligible(value.get(index, false), depth + 1) }
        else -> true
    }

    private fun valueEligible(value: PdfObject?, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        val resolved = (if (value is PdfIndirectReference) value.refersTo else value) ?: return true
        val reference = resolved.indirectReference
        if (resolved is PdfDictionary && reference != null) return reference.genNumber == 0 && eligible(resolved, reference.objNumber)
        if (resolved is PdfDictionary && isNavigation(resolved)) return false
        return entriesEligible(resolved, depth)
    }

    private fun copyNewStream(
        obj: PdfObject,
        outline: KeyBuffer,
        reference: PdfIndirectReference,
        documentTo: PdfDocument,
        copyFilter: ICopyFilter
    ): PdfObject {
        val copy = super.copyObject(obj, documentTo, false, copyFilter)
        val copied = copy.indirectReference ?: return copy
        known[reference] = copied
        val content = (copy as? PdfStream)?.outputStream?.outputStream as? ByteArrayOutputStream ?: return copy
        if (shared.size < maxKeys) {
            seenStreams += outline.hash()
            shared[keyOf(outline) { digest -> content.writeTo(DigestSink(digest)) }] = copied
        }
        return copy
    }

    private fun copyAndRemember(
        obj: PdfObject,
        key: Key,
        reference: PdfIndirectReference,
        documentTo: PdfDocument,
        copyFilter: ICopyFilter
    ): PdfObject {
        val copy = super.copyObject(obj, documentTo, false, copyFilter)
        copy.indirectReference?.let { copied ->
            known[reference] = copied
            if (shared.size < maxKeys || shared.containsKey(key)) shared[key] = copied
        }
        return copy
    }

    private fun usable(existing: PdfIndirectReference): PdfObject? {
        val found = existing.refersTo ?: return null
        return if (!found.isFlushed || found is PdfStream || !readableCopies) found else null
    }

    private fun shareable(value: PdfObject): Boolean = when (value) {
        is PdfStream -> !value.containsKey(PdfName.PieceInfo) &&
            value.getAsName(PdfName.Type) !in EXCLUDED_TYPES &&
            value.length <= maxHashedBytes
        is PdfDictionary -> value.getAsName(PdfName.Type) !in EXCLUDED_TYPES &&
            EXCLUDED_KEYS.none(value::containsKey) &&
            !isNavigation(value)
        else -> false
    }

    private fun isNavigation(value: PdfDictionary): Boolean =
        value.getAsName(PdfName.S) == PdfName.GoTo || value.getAsName(PdfName.Subtype) == PdfName.Link

    private inline fun keyOf(outline: KeyBuffer, content: (MessageDigest) -> Unit = {}): Key {
        val digest = digests.removeLastOrNull() ?: MessageDigest.getInstance(DIGEST)
        digest.update(outline.bytes, 0, outline.size)
        content(digest)
        val key = Key(digest.digest())
        digests.addLast(digest)
        return key
    }

    private fun KeyBuffer.describe(target: PdfObject, documentTo: PdfDocument, filter: ICopyFilter): Boolean = try {
        write(this, target, documentTo, filter, 0)
        seal()
        true
    } catch (_: Unshareable) {
        false
    }

    private fun write(buffer: KeyBuffer, value: PdfObject?, documentTo: PdfDocument, filter: ICopyFilter, depth: Int) {
        if (depth > MAX_DEPTH || buffer.size > MAX_KEY_BYTES) throw Unshareable()
        val resolved = if (value is PdfIndirectReference) value.refersTo else value
        if (depth > 0 && resolved is PdfDictionary && resolved.indirectReference != null) {
            if (!shareable(resolved)) throw Unshareable()
            val copy = copyObject(resolved, documentTo, false, filter)
            val reference = copy.indirectReference ?: throw Unshareable()
            buffer.put(REFERENCE)
            buffer.putInt(reference.objNumber)
            buffer.putInt(reference.genNumber)
            return
        }
        when (resolved) {
            null -> buffer.put(NULL)
            is PdfStream -> {
                buffer.put(STREAM)
                buffer.putInt(resolved.length)
                dictionary(buffer, resolved, documentTo, filter, depth)
            }
            is PdfDictionary -> {
                if (isNavigation(resolved)) throw Unshareable()
                buffer.put(DICTIONARY)
                dictionary(buffer, resolved, documentTo, filter, depth)
            }
            is PdfArray -> {
                buffer.put(ARRAY)
                buffer.putInt(resolved.size())
                for (index in 0 until resolved.size()) write(buffer, resolved.get(index, false), documentTo, filter, depth + 1)
            }
            is PdfString -> {
                buffer.put(STRING)
                buffer.putInt(resolved.valueBytes.size)
                buffer.put(resolved.valueBytes)
            }
            is PdfName -> {
                buffer.put(NAME)
                buffer.put(bytesOf(resolved))
            }
            is PdfNumber -> {
                buffer.put(NUMBER)
                buffer.putText(resolved.toString())
            }
            is PdfBoolean -> buffer.put(if (resolved.value) TRUE else FALSE)
            else -> {
                buffer.put(OTHER)
                buffer.put(resolved.toString().toByteArray())
            }
        }
        buffer.put(END)
    }

    private fun dictionary(buffer: KeyBuffer, value: PdfDictionary, documentTo: PdfDocument, filter: ICopyFilter, depth: Int) {
        for ((name, item) in value.entrySet()) {
            buffer.put(bytesOf(name))
            buffer.put(END)
            write(buffer, item, documentTo, filter, depth + 1)
        }
    }

    private fun bytesOf(name: PdfName): ByteArray =
        names[name] ?: name.value.toByteArray().also { if (names.size < MAX_NAMES) names[name] = it }

    private class DigestSink(private val digest: MessageDigest) : OutputStream() {
        override fun write(b: Int) = digest.update(b.toByte())

        override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
    }

    private class KeyBuffer {
        var bytes = ByteArray(INITIAL_BUFFER_BYTES)
        var size = LENGTH_BYTES

        fun put(value: Byte) {
            ensure(1)
            bytes[size++] = value
        }

        fun put(values: ByteArray) {
            ensure(values.size)
            System.arraycopy(values, 0, bytes, size, values.size)
            size += values.size
        }

        fun putInt(value: Int) {
            ensure(4)
            bytes[size++] = (value ushr 24).toByte()
            bytes[size++] = (value ushr 16).toByte()
            bytes[size++] = (value ushr 8).toByte()
            bytes[size++] = value.toByte()
        }

        fun putText(text: String) {
            putInt(text.length)
            ensure(text.length)
            for (char in text) bytes[size++] = char.code.toByte()
        }

        fun seal() {
            bytes[0] = (size ushr 24).toByte()
            bytes[1] = (size ushr 16).toByte()
            bytes[2] = (size ushr 8).toByte()
            bytes[3] = size.toByte()
        }

        fun hash(): Long {
            var hash = FNV_OFFSET
            for (index in 0 until size) hash = (hash xor (bytes[index].toLong() and 0xff)) * FNV_PRIME
            return hash
        }

        fun clear() {
            size = LENGTH_BYTES
            if (bytes.size > KEPT_BUFFER_BYTES) bytes = ByteArray(INITIAL_BUFFER_BYTES)
        }

        private fun ensure(extra: Int) {
            if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
        }
    }

    private class Key(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()

        override fun equals(other: Any?): Boolean = other is Key && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = hash
    }

    private class Unshareable : RuntimeException(null, null, false, false)

    companion object {
        const val MAX_KEYS = 300_000
        const val MAX_HASHED_BYTES = 16 * 1024 * 1024
        private const val MAX_DEPTH = 64
        private const val MAX_NAMES = 4096
        private const val MAX_KEY_BYTES = 4 * 1024 * 1024
        private const val INITIAL_BUFFER_BYTES = 256
        private const val KEPT_BUFFER_BYTES = 64 * 1024
        private const val LENGTH_BYTES = 4
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
        private const val DIGEST = "SHA-256"
        private const val UNKNOWN: Byte = 0
        private const val ELIGIBLE: Byte = 1
        private const val INELIGIBLE: Byte = 2
        private const val VISITING: Byte = 3
        private const val REFERENCE: Byte = 'R'.code.toByte()
        private const val NULL: Byte = 'N'.code.toByte()
        private const val STREAM: Byte = 'S'.code.toByte()
        private const val DICTIONARY: Byte = 'D'.code.toByte()
        private const val ARRAY: Byte = 'A'.code.toByte()
        private const val STRING: Byte = 's'.code.toByte()
        private const val NAME: Byte = 'n'.code.toByte()
        private const val NUMBER: Byte = '#'.code.toByte()
        private const val TRUE: Byte = 't'.code.toByte()
        private const val FALSE: Byte = 'f'.code.toByte()
        private const val OTHER: Byte = '?'.code.toByte()
        private const val END: Byte = 0

        private val EXCLUDED_TYPES = setOf(
            PdfName.Page, PdfName.Pages, PdfName.Catalog, PdfName.OCG, PdfName.OCMD, PdfName.Annot,
            PdfName.StructElem, PdfName.StructTreeRoot, PdfName.Outlines, PdfName.Sig, PdfName.ObjStm, PdfName.XRef
        )
        private val EXCLUDED_KEYS = listOf(
            PdfName.Parent, PdfName.P, PdfName.Kids, PdfName.Annots, PdfName.FT, PdfName.Rect, PdfName.PieceInfo
        )
    }
}
