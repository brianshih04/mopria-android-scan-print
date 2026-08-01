package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import kotlin.math.min

/** Converts user-selected PDF/JPEG content into the PDF stream expected by PrintManager. */
class UriPrintAdapter(
    private val context: Context,
    private val uris: List<Uri>,
    private val title: String,
) : PrintDocumentAdapter() {
    private data class PrintItem(
        val uri: Uri,
        val displayName: String,
        val isPdf: Boolean,
        val pageCount: Int,
    )

    private var printItems: List<PrintItem> = emptyList()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }

        try {
            printItems = uris.map { inspect(it) }
            val pageCount = printItems.sumOf { it.pageCount }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder(title)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build(),
                true,
            )
        } catch (error: Exception) {
            callback.onLayoutFailed(error.message ?: "無法讀取選取的文件")
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onWriteCancelled()
            return
        }

        val pdf = PdfDocument()
        var outputPage = 1
        try {
            printItems.forEach { item ->
                if (cancellationSignal.isCanceled) return@forEach
                if (item.isPdf) {
                    outputPage = renderPdf(item, pdf, outputPage, cancellationSignal)
                } else {
                    outputPage = renderImage(item, pdf, outputPage)
                }
            }
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output -> pdf.writeTo(output) }
            if (cancellationSignal.isCanceled) callback.onWriteCancelled()
            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (error: Exception) {
            callback.onWriteFailed(error.message)
        } finally {
            pdf.close()
        }
    }

    private fun inspect(uri: Uri): PrintItem {
        val displayName = queryDisplayName(uri)
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val isPdf = mimeType == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true)
        if (!isPdf) return PrintItem(uri, displayName, isPdf = false, pageCount = 1)

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("無法開啟 $displayName")
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                return PrintItem(uri, displayName, isPdf = true, pageCount = renderer.pageCount)
            }
        }
    }

    private fun renderPdf(
        item: PrintItem,
        output: PdfDocument,
        firstPage: Int,
        cancellationSignal: CancellationSignal,
    ): Int {
        val descriptor = context.contentResolver.openFileDescriptor(item.uri, "r")
            ?: throw FileNotFoundException("無法開啟 ${item.displayName}")
        var nextPage = firstPage
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                repeat(renderer.pageCount) { pageIndex ->
                    if (cancellationSignal.isCanceled) return nextPage
                    val sourcePage = renderer.openPage(pageIndex)
                    val bitmap = Bitmap.createBitmap(sourcePage.width, sourcePage.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    drawBitmapPage(output, bitmap, nextPage)
                    bitmap.recycle()
                    sourcePage.close()
                    nextPage += 1
                }
            }
        }
        return nextPage
    }

    private fun renderImage(item: PrintItem, output: PdfDocument, pageNumber: Int): Int {
        val bitmap = context.contentResolver.openInputStream(item.uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw FileNotFoundException("無法讀取 ${item.displayName}")
        drawBitmapPage(output, bitmap, pageNumber)
        bitmap.recycle()
        return pageNumber + 1
    }

    private fun drawBitmapPage(output: PdfDocument, bitmap: Bitmap, pageNumber: Int) {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        val page = output.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        val margin = 36f
        val scale = min(
            (PAGE_WIDTH - margin * 2) / bitmap.width.toFloat(),
            (PAGE_HEIGHT - margin * 2) / bitmap.height.toFloat(),
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (PAGE_WIDTH - width) / 2f
        val top = (PAGE_HEIGHT - height) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        output.finishPage(page)
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "選取文件"
    }

    private companion object {
        const val PAGE_WIDTH = 612
        const val PAGE_HEIGHT = 792
    }
}
