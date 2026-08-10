package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CropCoordinateMapperTest {
    private val source = CropRect(0.1f, 0.2f, 0.7f, 0.8f)

    @Test
    fun sourceCropMapsToEveryDisplayRotation() {
        assertCrop(source, CropCoordinateMapper.sourceToDisplay(source, 0))
        assertCrop(CropRect(0.2f, 0.1f, 0.8f, 0.7f), CropCoordinateMapper.sourceToDisplay(source, 90))
        assertCrop(CropRect(0.3f, 0.2f, 0.9f, 0.8f), CropCoordinateMapper.sourceToDisplay(source, 180))
        assertCrop(CropRect(0.2f, 0.3f, 0.8f, 0.9f), CropCoordinateMapper.sourceToDisplay(source, 270))
    }

    @Test
    fun displayRoundTripAlwaysReturnsSourceCoordinates() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val display = CropCoordinateMapper.sourceToDisplay(source, rotation)
            assertCrop(source, CropCoordinateMapper.displayToSource(display, rotation))
        }
    }

    private fun assertCrop(expected: CropRect, actual: CropRect) {
        assertEquals(expected.left, actual.left, 0.0001f)
        assertEquals(expected.top, actual.top, 0.0001f)
        assertEquals(expected.right, actual.right, 0.0001f)
        assertEquals(expected.bottom, actual.bottom, 0.0001f)
    }
}
