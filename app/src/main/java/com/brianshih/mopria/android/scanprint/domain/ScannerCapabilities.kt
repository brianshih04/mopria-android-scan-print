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
    val supportedColorModes: Set<ScanColorMode> = ScanColorMode.entries.toSet(),
    val maxAdfPages: Int = 50,
) {
    companion object {
        /** Default capabilities used in Mock mode — everything is available. */
        val DEFAULT = ScannerCapabilities()

        /**
         * Build from parsed eSCL capabilities. Maps raw protocol values to app enums,
         * keeping only resolutions the app offers (150/300/600) that the scanner also supports.
         */
        fun fromEscl(escl: EsclCapabilities): ScannerCapabilities {
            val appResolutions = setOf(150, 300, 600)
            val esclResolutions = escl.resolutions.toSet()
            // Intersect: only show dpi values the app offers AND the scanner supports.
            val resolutions = appResolutions.intersect(esclResolutions).let {
                if (it.isEmpty()) appResolutions else it
            }
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
            return ScannerCapabilities(
                supportedResolutions = resolutions,
                supportedSources = sources,
                supportedColorModes = colorModes,
            )
        }
    }
}
