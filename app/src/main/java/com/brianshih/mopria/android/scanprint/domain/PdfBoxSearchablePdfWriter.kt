package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.Normalizer
import kotlin.math.min

/** One image plus the ML Kit coordinates that should become its invisible PDF text layer. */
data class PdfBoxSearchablePage(
    val imageFile: File,
    val ocrLayout: OcrTextLayout,
)

data class PdfBoxSearchablePdfStats(
    val pageCount: Int,
    val textLineCount: Int,
    val textCharacterCount: Int,
)

enum class PdfBoxSearchableFontRole {
    Default,
    Japanese,
    Korean,
    ;

    companion object {
        fun forText(recognizedLanguage: String?, text: String): PdfBoxSearchableFontRole {
            val language = recognizedLanguage?.lowercase().orEmpty()
            return when {
                language == "ja" || language == "jpn" || language.startsWith("ja-") || text.hasKana() -> Japanese
                language == "ko" || language == "kor" || language.startsWith("ko-") || text.hasHangul() -> Korean
                else -> Default
            }
        }
    }
}

data class PdfBoxSearchableFont(
    val file: File,
    val role: PdfBoxSearchableFontRole,
)

/**
 * PDFBox-backed searchable-PDF writer.
 *
 * Each page contains the original JPEG and a Unicode text layer rendered with
 * [RenderingMode.NEITHER]. The caller controls whether it is used; the normal Android PDF
 * renderer remains the fallback when the user did not opt in or OCR did not produce a layout.
 */
object PdfBoxSearchablePdfWriter {
    private val pageSize = PDRectangle.LETTER
    private const val pageMargin = 36f
    private const val minimumFontSize = 4f
    private const val maximumFontSize = 48f
    private const val maximumMainMemoryBytes = 32L * 1024L * 1024L

    /**
     * Writes a PDF with the supplied image pages and OCR layouts.
     *
     * The caller owns [output] and [fontFiles]. Fonts are embedded and subset into the PDF so
     * text extraction does not depend on fonts installed on the viewing device.
     */
    @Throws(IOException::class)
    fun write(
        context: Context,
        pages: List<PdfBoxSearchablePage>,
        fontFiles: List<PdfBoxSearchableFont>,
        output: File,
        debugOverlay: Boolean = false,
    ): PdfBoxSearchablePdfStats = FileOutputStream(output).use { stream ->
        write(context, pages, fontFiles, stream, debugOverlay)
    }

