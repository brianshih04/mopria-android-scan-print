package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEditorTest {

    private fun doc(vararg pages: DocumentPage) = MopriaDocument(
        id = "test-doc",
        name = "Test",
        pages = pages.toList(),
        sourceLabel = "test",
    )

    private fun page(id: String, number: Int) = DocumentPage(id, number, "Page $number")

    @Test
    fun rotatePageAddsDegrees() {
        val document = doc(page("p1", 1), page("p2", 2))
        val rotated = DocumentEditor.rotatePage(document, "p1", 90)
        assertEquals(90, rotated.pages[0].rotationDegrees)
        assertEquals(0, rotated.pages[1].rotationDegrees)
    }

    @Test
    fun rotatePageWrapsAround360() {
        val document = doc(page("p1", 1).copy(rotationDegrees = 270))
        val rotated = DocumentEditor.rotatePage(document, "p1", 90)
        assertEquals(0, rotated.pages[0].rotationDegrees)
    }

    @Test
    fun rotatePage180() {
        val document = doc(page("p1", 1))
        val rotated = DocumentEditor.rotatePage(document, "p1", 180)
        assertEquals(180, rotated.pages[0].rotationDegrees)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rotateRejectsInvalidDegrees() {
        DocumentEditor.rotatePage(doc(page("p1", 1)), "p1", 45)
    }

    @Test
    fun movePageForward() {
        val document = doc(page("p1", 1), page("p2", 2), page("p3", 3))
        val moved = DocumentEditor.movePage(document, 0, 2)
        assertEquals(listOf("p2", "p3", "p1"), moved.pages.map { it.id })
        assertEquals(listOf(1, 2, 3), moved.pages.map { it.pageNumber })
    }

    @Test
    fun movePageBackward() {
        val document = doc(page("p1", 1), page("p2", 2), page("p3", 3))
        val moved = DocumentEditor.movePage(document, 2, 0)
        assertEquals(listOf("p3", "p1", "p2"), moved.pages.map { it.id })
    }

    @Test
    fun movePageSameIndex() {
        val document = doc(page("p1", 1), page("p2", 2))
        val moved = DocumentEditor.movePage(document, 0, 0)
        assertEquals(listOf("p1", "p2"), moved.pages.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun movePageOutOfRange() {
        DocumentEditor.movePage(doc(page("p1", 1)), 0, 5)
    }

    @Test
    fun deletePageRemovesAndRenumbers() {
        val document = doc(page("p1", 1), page("p2", 2), page("p3", 3))
        val result = DocumentEditor.deletePage(document, "p2")
        assertNotNull(result)
        assertEquals(listOf("p1", "p3"), result!!.pages.map { it.id })
        assertEquals(listOf(1, 2), result.pages.map { it.pageNumber })
    }

    @Test
    fun deleteLastPageReturnsNull() {
        val document = doc(page("p1", 1))
        val result = DocumentEditor.deletePage(document, "p1")
        assertNull(result)
    }

    @Test
    fun deleteNonExistentPageReturnsOriginal() {
        val document = doc(page("p1", 1))
        val result = DocumentEditor.deletePage(document, "p99")
        assertNotNull(result)
        assertEquals(1, result!!.pages.size)
    }
    @Test
    fun cropPageSetsCropRect() {
        val document = doc(page("p1", 1))
        val crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f)
        val cropped = DocumentEditor.cropPage(document, "p1", crop)
        assertEquals(crop, cropped.pages[0].cropRect)
    }

    @Test
    fun cropPageClearsWithNull() {
        val document = doc(page("p1", 1).copy(cropRect = CropRect(0.1f, 0.1f, 0.9f, 0.9f)))
        val cleared = DocumentEditor.cropPage(document, "p1", null)
        assertNull(cleared.pages[0].cropRect)
    }

    @Test
    fun cropPageOnlyAffectsTargetPage() {
        val document = doc(page("p1", 1), page("p2", 2))
        val crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val cropped = DocumentEditor.cropPage(document, "p1", crop)
        assertEquals(crop, cropped.pages[0].cropRect)
        assertNull(cropped.pages[1].cropRect)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cropRectRejectsLeftGreaterThanRight() {
        CropRect(0.8f, 0.1f, 0.2f, 0.9f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cropRectRejectsOutOfRange() {
        CropRect(-0.1f, 0f, 1f, 1f)
    }

    @Test
    fun cropRectComputesWidthHeight() {
        val crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f)
        assertEquals(0.8f, crop.width, 0.001f)
        assertEquals(0.6f, crop.height, 0.001f)
    }

}
