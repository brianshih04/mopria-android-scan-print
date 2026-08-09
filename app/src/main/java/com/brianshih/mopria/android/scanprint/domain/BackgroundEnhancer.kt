package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Post-scan document background cleanup using illumination normalization.
 *
 * Implements a morphological-division pipeline similar to OpenCV's:
 *   1. Convert to grayscale.
 *   2. Estimate the background illumination map via a large box blur (approximates
 *      morphological CLOSE — removes text/detail, preserves shadows/creases/lighting).
 *   3. Divide the original by the background model (scale=255) to flatten uneven lighting.
 *   4. Contrast adjustment (alpha/beta linear transform) to make colors more vivid.
 *   5. Threshold: pixels above a strength-dependent cutoff → white, below → preserved.
 *
 * [EnhancementStrength] controls how aggressive the contrast and threshold are:
 * - [EnhancementStrength.Light]: subtle cleanup (alpha=1.0, threshold=210)
 * - [EnhancementStrength.Normal]: balanced (alpha=1.05, beta=-10, threshold=200)
 * - [EnhancementStrength.Strong]: aggressive (alpha=1.15, beta=-20, threshold=185)
 *
 * See: https://docs.opencv.org/4.x/d7/d1b/group__imgproc__misc.html (morphologyEx + divide)
 */
object BackgroundEnhancer {

    /**
     * Apply background enhancement to an ARGB [bitmap] at the given [strength].
     *
     * Uses pyramid downsampling for performance: the background illumination map is estimated
     * at 1/4 resolution (since shadows are smooth gradients, low-res is sufficient), then
     * upscaled back to full resolution for the per-pixel division. This reduces the expensive
     * box blur cost by ~16x while maintaining full-resolution text clarity.
     *
     * Returns a new bitmap with the background normalized to white. The input is not modified.
     */
    fun apply(bitmap: Bitmap, strength: EnhancementStrength = EnhancementStrength.Normal): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val enhanced = processPixels(pixels, width, height, strength)

