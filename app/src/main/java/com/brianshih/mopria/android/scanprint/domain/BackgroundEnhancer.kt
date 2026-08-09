package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Post-scan document background cleanup using illumination normalization.
 *
 * Implements a morphological-division pipeline similar to OpenCV's:
 *   1. Convert to grayscale.
 *   2. Estimate the background illumination map via a large box blur (approximates
 *      morphological CLOSE — removes text/detail, preserves shadows/creases/lighting).
 *   3. Divide the original by the background model (scale=255) to flatten uneven lighting.
 *   4. Apply a gentle threshold: pixels above 200 → white, below → preserved.
 *
 * This produces clean white backgrounds while preserving text, signatures, and stamps.
 * Color information is retained: the normalization factor is applied to each RGB channel
 * independently, so colored content stays colored and only the background is whitened.
 *
 * See: https://docs.opencv.org/4.x/d7/d1b/group__imgproc__misc.html (morphologyEx + divide)
 */
object BackgroundEnhancer {

    /**
     * Apply background enhancement to an ARGB [bitmap].
     *
     * Returns a new bitmap with the background normalized to white. The input is not modified.
     */
    fun apply(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val enhanced = processPixels(pixels, width, height)

        val result = createBitmap(width, height)
        result.setPixels(enhanced, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Pure pixel-processing pipeline (testable without Android Bitmap APIs).
     *
     * [pixels] is a row-major ARGB array of [width]×[height] pixels.
     */
    internal fun processPixels(pixels: IntArray, width: Int, height: Int): IntArray {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return pixels

        // Step 1: Extract grayscale luminance map.
        val gray = IntArray(pixels.size) { luminance(pixels[it]) }

        // Step 2: Estimate background via box blur with large kernel.
        // Kernel radius ≈ max(width, height) / 16, clamped to [10, 60].
        // This approximates morphological CLOSE — it erases text but keeps large gradients.
        val radius = (max(width, height) / 16).coerceIn(10, 60)
        val background = boxBlur(gray, width, height, radius)

        // Step 3: Division normalization per RGB channel + gentle threshold.
        // For each pixel, compute the normalization factor = original_luminance / background_luminance.
        // Then scale each RGB channel by this factor, clamped to 255.
        // Pixels whose normalized luminance is very high (≥200) are pushed to pure white.
        return IntArray(pixels.size) { i ->
            val bg = max(background[i], 1) // avoid division by zero
            val orig = gray[i]
            val factor = orig.toFloat() / bg.toFloat()

            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val alpha = (pixels[i] shr 24) and 0xFF

            val nr = min(255, (r * factor).toInt())
            val ng = min(255, (g * factor).toInt())
            val nb = min(255, (b * factor).toInt())

            // Compute normalized luminance for threshold decision
            val nLum = (0.2126 * nr + 0.7152 * ng + 0.0722 * nb).toInt()

            if (nLum >= 200) {
                // Background pixel → push to pure white
                0xFFFFFFFF.toInt()
            } else {
                // Content pixel → use normalized color
                (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /**
     * Separable box blur on a grayscale [data] array of [width]×[height].
     * [radius] is the blur kernel half-size (actual kernel = 2*radius+1).
     * Uses the standard two-pass (horizontal then vertical) approach for O(n) performance.
     */
    private fun boxBlur(data: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(data.size)
        val result = IntArray(data.size)
        val kernel = (radius * 2 + 1)

        // Horizontal pass
        for (y in 0 until height) {
            var sum = 0
            // Initialize window
            for (x in -radius..radius) {
                val cx = min(max(x, 0), width - 1)
                sum += data[y * width + cx]
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / kernel
                // Slide window: subtract left edge, add right edge
                val leftX = min(max(x - radius, 0), width - 1)
                val rightX = min(max(x + radius + 1, 0), width - 1)
                sum += data[y * width + rightX] - data[y * width + leftX]
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            var sum = 0
            for (y in -radius..radius) {
                val cy = min(max(y, 0), height - 1)
                sum += horizontal[cy * width + x]
            }
            for (y in 0 until height) {
                result[y * width + x] = sum / kernel
                val topY = min(max(y - radius, 0), height - 1)
                val bottomY = min(max(y + radius + 1, 0), height - 1)
                sum += horizontal[bottomY * width + x] - horizontal[topY * width + x]
            }
        }

        return result
    }

    /**
     * Process a JPEG or PNG [file] in place: decode, enhance, re-encode.
     * Returns true if the file was successfully enhanced.
     * PDF files are skipped (enhancement applies to raster images only).
     */
    fun enhanceImageFile(file: File): Boolean {
        val name = file.name.lowercase()
        if (!name.endsWith(".jpg") && !name.endsWith(".jpeg") && !name.endsWith(".png")) return false

        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val enhanced = apply(bitmap)
        bitmap.recycle()

        val format = if (name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        return try {
            FileOutputStream(file).use { out ->
                enhanced.compress(format, 92, out)
            }
            true
        } finally {
            enhanced.recycle()
        }
    }

    /** Compute luminance (0–255) from an Android ARGB int (0xAARRGGBB). */
    internal fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)
    }
}
