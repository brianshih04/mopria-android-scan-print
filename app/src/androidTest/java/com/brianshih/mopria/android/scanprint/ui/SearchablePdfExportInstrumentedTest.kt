package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.OcrResult
import com.brianshih.mopria.android.scanprint.domain.OcrTextLayout
import com.brianshih.mopria.android.scanprint.domain.OcrTextLine
import com.brianshih.mopria.android.scanprint.domain.OcrTextBlock
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

@RunWith(AndroidJUnit4::class)
class SearchablePdfExportInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun explicitOptInProducesExtractableInvisibleTextLayer() = runBlocking {
        val imageFile = File(context.cacheDir, "searchable-export-test.jpg")
        val bitmap = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = 28f
            }
            canvas.drawText("OCR TEST", 24f, 52f, paint)
            canvas.drawText("中文", 24f, 102f, paint)
            FileOutputStream(imageFile).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
        } finally {
            bitmap.recycle()
        }

        val page = DocumentPage(
            id = "searchable-test-page",
            pageNumber = 1,
            title = "Searchable test",
            imagePath = imageFile.absolutePath,
            ocrResult = OcrResult.Applied(
                text = "OCR TEST\n中文",
                layout = OcrTextLayout(
                    blocks = listOf(
                        OcrTextBlock(
                            text = "OCR TEST\n中文",
                            bounds = null,
                            lines = listOf(
                                OcrTextLine(
                                    "OCR TEST",
                                    com.brianshih.mopria.android.scanprint.domain.OcrBounds(24, 24, 180, 58),
                                    recognizedLanguage = "en",
                                ),
                                OcrTextLine(
                                    "中文",
                                    com.brianshih.mopria.android.scanprint.domain.OcrBounds(24, 74, 150, 108),
                                    recognizedLanguage = "zh",
                                ),
                            ),
                        ),
                    ),
                    imageWidth = 300,
                    imageHeight = 180,
                ),
            ),
        )
        val document = MopriaDocument(
            id = "searchable-test-document",
            name = "Searchable export test",
            pages = listOf(page),
            sourceLabel = "instrumented test",
            searchablePdf = true,
        )

        try {
            val uri = ScanExportService(context).createSharePdf(document)
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
            assertTrue(bytes.size > 0)
            assertTrue(String(bytes, StandardCharsets.ISO_8859_1).contains("/ToUnicode"))

            PDFBoxResourceLoader.init(context)
            PDDocument.load(bytes.inputStream()).use { pdf ->
                val extracted = PDFTextStripper().getText(pdf)
                assertTrue("Extracted text: $extracted", extracted.contains("OCR TEST"))
                assertTrue("Extracted text: $extracted", extracted.contains("中文"))
            }
        } finally {
            imageFile.delete()
            File(context.cacheDir, "shared-scans").listFiles()?.forEach(File::delete)
        }
    }

    @Test
    fun explicitOptInWithoutOcrLayoutFailsInsteadOfWritingAPlainPdf() = runBlocking {
        val source = File(context.cacheDir, "searchable-export-missing-ocr.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val document = MopriaDocument(
            id = "missing-ocr-document",
            name = "Missing OCR",
            pages = listOf(
                DocumentPage(
                    id = "missing-ocr-page",
                    pageNumber = 1,
                    title = "Page 1",
                    imagePath = source.absolutePath,
                ),
            ),
            sourceLabel = "instrumented test",
            searchablePdf = true,
        )

        try {
            try {
                ScanExportService(context).createSharePdf(document)
                fail("Searchable PDF export should fail when the OCR layout is missing")
            } catch (error: SearchablePdfExportException) {
                assertTrue(error.failure == SearchablePdfExportFailure.MissingOcrLayout)
            }
            assertTrue(File(context.cacheDir, "shared-scans/Missing OCR.pdf").let { !it.exists() })
        } finally {
            source.delete()
        }
    }

    @Test
    fun maximumFiftyPageSearchableExportStaysWithinAbsoluteMemoryBudget() = runBlocking {
        val imageFile = File(context.cacheDir, "searchable-export-50-page.jpg")
        val bitmap = Bitmap.createBitmap(1_024, 1_400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = 42f
            }
            canvas.drawText("Searchable page", 64f, 120f, paint)
            FileOutputStream(imageFile).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        val layout = OcrTextLayout(
            blocks = listOf(
                OcrTextBlock(
                    text = "Searchable page",
                    bounds = null,
                    lines = listOf(
                        OcrTextLine(
                            text = "Searchable page",
                            bounds = com.brianshih.mopria.android.scanprint.domain.OcrBounds(64, 70, 420, 130),
                            recognizedLanguage = "en",
                        ),
                    ),
                ),
            ),
            imageWidth = 1_024,
            imageHeight = 1_400,
        )
        val document = MopriaDocument(
            id = "searchable-50-page-document",
            name = "Searchable 50 page memory",
            pages = (1..50).map { pageNumber ->
                DocumentPage(
                    id = "searchable-50-page-$pageNumber",
                    pageNumber = pageNumber,
                    title = "Page $pageNumber",
                    imagePath = imageFile.absolutePath,
                    ocrResult = OcrResult.Applied("Searchable page", layout),
                )
            },
            sourceLabel = "instrumented test",
            searchablePdf = true,
        )

        forceGcAndIdle()
        val peakPssKb = AtomicLong(Debug.getPss())
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakPssKb.updateAndGet { previous -> maxOf(previous, Debug.getPss()) }
                delay(20)
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val uri = ScanExportService(context).createSharePdf(document)
            sampler.cancelAndJoin()
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { pdf ->
                    assertTrue(pdf.numberOfPages == 50)
                }
            }
            val peakMb = peakPssKb.get() / 1024.0
            assertTrue(
                "50-page searchable PDF peak PSS was %.2f MB in %d ms".format(
                    peakMb,
                    SystemClock.elapsedRealtime() - startedAt,
                ),
                peakMb <= 256.0,
            )
        } finally {
            if (sampler.isActive) sampler.cancelAndJoin()
            imageFile.delete()
            File(context.cacheDir, "shared-scans").listFiles()?.forEach(File::delete)
        }
    }

    private suspend fun forceGcAndIdle() {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            delay(350)
        }
    }
}