    /** Writes to a caller-owned stream without closing it. */
    @Throws(IOException::class)
    fun write(
        context: Context,
        pages: List<PdfBoxSearchablePage>,
        fontFiles: List<PdfBoxSearchableFont>,
        output: OutputStream,
        debugOverlay: Boolean = false,
    ): PdfBoxSearchablePdfStats {
        require(pages.isNotEmpty()) { "At least one page is required" }
        require(fontFiles.isNotEmpty() && fontFiles.all { it.file.isFile && it.file.length() > 0L }) {
            "At least one Unicode font file is required for the searchable text layer"
        }

        PDFBoxResourceLoader.init(context.applicationContext)

        var textLineCount = 0
        var textCharacterCount = 0

        val memoryUsage = MemoryUsageSetting
            .setupMixed(maximumMainMemoryBytes)
            .setTempDir(context.applicationContext.cacheDir)
        PDDocument(memoryUsage).use { document ->
            val fonts = fontFiles.map { source ->
                LoadedFont(
                    font = FileInputStream(source.file).use { input ->
                        PDType0Font.load(document, input, true)
                    },
                    role = source.role,
                )
            }

            pages.forEach { sourcePage ->
                require(sourcePage.imageFile.isFile && sourcePage.imageFile.length() > 0L) {
                    "OCR page image is missing: ${sourcePage.imageFile}"
                }

                val imageSize = readImageSize(sourcePage.imageFile)
                val page = PDPage(pageSize)
                document.addPage(page)
                val placement = fitImage(imageSize.first, imageSize.second)
                val lines = sourcePage.ocrLayout.linesInReadingOrder()

                PDPageContentStream(document, page).use { content ->
                    FileInputStream(sourcePage.imageFile).use { input ->
                        val image = JPEGFactory.createFromStream(document, input)
                        content.drawImage(
                            image,
                            placement.left,
                            placement.bottom,
                            placement.width,
                            placement.height,
                        )
                    }

                    if (debugOverlay) {
                        content.setStrokingColor(1f, 0f, 0f)
                        content.setLineWidth(0.75f)
                        lines.forEach { line ->
                            line.bounds?.let { bounds ->
                                mapBoundsToPdf(bounds, imageSize, placement)?.let { mapped ->
                                    content.addRect(
                                        mapped.left,
                                        mapped.bottom,
                                        mapped.width,
                                        mapped.height,
                                    )
                                    content.stroke()
                                }
                            }
                        }
                    }

                    content.beginText()
                    content.setRenderingMode(RenderingMode.NEITHER)
                    lines.forEach { line ->
                        val bounds = line.bounds ?: return@forEach
                        val preparedText = prepareText(
                            value = line.text,
                            recognizedLanguage = line.recognizedLanguage,
                            fonts = fonts,
                        ) ?: return@forEach
                        if (bounds.width <= 0 || bounds.height <= 0) {
                            return@forEach
                        }

                        val mapped = mapBoundsToPdf(bounds, imageSize, placement)
                            ?: return@forEach
                        val fontSize = (mapped.height * 1.05f)
                            .coerceIn(minimumFontSize, maximumFontSize)
                        val naturalWidth = preparedText.widthUnits / 1000f * fontSize
                        val horizontalScale = naturalWidth
                            .takeIf { it > 0f && it.isFinite() }
                            ?.let { (mapped.width / it).coerceIn(0.25f, 4f) }
                            ?: 1f

                        content.setFont(preparedText.font, fontSize)
                        // ML Kit's bounds use a top-left origin. Keep the text matrix axis-aligned,
                        // scale glyphs to the same line width, and use the same mapped rectangle
                        // as the optional debug overlay.
                        content.setTextMatrix(
                            Matrix(
                                horizontalScale,
                                0f,
                                0f,
                                1f,
                                mapped.left,
                                mapped.baseline,
                            ),
                        )
                        runCatching { content.showText(preparedText.text) }
                            .onSuccess {
                                textLineCount += 1
                                textCharacterCount += preparedText.text.codePointCount(0, preparedText.text.length)
                            }
                    }
                    content.endText()
                }
            }

            document.documentInformation.title = "Searchable OCR PDF"
            document.documentInformation.subject = "ML Kit Text Recognition v2 with PDFBox Android"
            // PDFBox generates its own ToUnicode map while subsetting. Complete that one-time
            // subset pass first, then replace the ambiguous CJK reverse mappings before the
            // actual output write. The discard stream avoids retaining another PDF in memory.
            document.save(DiscardingOutputStream)
            fonts.forEach { loaded -> loaded.installToUnicode(document) }
            document.save(output)
        }

        return PdfBoxSearchablePdfStats(
            pageCount = pages.size,
            textLineCount = textLineCount,
            textCharacterCount = textCharacterCount,
        )
    }

