package hu.reelee81.reepdf.viewer

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

object ViewerTextLines {
    const val RUN_SIZE = 8
    private const val STRIKETHROUGH_SHIFT = 0.26f
    private const val VERTICAL_STRIKETHROUGH_SHIFT = 0f
    private const val COLUMN_HALF = 0.5f
    private const val MIN_THICKNESS = 0.5f
    internal val MARKS = Shape(thickness = 0.09f, underlineShift = -0.14f, waveShift = -0.17f, waveHeight = 0.16f, waveLength = 0.35f)
    internal val SAMPLES = Shape(thickness = 0.07f, underlineShift = -0.1f, waveShift = -0.13f, waveHeight = 0.12f, waveLength = 0.3f)

    internal class Shape(val thickness: Float, val underlineShift: Float, val waveShift: Float, val waveHeight: Float, val waveLength: Float)

    internal fun path(runs: FloatArray, kind: ViewerMarkKind, wavy: Boolean, shape: Shape): ViewerMarkPath {
        val verbs = ArrayList<Byte>()
        val points = ArrayList<Float>()
        for (run in 0 until runs.size / RUN_SIZE) {
            val offset = run * RUN_SIZE
            val vertical = runs[offset + 7] != 0f
            val corners = if (wavy && kind == ViewerMarkKind.UNDERLINE) {
                wave(runs, offset, if (vertical) COLUMN_HALF - shape.waveShift else shape.waveShift, shape)
            } else {
                band(runs, offset, shift(kind, vertical, shape), shape)
            }
            for (corner in 0 until corners.size / 2) {
                verbs += if (corner == 0) ViewerMarkPath.MOVE else ViewerMarkPath.LINE
                points += corners[corner * 2]
                points += corners[corner * 2 + 1]
            }
            verbs += ViewerMarkPath.CLOSE
        }
        return ViewerMarkPath(verbs.toByteArray(), points.toFloatArray())
    }

    private fun shift(kind: ViewerMarkKind, vertical: Boolean, shape: Shape): Float = when {
        vertical -> if (kind == ViewerMarkKind.UNDERLINE) COLUMN_HALF - shape.underlineShift else VERTICAL_STRIKETHROUGH_SHIFT
        kind == ViewerMarkKind.UNDERLINE -> shape.underlineShift
        else -> STRIKETHROUGH_SHIFT
    }

    private fun band(runs: FloatArray, offset: Int, shift: Float, shape: Shape): FloatArray {
        val upX = runs[offset + 4]
        val upY = runs[offset + 5]
        val size = runs[offset + 6]
        val half = max(size * shape.thickness, MIN_THICKNESS) / 2
        val low = size * shift - half
        val high = size * shift + half
        return floatArrayOf(
            runs[offset] + upX * low, runs[offset + 1] + upY * low,
            runs[offset + 2] + upX * low, runs[offset + 3] + upY * low,
            runs[offset + 2] + upX * high, runs[offset + 3] + upY * high,
            runs[offset] + upX * high, runs[offset + 1] + upY * high
        )
    }

    private fun wave(runs: FloatArray, offset: Int, shift: Float, shape: Shape): FloatArray {
        val startX = runs[offset]
        val startY = runs[offset + 1]
        val length = hypot(runs[offset + 2] - startX, runs[offset + 3] - startY)
        if (length <= 0f) return band(runs, offset, shift, shape)
        val alongX = (runs[offset + 2] - startX) / length
        val alongY = (runs[offset + 3] - startY) / length
        val upX = runs[offset + 4]
        val upY = runs[offset + 5]
        val size = runs[offset + 6]
        val center = size * shift
        val amplitude = size * shape.waveHeight / 2
        val wavelength = size * shape.waveLength
        val step = wavelength / 2
        val lift = max(size * shape.thickness, MIN_THICKNESS) / 2 * sqrt(1 + (amplitude * 2 / step) * (amplitude * 2 / step))
        val start = startX * alongX + startY * alongY
        val stops = ArrayList<Float>()
        stops += 0f
        var corner = floor(start / step) + 1
        while (corner * step - start < length) {
            stops += corner * step - start
            corner++
        }
        stops += length
        val points = FloatArray(stops.size * 4)
        for ((index, at) in stops.withIndex()) {
            val phase = (start + at) / wavelength
            val height = center + amplitude * (1 - 4 * abs(phase - floor(phase) - 0.5f))
            val x = startX + alongX * at
            val y = startY + alongY * at
            points[index * 2] = x + upX * (height + lift)
            points[index * 2 + 1] = y + upY * (height + lift)
            val back = (stops.size * 2 - 1 - index) * 2
            points[back] = x + upX * (height - lift)
            points[back + 1] = y + upY * (height - lift)
        }
        return points
    }
}
