package com.brianshih.mopria.android.scanprint.domain

/** Small, dependency-free eSCL XML boundary used by the real provider and JVM fixtures. */
data class EsclCapabilities(
    val documentFormats: List<String> = emptyList(),
    val colorModes: List<String> = emptyList(),
    val resolutions: List<Int> = emptyList(),
    val inputSources: List<String> = emptyList(),
)

object EsclProtocol {
    const val XML_NAMESPACE = "http://schemas.hp.com/imaging/escl/2011/05/03"

    fun parseCapabilities(xml: String): EsclCapabilities = EsclCapabilities(
        documentFormats = values(xml, "DocumentFormat") + values(xml, "DocumentFormatExt") + values(xml, "SupportedDocumentFormats"),
        colorModes = values(xml, "ColorMode"),
        resolutions = (values(xml, "XResolution") + values(xml, "YResolution") + values(xml, "Resolution"))
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .sorted(),
        inputSources = values(xml, "InputSource") + values(xml, "InputSourceType"),
    )

    fun buildScanSettings(
        inputSource: String = "Platen",
        documentFormat: String = "image/jpeg",
        resolution: Int = 300,
        colorMode: String = "RGB24",
    ): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <scan:ScanSettings xmlns:scan="$XML_NAMESPACE">
          <scan:Intent>Document</scan:Intent>
          <scan:InputSource>${escapeXml(inputSource)}</scan:InputSource>
          <scan:DocumentFormatExt>${escapeXml(documentFormat)}</scan:DocumentFormatExt>
          <scan:XResolution>$resolution</scan:XResolution>
          <scan:YResolution>$resolution</scan:YResolution>
          <scan:ColorMode>${escapeXml(colorMode)}</scan:ColorMode>
        </scan:ScanSettings>
    """.trimIndent()

    fun nextDocumentUrl(baseUrl: String, location: String): String {
        val normalizedLocation = location.trim()
        val jobUrl = when {
            normalizedLocation.startsWith("http://") || normalizedLocation.startsWith("https://") -> normalizedLocation
            normalizedLocation.startsWith("/") -> baseUrl.substringBefore("://") + "://" +
                baseUrl.substringAfter("://").substringBefore("/") + normalizedLocation
            else -> baseUrl.trimEnd('/') + "/" + normalizedLocation
        }
        return if (jobUrl.endsWith("/NextDocument", ignoreCase = true)) jobUrl else "$jobUrl/NextDocument"
    }

    private fun values(xml: String, localName: String): List<String> {
        val pattern = Regex(
            "<(?:(?:[A-Za-z_][\\w.-]*):)?$localName(?:\\s[^>]*)?>(.*?)</(?:(?:[A-Za-z_][\\w.-]*):)?$localName>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.findAll(xml).map { unescapeXml(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
