package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentStoreInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun searchableDocumentRestoresCompressedOcrLayoutAfterProcessDeath() {
        val store = DocumentStore(context)
        store.clear()
        val source = File(context.filesDir, "scans/document-store-ocr.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val result = OcrResult.Applied(
            text = "中文 OCR",
            layout = OcrTextLayout(
                blocks = listOf(
                    OcrTextBlock(
                        text = "中文 OCR",
                        bounds = OcrBounds(10, 20, 180, 60),
                        cornerPoints = listOf(OcrPoint(10, 20), OcrPoint(180, 60)),
                        recognizedLanguage = "zh",
                        lines = listOf(
                            OcrTextLine(
                                text = "中文 OCR",
                                bounds = OcrBounds(10, 20, 180, 60),
                                confidence = 0.98f,
                                recognizedLanguage = "zh",
                                elements = listOf(
                                    OcrTextElement(
                                        text = "中文",
                                        bounds = OcrBounds(10, 20, 90, 60),
                                        symbols = listOf(
                                            OcrTextSymbol("中", OcrBounds(10, 20, 48, 60)),
                                            OcrTextSymbol("文", OcrBounds(50, 20, 90, 60)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                imageWidth = 300,
                imageHeight = 180,
            ),
        )
        val document = MopriaDocument(
            id = "document-store-ocr",
            name = "OCR restore",
            pages = listOf(
                DocumentPage(
                    id = "page/with unsafe filename characters",
                    pageNumber = 1,
                    title = "Page 1",
                    imagePath = source.absolutePath,
                    ocrResult = result,
                ),
            ),
            sourceLabel = "instrumented test",
            searchablePdf = true,
            documentSize = ScanDocumentSize.A4,
        )

        try {
            store.save(listOf(document), null)

            val restored = store.load()?.documents?.single()
            assertNotNull(restored)
            assertTrue(requireNotNull(restored).searchablePdf)
            assertEquals(ScanDocumentSize.A4, restored.documentSize)
            assertEquals(result, restored.pages.single().ocrResult)
            assertEquals(listOf(result), restored.ocrResults)
            assertEquals(1, File(context.filesDir, "ocr-layouts").listFiles()?.size)
        } finally {
            store.clear()
            source.delete()
        }
    }

    @Test
    fun missingSidecarDoesNotInventAnOcrResult() {
        val store = DocumentStore(context)
        store.clear()
        val source = File(context.filesDir, "scans/document-store-missing-layout.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val document = MopriaDocument(
            id = "missing-layout",
            name = "Missing layout",
            pages = listOf(
                DocumentPage(
                    id = "missing-layout-page",
                    pageNumber = 1,
                    title = "Page 1",
                    imagePath = source.absolutePath,
                    ocrResult = OcrResult.Applied("text", OcrTextLayout(imageWidth = 10, imageHeight = 10)),
                ),
            ),
            sourceLabel = "instrumented test",
            searchablePdf = true,
        )

        try {
            store.save(listOf(document), null)
            File(context.filesDir, "ocr-layouts").deleteRecursively()

            val restored = requireNotNull(store.load()).documents.single()
            assertTrue(restored.searchablePdf)
            assertNull(restored.pages.single().ocrResult)
        } finally {
            store.clear()
            source.delete()
        }
    }
}
