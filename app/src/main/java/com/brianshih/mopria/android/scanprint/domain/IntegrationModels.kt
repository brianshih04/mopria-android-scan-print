package com.brianshih.mopria.android.scanprint.domain

import androidx.annotation.StringRes
import com.brianshih.mopria.android.scanprint.R

enum class DeviceKind(@StringRes val labelRes: Int) {
    Scanner(R.string.device_scanner),
    Printer(R.string.device_printer),
}

enum class IntegrationMode(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    Mock(R.string.mode_mock, R.string.mode_mock_description),
    Real(R.string.mode_real, R.string.mode_real_description),
}

/** How Real-mode printing is delivered: Android Print Framework (Mopria) or direct IPP/IPPS. */
enum class PrintMethod(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    System(R.string.print_method_system, R.string.print_method_system_description),
    Ipp(R.string.print_method_ipp, R.string.print_method_ipp_description),
}

enum class ScanInputSource(
    @StringRes val labelRes: Int,
    @StringRes val shortLabelRes: Int,
    @StringRes val descriptionRes: Int,
    val eSclValue: String,
) {
    Flatbed(R.string.source_flatbed, R.string.source_flatbed_short, R.string.source_flatbed_description, "Platen"),
    Adf(R.string.source_adf, R.string.source_adf_short, R.string.source_adf_description, "Feeder"),
}

enum class ScanColorMode(@StringRes val labelRes: Int, val eSclValue: String) {
    Color(R.string.color_color, "RGB24"),
    Grayscale(R.string.color_grayscale, "Grayscale8"),
    BlackAndWhite(R.string.color_black_white, "BlackAndWhite1"),
}


/**
 * Enhancement strength for background cleanup. Higher levels apply more aggressive
 * contrast boost and thresholding, producing whiter backgrounds at the cost of
 * potentially losing very faint content.
 */
enum class EnhancementStrength(@StringRes val labelRes: Int) {
    Light(R.string.enhance_light),
    Normal(R.string.enhance_normal),
    Strong(R.string.enhance_strong),
}

/** Optional on-device OCR backend. ML Kit keeps script models outside the app when available. */
enum class OcrMode(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    Disabled(R.string.ocr_disabled, R.string.ocr_disabled_description),
    MlKit(R.string.ocr_ml_kit, R.string.ocr_ml_kit_description),
}

/**
 * Quick-scan presets that auto-configure [ScanSettings] for common use cases.
 *
 * The user picks a purpose (document vs photo) and gets sensible defaults; individual
 * settings can still be fine-tuned below the preset selector.
 */
enum class ScanPreset(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    Document(R.string.preset_document, R.string.preset_document_description),
    Photo(R.string.preset_photo, R.string.preset_photo_description);

    /**
     * The default [ScanSettings] for this preset. The caller (ViewModel) intersects
     * these with the scanner's [ScannerCapabilities] to ensure only supported values are used.
     */
    fun defaultSettings(current: ScanSettings): ScanSettings = when (this) {
        Document -> current.copy(
            resolutionDpi = 300,
            colorMode = ScanColorMode.Color,
            combineAsPdf = true,
            searchablePdf = false,
            enhanceBackground = EnhancementStrength.Normal,
            ocrMode = OcrMode.Disabled,
            deskew = false,
            autoCrop = false,
            dropBlankPages = false,
        )
        Photo -> current.copy(
            resolutionDpi = 600,
            colorMode = ScanColorMode.Color,
            combineAsPdf = false,
            searchablePdf = false,
            enhanceBackground = null,
            ocrMode = OcrMode.Disabled,
            deskew = false,
            autoCrop = false,
            dropBlankPages = false,
        )
    }
}

data class ScanSettings(
    val inputSource: ScanInputSource = ScanInputSource.Flatbed,
    val resolutionDpi: Int = 300,
    val colorMode: ScanColorMode = ScanColorMode.Color,
    val maxPages: Int = 20,
    val combineAsPdf: Boolean = true,
    /** Adds an invisible OCR text layer to the PDF only when the user explicitly opts in. */
    val searchablePdf: Boolean = false,
    val enhanceBackground: EnhancementStrength? = EnhancementStrength.Normal,
    val ocrMode: OcrMode = OcrMode.Disabled,
    val ocrLanguage: OcrLanguagePack = OcrLanguagePack.SimplifiedChinese,
    val deskew: Boolean = false,
    val autoCrop: Boolean = false,
    val dropBlankPages: Boolean = false,
)

/** Background cleanup is intentionally limited to 300 dpi and below until memory soak passes. */
fun ScanSettings.enforceEnhancementSafety(): ScanSettings = if (
    resolutionDpi > MAX_ENHANCEMENT_DPI && enhanceBackground != null
) copy(enhanceBackground = null) else this

/** OCR is kept at or below 300 dpi until the selected ML Kit path has a measured memory budget. */
fun ScanSettings.enforceProcessingSafety(): ScanSettings {
    val safeEnhancement = enforceEnhancementSafety()
    val safeOcr = if (safeEnhancement.ocrMode != OcrMode.Disabled && safeEnhancement.resolutionDpi > MAX_OCR_DPI) {
        safeEnhancement.copy(ocrMode = OcrMode.Disabled)
    } else {
        safeEnhancement
    }
    return safeOcr.takeIf { it.ocrMode != OcrMode.Disabled }
        ?: safeOcr.copy(searchablePdf = false)
}

