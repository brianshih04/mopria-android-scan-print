package com.brianshih.mopria.android.scanprint.domain

enum class DeviceKind(val label: String) {
    Scanner("掃描器"),
    Printer("印表機"),
}

enum class IntegrationMode(val label: String, val description: String) {
    Mock("模擬模式", "使用內建 fixture，不需要實體設備"),
    Real("真實模式", "使用區域網路探索實體設備"),
}

enum class ScanInputSource(
    val label: String,
    val shortLabel: String,
    val description: String,
    val eSclValue: String,
    val maxPages: Int,
) {
    Flatbed("Flatbed 單頁", "Flatbed", "放在平面玻璃上，掃描一頁", "Platen", 1),
    Adf("ADF 多頁", "ADF", "由自動送稿器連續掃描多頁", "ADF", 20),
}

enum class ScanColorMode(val label: String, val eSclValue: String) {
    Color("彩色", "RGB24"),
    Grayscale("灰階", "Grayscale8"),
    BlackAndWhite("黑白", "BlackAndWhite1"),
}

data class ScanSettings(
    val inputSource: ScanInputSource = ScanInputSource.Flatbed,
    val resolutionDpi: Int = 300,
    val colorMode: ScanColorMode = ScanColorMode.Color,
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
)

data class DocumentPage(
    val id: String,
    val pageNumber: Int,
    val title: String,
    val imagePath: String? = null,
)

enum class ScanOutputFormat(val label: String, val extension: String) {
    Pdf("PDF", "pdf"),
    Jpeg("JPEG", "jpg"),
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

enum class JobKind(val label: String) {
    Scan("掃描"),
    Print("列印"),
    Export("匯出"),
}

enum class JobStatus(val label: String) {
    Queued("排隊中"),
    Running("處理中"),
    Completed("已完成"),
    Failed("失敗"),
    Cancelled("已取消"),
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
    val scanSettings: ScanSettings = ScanSettings(),
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
