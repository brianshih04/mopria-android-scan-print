package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hp.jipp.pdl.ColorSpace
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Android PdfRenderer path that turns a PDF into the two raster formats. */
@RunWith(AndroidJUnit4::class)
class IppRasterizerSmokeTest {

    @Test
    fun rendersPdfToPwgRasterAndPclm() {
        val cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val pdf = File.createTempFile("ipp-rasterizer", ".pdf", cacheDir)
        val pwg: File
        val pclm: File
        try {
            writeFixturePdf(pdf)
            pwg = IppRasterizer.rasterizeToPwgRaster(pdf, dpi = 72, colorSpace = ColorSpace.Rgb)
            pclm = IppRasterizer.rasterizeToPclm(pdf, dpi = 72, colorSpace = ColorSpace.Rgb, stripHeight = 16)

            val pwgBytes = pwg.readBytes()
            assertTrue("PWG-Raster should not be empty", pwgBytes.size > 4)
            assertArrayEquals("RaS2".toByteArray(Charsets.US_ASCII), pwgBytes.copyOfRange(0, 4))

            val pclmBytes = pclm.readBytes()
            assertTrue("PCLm should not be empty", pclmBytes.size > 4)
            assertArrayEquals("%PDF".toByteArray(Charsets.US_ASCII), pclmBytes.copyOfRange(0, 4))
        } finally {
            pdf.delete()
            File(pdf.parentFile, "${pdf.nameWithoutExtension}.pwg").delete()
            File(pdf.parentFile, "${pdf.nameWithoutExtension}.pclm").delete()
        }
    }

    private fun writeFixturePdf(file: File) {
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(72, 72, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawRect(8f, 8f, 64f, 64f, Paint().apply { color = Color.BLACK })
            pdf.finishPage(page)
            FileOutputStream(file).use { output -> pdf.writeTo(output) }
        } finally {
            pdf.close()
        }
    }
}
