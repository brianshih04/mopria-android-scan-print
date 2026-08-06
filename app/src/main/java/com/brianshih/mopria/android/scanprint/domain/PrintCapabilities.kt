package com.brianshih.mopria.android.scanprint.domain

/**
 * Direct IPP print capabilities advertised by a printer (from Get-Printer-Attributes).
 *
 * Holds only the option surfaces this app exposes — `copies`, `media`, `sides`, color mode, quality and
 * orientation. Lists are the raw IPP keywords (e.g. `"two-sided-long-edge"`, `"iso_a4_210x297mm"`); the
 * UI maps them to localized labels. Empty lists mean the printer did not advertise that option, so the
 * UI hides it and the client omits the attribute (PWG: sending an unsupported attribute is rejected or
 * silently ignored). See DIRECT_IPP_FOLLOWUPS.md P2.9.
 */
data class PrintCapabilities(
    val copies: IntRange,
    val media: List<String>,
    val sides: List<String>,
    val colorModes: List<String>,
    val qualities: List<String>,
    val orientations: List<String>,
) {
    /** Whether any printable option is actually configurable for this printer. */
    fun hasAnyOption(): Boolean =
        copies.last > 1 || media.isNotEmpty() || sides.isNotEmpty() || colorModes.isNotEmpty() || qualities.isNotEmpty() || orientations.isNotEmpty()

    companion object {
        /** Sentinel for a printer that advertised nothing (or Get-Printer-Attributes failed): no options, copies fixed at 1. */
        val EMPTY = PrintCapabilities(1..1, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * User-chosen Direct IPP job options. Every field is nullable and is only sent when the printer's
 * [PrintCapabilities] actually advertises the value; use [coerceTo] to drop unsupported selections
 * before submission so Create-Job never carries an attribute the printer rejects.
 */
data class PrintOptions(
    val copies: Int? = null,
    val media: String? = null,
    val sides: String? = null,
    val colorMode: String? = null,
    val quality: String? = null,
    val orientation: String? = null,
) {
    /** Returns a copy with every choice constrained to what [capabilities] advertises; unsupported values become null. */
    fun coerceTo(capabilities: PrintCapabilities): PrintOptions = PrintOptions(
        copies = copies?.takeIf { it in capabilities.copies },
        media = media?.takeIf { it in capabilities.media },
        sides = sides?.takeIf { it in capabilities.sides },
        colorMode = colorMode?.takeIf { it in capabilities.colorModes },
        quality = quality?.takeIf { it in capabilities.qualities },
        orientation = orientation?.takeIf { it in capabilities.orientations },
    )
}
