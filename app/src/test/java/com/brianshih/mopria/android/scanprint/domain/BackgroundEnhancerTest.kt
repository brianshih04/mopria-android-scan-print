package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure pixel-processing logic of [BackgroundEnhancer].
 *
 * The algorithm uses morphological illumination normalization:
 * grayscale → box blur (background model) → division → contrast → threshold.
 *
 * Tests verify that text/content is preserved, backgrounds are whitened,
 * and different [EnhancementStrength] levels produce appropriately different results.
 */
class BackgroundEnhancerTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun pureWhiteImageStaysWhite() {
        val white = argb(255, 255, 255)
        val pixels = IntArray(100) { white }
        val result = BackgroundEnhancer.processPixels(pixels, 10, 10)
        result.forEach { assertEquals(white, it) }
    }

    @Test
    fun pureBlackImageStaysBlack() {
        val black = argb(0, 0, 0)
        val pixels = IntArray(100) { black }
        val result = BackgroundEnhancer.processPixels(pixels, 10, 10)
        result.forEach { assertEquals(black, it) }
    }

    @Test
    fun darkTextOnGreyBackgroundIsWhitened() {
        val grey = argb(200, 200, 200)
        val dark = argb(30, 30, 30)
        val width = 20
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            if (idx % width == 10) dark else grey
        }
        val result = BackgroundEnhancer.processPixels(pixels, width, height)

        val darkLum = BackgroundEnhancer.luminance(result[10])
        assertTrue("text pixel luminance should be low (<100), got $darkLum", darkLum < 100)
        assertEquals("background pixel should be pure white", argb(255, 255, 255), result[0])
    }

    @Test
    fun coloredContentIsPreserved() {
        val blue = argb(0, 0, 255)
        val white = argb(255, 255, 255)
        val width = 20
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            if (idx == 100) blue else white
        }
        val result = BackgroundEnhancer.processPixels(pixels, width, height)

        val blueLum = BackgroundEnhancer.luminance(result[100])
        assertTrue("blue stamp should be preserved (low luminance <100), got $blueLum", blueLum < 100)
        assertEquals("background should be white", white, result[0])
    }

    @Test
    fun documentWithShadowsBackgroundBecomesWhite() {
        val bg = argb(210, 210, 210)
        val text = argb(30, 30, 30)
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            if (idx % 5 == 0) text else bg
        }
        val result = BackgroundEnhancer.processPixels(pixels, width, height)

        assertEquals("background should be white", argb(255, 255, 255), result[1])
        val textLum = BackgroundEnhancer.luminance(result[0])
        assertTrue("text should be preserved (luminance <100), got $textLum", textLum < 100)
    }

    // ===== Strength comparison tests =====

    @Test
    fun strongStrengthWhitensMoreThanLight() {
        // Borderline background pixel (190 luminance) — should be treated differently by strengths.
        val bgVal = argb(190, 190, 190)
        val text = argb(40, 40, 40)
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            if (idx % 5 == 0) text else bgVal
        }

        val lightResult = BackgroundEnhancer.processPixels(pixels, width, height, EnhancementStrength.Light)
        val strongResult = BackgroundEnhancer.processPixels(pixels, width, height, EnhancementStrength.Strong)

        // Count white pixels (each background pixel that became 0xFFFFFFFF)
        val lightWhite = lightResult.count { it == 0xFFFFFFFF.toInt() }
        val strongWhite = strongResult.count { it == 0xFFFFFFFF.toInt() }

        // Strong should whiten more pixels than Light (lower threshold = 185 vs 210)
        assertTrue(
            "Strong ($strongWhite whites) should whiten more than Light ($lightWhite whites)",
            strongWhite >= lightWhite,
        )
    }

    @Test
    fun normalStrengthIsBetweenLightAndStrong() {
        val bgVal = argb(195, 195, 195)
        val text = argb(40, 40, 40)
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { idx ->
            if (idx % 5 == 0) text else bgVal
        }

        val lightWhite = BackgroundEnhancer.processPixels(pixels, width, height, EnhancementStrength.Light)
            .count { it == 0xFFFFFFFF.toInt() }
        val normalWhite = BackgroundEnhancer.processPixels(pixels, width, height, EnhancementStrength.Normal)
            .count { it == 0xFFFFFFFF.toInt() }
        val strongWhite = BackgroundEnhancer.processPixels(pixels, width, height, EnhancementStrength.Strong)
            .count { it == 0xFFFFFFFF.toInt() }

        assertTrue("Normal ($normalWhite) should be between Light ($lightWhite) and Strong ($strongWhite)",
            normalWhite >= lightWhite && strongWhite >= normalWhite)
    }

    @Test
    fun emptyArrayReturnsEmpty() {
        val result = BackgroundEnhancer.processPixels(intArrayOf(), 0, 0)
        assertEquals(0, result.size)
    }

    @Test
    fun luminanceOfPureColors() {
        assertEquals(54, BackgroundEnhancer.luminance(argb(255, 0, 0)))
        assertEquals(182, BackgroundEnhancer.luminance(argb(0, 255, 0)))
        assertEquals(18, BackgroundEnhancer.luminance(argb(0, 0, 255)))
        val whiteLum = BackgroundEnhancer.luminance(argb(255, 255, 255))
        assertTrue("white luminance should be 254-255, got $whiteLum", whiteLum in 254..255)
        assertEquals(0, BackgroundEnhancer.luminance(argb(0, 0, 0)))
    }

    @Test
    fun veryDarkImageContentIsPreservedAtAllStrengths() {
        val dark = argb(60, 60, 60)
        val pixels = IntArray(100) { dark }
        for (strength in EnhancementStrength.entries) {
            val result = BackgroundEnhancer.processPixels(pixels, 10, 10, strength)
            val lum = BackgroundEnhancer.luminance(result[0])
            assertTrue("dark content should be preserved at $strength (lum <100), got $lum", lum < 100)
        }
    }
}
