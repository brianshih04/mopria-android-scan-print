package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import androidx.core.content.edit

/**
 * Reads and writes user preferences (integration mode, print method, scan settings) to
 * SharedPreferences. Extracted from [MopriaViewModel] so the ViewModel focuses on orchestration
 * rather than key-value serialization.
 */
class SettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadIntegrationMode(): IntegrationMode = preferences
        .getString(KEY_INTEGRATION_MODE, IntegrationMode.Mock.name)
        ?.let { value -> runCatching { IntegrationMode.valueOf(value) }.getOrDefault(IntegrationMode.Mock) }
        ?: IntegrationMode.Mock

    fun saveIntegrationMode(mode: IntegrationMode) =
        preferences.edit { putString(KEY_INTEGRATION_MODE, mode.name) }

    fun loadPrintMethod(): PrintMethod = preferences
        .getString(KEY_PRINT_METHOD, PrintMethod.System.name)
        ?.let { value -> runCatching { PrintMethod.valueOf(value) }.getOrDefault(PrintMethod.System) }
        ?: PrintMethod.System

    fun savePrintMethod(method: PrintMethod) =
        preferences.edit { putString(KEY_PRINT_METHOD, method.name) }

    fun loadScanPreset(): ScanPreset = preferences
        .getString(KEY_SCAN_PRESET, ScanPreset.Document.name)
        ?.let { value -> runCatching { ScanPreset.valueOf(value) }.getOrDefault(ScanPreset.Document) }
        ?: ScanPreset.Document

    fun saveScanPreset(preset: ScanPreset) =
        preferences.edit { putString(KEY_SCAN_PRESET, preset.name) }

    fun loadScanSettings(): ScanSettings = ScanSettings(
        inputSource = preferences.getString(KEY_SCAN_INPUT_SOURCE, ScanInputSource.Flatbed.name)
            ?.let { value -> runCatching { ScanInputSource.valueOf(value) }.getOrDefault(ScanInputSource.Flatbed) }
            ?: ScanInputSource.Flatbed,
        resolutionDpi = preferences.getInt(KEY_SCAN_RESOLUTION, 300)
            .takeIf { it in SUPPORTED_RESOLUTIONS }
            ?: 300,
        colorMode = preferences.getString(KEY_SCAN_COLOR_MODE, ScanColorMode.Color.name)
            ?.let { value -> runCatching { ScanColorMode.valueOf(value) }.getOrDefault(ScanColorMode.Color) }
            ?: ScanColorMode.Color,
        maxPages = preferences.getInt(KEY_SCAN_MAX_PAGES, DEFAULT_SCAN_MAX_PAGES)
            .coerceIn(MIN_SCAN_PAGES, MAX_SCAN_PAGES),
        combineAsPdf = preferences.getBoolean(KEY_SCAN_COMBINE_PDF, false),
    )

    fun saveScanSettings(settings: ScanSettings) = preferences.edit {
        putString(KEY_SCAN_INPUT_SOURCE, settings.inputSource.name)
        putInt(KEY_SCAN_RESOLUTION, settings.resolutionDpi)
        putString(KEY_SCAN_COLOR_MODE, settings.colorMode.name)
        putInt(KEY_SCAN_MAX_PAGES, settings.maxPages.coerceIn(MIN_SCAN_PAGES, MAX_SCAN_PAGES))
        putBoolean(KEY_SCAN_COMBINE_PDF, settings.combineAsPdf)
    }

    companion object {
        const val PREFERENCES_NAME = "mopria_settings"
        const val KEY_INTEGRATION_MODE = "integration_mode"
        const val KEY_PRINT_METHOD = "print_method"
        const val KEY_SCAN_INPUT_SOURCE = "scan_input_source"
        const val KEY_SCAN_RESOLUTION = "scan_resolution"
        const val KEY_SCAN_COLOR_MODE = "scan_color_mode"
        const val KEY_SCAN_MAX_PAGES = "scan_max_pages"
        const val KEY_SCAN_COMBINE_PDF = "scan_combine_pdf"
        const val KEY_SCAN_PRESET = "scan_preset"
        const val MIN_SCAN_PAGES = 1
        const val MAX_SCAN_PAGES = 50
        const val DEFAULT_SCAN_MAX_PAGES = 20
        val SUPPORTED_RESOLUTIONS = setOf(150, 300, 600)
    }
}
