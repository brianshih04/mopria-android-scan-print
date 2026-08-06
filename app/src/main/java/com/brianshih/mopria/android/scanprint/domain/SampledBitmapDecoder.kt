package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes a JPEG/PNG file at the smallest sample size that still covers [requestedWidth]×[requestedHeight],
 * so callers never hold a full-resolution scan in memory when a downscaled bitmap is all they draw.
 *
 * Shared by the IPP print renderer ([RealIntegrationProvider]) and the UI bitmap loader
 * ([com.brianshih.mopria.android.scanprint.ui.BitmapLoader]) to keep one sampling strategy.
 */
internal object SampledBitmapDecoder {
    /** Full path decode with bounds probe + [sampleSize]; returns null if [path] is not a decodable image. */
    fun decodeFile(path: String, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, requestedWidth, requestedHeight)
        if (sample <= 1) return BitmapFactory.decodeFile(path)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, options)
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
