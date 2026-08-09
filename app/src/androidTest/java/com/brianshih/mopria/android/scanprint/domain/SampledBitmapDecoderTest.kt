package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampledBitmapDecoderTest {

    @Test
    fun decodesImageWithinExactRequestedBounds() {
        val cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val sourceFile = File.createTempFile("sampled-bitmap", ".png", cacheDir)
        val source = createBitmap(1200, 1600)
        source.eraseColor(Color.BLUE)
        try {
            FileOutputStream(sourceFile).use { output ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            }

            val decoded = SampledBitmapDecoder.decodeFile(sourceFile.absolutePath, 600, 800)
            assertNotNull(decoded)
            val bitmap = requireNotNull(decoded)
            try {
                assertEquals(600, bitmap.width)
                assertEquals(800, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        } finally {
            source.recycle()
            sourceFile.delete()
        }
    }
}
