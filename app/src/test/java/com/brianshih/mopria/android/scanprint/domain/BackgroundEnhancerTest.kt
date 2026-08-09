package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure pixel-processing logic of [BackgroundEnhancer].
 *
 * The algorithm uses morphological illumination normalization:
 * grayscale → box blur (background model) → division → threshold.
 *
 * Tests verify that text/content is preserved, backgrounds are whitened,
 * and the box blur background estimation is correct.
 */
class BackgroundEnhancerTest {

    /** ARGB int from r, g, b (alpha always 0xFF). */
    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun pureWhiteImageStaysWhite() {
        val white = argb(255, 255, 255)
        // 10x10 all-white image
        val pixels = IntArray(100) { white }
        val result = BackgroundEnhancer.processPixels(pixels, 10, 10)
        // Background = 255 everywhere. factor = 255/255 = 1.0. nLum = 255 ≥ 200 → white.
        result.forEach { assertEquals(white, it) }
    }

    @Test
    fun pureBlackImageStaysBlack() {
        val black = argb(0, 0, 0)
        val pixels = IntArray(100) { black }
        val result = BackgroundEnhancer.processPixels(pixels, 10, 10)
        // Background = 0 (clamped to 1). factor = 0/1 = 0. nLum = 0 < 200 → preserved as black.
        result.forEach { assertEquals(black, it) }
    }

    @Test
    fun darkTextOnGreyBackgroundIsWhitened() {
        // Simulate a document: mostly grey background (200) with a few dark text pixels (30)
        val grey = argb(200, 200, 200)
        val dark = argb(30, 30, 30)
        val width = 20
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            // Put "text" pixels in a vertical stripe at x=10
            if (idx % width == 10) dark else grey
        }
        val result = BackgroundEnhancer.processPixels(pixels, width, height)

        // The box blur background at text pixels will be close to the grey background (200).
        // factor = 30/200 ≈ 0.15 → dark stays dark (nLum ≈ 30 < 200 → preserved).
        // At background pixels, factor = 200/200 = 1.0 → nLum = 200 ≥ 200 → white.
        val sampleDark = result[10] // x=10, y=0 → text pixel
        val sampleBg = result[0]    // x=0, y=0 → background pixel

        // Text should be preserved (dark)
        val darkLum = BackgroundEnhancer.luminance(sampleDark)
        assertTrue("text pixel luminance should be low (<100), got $darkLum", darkLum < 100)

        // Background should be pushed to white
        assertEquals("background pixel should be pure white", argb(255, 255, 255), sampleBg)
    }

    @Test
    fun documentWithShadowsBackgroundBecomesWhite() {
        // Simulate a realistic document: mostly light grey background (210) with dark text pixels (30)
        // spread throughout. The box blur background model should estimate ~210 for all positions,
        // making division push the background to white while preserving text.
        val bg = argb(210, 210, 210)
        val text = argb(30, 30, 30)
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            // Text pixels at regular intervals
            if (idx % 5 == 0) text else bg
        }
        val result = BackgroundEnhancer.processPixels(pixels, width, height)

        // Background pixels (non-text) should be pushed to white
        val bgSample = result[1] // index 1 is not a text pixel (1 % 5 ≠ 0)
        assertEquals("background should be white", argb(255, 255, 255), bgSample)

        // Text pixels should remain dark
        val textSample = result[0] // index 0 IS a text pixel (0 % 5 == 0)
        val textLum = BackgroundEnhancer.luminance(textSample)
        assertTrue("text should be preserved (luminance <100), got $textLum", textLum < 100)
    }

    @Test
    fun coloredContentIsPreserved() {
        // Blue stamp (low luminance) on white background
        val blue = argb(0, 0, 255) // luminance ≈ 18
        val white = argb(255, 255, 255)
        val width = 20
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            if (idx == 100) blue else white // single blue pixel in the middle
        }
        val result = BackgroundEnhancer.processPixels(pixels, width, height)

        // The blue pixel has very low luminance → factor ≈ 0 → stays dark/blue
        // Background pixels → white
        val blueResult = result[100]
        val blueLum = BackgroundEnhancer.luminance(blueResult)
        assertTrue("blue stamp should be preserved (low luminance <100), got $blueLum", blueLum < 100)

        // Most background pixels should be white
        val bgResult = result[0]
        assertEquals("background should be white", white, bgResult)
    }

    @Test
    fun emptyArrayReturnsEmpty() {
        val result = BackgroundEnhancer.processPixels(intArrayOf(), 0, 0)
        assertEquals(0, result.size)
    }

    @Test
    fun luminanceOfPureColors() {
        // Red: 0.2126 * 255 ≈ 54
        assertEquals(54, BackgroundEnhancer.luminance(argb(255, 0, 0)))
        // Green: 0.7152 * 255 ≈ 182
        assertEquals(182, BackgroundEnhancer.luminance(argb(0, 255, 0)))
        // Blue: 0.0722 * 255 ≈ 18
        assertEquals(18, BackgroundEnhancer.luminance(argb(0, 0, 255)))
        // White: ~255 (floating point may give 254)
        val whiteLum = BackgroundEnhancer.luminance(argb(255, 255, 255))
        assertTrue("white luminance should be 254-255, got $whiteLum", whiteLum in 254..255)
        // Black: 0
        assertEquals(0, BackgroundEnhancer.luminance(argb(0, 0, 0)))
    }
}
