package hu.reelee81.reepdf.core

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SignedPageEffectsReferenceTest {
    private val sizes = listOf(1 to 1, 7 to 5, 2480 to 3508)

    private fun image(width: Int, height: Int, seed: Int): ByteArray =
        ByteArray(width * height * 3).also { Random(seed).nextBytes(it) }

    private fun assertSameBytes(expected: ByteArray, actual: ByteArray) = assertArrayEquals(expected, actual)

    @Test
    fun grayscaleMatchesTheReferenceByteForByte() {
        for ((width, height) in sizes) {
            val expected = image(width, height, width)
            val actual = expected.copyOf()

            referenceGrayscale(expected)
            SignedPageEffects.grayscale(actual)

            assertSameBytes(expected, actual)
        }
    }

    @Test
    fun scanEffectMatchesTheReferenceByteForByte() {
        for ((width, height) in sizes) {
            val expected = image(width, height, height)
            val actual = expected.copyOf()

            referenceScanColors(expected)
            referenceEdgeShadow(expected, width, height)
            SignedPageEffects.scanEffect(actual, width, height)

            assertSameBytes(expected, actual)
        }
    }

    @Test
    fun grayscaleThenScanEffectMatchesTheReferenceByteForByte() {
        val (width, height) = sizes.last()
        val expected = image(width, height, 81)
        val actual = expected.copyOf()

        referenceGrayscale(expected)
        referenceScanColors(expected)
        referenceEdgeShadow(expected, width, height)
        SignedPageEffects.grayscale(actual)
        SignedPageEffects.scanEffect(actual, width, height)

        assertSameBytes(expected, actual)
    }

    private fun referenceGrayscale(rgb: ByteArray) {
        val m = saturation(0f)
        var i = 0
        while (i + 2 < rgb.size) {
            val r = u(rgb[i])
            val g = u(rgb[i + 1])
            val b = u(rgb[i + 2])
            rgb[i] = channel(m[0] * r + m[1] * g + m[2] * b).toByte()
            rgb[i + 1] = channel(m[3] * r + m[4] * g + m[5] * b).toByte()
            rgb[i + 2] = channel(m[6] * r + m[7] * g + m[8] * b).toByte()
            i += 3
        }
    }

    private fun referenceScanColors(rgb: ByteArray) {
        val m = saturation(SignedPageEffects.SCAN_SATURATION)
        var i = 0
        while (i + 2 < rgb.size) {
            val r = u(rgb[i])
            val g = u(rgb[i + 1])
            val b = u(rgb[i + 2])
            for (c in 0 until 3) {
                val base = m[c * 3] * r + m[c * 3 + 1] * g + m[c * 3 + 2] * b
                rgb[i + c] = channel(SignedPageEffects.SCAN_CONTRAST * base + SignedPageEffects.SCAN_BRIGHTNESS).toByte()
            }
            i += 3
        }
    }

    private fun referenceEdgeShadow(rgb: ByteArray, width: Int, height: Int) {
        val vertical = edgeFactors(height)
        val horizontal = edgeFactors(width)
        var i = 0
        for (y in 0 until height) {
            val fy = vertical[y]
            for (x in 0 until width) {
                val fx = horizontal[x]
                for (c in 0 until 3) {
                    val shadedY = channel(u(rgb[i + c]) * fy)
                    rgb[i + c] = channel(shadedY * fx).toByte()
                }
                i += 3
            }
        }
    }

    private fun saturation(sat: Float): FloatArray {
        val inv = 1f - sat
        val r = SignedPageEffects.GRAY_R * inv
        val g = SignedPageEffects.GRAY_G * inv
        val b = SignedPageEffects.GRAY_B * inv
        return floatArrayOf(
            r + sat, g, b,
            r, g + sat, b,
            r, g, b + sat
        )
    }

    private fun edgeFactors(size: Int): FloatArray = FloatArray(size) {
        val t = ((it + 0.5f) / size).coerceIn(0f, 1f)
        1f - SignedPageEffects.SCAN_EDGE_ALPHA * (1f - t) / 255f
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun channel(v: Float): Int = (v.coerceIn(0f, 255f) + 0.5f).toInt()
}
