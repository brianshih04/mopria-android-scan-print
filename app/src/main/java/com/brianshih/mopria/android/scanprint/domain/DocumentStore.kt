package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the in-memory document list and Flatbed session state to internal storage so they survive
 * process death. Scan image/PDF files already live on disk in `filesDir/scans/`; this store only
 * records the metadata (paths, names, page info) needed to reconstruct [MopriaDocument] objects.
 *
 * Uses `org.json` (built into Android) — no additional serialization dependencies required.
 */
class DocumentStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val storeFile = File(filesDir, STORE_FILENAME)
    private val ocrLayoutDirectory = File(filesDir, OCR_LAYOUT_DIRECTORY)

    /** Persist [documents] and [pendingFlatbedDocumentId] so they can be restored after process death. */
    fun save(documents: List<MopriaDocument>, pendingFlatbedDocumentId: String?) {
        val json = JSONObject()
        val docsArray = JSONArray()
        val retainedOcrSidecars = mutableSetOf<String>()
        for (doc in documents) {
            val pagesArray = JSONArray()
            for (page in doc.pages) {
                val pageJson = JSONObject()
                pageJson.put("id", page.id)
                pageJson.put("pageNumber", page.pageNumber)
                pageJson.put("title", page.title)
                page.imagePath?.let { pageJson.put("imagePath", it) }
                page.pdfPath?.let { pageJson.put("pdfPath", it) }
                page.pdfPageIndex?.let { pageJson.put("pdfPageIndex", it) }
                pageJson.put("rotationDegrees", page.rotationDegrees)
                page.generatedTitle?.let { pageJson.put("generatedTitle", it.name) }
                page.generatedTitleNumber?.let { pageJson.put("generatedTitleNumber", it) }
                page.cropRect?.let { crop ->
                    pageJson.put(
                        "cropRect",
                        JSONObject().apply {
                            put("left", crop.left)
                            put("top", crop.top)
                            put("right", crop.right)
                            put("bottom", crop.bottom)
                        },
                    )
                }
                (page.ocrResult as? OcrResult.Applied)?.let { result ->
                    val fileName = ocrSidecarName(page.id)
                    writeOcrSidecar(fileName, result)
                    retainedOcrSidecars += fileName
                    pageJson.put("ocrResultFile", fileName)
                }
                pagesArray.put(pageJson)
            }
            val docJson = JSONObject()
            docJson.put("id", doc.id)
            docJson.put("name", doc.name)
            docJson.put("sourceLabel", doc.sourceLabel)
            docJson.put("createdAt", doc.createdAt)
            docJson.put("pages", pagesArray)
            docJson.put("searchablePdf", doc.searchablePdf)
            doc.documentSize?.let { docJson.put("documentSize", it.name) }
            docJson.put("actualScanSettingsReported", doc.actualScanSettingsReported)
            doc.generatedName?.let { docJson.put("generatedName", it.name) }
            doc.generatedNameSuffix?.let { docJson.put("generatedNameSuffix", it) }
            doc.generatedNamePageNumber?.let { docJson.put("generatedNamePageNumber", it) }
            doc.exportedPath?.let { docJson.put("exportedPath", it) }
            docsArray.put(docJson)
        }
        json.put("documents", docsArray)
        pendingFlatbedDocumentId?.let { json.put("pendingFlatbedDocumentId", it) }

        writeTextAtomically(storeFile, json.toString())
        deleteUnreferencedOcrSidecars(retainedOcrSidecars)
    }

    /** Load persisted documents and Flatbed session, or null if no store exists or it is corrupt. */
    fun load(): PersistedState? {
        if (!storeFile.isFile) return null
        return runCatching {
            val json = JSONObject(storeFile.readText())
            val docsArray = json.optJSONArray("documents") ?: JSONArray()
            val documents = (0 until docsArray.length()).map { i ->
                val d = docsArray.getJSONObject(i)
                val pagesArray = d.optJSONArray("pages") ?: JSONArray()
                val pages = (0 until pagesArray.length()).map { j ->
                    val p = pagesArray.getJSONObject(j)
                    DocumentPage(
                        id = p.getString("id"),
                        pageNumber = p.getInt("pageNumber"),
                        title = p.optString("title", ""),
                        imagePath = p.optString("imagePath").takeIf { it.isNotBlank() },
                        pdfPath = p.optString("pdfPath").takeIf { it.isNotBlank() },
                        pdfPageIndex = p.optInt("pdfPageIndex", -1).takeIf { it >= 0 },
                        rotationDegrees = p.optInt("rotationDegrees", 0),
                        cropRect = p.optJSONObject("cropRect")?.let { crop ->
                            runCatching {
                                CropRect(
                                    left = crop.getDouble("left").toFloat(),
                                    top = crop.getDouble("top").toFloat(),
                                    right = crop.getDouble("right").toFloat(),
                                    bottom = crop.getDouble("bottom").toFloat(),
                                )
                            }.getOrNull()
                        },
                        ocrResult = p.optString("ocrResultFile")
                            .takeIf(String::isNotBlank)
                            ?.let(::readOcrSidecar),
                        generatedTitle = p.optString("generatedTitle")
                            .takeIf(String::isNotBlank)
                            ?.let { name -> GeneratedPageTitle.entries.firstOrNull { it.name == name } },
                        generatedTitleNumber = p.optInt("generatedTitleNumber", -1).takeIf { it >= 0 },
                    )
                }
                MopriaDocument(
                    id = d.getString("id"),
                    name = d.getString("name"),
                    pages = pages,
                    sourceLabel = d.optString("sourceLabel", ""),
                    createdAt = d.optLong("createdAt", System.currentTimeMillis()),
                    exportedPath = d.optString("exportedPath").takeIf { it.isNotBlank() },
                    ocrResults = pages.mapNotNull(DocumentPage::ocrResult),
                    searchablePdf = d.optBoolean("searchablePdf", false),
                    documentSize = d.optString("documentSize")
                        .takeIf(String::isNotBlank)
                        ?.let { name -> ScanDocumentSize.entries.firstOrNull { it.name == name } },
                    actualScanSettingsReported = d.optBoolean("actualScanSettingsReported", true),
                    generatedName = d.optString("generatedName")
                        .takeIf(String::isNotBlank)
                        ?.let { name -> GeneratedDocumentName.entries.firstOrNull { it.name == name } },
                    generatedNameSuffix = d.optString("generatedNameSuffix").takeIf(String::isNotBlank),
                    generatedNamePageNumber = d.optInt("generatedNamePageNumber", -1).takeIf { it >= 0 },
                )
            }
            // Filter out documents whose files no longer exist on disk (stale entries from a previous run).
            val valid = documents.filter { doc ->
                doc.pages.any { page ->
                    val imgPath = page.imagePath
                    val pdfPath = page.pdfPath
                    (imgPath != null && File(imgPath.removePrefix("file://")).isFile) ||
                        (pdfPath != null && File(pdfPath.removePrefix("file://")).isFile)
                }
            }
            PersistedState(
                documents = valid,
                pendingFlatbedDocumentId = json.optString("pendingFlatbedDocumentId").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    /** Remove the persisted store (e.g. when all documents are cleared). */
    fun clear() {
        storeFile.delete()
        ocrLayoutDirectory.deleteRecursively()
    }

    private fun writeOcrSidecar(fileName: String, result: OcrResult.Applied) {
        ocrLayoutDirectory.mkdirs()
        val target = File(ocrLayoutDirectory, fileName)
        val temporary = File.createTempFile("ocr-layout-", ".tmp", ocrLayoutDirectory)
        try {
            GZIPOutputStream(FileOutputStream(temporary).buffered()).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(OcrLayoutPersistence.encode(result).toString())
            }
            AtomicFileReplacer.replace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun readOcrSidecar(fileName: String): OcrResult.Applied? {
        if (fileName != File(fileName).name || !fileName.endsWith(OCR_LAYOUT_SUFFIX)) return null
        val file = File(ocrLayoutDirectory, fileName)
        if (!file.isFile || file.length() !in 1L..MAX_OCR_SIDECAR_BYTES) return null
        return runCatching {
            val json = GZIPInputStream(FileInputStream(file).buffered()).bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText())
            }
            OcrLayoutPersistence.decode(json)
        }.getOrNull()
    }

    private fun writeTextAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("document-store-", ".tmp", target.parentFile)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            AtomicFileReplacer.replace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun deleteUnreferencedOcrSidecars(retained: Set<String>) {
        ocrLayoutDirectory.listFiles()
            ?.filter { file -> file.isFile && file.name.endsWith(OCR_LAYOUT_SUFFIX) && file.name !in retained }
            ?.forEach(File::delete)
    }

    private fun ocrSidecarName(pageId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pageId.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + OCR_LAYOUT_SUFFIX
    }

    companion object {
        private const val STORE_FILENAME = "document_store.json"
        private const val OCR_LAYOUT_DIRECTORY = "ocr-layouts"
        private const val OCR_LAYOUT_SUFFIX = ".ocr.json.gz"
        private const val MAX_OCR_SIDECAR_BYTES = 32L * 1024L * 1024L
    }
}

/** Result of loading persisted state: the document list and the in-progress Flatbed session, if any. */
data class PersistedState(
    val documents: List<MopriaDocument>,
    val pendingFlatbedDocumentId: String?,
)
