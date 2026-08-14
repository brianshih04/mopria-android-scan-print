package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import java.io.File

/**
 * Manages the lifecycle of scan source files and print/share temp files.
 *
 * Scan source images live in `filesDir/scans/` and are referenced by [DocumentPage] paths.
 * After a document has been exported to the public Downloads folder or shared, the originals
 * are no longer needed and can be deleted to reclaim storage.
 *
 * Print render temp files live in `cacheDir/` and are deleted immediately after use by the
 * IPP client; this class also provides a periodic sweep of stale cache entries as a safety net.
 */
object TempFileCleanup {

    private const val SCANS_DIR = "scans"
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /** Files younger than this are left alone: they may belong to a scan that is still completing. */
    private const val SCANS_GRACE_PERIOD_MS = 10L * 60L * 1000L

    /**
     * Delete all source files referenced by [document] from `filesDir/scans/`.
     * Called after successful export or when a document is deleted by the user.
     * Files that don't exist or can't be deleted are silently skipped.
     */
    fun deleteDocumentFiles(context: Context, document: MopriaDocument) {
        document.pages.forEach { page ->
            page.imagePath?.let { deleteIfExists(it) }
            page.pdfPath?.let { deleteIfExists(it) }
        }
    }

    /**
     * Delete source files for all documents in [documents]. Used when clearing all documents.
     */
    fun deleteAllScanFiles(context: Context, documents: List<MopriaDocument>) {
        documents.forEach { deleteDocumentFiles(context, it) }
    }

    /**
     * Remove orphaned files in `filesDir/scans/` that are no longer referenced by any document.
     * Called on app startup to reclaim space from documents that were deleted in a previous session
     * but whose files were left behind (e.g. process death before cleanup ran).
     *
     * [referencedPaths] is the set of all absolute file paths that current documents point to.
     * Unreferenced files newer than [SCANS_GRACE_PERIOD_MS] are kept, because a scan completing
     * concurrently with startup may not have entered the referenced set yet.
     */
    fun deleteOrphanedScans(context: Context, referencedPaths: Set<String>) {
        val scansDir = File(context.applicationContext.filesDir, SCANS_DIR)
        if (!scansDir.isDirectory) return
        val cutoff = System.currentTimeMillis() - SCANS_GRACE_PERIOD_MS
        scansDir.listFiles()?.forEach { file ->
            val absolute = file.absolutePath
            if (absolute !in referencedPaths && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    /**
     * Remove stale files in `cacheDir/` older than [CACHE_MAX_AGE_MS].
     * This is a safety net for print/share temp files that were not deleted by their
     * owning code path (e.g. crash during IPP print). Called on app startup.
     */
    fun sweepStaleCache(context: Context) {
        val cacheDir = context.applicationContext.cacheDir
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private fun deleteIfExists(path: String) {
        val file = File(path.removePrefix("file://"))
        if (file.isFile) runCatching { file.delete() }
    }
}
