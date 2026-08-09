package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs

@RunWith(AndroidJUnit4::class)
class OpenCvStressInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun a4At300DpiTenPageSoakMeetsMemoryAndPerformanceBudget() = runBlocking {
        assertTrue(OpenCvRuntime.state() is OpenCvRuntime.State.Available)
        val source = File(context.cacheDir, "opencv-a4-300dpi-soak.jpg")
        createA4Fixture(source)
        forceGcAndIdle()

        val baselinePssKb = Debug.getPss()
        val baselineNativeHeapBytes = Debug.getNativeHeapAllocatedSize()
        val peakPssKb = AtomicLong(baselinePssKb)
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakPssKb.updateAndGet { previous -> maxOf(previous, Debug.getPss()) }
                delay(10)
            }
        }
        val durations = mutableListOf<Long>()
        val pageMemory = mutableListOf<String>()
        try {
            repeat(10) {
                val startedAt = SystemClock.elapsedRealtime()
                assertEquals(
                    EnhancementResult.Applied,
                    BackgroundEnhancer.enhanceImageFile(source, EnhancementStrength.Normal),
                )
                durations += SystemClock.elapsedRealtime() - startedAt
                pageMemory += memorySnapshot()
            }
        } finally {
            sampler.cancelAndJoin()
        }

        forceGcAndIdle()
        val finalPssKb = Debug.getPss()
        val finalNativeHeapBytes = Debug.getNativeHeapAllocatedSize()
        val peakDeltaMb = (peakPssKb.get() - baselinePssKb) / 1024.0
        val retainedDeltaMb = (finalPssKb - baselinePssKb) / 1024.0
        val sorted = durations.sorted()
        val medianMs = sorted[sorted.size / 2]
        val p95Ms = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceIn(sorted.indices)]
        val returnRatio = finalPssKb.toDouble() / baselinePssKb.coerceAtLeast(1)
        val metrics = "baseline=${baselinePssKb / 1024.0}MB, peak=${peakPssKb.get() / 1024.0}MB, " +
            "delta=${peakDeltaMb}MB, final=${finalPssKb / 1024.0}MB, ratio=$returnRatio, " +
            "nativeHeap=${baselineNativeHeapBytes / 1024.0 / 1024.0}MB->${finalNativeHeapBytes / 1024.0 / 1024.0}MB, " +
            "median=${medianMs}ms, p95=${p95Ms}ms, " +
            "pages=$pageMemory"

        source.delete()
        assertTrue(metrics, peakDeltaMb <= MAX_PEAK_DELTA_MB)
        assertTrue("retained PSS delta was %.3f MB; $metrics", retainedDeltaMb <= MAX_RETAINED_DELTA_MB)
        assertTrue("median was ${medianMs}ms", medianMs <= 1_000)
        assertTrue("p95 was ${p95Ms}ms", p95Ms <= 1_500)
    }

    @Test
    fun rawMatReleaseDoesNotAccumulateOnePagePerIteration() = runBlocking {
        assertTrue(OpenCvRuntime.state() is OpenCvRuntime.State.Available)
        forceGcAndIdle()
        val baseline = Debug.getPss()
        repeat(10) {
            val mat = Mat(A4_HEIGHT_300_DPI, A4_WIDTH_300_DPI, CvType.CV_8UC3)
            mat.setTo(Scalar(255.0, 255.0, 255.0))
            mat.release()
        }
        forceGcAndIdle()
        val final = Debug.getPss()
        val ratio = final.toDouble() / baseline.coerceAtLeast(1)
        assertTrue("raw Mat PSS ratio was %.3f (%d -> %d KB)".format(ratio, baseline, final), ratio <= 1.15)
    }

    @Test
    fun codecMatReleaseDoesNotAccumulateOnePagePerIteration() = runBlocking {
        assertTrue(OpenCvRuntime.state() is OpenCvRuntime.State.Available)
        val source = File(context.cacheDir, "opencv-codec-soak.jpg")
        val destination = File(context.cacheDir, "opencv-codec-soak-output.jpg")
        createA4Fixture(source)
        forceGcAndIdle()
        val baseline = Debug.getPss()
        repeat(10) {
            val mat = Imgcodecs.imread(source.absolutePath, Imgcodecs.IMREAD_UNCHANGED)
            check(!mat.empty())
            val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92)
            try {
                check(Imgcodecs.imwrite(destination.absolutePath, mat, parameters))
            } finally {
                parameters.release()
                mat.release()
                destination.delete()
            }
        }
        forceGcAndIdle()
        val final = Debug.getPss()
        val ratio = final.toDouble() / baseline.coerceAtLeast(1)
        source.delete()
        assertTrue("codec Mat PSS ratio was %.3f (%d -> %d KB)".format(ratio, baseline, final), ratio <= 1.15)
    }

    @Test
    fun enhancementMatReleaseDoesNotAccumulateOnePagePerIteration() = runBlocking {
        assertTrue(OpenCvRuntime.state() is OpenCvRuntime.State.Available)
        val source = File(context.cacheDir, "opencv-mat-soak.jpg")
        createA4Fixture(source)
        forceGcAndIdle()
        val baseline = Debug.getPss()
        repeat(10) {
            val scope = MatScope()
            try {
                val input = scope.own(Imgcodecs.imread(source.absolutePath, Imgcodecs.IMREAD_UNCHANGED))
                check(!input.empty())
                BackgroundEnhancer.enhanceMatForTesting(input, EnhancementStrength.Normal, scope)
            } finally {
                scope.close()
            }
        }
        forceGcAndIdle()
        val final = Debug.getPss()
        source.delete()
        val retainedDeltaMb = (final - baseline) / 1024.0
        assertTrue(
            "enhancement Mat retained PSS delta was %.3f MB (%d -> %d KB)".format(retainedDeltaMb, baseline, final),
            retainedDeltaMb <= MAX_RETAINED_DELTA_MB,
        )
    }

    private fun createA4Fixture(file: File) {
        val bitmap = createBitmap(A4_WIDTH_300_DPI, A4_HEIGHT_300_DPI)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(205, 205, 205))
        val textPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            strokeWidth = 7f
        }
        for (y in 180 until bitmap.height - 180 step 130) {
            canvas.drawLine(160f, y.toFloat(), (bitmap.width - 160).toFloat(), y.toFloat(), textPaint)
        }
        textPaint.color = Color.rgb(35, 80, 210)
        canvas.drawRect(250f, 500f, 700f, 850f, textPaint)
        textPaint.color = Color.rgb(210, 55, 35)
        canvas.drawRect(1_600f, 2_400f, 2_050f, 2_750f, textPaint)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
        }
        bitmap.recycle()
    }

    private suspend fun forceGcAndIdle() {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            delay(350)
        }
    }

    private fun memorySnapshot(): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val stats = info.memoryStats
            .filterKeys { it.startsWith("summary.") }
            .toSortedMap()
            .entries
            .joinToString(",") { (key, value) -> "$key=$value" }
        return "${info.totalPss}/${info.dalvikPss}/${info.nativePss}/${info.otherPss}[$stats]"
    }

    private companion object {
        const val MAX_PEAK_DELTA_MB = 256.0
        const val MAX_RETAINED_DELTA_MB = 64.0
        const val A4_WIDTH_300_DPI = 2_480
        const val A4_HEIGHT_300_DPI = 3_508
    }
}
