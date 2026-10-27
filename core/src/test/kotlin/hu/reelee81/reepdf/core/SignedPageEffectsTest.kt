package hu.reelee81.reepdf.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedPageEffectsTest {
    private fun rgb(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun pixel(image: ByteArray, width: Int, x: Int, y: Int): IntArray {
        val i = (y * width + x) * 3
        return IntArray(3) { image[i + it].toInt() and 0xFF }
    }

    private fun assertPixel(expected: IntArray, actual: IntArray) = assertArrayEquals(expected, actual)

    private fun filled(width: Int, height: Int, r: Int, g: Int, b: Int): ByteArray =
        ByteArray(width * height * 3) { i ->
            when (i % 3) {
                0 -> r
                1 -> g
                else -> b
            }.toByte()
        }

    @Test
    fun grayscaleUsesTheAndroidSaturationWeights() {
        val image = rgb(255, 0, 0, 0, 255, 0, 0, 0, 255, 10, 20, 30, 255, 255, 255, 0, 0, 0)

        SignedPageEffects.grayscale(image)

        assertPixel(intArrayOf(54, 54, 54), pixel(image, 6, 0, 0))
        assertPixel(intArrayOf(182, 182, 182), pixel(image, 6, 1, 0))
        assertPixel(intArrayOf(18, 18, 18), pixel(image, 6, 2, 0))
        assertPixel(intArrayOf(19, 19, 19), pixel(image, 6, 3, 0))
        assertPixel(intArrayOf(255, 255, 255), pixel(image, 6, 4, 0))
        assertPixel(intArrayOf(0, 0, 0), pixel(image, 6, 5, 0))
    }

    @Test
    fun aSinglePixelGetsHalfOfBothEdgeShadows() {
        val white = rgb(255, 255, 255)
        val gray = rgb(100, 100, 100)
        val black = rgb(0, 0, 0)

        SignedPageEffects.scanEffect(white, 1, 1)
        SignedPageEffects.scanEffect(gray, 1, 1)
        SignedPageEffects.scanEffect(black, 1, 1)

        assertPixel(intArrayOf(243, 243, 243), pixel(white, 1, 0, 0))
        assertPixel(intArrayOf(96, 96, 96), pixel(gray, 1, 0, 0))
        assertPixel(intArrayOf(0, 0, 0), pixel(black, 1, 0, 0))
    }

    @Test
    fun theEdgeShadowFadesFromTheTopLeftCorner() {
        val image = filled(10, 10, 255, 255, 255)

        SignedPageEffects.scanEffect(image, 10, 10)

        assertPixel(intArrayOf(233, 233, 233), pixel(image, 10, 0, 0))
        assertPixel(intArrayOf(253, 253, 253), pixel(image, 10, 9, 9))
    }

    @Test
    fun theScanColourMatrixDesaturatesSlightlyThenRaisesContrastAndDarkens() {
        val image = filled(100, 100, 200, 0, 0)

        SignedPageEffects.scanEffect(image, 100, 100)

        assertPixel(intArrayOf(197, 0, 0), pixel(image, 100, 99, 99))
    }

    private fun gray(width: Int, height: Int, value: (x: Int, y: Int) -> Int): SignedPageEffects.Rgba =
        SignedPageEffects.Rgba(width, height, ByteArray(width * height * 4) { i ->
            val p = i / 4
            if (i % 4 == 3) 255.toByte() else value(p % width, p / width).toByte()
        })

    private fun value(image: SignedPageEffects.Rgba, x: Int, y: Int): Int =
        image.premultiplied[(y * image.width + x) * 4].toInt() and 0xFF

    private fun assertValues(expected: IntArray, actual: IntArray) = assertArrayEquals(expected, actual)

    @Test
    fun anOpaqueSignaturePixelReplacesAndATransparentOneKeepsThePage() {
        val page = filled(2, 1, 255, 255, 255)
        val signature = SignedPageEffects.Rgba(2, 1, rgb(10, 20, 30, 255, 0, 0, 0, 0))

        SignedPageEffects.drawNearest(page, 2, 1, signature, 0f, 0f, 2, 1)

        assertPixel(intArrayOf(10, 20, 30), pixel(page, 2, 0, 0))
        assertPixel(intArrayOf(255, 255, 255), pixel(page, 2, 1, 0))
    }

    @Test
    fun aHalfTransparentSignaturePixelIsMixedOverThePage() {
        val page = filled(1, 1, 255, 255, 255)
        val signature = SignedPageEffects.Rgba(1, 1, rgb(64, 0, 0, 128))

        SignedPageEffects.drawNearest(page, 1, 1, signature, 0f, 0f, 1, 1)

        assertPixel(intArrayOf(191, 127, 127), pixel(page, 1, 0, 0))
    }

    @Test
    fun aSignatureHangingOverThePageEdgeIsClipped() {
        val page = filled(2, 2, 255, 255, 255)
        val signature = gray(2, 2) { x, y -> 1 + x + y * 2 }

        SignedPageEffects.drawNearest(page, 2, 2, signature, -1f, -1f, 2, 2)

        assertPixel(intArrayOf(4, 4, 4), pixel(page, 2, 0, 0))
        assertPixel(intArrayOf(255, 255, 255), pixel(page, 2, 1, 0))
        assertPixel(intArrayOf(255, 255, 255), pixel(page, 2, 0, 1))
        assertPixel(intArrayOf(255, 255, 255), pixel(page, 2, 1, 1))
    }

    @Test
    fun aFractionalPositionIsRoundedToTheNearestPixelEdge() {
        val low = filled(4, 1, 255, 255, 255)
        val high = filled(4, 1, 255, 255, 255)
        val signature = gray(1, 1) { _, _ -> 0 }

        SignedPageEffects.drawNearest(low, 4, 1, signature, 1.4f, 0f, 1, 1)
        SignedPageEffects.drawNearest(high, 4, 1, signature, 1.6f, 0f, 1, 1)

        assertValues(intArrayOf(255, 0, 255, 255), IntArray(4) { pixel(low, 4, it, 0)[0] })
        assertValues(intArrayOf(255, 255, 0, 255), IntArray(4) { pixel(high, 4, it, 0)[0] })
    }

    @Test
    fun downscalingPicksThePixelUnderEachTargetCentreWithoutAveraging() {
        val page = filled(2, 1, 255, 255, 255)
        val signature = gray(4, 1) { x, _ -> 10 * (x + 1) }

        SignedPageEffects.drawNearest(page, 2, 1, signature, 0f, 0f, 2, 1)

        assertValues(intArrayOf(20, 40), IntArray(2) { pixel(page, 2, it, 0)[0] })
    }

    @Test
    fun upscalingRepeatsSourcePixels() {
        val page = filled(4, 1, 255, 255, 255)
        val signature = gray(2, 1) { x, _ -> 10 * (x + 1) }

        SignedPageEffects.drawNearest(page, 4, 1, signature, 0f, 0f, 4, 1)

        assertValues(intArrayOf(10, 10, 20, 20), IntArray(4) { pixel(page, 4, it, 0)[0] })
    }

    @Test
    fun subsamplingTakesEveryNthPixelStartingHalfASampleIn() {
        val signature = gray(9, 9) { x, y -> x + 10 * y }

        val sampled = SignedPageEffects.subsample(signature, 4)

        assertValues(intArrayOf(2, 2), intArrayOf(sampled.width, sampled.height))
        assertValues(
            intArrayOf(22, 26, 62, 66),
            intArrayOf(value(sampled, 0, 0), value(sampled, 1, 0), value(sampled, 0, 1), value(sampled, 1, 1))
        )
    }

    @Test
    fun subsamplingBelowTwoKeepsTheImageAndATooLargeSampleLeavesOnePixel() {
        val signature = gray(3, 3) { x, y -> x + 10 * y }

        val same = SignedPageEffects.subsample(signature, 1)
        val single = SignedPageEffects.subsample(signature, 8)

        assertTrue(same === signature)
        assertValues(intArrayOf(1, 1, 22), intArrayOf(single.width, single.height, value(single, 0, 0)))
    }
}
