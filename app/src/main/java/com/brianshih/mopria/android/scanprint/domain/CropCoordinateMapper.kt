package com.brianshih.mopria.android.scanprint.domain

/**
 * Converts normalized crop rectangles between the unrotated source page and the bitmap displayed
 * after a 90-degree page rotation. [DocumentPage.cropRect] is always stored in source space.
 */
object CropCoordinateMapper {
    fun sourceToDisplay(crop: CropRect, rotationDegrees: Int): CropRect = when (normalize(rotationDegrees)) {
        0 -> crop
        90 -> CropRect(
            left = 1f - crop.bottom,
            top = crop.left,
            right = 1f - crop.top,
            bottom = crop.right,
        )
        180 -> CropRect(
            left = 1f - crop.right,
            top = 1f - crop.bottom,
            right = 1f - crop.left,
            bottom = 1f - crop.top,
        )
        else -> CropRect(
            left = crop.top,
            top = 1f - crop.right,
            right = crop.bottom,
            bottom = 1f - crop.left,
        )
    }

    fun displayToSource(crop: CropRect, rotationDegrees: Int): CropRect =
        sourceToDisplay(crop, (360 - normalize(rotationDegrees)) % 360)

    private fun normalize(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized in setOf(0, 90, 180, 270)) { "Rotation must be a multiple of 90 degrees" }
        return normalized
    }
}
