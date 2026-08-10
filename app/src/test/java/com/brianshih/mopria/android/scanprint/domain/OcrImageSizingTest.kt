package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrImageSizingTest {
    @Test
    fun imageWithinBudgetDoesNotDownsample() {
        assertEquals(1, OcrImageSizing.sampleSize(width = 3_000, height = 4_000))
    }

    @Test
    fun largeA3PageUsesPowerOfTwoSampling() {
        val sampleSize = OcrImageSizing.sampleSize(width = 7_000, height = 9_900)

        assertEquals(4, sampleSize)
        assertTrue(OcrImageSizing.withinLimits(7_000, 9_900, requireNotNull(sampleSize)))
    }

    @Test
    fun veryLongAdfPageIsBoundedByLongEdge() {
        assertEquals(32, OcrImageSizing.sampleSize(width = 1_000, height = 100_000))
    }

    @Test
    fun invalidBoundsAreRejected() {
        assertNull(OcrImageSizing.sampleSize(width = -1, height = -1))
        assertNull(OcrImageSizing.sampleSize(width = 0, height = 100))
    }
}
