package com.brianshih.mopria.android.scanprint.domain

import java.io.StringReader
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class EsclResolutionSupport(
    val colorMode: String? = null,
    val discreteResolutions: Set<EsclResolution> = emptySet(),
    val minDpi: Int? = null,
    val maxDpi: Int? = null,
    val normalDpi: Int? = null,
    val stepDpi: Int = 1,
    val xRange: EsclAxisResolutionRange? = null,
    val yRange: EsclAxisResolutionRange? = null,
) {
    fun nearest(requested: Int): EsclResolution? {
        val candidates = discreteResolutions.toMutableSet()
        if (xRange != null && yRange != null) {
            candidates += EsclResolution(xRange.nearest(requested), yRange.nearest(requested))
        } else if (minDpi != null && maxDpi != null && minDpi <= maxDpi) {
            val bounded = requested.coerceIn(minDpi, maxDpi)
            val step = stepDpi.coerceAtLeast(1)
            val maxStepIndex = (maxDpi - minDpi) / step
            val stepIndex = ((bounded - minDpi).toDouble() / step).roundToInt().coerceIn(0, maxStepIndex)
            val dpi = minDpi + stepIndex * step
            candidates += EsclResolution(dpi, dpi)
            normalDpi?.takeIf { it in minDpi..maxDpi }?.let { candidates += EsclResolution(it, it) }
        }
        return candidates.minWithOrNull(
            compareBy<EsclResolution> { abs(it.xDpi - requested) + abs(it.yDpi - requested) }
                .thenBy { abs(it.xDpi - it.yDpi) }
                .thenBy { it.xDpi }
                .thenBy { it.yDpi },
        )
    }
}

data class EsclResolution(
    val xDpi: Int,
    val yDpi: Int,
)

data class EsclAxisResolutionRange(
    val minDpi: Int,
    val maxDpi: Int,
    val normalDpi: Int?,
    val stepDpi: Int,
) {
    fun supports(value: Int): Boolean = value in minDpi..maxDpi && (value - minDpi) % stepDpi.coerceAtLeast(1) == 0

    fun nearest(requested: Int): Int {
        val bounded = requested.coerceIn(minDpi, maxDpi)
        val step = stepDpi.coerceAtLeast(1)
        val maxStepIndex = (maxDpi - minDpi) / step
        val stepIndex = ((bounded - minDpi).toDouble() / step).roundToInt().coerceIn(0, maxStepIndex)
        return minDpi + stepIndex * step
    }
}

data class EsclSettingProfile(
    val colorModes: Set<String> = emptySet(),
    val documentFormats: Set<String> = emptySet(),
    val resolutions: List<EsclResolutionSupport> = emptyList(),
) {
    fun supports(colorMode: String, documentFormat: String): Boolean =
        colorModes.any { it.equals(colorMode, true) } &&
            documentFormats.any { it.equals(documentFormat, true) }

    fun nearestResolution(colorMode: String, requested: Int): EsclResolution? {
        val relevant = resolutions.filter { it.colorMode == null || it.colorMode.equals(colorMode, true) }
        val specific = relevant.filter { it.colorMode != null }
        return (specific.ifEmpty { relevant }).mapNotNull { it.nearest(requested) }
            .minWithOrNull(
                compareBy<EsclResolution> { abs(it.xDpi - requested) + abs(it.yDpi - requested) }
                    .thenBy { abs(it.xDpi - it.yDpi) }
                    .thenBy { it.xDpi }
                    .thenBy { it.yDpi },
            )
    }
}

data class EsclInputCapabilities(
    val inputSource: String,
    val profiles: List<EsclSettingProfile>,
    val selectSinglePage: Boolean = false,
)

data class EsclCapabilities(
    val version: String = EsclProtocol.DEFAULT_VERSION,
    val documentFormats: List<String> = emptyList(),
    val colorModes: List<String> = emptyList(),
    val resolutions: List<Int> = emptyList(),
    val inputSources: List<String> = emptyList(),
    val inputs: Map<String, EsclInputCapabilities> = emptyMap(),
)

data class EsclNegotiatedSettings(
    val version: String,
    val inputSource: String,
    val documentFormat: String,
    val resolution: Int,
    val colorMode: String,
    val yResolution: Int = resolution,
    val numberOfPages: Int? = null,
)

