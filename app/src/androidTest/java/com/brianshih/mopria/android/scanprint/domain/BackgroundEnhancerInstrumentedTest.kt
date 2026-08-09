package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
class BackgroundEnhancerInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun jpegPipelineWhitensBackgroundAndPreservesBlueAndRed() = runBlocking {
        val source = File(context.cacheDir, "opencv-color-fixture.jpg")
        try {
            writeColorFixture(source, Bitmap.CompressFormat.JPEG)

            assertEquals(
                EnhancementResult.Applied,
                BackgroundEnhancer.enhanceImageFile(source, EnhancementStrength.Normal),
            )

            val output = requireNotNull(BitmapFactory.decodeFile(source.absolutePath))
            try {
                assertEquals(320, output.width)
                assertEquals(240, output.height)
                val background = output.getPixel(20, 20)
                assertTrue(Color.red(background) >= 245)
                assertTrue(Color.green(background) >= 245)
                assertTrue(Color.blue(background) >= 245)

                val blue = output.getPixel(100, 120)
                assertTrue("blue signature lost: $blue", Color.blue(blue) > Color.red(blue) + 40)
                assertTrue("blue signature became white: $blue", Color.blue(blue) - Color.red(blue) > 20)

                val red = output.getPixel(220, 120)
                assertTrue("red stamp lost: $red", Color.red(red) > Color.blue(red) + 40)
                assertTrue("red stamp became white: $red", Color.red(red) - Color.blue(red) > 20)
            } finally {
                output.recycle()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun pngPipelinePreservesAlpha() = runBlocking {
        val source = File(context.cacheDir, "opencv-alpha-fixture.png")
        val bitmap = createBitmap(160, 120)
        bitmap.eraseColor(Color.argb(255, 205, 205, 205))
        bitmap.setPixel(10, 10, Color.argb(0, 60, 70, 80))
        bitmap.setPixel(80, 60, Color.argb(128, 200, 40, 40))
        writeBitmap(source, bitmap, Bitmap.CompressFormat.PNG)
        bitmap.recycle()

        try {
            assertEquals(
                EnhancementResult.Applied,
                BackgroundEnhancer.enhanceImageFile(source, EnhancementStrength.Normal),
            )

            val output = requireNotNull(BitmapFactory.decodeFile(source.absolutePath))
            try {
                assertEquals(0, Color.alpha(output.getPixel(10, 10)))
                assertTrue(Color.alpha(output.getPixel(80, 60)) in 126..130)
            } finally {
                output.recycle()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun allStrengthsProduceDistinctOpenCvOutputs() = runBlocking {
        val files = EnhancementStrength.entries.associateWith { strength ->
            File(context.cacheDir, "opencv-strength-${strength.name}.png").also { file ->
                writeGradientFixture(file)
            }
        }
        try {
            files.forEach { (strength, file) ->
                assertEquals(EnhancementResult.Applied, BackgroundEnhancer.enhanceImageFile(file, strength))
            }

            val lightHash = sha256(requireNotNull(files[EnhancementStrength.Light]))
            val normalHash = sha256(requireNotNull(files[EnhancementStrength.Normal]))
            val strongHash = sha256(requireNotNull(files[EnhancementStrength.Strong]))
            assertNotEquals(lightHash, normalHash)
            assertNotEquals(normalHash, strongHash)
            assertNotEquals(lightHash, strongHash)
        } finally {
            files.values.forEach(File::delete)
        }
    }

    private fun writeColorFixture(file: File, format: Bitmap.CompressFormat) {
        val bitmap = createBitmap(320, 240)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(205, 205, 205))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(25, 25, 25)
        paint.strokeWidth = 5f
        canvas.drawLine(30f, 45f, 290f, 45f, paint)
        paint.color = Color.rgb(35, 80, 210)
        canvas.drawRect(70f, 90f, 130f, 150f, paint)
        paint.color = Color.rgb(210, 55, 35)
        canvas.drawRect(190f, 90f, 250f, 150f, paint)
        writeBitmap(file, bitmap, format)
        bitmap.recycle()
    }

    private fun writeGradientFixture(file: File) {
        val bitmap = createBitmap(320, 120)
        for (x in 0 until bitmap.width) {
            val level = 150 + (x * 95 / (bitmap.width - 1))
            for (y in 0 until bitmap.height) bitmap.setPixel(x, y, Color.rgb(level, level, level))
        }
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.rgb(30, 80, 190) }
        canvas.drawRect(90f, 35f, 140f, 85f, paint)
        writeBitmap(file, bitmap, Bitmap.CompressFormat.PNG)
        bitmap.recycle()
    }

    private fun writeBitmap(file: File, bitmap: Bitmap, format: Bitmap.CompressFormat) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(format, 95, output))
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
