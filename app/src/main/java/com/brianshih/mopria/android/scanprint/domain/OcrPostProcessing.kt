package com.brianshih.mopria.android.scanprint.domain

import kotlin.math.abs
import kotlin.math.max

/** A serializable, Android-independent rectangle returned by ML Kit. */
data class OcrBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

data class OcrPoint(
    val x: Int,
    val y: Int,
)

data class OcrTextSymbol(
    val text: String,
    val bounds: OcrBounds?,
    val cornerPoints: List<OcrPoint> = emptyList(),
    val angle: Float = 0f,
    val confidence: Float? = null,
    val recognizedLanguage: String? = null,
)

data class OcrTextElement(
    val text: String,
    val bounds: OcrBounds?,
    val cornerPoints: List<OcrPoint> = emptyList(),
    val angle: Float = 0f,
    val confidence: Float? = null,
    val recognizedLanguage: String? = null,
    val symbols: List<OcrTextSymbol> = emptyList(),
)

data class OcrTextLine(
    val text: String,
    val bounds: OcrBounds?,
    val cornerPoints: List<OcrPoint> = emptyList(),
    val angle: Float = 0f,
    val confidence: Float? = null,
    val recognizedLanguage: String? = null,
    val elements: List<OcrTextElement> = emptyList(),
)

data class OcrTextBlock(
    val text: String,
    val bounds: OcrBounds?,
    val cornerPoints: List<OcrPoint> = emptyList(),
    val recognizedLanguage: String? = null,
    val lines: List<OcrTextLine> = emptyList(),
)

data class OcrTextLayout(
    val blocks: List<OcrTextBlock> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)

/**
 * Makes ML Kit output useful for documents instead of flattening it immediately into one string.
 * Two-column pages are emitted column-by-column when a strong page-wide gutter is present;
 * ordinary single-column text remains in top-to-bottom, left-to-right order.
 */
object OcrTextFormatter {
    fun format(layout: OcrTextLayout, fallback: String = ""): String {
        val lines = layout.blocks
            .flatMap { it.lines }
            .filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return OcrTextNormalizer.normalize(fallback).trim()

        val formatted = if (isWideTableLikeLayout(layout, lines)) {
            formatRows(lines)
        } else {
            orderLines(lines, layout.imageWidth).map { line -> renderLine(line) }
        }
        return formatted.joinToString("\n").trim()
    }

    private fun isWideTableLikeLayout(layout: OcrTextLayout, lines: List<OcrTextLine>): Boolean =
        layout.imageWidth > layout.imageHeight && lines.count { it.bounds != null } >= 6

    private fun formatRows(lines: List<OcrTextLine>): List<String> {
        val bounded = lines.filter { it.bounds != null }.sortedBy { it.bounds!!.centerY }
        if (bounded.isEmpty()) return lines.map(::renderLine)

        val heights = bounded.map { it.bounds!!.height }.filter { it > 0 }.sorted()
        val medianHeight = heights.getOrNull(heights.size / 2) ?: 20
        val rowTolerance = max(8, (medianHeight * 0.7).toInt())
        val rows = mutableListOf<MutableList<OcrTextLine>>()
        bounded.forEach { line ->
            val last = rows.lastOrNull()
            val lastCenter = last?.mapNotNull { it.bounds?.centerY }?.average()
            if (last != null && lastCenter != null && abs(line.bounds!!.centerY - lastCenter) <= rowTolerance) {
                last += line
            } else {
                rows += mutableListOf(line)
            }
        }

        val renderedRows = rows.map { row ->
            row.sortedWith(compareBy({ it.bounds!!.left }, { it.bounds!!.top }))
                .joinToString("\t", transform = ::renderLine)
        }
        val unbounded = lines.filter { it.bounds == null }.map(::renderLine)
        return renderedRows + unbounded
    }

    private fun renderLine(line: OcrTextLine): String = OcrTextNormalizer.normalize(
        line.text.ifBlank { line.elements.joinToString(separator = "") { it.text } },
    )

