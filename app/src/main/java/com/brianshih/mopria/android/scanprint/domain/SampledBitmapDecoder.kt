package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.roundToInt

/**
 * Decodes a JPEG/PNG file no larger than [requestedWidth]×[requestedHeight], preserving aspect ratio,
 * so callers never hold a full-resolution scan in memory when a bounded bitmap is all they draw.
 *
 * Shared by the IPP print renderer ([RealIntegrationProvider]) and the UI bitmap loader
 * ([com.brianshih.mopria.android.scanprint.ui.BitmapLoader]) to keep one sampling strategy.
 */
internal object SampledBitmapDecoder {
    /** Exact bounded file decode; returns null if [path] is not a decodable image. */
    fun decodeFile(path: String, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        if (requestedWidth <= 0 || requestedHeight <= 0) return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val sourceWidth = info.size.width
                val sourceHeight = info.size.height
                val scale = minOf(
                    requestedWidth.toFloat() / sourceWidth,
                    requestedHeight.toFloat() / sourceHeight,
                    1f,
                )
                val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                if (targetWidth != sourceWidth || targetHeight != sourceHeight) {
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
                // Printing and PdfDocument require a software bitmap; hardware bitmaps cannot be drawn
                // into every Canvas implementation used by the app.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    /**
     * Largest power-of-two sample keeping the decoded dimensions at least [requestedWidth]×[requestedHeight]
     * (powers of two are required by [BitmapFactory] for sampling).
     */
    fun sampleSize(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= requestedWidth && height / (sample * 2) >= requestedHeight) {
            sample *= 2
        }
        return sample
    }
}
