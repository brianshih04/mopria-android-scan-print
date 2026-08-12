package com.brianshih.mopria.android.scanprint.domain

/**
 * UI-facing summary of what a scanner supports, derived from eSCL capabilities.
 *
 * In Real mode, this is fetched from the scanner after discovery and used by [ScanScreen]
 * to show only supported resolution/color/source options. In Mock mode, it defaults to
 * the full set of app-defined options (all resolutions, both sources, all color modes).
 */
data class ScannerCapabilities(
    val supportedResolutions: Set<Int> = setOf(150, 300, 600),
    val supportedSources: Set<ScanInputSource> = ScanInputSource.entries.toSet(),
    val supportedAdfModes: Set<ScanAdfMode> = ScanAdfMode.entries.toSet(),
    val supportedColorModes: Set<ScanColorMode> = ScanColorMode.entries.toSet(),
    val maxAdfPages: Int = 50,
) {
    /** Coerce persisted/UI settings to values the current scanner actually advertises. */
    fun reconcile(settings: ScanSettings): ScanSettings {
        val supported = supportedResolutions
            .filter { it > 0 }
        val ocrResolutions = if (settings.ocrMode != OcrMode.Disabled) {
            supported.filter { it <= MAX_OCR_DPI }
        } else {
            supported
        }
        val resolutionPool = ocrResolutions.ifEmpty { supported }
        val resolution = resolutionPool.let { available ->
            val minimumDistance = available.minOfOrNull { kotlin.math.abs(it - settings.resolutionDpi) }
            available.filter { kotlin.math.abs(it - settings.resolutionDpi) == minimumDistance }.maxOrNull()
            }
            ?: settings.resolutionDpi
        val source = settings.inputSource.takeIf(supportedSources::contains)
            ?: ScanInputSource.Flatbed.takeIf(supportedSources::contains)
            ?: supportedSources.firstOrNull()
            ?: settings.inputSource
        val adfMode = settings.adfMode.takeIf(supportedAdfModes::contains)
            ?: ScanAdfMode.Simplex.takeIf(supportedAdfModes::contains)
            ?: supportedAdfModes.firstOrNull()
            ?: settings.adfMode
        val requestedOcr = settings.ocrMode != OcrMode.Disabled
        val ocrMode = if (requestedOcr && ocrResolutions.isNotEmpty()) settings.ocrMode else OcrMode.Disabled
        val colorMode = if (ocrMode != OcrMode.Disabled && ScanColorMode.Grayscale in supportedColorModes) {
            ScanColorMode.Grayscale
        } else settings.colorMode.takeIf(supportedColorModes::contains)
            ?: ScanColorMode.Color.takeIf(supportedColorModes::contains)
            ?: supportedColorModes.firstOrNull()
            ?: settings.colorMode
        return settings.copy(
            inputSource = source,
            adfMode = adfMode,
            resolutionDpi = resolution,
            colorMode = colorMode,
            maxPages = settings.maxPages.coerceIn(1, maxAdfPages.coerceAtLeast(1)),
            ocrMode = ocrMode,
        ).enforceProcessingSafety()
    }

    companion object {
        /** Default capabilities used in Mock mode — everything is available. */
        val DEFAULT = ScannerCapabilities()

        /**
         * Build from parsed eSCL capabilities. Maps raw protocol values to app enums and keeps
         * every sane scanner-advertised resolution so the UI never offers an unsupported DPI.
         */
        fun fromEscl(escl: EsclCapabilities): ScannerCapabilities {
            val appResolutions = setOf(150, 300, 600)
            val resolutions = escl.resolutions
                .filter { it in SettingsStore.MIN_SCAN_RESOLUTION..SettingsStore.MAX_SCAN_RESOLUTION }
                .toSet()
                .ifEmpty { appResolutions }
            val sources = buildSet {
                if (escl.inputSources.any { it.equals("Platen", true) }) add(ScanInputSource.Flatbed)
                if (escl.inputSources.any { it.equals("Feeder", true) }) add(ScanInputSource.Adf)
                if (isEmpty()) addAll(ScanInputSource.entries)
            }
            val colorModes = buildSet {
                escl.colorModes.forEach { mode ->
                    when {
                        mode.equals("RGB24", true) -> add(ScanColorMode.Color)
                        mode.equals("Grayscale8", true) -> add(ScanColorMode.Grayscale)
                        mode.equals("BlackAndWhite1", true) -> add(ScanColorMode.BlackAndWhite)
                    }
                }
                if (isEmpty()) addAll(ScanColorMode.entries)
            }
            val hasFeeder = escl.inputSources.any { it.equals("Feeder", true) }
            val adfModes = buildSet {
                if (hasFeeder && (escl.adfSimplexInput != null || escl.adfDuplexInput == null)) {
                    add(ScanAdfMode.Simplex)
                }
                if (escl.adfDuplexInput != null) add(ScanAdfMode.Duplex)
            }
            return ScannerCapabilities(
                supportedResolutions = resolutions,
                supportedSources = sources,
                supportedAdfModes = adfModes,
                supportedColorModes = colorModes,
            )
        }
    }
}
