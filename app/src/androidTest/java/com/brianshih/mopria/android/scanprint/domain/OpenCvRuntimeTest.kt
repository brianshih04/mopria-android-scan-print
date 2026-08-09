package com.brianshih.mopria.android.scanprint.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

@RunWith(AndroidJUnit4::class)
class OpenCvRuntimeTest {
    @Test
    fun initializesAndRunsSmallMatOperation() {
        assertTrue(OpenCvRuntime.state() is OpenCvRuntime.State.Available)

        val source = Mat.ones(2, 2, CvType.CV_8UC1)
        val multiplier = Mat.ones(2, 2, CvType.CV_8UC1)
        val result = Mat()
        try {
            Core.multiply(source, multiplier, result)
            assertEquals(1.0, result.get(0, 0)[0], 0.0)
        } finally {
            source.release()
            multiplier.release()
            result.release()
        }
    }
}
