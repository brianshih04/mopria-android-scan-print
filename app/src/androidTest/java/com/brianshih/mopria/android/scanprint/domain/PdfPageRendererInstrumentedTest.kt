package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPageRendererInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun selectedA4OriginalProducesA4PdfMediaBox() {
        val output = File(context.cacheDir, "a4-page-size-test.pdf")
        val document = MopriaDocument(
            id = "a4",
            name = "A4",
            sourceLabel = "test",
            pages = listOf(DocumentPage("page", 1, "Page")),
            documentSize = ScanDocumentSize.A4,
        )
        try {
            FileOutputStream(output).use { stream ->
                PdfPageRenderer.writePdf(document, stream) { canvas, _, _, _ ->
                    canvas.drawColor(android.graphics.Color.WHITE)
                }
            }
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                    }
                }
            }
        } finally {
            output.delete()
        }
    }
}
