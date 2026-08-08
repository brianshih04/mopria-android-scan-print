package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import java.io.File
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
    private val storeFile = File(context.applicationContext.filesDir, STORE_FILENAME)

    /** Persist [documents] and [pendingFlatbedDocumentId] so they can be restored after process death. */
    fun save(documents: List<MopriaDocument>, pendingFlatbedDocumentId: String?) {
        val json = JSONObject()
        val docsArray = JSONArray()
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
                pagesArray.put(pageJson)
            }
            val docJson = JSONObject()
            docJson.put("id", doc.id)
            docJson.put("name", doc.name)
            docJson.put("sourceLabel", doc.sourceLabel)
            docJson.put("createdAt", doc.createdAt)
            docJson.put("pages", pagesArray)
            doc.exportedPath?.let { docJson.put("exportedPath", it) }
            docsArray.put(docJson)
        }
        json.put("documents", docsArray)
        pendingFlatbedDocumentId?.let { json.put("pendingFlatbedDocumentId", it) }

        storeFile.writeText(json.toString())
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
                    )
                }
                MopriaDocument(
                    id = d.getString("id"),
                    name = d.getString("name"),
                    pages = pages,
                    sourceLabel = d.optString("sourceLabel", ""),
                    createdAt = d.optLong("createdAt", System.currentTimeMillis()),
                    exportedPath = d.optString("exportedPath").takeIf { it.isNotBlank() },
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
    }

    companion object {
        private const val STORE_FILENAME = "document_store.json"
    }
}

/** Result of loading persisted state: the document list and the in-progress Flatbed session, if any. */
data class PersistedState(
    val documents: List<MopriaDocument>,
    val pendingFlatbedDocumentId: String?,
)
