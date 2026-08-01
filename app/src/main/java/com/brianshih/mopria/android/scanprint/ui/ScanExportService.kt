package com.brianshih.mopria.android.scanprint.ui

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.SavedScanFile
import com.brianshih.mopria.android.scanprint.domain.ScanOutputFormat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScanExportService(private val context: Context) {
    suspend fun save(document: MopriaDocument, format: ScanOutputFormat): List<SavedScanFile> =
        withContext(Dispatchers.IO) {
            when (format) {
                ScanOutputFormat.Pdf -> listOf(savePdf(document))
                ScanOutputFormat.Jpeg -> document.pages.mapIndexed { index, page ->
                    saveJpeg(document, page, index)
                }
            }
        }

    suspend fun loadSavedDocuments(): List<MopriaDocument> = withContext(Dispatchers.IO) {
        val entries = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStoreEntries()
        } else {
            queryLegacyEntries()
        }
        entries.groupBy { baseName(it.displayName) }.map { (base, files) ->
            val pdf = files.firstOrNull { it.mimeType == "application/pdf" || it.displayName.endsWith(".pdf", true) }
            val jpegFiles = files.filter { it.mimeType == "image/jpeg" || it.displayName.endsWith(".jpg", true) }
            val pageCount = when {
                jpegFiles.isNotEmpty() -> jpegFiles.size
                pdf != null -> pdfPageCount(pdf.uri)
                else -> 1
            }
            MopriaDocument(
                id = "saved-${base.hashCode()}",
                name = base,
                sourceLabel = "Download/$PUBLIC_FOLDER",
                createdAt = files.maxOf { it.createdAt },
                pages = (1..pageCount).map { page ->
                    DocumentPage("$base-page-$page", page, "已儲存頁面 $page")
                },
                savedFiles = files.map { file ->
                    SavedScanFile(
                        format = if (file.mimeType == "application/pdf" || file.displayName.endsWith(".pdf", true)) {
                            ScanOutputFormat.Pdf
                        } else {
                            ScanOutputFormat.Jpeg
                        },
                        displayName = file.displayName,
                        location = "Download/$PUBLIC_FOLDER/${file.displayName}",
                    )
                },
                exportedPath = files.firstOrNull()?.let { "Download/$PUBLIC_FOLDER/${it.displayName}" },
            )
        }.sortedByDescending { it.createdAt }
    }

    private data class StoredEntry(
        val displayName: String,
        val mimeType: String,
        val createdAt: Long,
        val uri: Uri?,
    )

    private fun queryMediaStoreEntries(): List<StoredEntry> {
        val entries = mutableListOf<StoredEntry>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        val relativePath = "$PUBLIC_RELATIVE_PATH/"
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                entries += StoredEntry(
                    displayName = cursor.getString(nameColumn),
                    mimeType = cursor.getString(mimeColumn).orEmpty(),
                    createdAt = cursor.getLong(dateColumn) * 1000L,
                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                )
            }
        }
        return entries
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyEntries(): List<StoredEntry> {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PUBLIC_FOLDER,
        )
        return directory.listFiles()?.filter { it.isFile }?.map { file ->
            StoredEntry(
                displayName = file.name,
                mimeType = if (file.extension.lowercase(Locale.US) == "pdf") "application/pdf" else "image/jpeg",
                createdAt = file.lastModified(),
                uri = Uri.fromFile(file),
            )
        }.orEmpty()
    }

    private fun pdfPageCount(uri: Uri?): Int {
        if (uri == null) return 1
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            } ?: 1
        }.getOrDefault(1)
    }

    private fun baseName(displayName: String): String {
        return displayName
            .replace(Regex("-page-\\d+\\.jpg$", RegexOption.IGNORE_CASE), "")
            .removeSuffix(".pdf")
            .removeSuffix(".PDF")
    }

    private fun savePdf(document: MopriaDocument): SavedScanFile {
        val name = safeName(document.name) + ".pdf"
        return writePublicFile(name, "application/pdf") { output ->
            val pdf = PdfDocument()
            try {
                document.pages.forEachIndexed { index, page ->
                    val pageInfo = PdfDocument.PageInfo.Builder(612, 792, index + 1).create()
                    val pdfPage = pdf.startPage(pageInfo)
                    drawPage(pdfPage.canvas, document, page)
                    pdf.finishPage(pdfPage)
                }
                pdf.writeTo(output)
            } finally {
                pdf.close()
            }
        }
    }

    private fun saveJpeg(document: MopriaDocument, page: DocumentPage, index: Int): SavedScanFile {
        val name = "${safeName(document.name)}-page-${index + 1}.jpg"
        return writePublicFile(name, "image/jpeg") { output ->
            val bitmap = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
            try {
                drawPage(Canvas(bitmap), document, page)
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "JPEG 壓縮失敗" }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun writePublicFile(
        displayName: String,
        mimeType: String,
        write: (OutputStream) -> Unit,
    ): SavedScanFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, PUBLIC_RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = requireNotNull(context.contentResolver.insert(collection, values)) {
                "無法建立 $displayName"
            }
            try {
                context.contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "無法寫入 $displayName" }
                    write(output)
                }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                return SavedScanFile(
                    format = if (mimeType == "application/pdf") ScanOutputFormat.Pdf else ScanOutputFormat.Jpeg,
                    displayName = displayName,
                    location = "Download/$PUBLIC_FOLDER/$displayName",
                )
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PUBLIC_FOLDER,
        ).apply { mkdirs() }
        val file = File(directory, displayName)
        FileOutputStream(file).use(write)
        return SavedScanFile(
            format = if (mimeType == "application/pdf") ScanOutputFormat.Pdf else ScanOutputFormat.Jpeg,
            displayName = displayName,
            location = "Download/$PUBLIC_FOLDER/$displayName",
        )
    }

    private fun drawPage(canvas: Canvas, document: MopriaDocument, page: DocumentPage) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 54, 78)
            textSize = 26f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 16f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 195, 215)
            strokeWidth = 2f
        }
        canvas.drawColor(Color.WHITE)
        canvas.drawText("Mopria Scan & Print", 54f, 76f, titlePaint)
        canvas.drawText(document.name, 54f, 112f, bodyPaint)
        canvas.drawLine(54f, 140f, 558f, 140f, linePaint)
        canvas.drawText("${page.pageNumber}. ${page.title}", 54f, 190f, titlePaint)
        canvas.drawText("Saved by Mock Integration Mode.", 54f, 236f, bodyPaint)
        canvas.drawText("The real eSCL scan image will replace this fixture.", 54f, 264f, bodyPaint)
        canvas.drawText("Source: ${document.sourceLabel}", 54f, 690f, bodyPaint)
        canvas.drawText("Page ${page.pageNumber} of ${document.pages.size}", 54f, 728f, bodyPaint)
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fff._-]"), "-")
        .trim('-')
        .ifBlank { "mopria-scan" }

    private companion object {
        const val PUBLIC_FOLDER = "Mopria Scan & Print/Scans"
        const val PUBLIC_RELATIVE_PATH = "Download/$PUBLIC_FOLDER"
    }
}