    private fun readImageSize(imageFile: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) {
            "Unable to decode image bounds: $imageFile"
        }
        return options.outWidth to options.outHeight
    }

    private fun fitImage(sourceWidth: Int, sourceHeight: Int): ImagePlacement {
        val availableWidth = pageSize.width - pageMargin * 2
        val availableHeight = pageSize.height - pageMargin * 2
        val scale = min(
            availableWidth / sourceWidth.toFloat(),
            availableHeight / sourceHeight.toFloat(),
        )
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        return ImagePlacement(
            left = (pageSize.width - width) / 2f,
            bottom = (pageSize.height - height) / 2f,
            width = width,
            height = height,
        )
    }

    private fun mapBoundsToPdf(
        bounds: OcrBounds,
        imageSize: Pair<Int, Int>,
        placement: ImagePlacement,
    ): TextPlacement? {
        if (imageSize.first <= 0 || imageSize.second <= 0 || bounds.width <= 0 || bounds.height <= 0) {
            return null
        }
        val scaleX = placement.width / imageSize.first
        val scaleY = placement.height / imageSize.second
        val left = placement.left + bounds.left * scaleX
        val bottom = placement.bottom + placement.height - bounds.bottom * scaleY
        val width = bounds.width * scaleX
        val height = bounds.height * scaleY
        val fontSize = (height * 1.05f).coerceIn(minimumFontSize, maximumFontSize)
        return TextPlacement(
            left = left,
            bottom = bottom,
            width = width,
            height = height,
            baseline = bottom + fontSize * 0.12f,
        )
    }

    private fun safeText(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .filter { character -> !character.isISOControl() }
        .let { text -> Normalizer.normalize(text, Normalizer.Form.NFKC) }

    private fun prepareText(
        value: String,
        recognizedLanguage: String?,
        fonts: List<LoadedFont>,
    ): EncodableText? {
        val text = safeText(value)
        if (text.isBlank()) return null

        val preferredRole = PdfBoxSearchableFontRole.forText(recognizedLanguage, text)
        val orderedFonts = fonts.sortedBy { loaded ->
            when (loaded.role) {
                preferredRole -> 0
                PdfBoxSearchableFontRole.Default -> 1
                else -> 2
            }
        }
        orderedFonts.forEach { loaded ->
            val width = runCatching { loaded.font.getStringWidth(text) }.getOrNull()
            if (width != null) {
                loaded.recordUnicodeMappings(text)
                return EncodableText(loaded.font, text, width)
            }
        }

        // Preserve every encodable character even when no bundled font covers the complete line.
        // Unsupported code points become spaces instead of causing PDFBox to discard the line.
        val primary = orderedFonts.first()
        val sanitized = buildString {
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                val glyph = String(Character.toChars(codePoint))
                if (runCatching { primary.font.getStringWidth(glyph) }.isSuccess) append(glyph) else append(' ')
                offset += Character.charCount(codePoint)
            }
        }
        if (sanitized.isBlank()) return null
        val width = runCatching { primary.font.getStringWidth(sanitized) }.getOrNull() ?: return null
        primary.recordUnicodeMappings(sanitized)
        return EncodableText(primary.font, sanitized, width)
    }

    private data class ImagePlacement(
        val left: Float,
        val bottom: Float,
        val width: Float,
        val height: Float,
    )

    private data class TextPlacement(
        val left: Float,
        val bottom: Float,
        val width: Float,
        val height: Float,
        val baseline: Float,
    )

    private data class EncodableText(
        val font: PDType0Font,
        val text: String,
        val widthUnits: Float,
    )

    private class LoadedFont(
        val font: PDType0Font,
        val role: PdfBoxSearchableFontRole,
    ) {
        private val unicodeMappings = linkedMapOf<String, String>()

        fun recordUnicodeMappings(text: String) {
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                val glyph = String(Character.toChars(codePoint))
                runCatching { font.encode(glyph) }.getOrNull()?.let { encoded ->
                    unicodeMappings.putIfAbsent(encoded.toHex(), glyph)
                }
                offset += Character.charCount(codePoint)
            }
        }

        fun installToUnicode(document: PDDocument) {
            if (unicodeMappings.isEmpty()) return
            val codeLengths = unicodeMappings.keys.map { encoded -> encoded.length / 2 }.toSortedSet()
            val cMap = buildString {
                appendLine("/CIDInit /ProcSet findresource begin")
                appendLine("12 dict begin")
                appendLine("begincmap")
                appendLine("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def")
                appendLine("/CMapName /Mopria-${role.name}-UCS def")
                appendLine("/CMapType 2 def")
                appendLine("${codeLengths.size} begincodespacerange")
                codeLengths.forEach { length ->
                    appendLine("<${"00".repeat(length)}> <${"FF".repeat(length)}>")
                }
                appendLine("endcodespacerange")
                unicodeMappings.entries.chunked(100).forEach { entries ->
                    appendLine("${entries.size} beginbfchar")
                    entries.forEach { (encoded, unicode) ->
                        appendLine("<$encoded> <${unicode.toByteArray(Charsets.UTF_16BE).toHex()}>")
                    }
                    appendLine("endbfchar")
                }
                appendLine("endcmap")
                appendLine("CMapName currentdict /CMap defineresource pop")
                appendLine("end")
                appendLine("end")
            }
            val stream = document.document.createCOSStream()
            stream.createOutputStream().bufferedWriter(Charsets.US_ASCII).use { writer ->
                writer.write(cMap)
            }
            font.cosObject.setItem(COSName.TO_UNICODE, stream)
        }
    }

    private object DiscardingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02X".format(byte) }

private fun String.hasKana(): Boolean = codePoints().anyMatch { codePoint ->
    codePoint in 0x3040..0x30FF || codePoint in 0x31F0..0x31FF
}

private fun String.hasHangul(): Boolean = codePoints().anyMatch { codePoint ->
    codePoint in 0x1100..0x11FF || codePoint in 0x3130..0x318F || codePoint in 0xAC00..0xD7AF
}

private fun OcrTextLayout.linesInReadingOrder(): List<OcrTextLine> = blocks
    .flatMap { block -> block.lines }
    .filter { line -> line.text.isNotBlank() && line.bounds != null }
    .sortedWith(
        compareBy<OcrTextLine>({ it.bounds?.top ?: Int.MAX_VALUE }, { it.bounds?.left ?: Int.MAX_VALUE }),
    )