    private fun orderLines(lines: List<OcrTextLine>, imageWidth: Int): List<OcrTextLine> {
        val bounded = lines.filter { it.bounds != null }
        if (imageWidth <= 0 || bounded.size < 6) {
            return lines.sortedWith(lineComparator)
        }

        val centers = bounded.map { it.bounds!!.centerX }.distinct().sorted()
        val candidate = centers.zipWithNext()
            .maxByOrNull { (left, right) -> right - left }
        val gap = candidate?.let { (left, right) -> right - left } ?: 0
        val split = candidate?.let { (left, right) -> (left + right) / 2 }
        val minimumGutter = max(48, (imageWidth * 0.12).toInt())
        if (split == null || gap < minimumGutter) {
            return lines.sortedWith(lineComparator)
        }

        val left = bounded.filter { it.bounds!!.centerX < split }
        val right = bounded.filter { it.bounds!!.centerX >= split }
        if (left.size < 3 || right.size < 3) {
            return lines.sortedWith(lineComparator)
        }

        val unbounded = lines.filter { it.bounds == null }
        return left.sortedWith(lineComparator) + right.sortedWith(lineComparator) + unbounded
    }

    private val lineComparator = compareBy<OcrTextLine>(
        { it.bounds?.top ?: Int.MAX_VALUE },
        { it.bounds?.left ?: Int.MAX_VALUE },
    )
}

/** Conservative normalisation for OCR output that looks like a number. */
object OcrTextNormalizer {
    private val numericToken = Regex(
        """(?<![\p{L}\d])[+\-]?[\d][\d,，.．·⋅﹒｡]*[\d](?:[%％]|[元円$€£])?(?![\p{L}\d])""",
    )
    private val splitGroupedNumber = Regex(
        """(?<![\p{L}\d])([+\-]?\d{1,3}(?:[,.]\d{3})+[,.]\d{1,2})[\p{Zs}\t]+(\d{1,2})(?=(?:[%％]|[元円$€£])?(?![\p{L}\d]))""",
    )

    fun normalize(text: String): String {
        val mapped = text.map(::mapCharacter).joinToString("")
        // Repair only a clearly incomplete final thousands group (for example 1,559,43 1).
        // General spaces stay intact so adjacent table cells such as 20,000 9.34% never merge.
        val repaired = splitGroupedNumber.replace(mapped) { match ->
            match.groupValues[1] + match.groupValues[2]
        }
        return numericToken.replace(repaired) { match -> normalizeNumber(match.value) }
    }

    private fun normalizeNumber(value: String): String {
        val suffix = value.lastOrNull()?.takeIf { it in "%元円$€£" }?.toString().orEmpty()
        val core = if (suffix.isEmpty()) value else value.dropLast(1)
        val compact = core
            .replace('，', ',')
            .replace('．', '.')
            .replace('·', '.')
            .replace('⋅', '.')
            .replace('﹒', '.')
            .replace('｡', '.')
        if ('/' in compact) return compact + suffix

        val sign = compact.firstOrNull()
            ?.takeIf { it == '+' || it == '-' }
            ?.toString()
            .orEmpty()
        val unsigned = compact.removePrefix("+").removePrefix("-")
        val groups = unsigned.split(',', '.')
        val groupedInteger = groups.size >= 2 &&
            groups.first().length in 1..3 &&
            groups.drop(1).all { it.length == 3 } &&
            groups.all { it.all(Char::isDigit) }
        return if (groupedInteger) {
            sign + groups.joinToString(",") + suffix
        } else {
            compact + suffix
        }
    }

    private fun mapCharacter(character: Char): Char = when (character) {
        in '０'..'９' -> ('0'.code + (character.code - '０'.code)).toChar()
        '，' -> ','
        '．' -> '.'
        '％' -> '%'
        '／' -> '/'
        '－', '–', '—', '﹣' -> '-'
        '＋' -> '+'
        else -> character
    }
}
