package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a bounded preview bitmap without decoding an original scan at full resolution. */
internal object DocumentPreviewLoader {
    suspend fun load(
        context: Context,
        page: DocumentPage,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        page.imagePath?.let { path ->
            BitmapLoader.load(context, path, requestedWidth, requestedHeight)?.let { return@withContext it }
        }
        renderPdfPage(context, page, requestedWidth, requestedHeight)
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
            context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return@use null
                    renderer.openPage(pageIndex).use { pdfPage ->
                        val scale = minOf(
                            requestedWidth.toFloat() / pdfPage.width,
                            requestedHeight.toFloat() / pdfPage.height,
                        ).coerceAtMost(1f)
                        val width = max(1, (pdfPage.width * scale).toInt())
                        val height = max(1, (pdfPage.height * scale).toInt())
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }.getOrNull()
    }
}
