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

    fun loadScanSettings(): ScanSettings {
        val defaults = loadScanPreset().defaultSettings(ScanSettings())
        return ScanSettings(
        inputSource = preferences.getString(KEY_SCAN_INPUT_SOURCE, defaults.inputSource.name)
            ?.let { value -> runCatching { ScanInputSource.valueOf(value) }.getOrDefault(ScanInputSource.Flatbed) }
            ?: defaults.inputSource,
        resolutionDpi = preferences.getInt(KEY_SCAN_RESOLUTION, defaults.resolutionDpi)
            .takeIf { it in MIN_SCAN_RESOLUTION..MAX_SCAN_RESOLUTION }
            ?: defaults.resolutionDpi,
        colorMode = preferences.getString(KEY_SCAN_COLOR_MODE, defaults.colorMode.name)
            ?.let { value -> runCatching { ScanColorMode.valueOf(value) }.getOrDefault(ScanColorMode.Color) }
            ?: defaults.colorMode,
        maxPages = preferences.getInt(KEY_SCAN_MAX_PAGES, DEFAULT_SCAN_MAX_PAGES)
            .coerceIn(MIN_SCAN_PAGES, MAX_SCAN_PAGES),
        combineAsPdf = preferences.getBoolean(KEY_SCAN_COMBINE_PDF, defaults.combineAsPdf),
        searchablePdf = preferences.getBoolean(KEY_SCAN_SEARCHABLE_PDF, defaults.searchablePdf),
        enhanceBackground = preferences.getString(KEY_SCAN_ENHANCE_BACKGROUND, defaults.enhanceBackground?.name)
            ?.takeUnless { it == ENHANCEMENT_DISABLED }
            ?.let { value -> runCatching { EnhancementStrength.valueOf(value) }.getOrNull() },
        ocrMode = preferences.getString(KEY_SCAN_OCR_MODE, defaults.ocrMode.name)
            ?.let { value -> runCatching { OcrMode.valueOf(value) }.getOrDefault(OcrMode.Disabled) }
            ?: defaults.ocrMode,
        ocrLanguage = loadActiveOcrLanguage(),
        deskew = preferences.getBoolean(KEY_SCAN_DESKEW, defaults.deskew),
        autoCrop = preferences.getBoolean(KEY_SCAN_AUTO_CROP, defaults.autoCrop),
        dropBlankPages = preferences.getBoolean(KEY_SCAN_DROP_BLANK_PAGES, defaults.dropBlankPages),
        ).enforceProcessingSafety()
    }

    fun saveScanSettings(settings: ScanSettings) = preferences.edit {
        putString(KEY_SCAN_INPUT_SOURCE, settings.inputSource.name)
        putInt(KEY_SCAN_RESOLUTION, settings.resolutionDpi)
        putString(KEY_SCAN_COLOR_MODE, settings.colorMode.name)
        putInt(KEY_SCAN_MAX_PAGES, settings.maxPages.coerceIn(MIN_SCAN_PAGES, MAX_SCAN_PAGES))
        putBoolean(KEY_SCAN_COMBINE_PDF, settings.combineAsPdf)
        putBoolean(KEY_SCAN_SEARCHABLE_PDF, settings.searchablePdf)
        putString(KEY_SCAN_ENHANCE_BACKGROUND, settings.enhanceBackground?.name ?: ENHANCEMENT_DISABLED)
        putString(KEY_SCAN_OCR_MODE, settings.ocrMode.name)
        putString(KEY_SCAN_OCR_LANGUAGE, settings.ocrLanguage.name)
        putBoolean(KEY_SCAN_DESKEW, settings.deskew)
        putBoolean(KEY_SCAN_AUTO_CROP, settings.autoCrop)
        putBoolean(KEY_SCAN_DROP_BLANK_PAGES, settings.dropBlankPages)
    }

    fun loadOcrLanguagePacks(): Set<OcrLanguagePack> = preferences
        .getStringSet(KEY_OCR_LANGUAGE_PACKS, null)
        ?.mapNotNull(OcrLanguagePack::fromStoredValue)
        ?.filterTo(linkedSetOf()) { it.model != OcrLanguageModel.Unsupported }
        ?.takeIf { it.isNotEmpty() }
        ?: OcrLanguagePack.defaultSelection

    fun saveOcrLanguagePacks(languages: Set<OcrLanguagePack>) {
        val safeLanguages = languages
            .filterTo(linkedSetOf()) { it.model != OcrLanguageModel.Unsupported }
            .ifEmpty { OcrLanguagePack.defaultSelection }
        preferences.edit {
            putStringSet(KEY_OCR_LANGUAGE_PACKS, safeLanguages.map { it.name }.toSet())
        }
    }

    fun loadActiveOcrLanguage(): OcrLanguagePack {
        val selected = loadOcrLanguagePacks()
        val stored = OcrLanguagePack.fromStoredValue(
            preferences.getString(KEY_SCAN_OCR_LANGUAGE, null),
        )
        return stored?.takeIf { it in selected } ?: when {
            OcrLanguagePack.SimplifiedChinese in selected -> OcrLanguagePack.SimplifiedChinese
            else -> OcrLanguagePack.entries.first { it in selected }
        }
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
        const val KEY_SCAN_SEARCHABLE_PDF = "scan_searchable_pdf"
        const val KEY_SCAN_PRESET = "scan_preset"
        const val KEY_SCAN_ENHANCE_BACKGROUND = "scan_enhance_background"
        const val KEY_SCAN_OCR_MODE = "scan_ocr_mode"
        const val KEY_SCAN_OCR_LANGUAGE = "scan_ocr_language"
        const val KEY_OCR_LANGUAGE_PACKS = "ocr_language_packs"
        const val KEY_SCAN_DESKEW = "scan_deskew"
        const val KEY_SCAN_AUTO_CROP = "scan_auto_crop"
        const val KEY_SCAN_DROP_BLANK_PAGES = "scan_drop_blank_pages"
        const val MIN_SCAN_PAGES = 1
        const val MAX_SCAN_PAGES = 50
        const val DEFAULT_SCAN_MAX_PAGES = 20
        const val MIN_SCAN_RESOLUTION = 75
        const val MAX_SCAN_RESOLUTION = 1_200
        const val ENHANCEMENT_DISABLED = "Disabled"
        val SUPPORTED_RESOLUTIONS = setOf(150, 300, 600)
    }
}
