package hu.reelee81.reepdf.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

class EditorTransfer(
    val target: Target,
    val settings: String?,
    val signatures: List<String?>,
    val values: Map<String, Value> = emptyMap()
) {
    enum class Target { PROFILE, LIVE }

    sealed interface Value {
        data class Text(val text: String) : Value
        data class Number(val number: Int) : Value
        data class Flag(val flag: Boolean) : Value
    }

    init {
        require(signatures.size == VectorSignatureFiles.SLOTS) { "A transfer needs every signature slot" }
        require(target == Target.PROFILE || (settings != null && values.isEmpty())) { "The editor takes only its settings" }
    }
}

object EditorTransferFile {
    private const val MAGIC = 0x52454554
    private const val VERSION = 1
    private const val NONE = -1
    private const val MAX_TEXT = VectorSignatureFiles.MAX_BYTES.toInt()
    private const val MAX_VALUES = 256
    private const val MAX_FILE = 4L * 1024 * 1024
    private const val TEXT = 0
    private const val NUMBER = 1
    private const val FLAG = 2
    private const val TEMPORARY_SUFFIX = ".tmp"

    fun write(file: File, transfer: EditorTransfer) {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).run {
            writeInt(MAGIC)
            writeByte(VERSION)
            writeByte(transfer.target.ordinal)
            optionalText(transfer.settings)
            writeByte(transfer.signatures.size)
            for (signature in transfer.signatures) optionalText(signature)
            writeInt(transfer.values.size)
            for ((key, value) in transfer.values) {
                text(key)
                when (value) {
                    is EditorTransfer.Value.Text -> {
                        writeByte(TEXT)
                        text(value.text)
                    }
                    is EditorTransfer.Value.Number -> {
                        writeByte(NUMBER)
                        writeInt(value.number)
                    }
                    is EditorTransfer.Value.Flag -> {
                        writeByte(FLAG)
                        writeBoolean(value.flag)
                    }
                }
            }
            flush()
        }
        val content = ByteArrayOutputStream()
        DataOutputStream(content).run {
            write(body.toByteArray())
            writeInt(CRC32().apply { update(body.toByteArray()) }.value.toInt())
            flush()
        }
        if (content.size() > MAX_FILE) throw IOException("The editor transfer is too large")
        val dir = file.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.name}")
        val temporary = File(dir, file.name + TEMPORARY_SUFFIX)
        try {
            temporary.writeBytes(content.toByteArray())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
    }

    fun read(file: File): EditorTransfer? {
        if (!file.isFile || file.length() > MAX_FILE) return null
        val bytes = file.readBytes()
        val body = bytes.size - Int.SIZE_BYTES
        if (body < 0) return null
        val stored = DataInputStream(bytes.inputStream(body, Int.SIZE_BYTES)).readInt()
        if (CRC32().apply { update(bytes, 0, body) }.value.toInt() != stored) return null
        return try {
            val input = DataInputStream(bytes.inputStream(0, body))
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return null
            val target = EditorTransfer.Target.entries.getOrNull(input.readUnsignedByte()) ?: return null
            val settingsLength = input.readInt()
            val settings = if (settingsLength == NONE) null else input.text(settingsLength) ?: return null
            if (input.readUnsignedByte() != VectorSignatureFiles.SLOTS) return null
            val signatures = List(VectorSignatureFiles.SLOTS) {
                val length = input.readInt()
                if (length == NONE) null else input.text(length) ?: return null
            }
            val count = input.readInt()
            if (count !in 0..MAX_VALUES) return null
            val values = LinkedHashMap<String, EditorTransfer.Value>()
            repeat(count) {
                val key = input.text(input.readInt()) ?: return null
                values[key] = when (input.readUnsignedByte()) {
                    TEXT -> EditorTransfer.Value.Text(input.text(input.readInt()) ?: return null)
                    NUMBER -> EditorTransfer.Value.Number(input.readInt())
                    FLAG -> EditorTransfer.Value.Flag(input.readBoolean())
                    else -> return null
                }
            }
            if (input.available() != 0) return null
            if (target == EditorTransfer.Target.LIVE && (settings == null || values.isNotEmpty())) return null
            EditorTransfer(target, settings, signatures, values)
        } catch (_: EOFException) {
            null
        }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT) throw IOException("A text of the editor transfer is too long")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.optionalText(value: String?) {
        if (value == null) writeInt(NONE) else text(value)
    }

    private fun DataInputStream.text(length: Int): String? {
        if (length !in 0..MAX_TEXT) return null
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}

class EditorTransfers(
    private val record: File,
    private val live: VectorSignatureFiles,
    private val profile: VectorSignatureFiles,
    private val applySettings: (EditorTransfer) -> Unit
) {
    fun run(transfer: EditorTransfer) {
        EditorTransferFile.write(record, transfer)
        apply(transfer)
        record.delete()
    }

    fun recover() {
        if (!record.exists()) return
        EditorTransferFile.read(record)?.let(::apply)
        record.delete()
    }

    private fun apply(transfer: EditorTransfer) {
        if (transfer.settings != null) {
            val target = if (transfer.target == EditorTransfer.Target.PROFILE) profile else live
            for (slot in 0 until VectorSignatureFiles.SLOTS) {
                val signature = transfer.signatures[slot]
                when {
                    signature != null -> target.write(slot, signature)
                    transfer.target == EditorTransfer.Target.PROFILE -> target.delete(slot)
                }
            }
        }
        applySettings(transfer)
    }
}
