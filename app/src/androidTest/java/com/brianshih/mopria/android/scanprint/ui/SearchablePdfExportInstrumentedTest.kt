package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
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
}
