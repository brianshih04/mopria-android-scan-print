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

data class ScanSettings(
    val inputSource: ScanInputSource = ScanInputSource.Flatbed,
    val resolutionDpi: Int = 300,
    val colorMode: ScanColorMode = ScanColorMode.Color,
    val maxPages: Int = 20,
    val combineAsPdf: Boolean = false,
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

data class DocumentPage(
    val id: String,
    val pageNumber: Int,
    val title: String,
    val imagePath: String? = null,
    val pdfPath: String? = null,
    val pdfPageIndex: Int? = null,
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