data class EsclJobInfo(
    val uri: String,
    val uuid: String?,
    val state: String,
    val stateReasons: List<String>,
    val imagesCompleted: Int,
    val imagesToTransfer: Int?,
)

data class EsclScannerStatus(
    val version: String,
    val state: String,
    val adfState: String?,
    val jobs: List<EsclJobInfo>,
) {
    fun jobFor(jobUrl: String): EsclJobInfo? {
        val expectedPath = runCatching { URI(jobUrl).path.trimEnd('/') }.getOrDefault(jobUrl.trimEnd('/'))
        return jobs.firstOrNull { job ->
            val jobPath = runCatching { URI(job.uri).path.trimEnd('/') }.getOrDefault(job.uri.trimEnd('/'))
            jobPath == expectedPath || expectedPath.endsWith(jobPath) || jobPath.endsWith(expectedPath)
        }
    }
}

/** Namespace-aware, XXE-hardened eSCL XML and URL policy boundary. */
object EsclProtocol {
    const val XML_NAMESPACE = "http://schemas.hp.com/imaging/escl/2011/05/03"
    const val PWG_NAMESPACE = "http://www.pwg.org/schemas/2010/12/sm"
    const val DEFAULT_VERSION = "2.0"
    const val CLIENT_VERSION = "2.97"

    fun parseCapabilities(xml: String): EsclCapabilities {
        val root = parseXml(xml, "ScannerCapabilities")
        val version = requiredVersion(root)
        val sharedProfiles = root.directChild(XML_NAMESPACE, "SettingProfiles")
            ?.directChildren(XML_NAMESPACE, "SettingProfile")
            .orEmpty()
            .mapNotNull { profile -> profile.getAttribute("name").takeIf(String::isNotBlank)?.let { it to parseProfile(profile, version) } }
            .toMap()

        val inputs = linkedMapOf<String, EsclInputCapabilities>()
        parseInput(root, "Platen", "PlatenInputCaps", sharedProfiles)?.let { inputs[it.inputSource] = it }
        parseInput(root, "Feeder", "AdfSimplexInputCaps", sharedProfiles)?.let { inputs[it.inputSource] = it }

        val profiles = inputs.values.flatMap { it.profiles }.ifEmpty { sharedProfiles.values.toList() }
        val formats = profiles.flatMap { it.documentFormats }.distinctBy { it.lowercase(Locale.ROOT) }
        val modes = profiles.flatMap { it.colorModes }.distinctBy { it.lowercase(Locale.ROOT) }
        val resolutions = profiles.flatMap { profile ->
            profile.resolutions.flatMap { support ->
                support.discreteResolutions.flatMap { listOf(it.xDpi, it.yDpi) } +
                    listOfNotNull(support.normalDpi, support.minDpi, support.maxDpi)
            }
        }.distinct().sorted()

        return EsclCapabilities(
            version = version,
            documentFormats = formats,
            colorModes = modes,
            resolutions = resolutions,
            inputSources = inputs.keys.toList(),
            inputs = inputs,
        )
    }

    fun parseScannerStatus(xml: String): EsclScannerStatus {
        val root = parseXml(xml, "ScannerStatus")
        val jobs = root.descendants(XML_NAMESPACE, "JobInfo").map { job ->
            EsclJobInfo(
                uri = job.firstDescendant(PWG_NAMESPACE, "JobUri")?.textValue().orEmpty(),
                uuid = job.firstDescendant(PWG_NAMESPACE, "JobUuid")?.textValue(),
                state = job.firstDescendant(PWG_NAMESPACE, "JobState")?.textValue().orEmpty(),
                stateReasons = job.descendants(PWG_NAMESPACE, "JobStateReason").map { it.textValue() },
                imagesCompleted = job.firstDescendant(PWG_NAMESPACE, "ImagesCompleted")?.textValue()?.toIntOrNull() ?: 0,
                imagesToTransfer = job.firstDescendant(PWG_NAMESPACE, "ImagesToTransfer")?.textValue()?.toIntOrNull(),
            )
        }
        return EsclScannerStatus(
            version = requiredVersion(root),
            state = root.firstDescendant(PWG_NAMESPACE, "State")?.textValue()
                ?.takeIf(String::isNotBlank)
                ?: error("eSCL ScannerStatus 缺少必要的 pwg:State"),
            adfState = root.firstDescendant(XML_NAMESPACE, "AdfState")?.textValue(),
            jobs = jobs,
        )
    }

