package hu.reelee81.reepdf.pdf

import java.io.File

object PdfRevisionRecovery {
    fun withEarlierRevision(
        input: File,
        workDir: File,
        ensureActive: () -> Unit,
        isUsable: (File) -> Boolean,
        block: (File) -> Unit
    ): Boolean {
        val ends = endOfFileMarkers(input)
        if (ends.size < 2) return false
        val revision = File(workDir, "revision_${System.nanoTime()}.pdf")
        try {
            for (end in ends.dropLast(1).asReversed()) {
                ensureActive()
                copyPrefix(input, revision, end)
                if (isUsable(revision)) {
                    block(revision)
                    return true
                }
            }
            return false
        } finally {
            revision.delete()
        }
    }

    private fun endOfFileMarkers(input: File): List<Long> {
        val ends = ArrayList<Long>()
        val buffer = ByteArray(BUFFER_SIZE)
        var state = 0
        var position = 0L
        input.inputStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                for (index in 0 until read) {
                    position++
                    state = when (buffer[index].toInt()) {
                        '%'.code -> if (state == 1 || state == 2) 2 else 1
                        'E'.code -> if (state == 2) 3 else 0
                        'O'.code -> if (state == 3) 4 else 0
                        'F'.code -> if (state == 4) 5 else 0
                        else -> 0
                    }
                    if (state == 5) {
                        ends += position
                        state = 0
                    }
                }
            }
        }
        return ends
    }

    private fun copyPrefix(input: File, output: File, length: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        input.inputStream().use { source ->
            output.outputStream().use { target ->
                var remaining = length
                while (remaining > 0) {
                    val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    target.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
