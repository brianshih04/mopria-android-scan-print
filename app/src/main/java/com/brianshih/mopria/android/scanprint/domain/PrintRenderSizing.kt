package com.brianshih.mopria.android.scanprint.domain

import kotlin.math.roundToInt

/** Pixel dimensions used when preparing a point-sized page for direct IPP output. */
internal data class PrintRenderSize(
    val dpi: Int,
    val pageWidthPixels: Int,
    val pageHeightPixels: Int,
    val marginPixels: Int,
) {
    val contentWidthPixels: Int = (pageWidthPixels - marginPixels * 2).coerceAtLeast(1)
    val contentHeightPixels: Int = (pageHeightPixels - marginPixels * 2).coerceAtLeast(1)
}

/**
 * Converts PDF point dimensions to a bounded print-resolution bitmap budget.
 *
 * 300 dpi preserves normal scan/office-document detail while bounding an A4 page to about 32 MiB
 * per ARGB bitmap. A printer may still request a higher raster output resolution, but source images
 * embedded in the intermediate PDF are capped here instead of being decoded at unlimited scan size.
 */
internal object PrintRenderSizing {
    private const val POINTS_PER_INCH = 72.0
    const val MAX_SOURCE_DPI = 300

    fun page(widthPoints: Int, heightPoints: Int, marginPoints: Int, requestedDpi: Int): PrintRenderSize {
        val boundedDpi = requestedDpi.coerceIn(1, MAX_SOURCE_DPI)
        return PrintRenderSize(
            dpi = boundedDpi,
            pageWidthPixels = pointsToPixels(widthPoints, boundedDpi),
            pageHeightPixels = pointsToPixels(heightPoints, boundedDpi),
            marginPixels = pointsToPixels(marginPoints, boundedDpi),
        )
    }

    private fun pointsToPixels(points: Int, dpi: Int): Int =
        (points / POINTS_PER_INCH * dpi).roundToInt().coerceAtLeast(1)
}