    fun negotiate(capabilities: EsclCapabilities, settings: ScanSettings): EsclNegotiatedSettings {
        if (settings.inputSource == ScanInputSource.Adf) {
            require(settings.maxPages in 1..MAX_REQUESTED_PAGES) {
                "ADF 掃描頁數必須介於 1 到 $MAX_REQUESTED_PAGES 頁"
            }
        }
        val source = settings.inputSource.eSclValue
        val input = capabilities.inputs.entries.firstOrNull { it.key.equals(source, true) }?.value
            ?: error("掃描器未提供 $source 的 eSCL capabilities")
        val formatPreference = if (settings.colorMode == ScanColorMode.BlackAndWhite) {
            listOf("application/pdf")
        } else {
            listOf("image/jpeg", "application/pdf")
        }
        val selection = formatPreference.firstNotNullOfOrNull { format ->
            input.profiles.firstNotNullOfOrNull { profile ->
                profile.takeIf { it.supports(settings.colorMode.eSclValue, format) }
                    ?.nearestResolution(settings.colorMode.eSclValue, settings.resolutionDpi)
                    ?.let { Triple(profile, format, it) }
            }
        } ?: error("掃描器不支援 ${settings.inputSource.shortLabel}／${settings.colorMode.label} 的 JPEG 或 PDF 組合")
        val resolution = selection.third
        return EsclNegotiatedSettings(
            version = compatibleVersion(capabilities.version),
            inputSource = source,
            documentFormat = selection.second,
            resolution = resolution.xDpi,
            colorMode = settings.colorMode.eSclValue,
            yResolution = resolution.yDpi,
            numberOfPages = settings.maxPages.takeIf { settings.inputSource == ScanInputSource.Adf && input.selectSinglePage },
        )
    }

