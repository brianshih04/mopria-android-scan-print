package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
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
import androidx.core.graphics.createBitmap
import com.brianshih.mopria.android.scanprint.R
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Converts user-selected PDF/JPEG/PNG content into the PDF stream expected by PrintManager. */
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

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var printItems: List<PrintItem> = emptyList()

    @Volatile
    private var pageWidth = DEFAULT_PAGE_WIDTH

    @Volatile
    private var pageHeight = DEFAULT_PAGE_HEIGHT

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

        workerScope.launch {
            try {
                updatePageSize(newAttributes)
                val inspectedItems = uris.map { uri ->
                    if (cancellationSignal.isCanceled) throw CancellationException("Print layout cancelled")
                    inspect(uri)
                }
                printItems = inspectedItems
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(title)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(inspectedItems.sumOf { it.pageCount })
                        .build(),
                    oldAttributes != newAttributes,
                )
            } catch (_: CancellationException) {
                callback.onLayoutCancelled()
            } catch (_: Exception) {
                callback.onLayoutFailed(context.getString(R.string.print_read_failed))
            }
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal.isCanceled) {
            runCatching { destination.close() }
            callback.onWriteCancelled()
            return
        }

        workerScope.launch {
            val pdf = PdfDocument()
            try {
                var sourcePageIndex = 0
                var outputPageNumber = 1
                printItems.forEach { item ->
                    if (item.isPdf) {
                        val descriptor = context.contentResolver.openFileDescriptor(item.uri, "r")
                            ?: throw FileNotFoundException("Could not open ${item.displayName}")
                        descriptor.use { fileDescriptor ->
                            PdfRenderer(fileDescriptor).use { renderer ->
                                repeat(renderer.pageCount) { pageIndex ->
                                    ensureNotCancelled(cancellationSignal)
                                    if (isPageRequested(sourcePageIndex, pages)) {
                                        val sourcePage = renderer.openPage(pageIndex)
                                        try {
                                            val bitmap = createPageBitmap(sourcePage)
                                            try {
                                                sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                                drawBitmapPage(pdf, bitmap, outputPageNumber)
                                            } finally {
                                                bitmap.recycle()
                                            }
                                        } finally {
                                            sourcePage.close()
                                        }
                                        outputPageNumber += 1
                                    }
                                    sourcePageIndex += 1
                                }
                            }
                        }
                    } else {
                        ensureNotCancelled(cancellationSignal)
                        if (isPageRequested(sourcePageIndex, pages)) {
                            val bitmap = BitmapLoader.load(
                                context,
                                item.uri,
                                requestedWidth = MAX_BITMAP_EDGE,
                                requestedHeight = MAX_BITMAP_EDGE,
                            ) ?: throw FileNotFoundException("Could not read ${item.displayName}")
                            try {
                                drawBitmapPage(pdf, bitmap, outputPageNumber)
                            } finally {
                                bitmap.recycle()
                            }
                            outputPageNumber += 1
                        }
                        sourcePageIndex += 1
                    }
                }
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output -> pdf.writeTo(output) }
                if (cancellationSignal.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (_: CancellationException) {
                runCatching { destination.close() }
                callback.onWriteCancelled()
            } catch (_: Exception) {
                runCatching { destination.close() }
                callback.onWriteFailed(context.getString(R.string.print_write_failed))
            } finally {
                pdf.close()
            }
        }
    }

    override fun onFinish() {
        workerScope.cancel()
        super.onFinish()
    }

    private fun inspect(uri: Uri): PrintItem {
        val displayName = queryDisplayName(uri)
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val isPdf = mimeType == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true)
        if (!isPdf) return PrintItem(uri, displayName, isPdf = false, pageCount = 1)

        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Could not open $displayName")
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                return PrintItem(uri, displayName, isPdf = true, pageCount = renderer.pageCount)
            }
        }
    }

    private fun createPageBitmap(page: PdfRenderer.Page): Bitmap {
        val scale = min(
            1f,
            min(MAX_BITMAP_EDGE / page.width.toFloat(), MAX_BITMAP_EDGE / page.height.toFloat()),
        )
        val width = max(1, (page.width * scale).roundToInt())
        val height = max(1, (page.height * scale).roundToInt())
        return createBitmap(width, height).apply {
            eraseColor(Color.WHITE)
        }
    }

    private fun drawBitmapPage(output: PdfDocument, bitmap: Bitmap, pageNumber: Int) {
        val width = pageWidth
        val height = pageHeight
        val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageNumber).create()
        val page = output.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        val margin = min(width, height) * 0.06f
        val scale = min(
            (width - margin * 2) / bitmap.width.toFloat(),
            (height - margin * 2) / bitmap.height.toFloat(),
        )
        val renderedWidth = bitmap.width * scale
        val renderedHeight = bitmap.height * scale
        val left = (width - renderedWidth) / 2f
        val top = (height - renderedHeight) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + renderedWidth, top + renderedHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        output.finishPage(page)
    }

    private fun updatePageSize(attributes: PrintAttributes) {
        attributes.mediaSize?.let { mediaSize ->
            pageWidth = max(1, (mediaSize.widthMils / 1000f * POINTS_PER_INCH).roundToInt())
            pageHeight = max(1, (mediaSize.heightMils / 1000f * POINTS_PER_INCH).roundToInt())
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: context.getString(R.string.print_selected_document_name)
    }

    private fun ensureNotCancelled(signal: CancellationSignal) {
        if (signal.isCanceled) throw CancellationException("Print cancelled")
    }

    private fun isPageRequested(pageIndex: Int, ranges: Array<out PageRange>): Boolean =
        ranges.any { pageIndex in it.start..it.end }

    private companion object {
        const val DEFAULT_PAGE_WIDTH = 595
        const val DEFAULT_PAGE_HEIGHT = 842
        const val POINTS_PER_INCH = 72f
        const val MAX_BITMAP_EDGE = 3072
    }
}
