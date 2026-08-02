package com.brianshih.mopria.android.scanprint.domain

/** Pure document assembly rules shared by mock and real scan workflows. */
object ScanDocumentOrganizer {
    fun appendFlatbedPage(existing: MopriaDocument?, scanned: MopriaDocument): MopriaDocument {
        require(scanned.pages.isNotEmpty()) { "Flatbed 掃描沒有可合併的頁面" }
        val base = existing ?: scanned.copy(name = "Flatbed 多頁文件 ${scanned.createdAt.toString().takeLast(4)}")
        val combinedPages = (existing?.pages.orEmpty() + scanned.pages).mapIndexed { index, page ->
            page.copy(pageNumber = index + 1, title = "掃描頁 ${index + 1}")
        }
        return base.copy(
            sourceLabel = "${scanned.sourceLabel.substringBeforeLast(" · 多頁 PDF")} · 多頁 PDF",
            pages = combinedPages,
        )
    }

    fun splitPages(document: MopriaDocument): List<MopriaDocument> {
        require(document.pages.isNotEmpty()) { "ADF 掃描沒有可拆分的頁面" }
        return document.pages.mapIndexed { index, page ->
            document.copy(
                id = "${document.id}-page-${index + 1}",
                name = "${document.name}－第 ${index + 1} 頁",
                pages = listOf(page.copy(pageNumber = 1, title = "掃描頁 1")),
            )
        }
    }
}
