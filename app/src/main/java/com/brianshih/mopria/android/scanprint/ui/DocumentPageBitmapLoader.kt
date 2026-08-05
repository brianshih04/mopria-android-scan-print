package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import java.io.File
import kotlin.math.max

/** Shared bounded decoder for image-backed and PDF-backed scan pages. */
internal object DocumentPageBitmapLoader {
    fun load(context: Context, page: DocumentPage, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        page.imagePath?.let { path ->
            BitmapLoader.load(context, path, requestedWidth, requestedHeight)?.let { return it }
        }
        return renderPdfPage(context, page, requestedWidth, requestedHeight)
    }

    private fun renderPdfPage(
        context: Context,
        page: DocumentPage,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Bitmap? {
        val path = page.pdfPath ?: return null
        val pageIndex = page.pdfPageIndex ?: return null
        return runCatching {
            openDescriptor(context, path)?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(pageIndex in 0 until renderer.pageCount) { "PDF page index 超出範圍" }
                    renderer.openPage(pageIndex).use { pdfPage ->
                        val scale = minOf(
                            requestedWidth.toFloat() / pdfPage.width,
                            requestedHeight.toFloat() / pdfPage.height,
                        ).coerceAtLeast(0.01f)
                        val width = max(1, (pdfPage.width * scale).toInt())
                        val height = max(1, (pdfPage.height * scale).toInt())
                        createBitmap(width, height).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }.getOrNull()
    }

    private fun openDescriptor(context: Context, path: String): ParcelFileDescriptor? = when {
        path.startsWith("content://") -> context.contentResolver.openFileDescriptor(path.toUri(), "r")
        path.startsWith("file://") -> path.toUri().path?.let { ParcelFileDescriptor.open(File(it), ParcelFileDescriptor.MODE_READ_ONLY) }
        else -> ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
