package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitOcrEngineInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun invalidSourceIsRejectedBeforeModelAccess() = runBlocking {
        val missing = File(context.cacheDir, "ml-kit-ocr-missing.jpg")
        missing.delete()

        assertEquals(
            OcrResult.Failed(OcrError.InvalidSource),
            MlKitOcrEngine(context).recognize(missing, OcrLanguagePack.English),
        )
    }
}