const val MAX_ENHANCEMENT_DPI = 300
const val MAX_OCR_DPI = 300

enum class ScanProgressStage {
    Preparing,
    Downloading,
    Processing,
    Enhancing,
    Ocr,
}

data class ScanProgress(
    val stage: ScanProgressStage,
    val completedPages: Int = 0,
    val totalPages: Int? = null,
)

data class IntegrationDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val protocol: String,
    val isMock: Boolean = true,
    val host: String? = null,
    val port: Int? = null,
    val secure: Boolean = false,
    val serviceType: String? = null,
    val resourcePath: String = "eSCL",
    val uuid: String? = null,
    val esclVersion: String? = null,
    val advertisedFormats: List<String> = emptyList(),
)

/**
 * Crop region as normalized fractions (0.0 to 1.0) relative to the original page dimensions.
 * null means no cropping. Used by [DocumentEditor.cropPage] and the UI to apply a crop overlay.
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) {
            "Crop fractions must be 0..1"
        }
        require(left < right && top < bottom) { "left must be < right and top < bottom" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class DocumentPage(
    val id: String,
    val pageNumber: Int,
    val title: String,
    val imagePath: String? = null,
    val pdfPath: String? = null,
    val pdfPageIndex: Int? = null,
    val rotationDegrees: Int = 0,
    val cropRect: CropRect? = null,
    val ocrResult: OcrResult? = null,
)

enum class ScanOutputFormat(@StringRes val labelRes: Int, val extension: String) {
    Pdf(R.string.format_pdf, "pdf"),
    Jpeg(R.string.format_jpeg, "jpg"),
}

data class SavedScanFile(
    val format: ScanOutputFormat,
    val displayName: String,
    val location: String,
)

data class MopriaDocument(
    val id: String,
    val name: String,
    val pages: List<DocumentPage>,
    val sourceLabel: String,
    val createdAt: Long = System.currentTimeMillis(),
    val exportedPath: String? = null,
    val savedFiles: List<SavedScanFile> = emptyList(),
    val enhancementResults: List<EnhancementResult> = emptyList(),
    val imageProcessingResults: List<ScanImageResult> = emptyList(),
    val ocrResults: List<OcrResult> = emptyList(),
    /** Whether the user opted in to an OCR text layer when exporting this document as PDF. */
    val searchablePdf: Boolean = false,
)

enum class JobKind(@StringRes val labelRes: Int) {
    Scan(R.string.job_scan),
    Print(R.string.job_print),
    Export(R.string.job_export),
}

enum class JobStatus(@StringRes val labelRes: Int) {
    Queued(R.string.status_queued),
    Running(R.string.status_running),
    Completed(R.string.status_completed),
    Failed(R.string.status_failed),
    Cancelled(R.string.status_cancelled),
}

data class JobRecord(
    val id: String,
    val kind: JobKind,
    val title: String,
    val targetLabel: String,
    val status: JobStatus,
    val progress: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val detail: String? = null,
)

data class MopriaUiState(
    val isDiscovering: Boolean = false,
    val activeJobId: String? = null,
    val devices: List<IntegrationDevice> = emptyList(),
    val documents: List<MopriaDocument> = emptyList(),
    val jobs: List<JobRecord> = emptyList(),
    val selectedDocumentId: String? = null,
    val lastExportPath: String? = null,
    val integrationMode: IntegrationMode = IntegrationMode.Mock,
    val printMethod: PrintMethod = PrintMethod.System,
    val scanSettings: ScanSettings = ScanSettings(),
    val pendingFlatbedDocumentId: String? = null,
    val awaitingNextFlatbedPage: Boolean = false,
    val directIppPrintPrompt: DirectIppPrintPrompt? = null,
    val scannerCapabilities: ScannerCapabilities = ScannerCapabilities.DEFAULT,
    val scanPreset: ScanPreset = ScanPreset.Document,
    val ocrLanguagePacks: List<OcrLanguagePackState> = OcrLanguagePack.entries.map { language ->
        OcrLanguagePackState(
            language = language,
            selected = language.defaultSelected,
            active = language == OcrLanguagePack.SimplifiedChinese,
            status = OcrLanguagePackStatus.ManagedByMlKit,
        )
    },
) {
    val mockMode: Boolean
        get() = integrationMode == IntegrationMode.Mock

    val isBusy: Boolean
        get() = isDiscovering || activeJobId != null

    val scanners: List<IntegrationDevice>
        get() = devices.filter { it.kind == DeviceKind.Scanner }

    val printers: List<IntegrationDevice>
        get() = devices.filter { it.kind == DeviceKind.Printer }
}

/**
 * Pending Direct IPP print awaiting the user's option choices. Shown as a sheet; [confirmDirectIppPrint]
 * submits with the chosen [options], [dismissDirectIppPrintPrompt] cancels.
 */
data class DirectIppPrintPrompt(
    val document: MopriaDocument,
    val printer: IntegrationDevice,
    val capabilities: PrintCapabilities,
    val options: PrintOptions = PrintOptions(),
)
