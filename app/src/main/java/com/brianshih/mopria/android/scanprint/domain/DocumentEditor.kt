package com.brianshih.mopria.android.scanprint.domain

/**
 * Pure document editing operations: rotate, reorder, and delete pages.
 * All operations return new [MopriaDocument] instances — no mutation.
 * Tested via [DocumentEditorTest] without Android dependencies.
 */
object DocumentEditor {

    /** Rotate a page by [degrees] (must be 90, 180, or 270). Returns a new document. */
    fun rotatePage(document: MopriaDocument, pageId: String, degrees: Int): MopriaDocument {
        require(degrees in setOf(90, 180, 270)) { "Rotation must be 90, 180, or 270 degrees" }
        return document.copy(
            pages = document.pages.map { page ->
                if (page.id == pageId) {
                    page.copy(rotationDegrees = (page.rotationDegrees + degrees) % 360)
                } else {
                    page
                }
            },
        )
    }

    /** Move a page from [fromIndex] to [toIndex]. Returns a new document with reordered pages. */
    fun movePage(document: MopriaDocument, fromIndex: Int, toIndex: Int): MopriaDocument {
        val pages = document.pages.toMutableList()
        require(fromIndex in pages.indices) { "fromIndex $fromIndex out of range" }
        require(toIndex in pages.indices) { "toIndex $toIndex out of range" }
        val page = pages.removeAt(fromIndex)
        pages.add(toIndex, page)
        return document.copy(pages = pages.mapIndexed { index, p -> p.copy(pageNumber = index + 1) })
    }

    /** Delete a page by [pageId]. Returns a new document without that page, or null if it was the last page. */
    fun deletePage(document: MopriaDocument, pageId: String): MopriaDocument? {
        val newPages = document.pages.filterNot { it.id == pageId }
        if (newPages.isEmpty()) return null
        return document.copy(pages = newPages.mapIndexed { index, p -> p.copy(pageNumber = index + 1) })
    }
    /** Apply or remove a crop region on a page. Returns a new document. */
    fun cropPage(document: MopriaDocument, pageId: String, crop: CropRect?): MopriaDocument {
        return document.copy(
            pages = document.pages.map { page ->
                if (page.id == pageId) page.copy(cropRect = crop) else page
            },
        )
    }

}