    fun buildScanSettings(settings: EsclNegotiatedSettings): String {
        val formatElement = if (versionAtLeast(settings.version, 2, 1)) {
            "<scan:DocumentFormatExt>${escapeXml(settings.documentFormat)}</scan:DocumentFormatExt>"
        } else {
            "<pwg:DocumentFormat>${escapeXml(settings.documentFormat)}</pwg:DocumentFormat>"
        }
        val numberOfPages = settings.numberOfPages?.let { "\n  <scan:NumberOfPages>$it</scan:NumberOfPages>" }.orEmpty()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <scan:ScanSettings xmlns:scan="$XML_NAMESPACE" xmlns:pwg="$PWG_NAMESPACE">
              <pwg:Version>${escapeXml(settings.version)}</pwg:Version>
              <scan:Intent>Document</scan:Intent>
              $formatElement
              <pwg:InputSource>${escapeXml(settings.inputSource)}</pwg:InputSource>
              <scan:XResolution>${settings.resolution}</scan:XResolution>
              <scan:YResolution>${settings.yResolution}</scan:YResolution>
              <scan:ColorMode>${escapeXml(settings.colorMode)}</scan:ColorMode>$numberOfPages
            </scan:ScanSettings>
        """.trimIndent()
    }

    fun buildScanSettings(
        inputSource: String = "Platen",
        documentFormat: String = "image/jpeg",
        resolution: Int = 300,
        colorMode: String = "RGB24",
        version: String = "2.97",
    ): String = buildScanSettings(
        EsclNegotiatedSettings(version, inputSource, documentFormat, resolution, colorMode),
    )

    fun buildScanSettings(settings: ScanSettings): String = buildScanSettings(
        inputSource = settings.inputSource.eSclValue,
        documentFormat = if (settings.colorMode == ScanColorMode.BlackAndWhite) "application/pdf" else "image/jpeg",
        resolution = settings.resolutionDpi,
        colorMode = settings.colorMode.eSclValue,
    )

    fun scanJobUrl(baseUrl: String, location: String): String {
        val base = URI(baseUrl.trimEnd('/') + "/")
        val rawJob = base.resolve(URI(location.trim()))
        val job = rawJob.normalize()
        require(rawJob == job) { "eSCL ScanJob Location 不得包含相對路徑跳脫" }
        val sameHost = job.host.equals(base.host, ignoreCase = true)
        val sameTransport = job.scheme.equals(base.scheme, true) && effectivePort(job) == effectivePort(base)
        val secureUpgrade = base.scheme.equals("http", true) && job.scheme.equals("https", true)
        require(sameHost && (sameTransport || secureUpgrade)) {
            "eSCL ScanJob Location 必須與掃描器同源，且只能升級到 HTTPS"
        }
        require(job.userInfo == null && job.query == null && job.fragment == null) {
            "eSCL ScanJob Location 格式不安全"
        }
        val jobPrefix = "${base.path.trimEnd('/')}/ScanJobs/"
        require(job.path.startsWith(jobPrefix) && job.path.removePrefix(jobPrefix).isNotBlank()) {
            "eSCL ScanJob Location 必須位於 {root}/ScanJobs/{job-id}"
        }
        return job.toString().trimEnd('/')
    }

    fun nextDocumentUrl(baseUrl: String, location: String): String {
        val jobUrl = scanJobUrl(baseUrl, location)
        return if (jobUrl.endsWith("/NextDocument", ignoreCase = true)) jobUrl else "$jobUrl/NextDocument"
    }

    private fun parseInput(
        root: Element,
        source: String,
        capsName: String,
        sharedProfiles: Map<String, EsclSettingProfile>,
    ): EsclInputCapabilities? {
        val caps = root.firstDescendant(XML_NAMESPACE, capsName) ?: return null
        val profileElements = caps.directChild(XML_NAMESPACE, "SettingProfiles")
            ?.directChildren(XML_NAMESPACE, "SettingProfile")
            .orEmpty()
        val inlineProfiles = profileElements
            .orEmpty()
            .mapNotNull { profile ->
                val reference = profile.getAttribute("ref").takeIf(String::isNotBlank)
                if (reference == null) parseProfile(profile, requiredVersion(root)) else sharedProfiles[reference]
            }
        val selectSinglePage = source == "Feeder" && root.descendants(XML_NAMESPACE, "AdfOption")
            .any { it.textValue().equals("SelectSinglePage", true) }
        return EsclInputCapabilities(source, inlineProfiles, selectSinglePage)
    }

    private fun parseProfile(profile: Element, version: String): EsclSettingProfile {
        val formats = if (versionAtLeast(version, 2, 1)) {
            profile.descendants(XML_NAMESPACE, "DocumentFormatExt")
        } else {
            profile.descendants(PWG_NAMESPACE, "DocumentFormat")
        }
            .map { it.textValue() }.filter(String::isNotBlank).toSet()
        val modes = profile.descendants(XML_NAMESPACE, "ColorMode").map { it.textValue() }.filter(String::isNotBlank).toSet()
        val resolutions = profile.descendants(XML_NAMESPACE, "SupportedResolutions").mapNotNull(::parseResolutionSupport)
        return EsclSettingProfile(modes, formats, resolutions)
    }

    private fun parseResolutionSupport(element: Element): EsclResolutionSupport? {
        val colorMode = element.directChild(XML_NAMESPACE, "ColorMode")?.textValue()
        val discrete = element.descendants(XML_NAMESPACE, "DiscreteResolution").mapNotNull { resolution ->
            val x = resolution.firstDescendant(XML_NAMESPACE, "XResolution")?.textValue()?.toIntOrNull()
            val y = resolution.firstDescendant(XML_NAMESPACE, "YResolution")?.textValue()?.toIntOrNull()
            if (x != null && y != null && x > 0 && y > 0) EsclResolution(x, y) else null
        }.toSet()
        val xRange = element.firstDescendant(XML_NAMESPACE, "XResolutionRange")
        val yRange = element.firstDescendant(XML_NAMESPACE, "YResolutionRange")
        val xMin = xRange?.directChild(XML_NAMESPACE, "Min")?.textValue()?.toIntOrNull()
        val yMin = yRange?.directChild(XML_NAMESPACE, "Min")?.textValue()?.toIntOrNull()
        val xMax = xRange?.directChild(XML_NAMESPACE, "Max")?.textValue()?.toIntOrNull()
        val yMax = yRange?.directChild(XML_NAMESPACE, "Max")?.textValue()?.toIntOrNull()
        val min = listOfNotNull(xMin, yMin).maxOrNull()
        val max = listOfNotNull(xMax, yMax).minOrNull()
        val xNormal = xRange?.directChild(XML_NAMESPACE, "Normal")?.textValue()?.toIntOrNull()
        val yNormal = yRange?.directChild(XML_NAMESPACE, "Normal")?.textValue()?.toIntOrNull()
        val normal = xNormal?.takeIf { it == yNormal }
        val xStep = xRange?.directChild(XML_NAMESPACE, "Step")?.textValue()?.toIntOrNull()
        val yStep = yRange?.directChild(XML_NAMESPACE, "Step")?.textValue()?.toIntOrNull()
        val step = if (xStep != null && xStep == yStep) xStep else 1
        val parsedXRange = axisRange(xRange)
        val parsedYRange = axisRange(yRange)
        return EsclResolutionSupport(
            colorMode = colorMode,
            discreteResolutions = discrete,
            minDpi = min,
            maxDpi = max,
            normalDpi = normal,
            stepDpi = step.coerceAtLeast(1),
            xRange = parsedXRange,
            yRange = parsedYRange,
        )
            .takeIf { it.discreteResolutions.isNotEmpty() || (it.xRange != null && it.yRange != null) }
    }

    private fun axisRange(element: Element?): EsclAxisResolutionRange? {
        val min = element?.directChild(XML_NAMESPACE, "Min")?.textValue()?.toIntOrNull() ?: return null
        val max = element.directChild(XML_NAMESPACE, "Max")?.textValue()?.toIntOrNull() ?: return null
        val normal = element.directChild(XML_NAMESPACE, "Normal")?.textValue()?.toIntOrNull()
        val step = element.directChild(XML_NAMESPACE, "Step")?.textValue()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return EsclAxisResolutionRange(min, max, normal, step).takeIf { min > 0 && max >= min }
    }

    private fun parseXml(xml: String, expectedRoot: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val root = runCatching { factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement }
            .getOrElse { error -> throw IllegalArgumentException("無法解析 eSCL $expectedRoot XML", error) }
        require(root.localTag().equals(expectedRoot, true)) { "eSCL XML root 必須是 $expectedRoot" }
        require(root.namespaceURI == XML_NAMESPACE) { "eSCL $expectedRoot root namespace 不正確" }
        return root
    }

    private fun requiredVersion(root: Element): String = root.directChild(PWG_NAMESPACE, "Version")
        ?.textValue()
        ?.takeIf(String::isNotBlank)
        ?.also { require(VERSION_PATTERN.matches(it)) { "eSCL ${root.localTag()} 的 pwg:Version 格式無效：$it" } }
        ?: error("eSCL ${root.localTag()} 缺少必要的 pwg:Version")

    private fun Element.localTag(): String = localName ?: tagName.substringAfter(':')
    private fun Element.textValue(): String = textContent.orEmpty().trim()
    private fun Element.directChildren(namespace: String, name: String): List<Element> = childNodes.asElements()
        .filter { it.namespaceURI == namespace && it.localTag().equals(name, true) }
    private fun Element.directChild(namespace: String, name: String): Element? = directChildren(namespace, name).firstOrNull()
    private fun Element.descendants(namespace: String, name: String): List<Element> = getElementsByTagNameNS(namespace, name).asElements()
    private fun Element.firstDescendant(namespace: String, name: String): Element? = descendants(namespace, name).firstOrNull()
    private fun org.w3c.dom.NodeList.asElements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

    private fun versionAtLeast(value: String, major: Int, minor: Int): Boolean {
        val parts = value.split('.')
        val actualMajor = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val actualMinor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return actualMajor > major || (actualMajor == major && actualMinor >= minor)
    }

    private fun compatibleVersion(providerVersion: String): String {
        require(versionAtLeast(providerVersion, 2, 0)) { "掃描器 eSCL 版本無效或低於 2.0：$providerVersion" }
        return if (compareVersions(providerVersion, CLIENT_VERSION) <= 0) providerVersion else CLIENT_VERSION
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
        return (0 until maxOf(leftParts.size, rightParts.size))
            .firstNotNullOfOrNull { index ->
                (leftParts.getOrElse(index) { 0 } - rightParts.getOrElse(index) { 0 }).takeIf { it != 0 }
            }
            ?: 0
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private val VERSION_PATTERN = Regex("^[0-9]+(?:\\.[0-9]+)+$")
    private const val MAX_REQUESTED_PAGES = 50
}
