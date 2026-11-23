package hu.reelee81.reepdf.viewer

import kotlin.math.roundToLong

class ViewerSignature(val width: Float, val strokes: List<FloatArray>) {
    init {
        require(WIDTHS.any { it == width }) { "Invalid signature width $width" }
        require(strokes.isNotEmpty()) { "The signature has no strokes" }
        require(strokes.size <= MAX_STROKES) { "The signature has ${strokes.size} strokes" }
        require(strokes.sumOf { it.size / 2 } <= MAX_POINTS) { "The signature has too many points" }
        for (stroke in strokes) {
            require(stroke.size >= 2 && stroke.size % 2 == 0) { "A signature stroke needs whole points" }
            for (index in stroke.indices) {
                val limit = if (index % 2 == 0) 1f else HEIGHT
                require(stroke[index] in 0f..limit) { "A signature point lies outside the pad" }
            }
        }
    }

    fun withWidth(chosen: Float): ViewerSignature = ViewerSignature(chosen, strokes)

    fun bounds(): FloatArray = ViewerSignaturePlacement.box(path(1f, 0f, 0f), width / PAD_WIDTH_ON_PAGE)

    fun path(scale: Float, dx: Float, dy: Float): ViewerMarkPath = pathOf(strokes, scale, dx, dy)

    companion object {
        const val SLOTS = 2
        const val HEIGHT = 0.4f
        const val PAD_WIDTH_ON_PAGE = 170.07874f
        const val MAX_STROKES = 1_000
        const val MAX_POINTS = 20_000
        const val DEFAULT_WIDTH = 1
        val WIDTHS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 2.5f)

        fun pathOf(strokes: List<FloatArray>, scale: Float, dx: Float, dy: Float): ViewerMarkPath {
            val parts = strokes.map { stroke ->
                val builder = ViewerStrokeBuilder(0f)
                for (index in stroke.indices step 2) builder.add(stroke[index] * scale + dx, stroke[index + 1] * scale + dy)
                builder.path()
            }
            val verbs = ByteArray(parts.sumOf { it.verbs.size })
            val points = FloatArray(parts.sumOf { it.points.size })
            var verb = 0
            var point = 0
            for (part in parts) {
                part.verbs.copyInto(verbs, verb)
                part.points.copyInto(points, point)
                verb += part.verbs.size
                point += part.points.size
            }
            return ViewerMarkPath(verbs, points)
        }
    }
}

object ViewerSignatureFormat {
    private const val HEADER = "ReePDF signature 1"
    private const val WIDTH = "width "
    private const val SCALE = 10_000L
    private const val DIGITS = 4
    private val SPACES = Regex("\\s+")

    fun encode(signature: ViewerSignature): String {
        val text = StringBuilder(HEADER).append('\n').append(WIDTH)
        text.number(signature.width)
        for (stroke in signature.strokes) {
            text.append('\n')
            for (index in stroke.indices) {
                if (index > 0) text.append(' ')
                text.number(stroke[index])
            }
        }
        return text.append('\n').toString()
    }

    fun decode(text: String): ViewerSignature? {
        val lines = text.lines().let { if (it.lastOrNull()?.isBlank() == true) it.dropLast(1) else it }
        if (lines.size < 3 || lines.size - 2 > ViewerSignature.MAX_STROKES) return null
        if (lines[0].trim() != HEADER) return null
        val head = lines[1].trim()
        if (!head.startsWith(WIDTH)) return null
        val width = head.substring(WIDTH.length).trim().toFloatOrNull() ?: return null
        val strokes = ArrayList<FloatArray>(lines.size - 2)
        var points = 0
        for (line in lines.subList(2, lines.size)) {
            val parts = line.trim().split(SPACES)
            points += parts.size / 2
            if (points > ViewerSignature.MAX_POINTS) return null
            strokes += FloatArray(parts.size) { parts[it].toFloatOrNull() ?: return null }
        }
        return try {
            ViewerSignature(width, strokes)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun StringBuilder.number(value: Float) {
        val scaled = (value.toDouble() * SCALE).roundToLong()
        append(scaled / SCALE)
        val fraction = scaled % SCALE
        if (fraction != 0L) append('.').append(fraction.toString().padStart(DIGITS, '0').trimEnd('0'))
    }
}