        val result = createBitmap(width, height)
        result.setPixels(enhanced, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Pure pixel-processing pipeline (testable without Android Bitmap APIs).
     *
     * [pixels] is a row-major ARGB array of [width]×[height] pixels.
     */
    internal fun processPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: EnhancementStrength = EnhancementStrength.Normal,
    ): IntArray {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return pixels

        val (alpha, beta, threshold) = strengthParams(strength)

        // Step 1: Extract grayscale luminance map.
        val gray = IntArray(pixels.size) { luminance(pixels[it]) }

        // Step 2: Estimate background via pyramid downsampling + box blur.
        // Downsample to 1/4 resolution for the expensive blur (shadows are smooth,
        // don't need full-res), then bilinear-upscale back. ~16x faster for A4 300dpi.
        val background = downsampledBackground(gray, width, height)

        // Step 3-5: Division normalization + contrast + threshold per RGB channel.
        return IntArray(pixels.size) { i ->
            val bg = max(background[i], 1)
            val orig = gray[i]
            val factor = orig.toFloat() / bg.toFloat()

            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val a = (pixels[i] shr 24) and 0xFF

            // Normalize (divide by background), then apply contrast (alpha*x + beta).
            val nr = applyContrast(r * factor, alpha, beta)
            val ng = applyContrast(g * factor, alpha, beta)
            val nb = applyContrast(b * factor, alpha, beta)

            val nLum = (0.2126 * nr + 0.7152 * ng + 0.0722 * nb).roundToInt()

            if (nLum >= threshold) {
                0xFFFFFFFF.toInt() // push to pure white
            } else {
                (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /** Returns (alphaMultiplier, betaOffset, whiteThreshold) for each strength level. */
    private fun strengthParams(strength: EnhancementStrength): Triple<Float, Float, Int> = when (strength) {
        EnhancementStrength.Light -> Triple(1.0f, 0.0f, 210)
        EnhancementStrength.Normal -> Triple(1.05f, -10.0f, 200)
        EnhancementStrength.Strong -> Triple(1.15f, -20.0f, 185)
    }

    /** Linear contrast transform: out = clamp(alpha * value + beta, 0, 255). */
    private fun applyContrast(value: Float, alpha: Float, beta: Float): Int =
        min(255f, max(0f, alpha * value + beta)).roundToInt()

    /**
     * Pyramid downsampling background estimation:
     * 1. Downsample grayscale to 1/4 size.
     * 2. Box blur on the small image (kernel also 1/4 size).
     * 3. Bilinear upscale back to original dimensions.
     *
     * This is ~16x faster than full-resolution blur for large images (A4 300dpi ≈ 2480×3508),
     * with negligible quality loss since background illumination is inherently smooth.
     */
    private fun downsampledBackground(gray: IntArray, width: Int, height: Int): IntArray {
        val smallW = max(1, width / 4)
        val smallH = max(1, height / 4)

        // Downsample by 4x using area averaging
        val small = downsample(gray, width, height, smallW, smallH)

        // Box blur on small image with proportionally smaller kernel
        val smallRadius = (max(smallW, smallH) / 16).coerceIn(3, 16)
        val smallBlurred = boxBlur(small, smallW, smallH, smallRadius)

        // Upscale back to original size using bilinear interpolation
        return upscale(smallBlurred, smallW, smallH, width, height)
    }

    /** Area-average downsample from [srcW]×[srcH] to [dstW]×[dstH]. */
    private fun downsample(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val dst = IntArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            val sy0 = (dy * yRatio).toInt()
            val sy1 = min(((dy + 1) * yRatio).toInt(), srcH)
            for (dx in 0 until dstW) {
                val sx0 = (dx * xRatio).toInt()
                val sx1 = min(((dx + 1) * xRatio).toInt(), srcW)
                var sum = 0
                var count = 0
                for (sy in sy0 until sy1) {
                    for (sx in sx0 until sx1) {
                        sum += src[sy * srcW + sx]
                        count++
                    }
                }
                dst[dy * dstW + dx] = if (count > 0) sum / count else 0
            }
        }
        return dst
    }

    /** Bilinear upscale from [srcW]×[srcH] to [dstW]×[dstH]. */
    private fun upscale(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val dst = IntArray(dstW * dstH)
        val xRatio = (srcW - 1).toFloat() / max(1, dstW - 1)
        val yRatio = (srcH - 1).toFloat() / max(1, dstH - 1)
        for (dy in 0 until dstH) {
            val sy = dy * yRatio
            val sy0 = sy.toInt().coerceIn(0, srcH - 1)
            val sy1 = (sy0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - sy0
            for (dx in 0 until dstW) {
                val sx = dx * xRatio
                val sx0 = sx.toInt().coerceIn(0, srcW - 1)
                val sx1 = (sx0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - sx0
                val v00 = src[sy0 * srcW + sx0]
                val v01 = src[sy0 * srcW + sx1]
                val v10 = src[sy1 * srcW + sx0]
                val v11 = src[sy1 * srcW + sx1]
                val top = v00 + (v01 - v00) * fx
                val bottom = v10 + (v11 - v10) * fx
                dst[dy * dstW + dx] = (top + (bottom - top) * fy).toInt()
            }
        }
        return dst
    }

    /**
     * Separable box blur on a grayscale [data] array of [width]×[height].
     * [radius] is the blur kernel half-size (actual kernel = 2*radius+1).
     */
    private fun boxBlur(data: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(data.size)
        val result = IntArray(data.size)
        val kernel = (radius * 2 + 1)

        for (y in 0 until height) {
            var sum = 0
            for (x in -radius..radius) {
                val cx = min(max(x, 0), width - 1)
                sum += data[y * width + cx]
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / kernel
                val leftX = min(max(x - radius, 0), width - 1)
                val rightX = min(max(x + radius + 1, 0), width - 1)
                sum += data[y * width + rightX] - data[y * width + leftX]
            }
        }

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
     */
    fun enhanceImageFile(file: File, strength: EnhancementStrength = EnhancementStrength.Normal): Boolean {
        val name = file.name.lowercase()
        if (!name.endsWith(".jpg") && !name.endsWith(".jpeg") && !name.endsWith(".png")) return false

        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val enhanced = apply(bitmap, strength)
        bitmap.recycle()

        val format = if (name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        return try {
            FileOutputStream(file).use { out -> enhanced.compress(format, 92, out) }
            true
        } finally {
            enhanced.recycle()
        }
    }

    /** Compute luminance (0–255) from an Android ARGB int. */
    internal fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)
    }
}
