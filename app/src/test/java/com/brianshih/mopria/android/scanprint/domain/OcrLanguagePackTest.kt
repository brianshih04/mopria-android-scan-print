package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrLanguagePackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultsIncludeEnglishAndChineseOnly() {
        assertEquals(
            setOf(
                OcrLanguagePack.English,
                OcrLanguagePack.TraditionalChinese,
                OcrLanguagePack.SimplifiedChinese,
            ),
            OcrLanguagePack.defaultSelection,
        )
    }

    @Test
    fun japaneseAndKoreanUseVerifiedOnDemandFontPacks() {
        assertEquals(
            OcrDownloadableFontPack.Japanese,
            OcrDownloadableFontPack.forLanguage(OcrLanguagePack.Japanese),
        )
        assertEquals(
            OcrDownloadableFontPack.Korean,
            OcrDownloadableFontPack.forLanguage(OcrLanguagePack.Korean),
        )
        OcrDownloadableFontPack.entries.forEach { pack ->
            assertTrue(pack.downloadUrl.startsWith("https://raw.githubusercontent.com/notofonts/noto-cjk/"))
            assertFalse(pack.downloadUrl.contains("/main/"))
            assertTrue(pack.downloadUrl.contains(Regex("/noto-cjk/[0-9a-f]{40}/")))
            assertTrue(pack.byteLength in 1L..20L * 1024L * 1024L)
            assertTrue(pack.sha256.matches(Regex("[0-9A-F]{64}")))
            assertTrue(!pack.language.defaultSelected)
        }
    }

    @Test
    fun searchableFontRoleUsesLanguageAndScriptHints() {
        assertEquals(PdfBoxSearchableFontRole.Japanese, PdfBoxSearchableFontRole.forText("ja-JP", "文書"))
        assertEquals(PdfBoxSearchableFontRole.Japanese, PdfBoxSearchableFontRole.forText(null, "かな"))
        assertEquals(PdfBoxSearchableFontRole.Korean, PdfBoxSearchableFontRole.forText("ko-KR", "문서"))
        assertEquals(PdfBoxSearchableFontRole.Korean, PdfBoxSearchableFontRole.forText(null, "한글"))
        assertEquals(PdfBoxSearchableFontRole.Default, PdfBoxSearchableFontRole.forText("zh-Hant", "中文"))
    }

    @Test
    fun downloadedFontChecksumRejectsChangedContent() {
        val font = temporaryFolder.newFile("font.ttf")
        font.writeText("abc")
        val expected = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"

        assertTrue(OcrFontPackDownloader.matchesChecksum(font, expected))
        font.appendText("changed")
        assertFalse(OcrFontPackDownloader.matchesChecksum(font, expected))
    }

    @Test
    fun languageCatalogIsGroupedByRegion() {
        assertTrue(OcrLanguagePack.entries.any { it.region == OcrLanguageRegion.EastAsia })
        assertTrue(OcrLanguagePack.entries.any { it.region == OcrLanguageRegion.EuropeAmericas })
        assertTrue(OcrLanguagePack.entries.any { it.region == OcrLanguageRegion.Global })
    }
}
