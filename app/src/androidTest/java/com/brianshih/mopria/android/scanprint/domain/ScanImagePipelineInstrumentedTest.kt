package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanImagePipelineInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun blankPageIsDroppedWithoutChangingSource() = runBlocking {
        val source = File(context.cacheDir, "scan-pipeline-blank.jpg")
        writeBitmap(source, createBitmap(800, 600).also { it.eraseColor(Color.WHITE) })
        val before = sha256(source)

        val result = ScanImagePipeline.process(source, ScanSettings(dropBlankPages = true))

        assertEquals(ScanImageResult.DroppedBlankPage, result)
        assertTrue(before.contentEquals(sha256(source)))
        source.delete()
        Unit
    }

    @Test
    fun autoCropReplacesImageWithDetectedPageBounds() = runBlocking {
        val source = File(context.cacheDir, "scan-pipeline-crop.jpg")
        val bitmap = createBitmap(800, 600)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(35f, 35f, 765f, 565f, Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 18f
                color = Color.DKGRAY
            })
        }
        writeBitmap(source, bitmap)

        val result = ScanImagePipeline.process(source, ScanSettings(autoCrop = true))
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)

        assertEquals(ScanImageResult.Applied, result)
        assertTrue(bounds.outWidth < 800)
        assertTrue(bounds.outHeight < 600)
        source.delete()
        Unit
    }

    @Test
    fun deskewKeepsImageDimensionsAndProducesValidOutput() = runBlocking {
        val source = File(context.cacheDir, "scan-pipeline-deskew.jpg")
        val bitmap = createBitmap(800, 600)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            save()
            rotate(3f, 400f, 300f)
            val paint = Paint().apply {
                color = Color.BLACK
                strokeWidth = 8f
            }
            for (y in 120 until 520 step 70) drawLine(80f, y.toFloat(), 720f, y.toFloat(), paint)
            restore()
        }
        writeBitmap(source, bitmap)

        val result = ScanImagePipeline.process(source, ScanSettings(deskew = true))
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)

        assertNotEquals(ScanImageResult.Failed(ScanImageError.DecodeFailed), result)
        assertTrue(bounds.outWidth == 800 && bounds.outHeight == 600)
        source.delete()
        Unit
    }

    private fun writeBitmap(file: File, bitmap: android.graphics.Bitmap) {
        FileOutputStream(file).use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) }
        bitmap.recycle()
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
