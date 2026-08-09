package com.brianshih.mopria.android.scanprint.ui

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.OcrLayoutTransforms
import com.brianshih.mopria.android.scanprint.domain.OcrDownloadableFontPack
import com.brianshih.mopria.android.scanprint.domain.OcrResult
import com.brianshih.mopria.android.scanprint.domain.OcrTextLayout
import com.brianshih.mopria.android.scanprint.domain.PdfBoxSearchableFont
import com.brianshih.mopria.android.scanprint.domain.PdfBoxSearchableFontRole
import com.brianshih.mopria.android.scanprint.domain.PdfBoxSearchablePage
import com.brianshih.mopria.android.scanprint.domain.PdfBoxSearchablePdfWriter
import com.brianshih.mopria.android.scanprint.domain.SavedScanFile
import com.brianshih.mopria.android.scanprint.domain.PdfPageRenderer
import com.brianshih.mopria.android.scanprint.domain.ScanOutputFormat
import com.brianshih.mopria.android.scanprint.domain.hasPositionedText
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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

    suspend fun createSharePdf(document: MopriaDocument): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
        directory.listFiles()
            ?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > SHARE_MAX_AGE_MS }
            ?.forEach { it.delete() }
        val file = File(directory, safeName(document.name) + ".pdf")
        FileOutputStream(file).use { output -> writePdf(document, output) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
                    DocumentPage(
                        id = "$base-page-$page",
                        pageNumber = page,
                        title = LanguageManager.wrap(context).getString(R.string.document_saved_page, page),
                        imagePath = jpegFiles.getOrNull(page - 1)?.uri?.toString(),
                        pdfPath = pdf?.uri?.toString(),
                        pdfPageIndex = pdf?.let { page - 1 },
                    )
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

    @RequiresApi(Build.VERSION_CODES.Q)
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
            .replace(Regex(" \\(\\d+\\)(?=\\.[^.]+$)"), "")
            .replace(Regex("-page-\\d+\\.jpg$", RegexOption.IGNORE_CASE), "")
            .removeSuffix(".pdf")
            .removeSuffix(".PDF")
    }

    private fun savePdf(document: MopriaDocument): SavedScanFile {
        val name = safeName(document.name) + ".pdf"
        return writePublicFile(name, "application/pdf") { output ->
            writePdf(document, output)
        }
    }

    private fun writePdf(document: MopriaDocument, output: OutputStream) {
        if (document.searchablePdf && document.pages.any { it.ocrResult.hasPositionedText() }) {
            val requiredFonts = requiredOptionalFonts(document)
            val optionalFonts = requiredFonts.mapNotNull { pack ->
                pack.availableFile(context)?.let { fontFile -> pack to fontFile }
            }
            if (optionalFonts.size == requiredFonts.size) {
                writeSearchablePdf(document, optionalFonts, output)
                return
            }
        }
        writeStandardPdf(document, output)
    }

    private fun writeStandardPdf(document: MopriaDocument, output: OutputStream) {
        PdfPageRenderer.writePdf(document, output) { canvas, doc, page, _ ->
            drawPage(canvas, doc, page)
        }
    }

    private fun writeSearchablePdf(
        document: MopriaDocument,
        optionalFonts: List<Pair<OcrDownloadableFontPack, File>>,
        output: OutputStream,
    ) {
        val staging = File(context.cacheDir, "searchable-pdf-${System.nanoTime()}").apply { mkdirs() }
        try {
            val defaultFont = File(staging, SEARCHABLE_FONT_ASSET.fileName).also { destination ->
                context.assets.open(SEARCHABLE_FONT_ASSET.assetPath).use { input ->
                        FileOutputStream(destination).use { fileOutput -> input.copyTo(fileOutput) }
                }
            }
            val fontFiles = buildList {
                add(PdfBoxSearchableFont(defaultFont, SEARCHABLE_FONT_ASSET.role))
                optionalFonts.forEach { (pack, fontFile) ->
                    add(PdfBoxSearchableFont(fontFile, pack.role))
                }
            }

            val pages = document.pages.mapIndexed { index, page ->
                val bitmap = requireNotNull(loadPageBitmap(page)) {
                    "Could not load page ${page.pageNumber} for searchable PDF"
                }
                val imageFile = File(staging, "page-${index + 1}.jpg")
                try {
                    FileOutputStream(imageFile).use { imageOutput ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, imageOutput)) {
                            "Could not encode page ${page.pageNumber} for searchable PDF"
                        }
                    }
                    val layout = (page.ocrResult as? OcrResult.Applied)?.layout
                        ?.let { ocrLayout ->
                            OcrLayoutTransforms.forPage(
                                layout = ocrLayout,
                                page = page,
                                outputWidth = bitmap.width,
                                outputHeight = bitmap.height,
                            )
                        }
                        ?: OcrTextLayout(imageWidth = bitmap.width, imageHeight = bitmap.height)
                    PdfBoxSearchablePage(imageFile = imageFile, ocrLayout = layout)
                } finally {
                    bitmap.recycle()
                }
            }

            PdfBoxSearchablePdfWriter.write(
                context = context,
                pages = pages,
                fontFiles = fontFiles,
                output = output,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun requiredOptionalFonts(document: MopriaDocument): Set<OcrDownloadableFontPack> =
        document.pages.asSequence()
            .mapNotNull { page -> (page.ocrResult as? OcrResult.Applied)?.layout }
            .flatMap { layout -> layout.blocks.asSequence() }
            .flatMap { block -> block.lines.asSequence() }
            .filter { line ->
                line.text.isNotBlank() &&
                    line.bounds?.let { bounds -> bounds.width > 0 && bounds.height > 0 } == true
            }
            .map { line -> PdfBoxSearchableFontRole.forText(line.recognizedLanguage, line.text) }
            .mapNotNull { role -> OcrDownloadableFontPack.entries.firstOrNull { it.role == role } }
            .toSet()

    private fun saveJpeg(document: MopriaDocument, page: DocumentPage, index: Int): SavedScanFile {
        val name = "${safeName(document.name)}-page-${index + 1}.jpg"
        return writePublicFile(name, "image/jpeg") { output ->
            if (copyOriginalJpeg(page.imagePath, output)) return@writePublicFile
            val bitmap = loadPageBitmap(page)
            try {
                if (bitmap != null) {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "JPEG compression failed" }
                } else {
                    val fixture = createBitmap(612, 792)
                    try {
                        drawPage(Canvas(fixture), document, page)
                        check(fixture.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "JPEG compression failed" }
                    } finally {
                        fixture.recycle()
                    }
                }
            } finally {
                bitmap?.recycle()
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
                "Could not create $displayName"
            }
            try {
                context.contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Could not write $displayName" }
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
        val bitmap = loadPageBitmap(page)
        if (bitmap != null) {
            val margin = 54f
            val top = 158f
            val bottom = 640f
            PdfPageRenderer.drawFittedInArea(canvas, bitmap, margin, top, bottom)
            canvas.drawText("${page.pageNumber}. ${page.title}", margin, 684f, titlePaint)
            bitmap.recycle()
        } else {
            canvas.drawText("${page.pageNumber}. ${page.title}", 54f, 190f, titlePaint)
            canvas.drawText("Saved by Mock Integration Mode.", 54f, 236f, bodyPaint)
            canvas.drawText("The real eSCL scan image will replace this fixture.", 54f, 264f, bodyPaint)
        }
        canvas.drawText("Source: ${document.sourceLabel}", 54f, 690f, bodyPaint)
        canvas.drawText("Page ${page.pageNumber} of ${document.pages.size}", 54f, 728f, bodyPaint)
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fff._-]"), "-")
        .trim('-')
        .ifBlank { "mopria-scan" }

    private fun loadBitmap(path: String?): Bitmap? {
        return BitmapLoader.load(context, path, requestedWidth = 2048, requestedHeight = 2048)
    }

    private fun loadPageBitmap(page: DocumentPage): Bitmap? =
        DocumentPageBitmapLoader.load(context, page, requestedWidth = 2048, requestedHeight = 2048)

    private fun copyOriginalJpeg(path: String?, output: OutputStream): Boolean {
        if (path.isNullOrBlank()) return false
        val uri = path.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        val isJpeg = if (uri != null) {
            context.contentResolver.getType(uri).equals("image/jpeg", ignoreCase = true)
        } else {
            val filePath = if (path.startsWith("file://")) path.toUri().path else path
            filePath?.endsWith(".jpg", ignoreCase = true) == true ||
                filePath?.endsWith(".jpeg", ignoreCase = true) == true
        }
        if (!isJpeg) return false

        val input: InputStream = if (uri != null) {
            context.contentResolver.openInputStream(uri)
        } else {
            val filePath = if (path.startsWith("file://")) path.toUri().path else path
            filePath?.let { File(it).inputStream() }
        } ?: return false
        input.use { it.copyTo(output) }
        return true
    }

    private data class SearchableFontAsset(
        val assetPath: String,
        val fileName: String,
        val role: PdfBoxSearchableFontRole,
    )

    private companion object {
        const val PUBLIC_FOLDER = "Mopria Scan & Print/Scans"
        const val PUBLIC_RELATIVE_PATH = "Download/$PUBLIC_FOLDER"
        const val SHARE_FOLDER = "shared-scans"
        const val SHARE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        val SEARCHABLE_FONT_ASSET = SearchableFontAsset(
            "ocr/fonts/NotoSansTC-VF.ttf",
            "NotoSansTC-VF.ttf",
            PdfBoxSearchableFontRole.Default,
        )
    }
}
