package com.brianshih.mopria.android.scanprint.domain

import kotlinx.coroutines.delay

/**
 * A deterministic integration seam for emulator-first development.
 * Real eSCL discovery and Android Print Framework adapters will implement
 * the same operations without changing the UI state model.
 */
class MockIntegrationProvider : DeviceDiscovery, ScanAcquisitionProvider, PrintProvider {
    private val devices = listOf(
        IntegrationDevice(
            id = "mock-scanner-01",
            name = "Demo eSCL Scanner",
            kind = DeviceKind.Scanner,
            protocol = "eSCL / AirScan",
        ),
        IntegrationDevice(
            id = "mock-printer-01",
            name = "Demo Mopria Printer",
            kind = DeviceKind.Printer,
            protocol = "Android Print Framework",
        ),
    )

    override suspend fun discover(manualDeviceAddress: String?): List<IntegrationDevice> {
        delay(650)
        return devices
    }

    suspend fun discover(): List<IntegrationDevice> = discover(null)

    override suspend fun scan(
        scanner: IntegrationDevice,
        settings: ScanSettings,
        onProgress: (ScanProgress) -> Unit,
    ): MopriaDocument {
        onProgress(ScanProgress(ScanProgressStage.Preparing))
        delay(850)
        val timestamp = System.currentTimeMillis()
        val pageCount = if (settings.inputSource == ScanInputSource.Flatbed) 1 else settings.maxPages.coerceIn(1, 50)
        onProgress(ScanProgress(ScanProgressStage.Downloading, pageCount, pageCount))
        return MopriaDocument(
            id = "scan-$timestamp",
            name = "Mock scan document ${timestamp.toString().takeLast(4)}",
            sourceLabel = "${scanner.name} · %mock · ${settings.inputSource.displayToken} · ${settings.resolutionDpi} dpi · ${settings.colorMode.displayToken}",
            pages = (1..pageCount).map { page ->
                DocumentPage(
                    "$timestamp-page-$page",
                    page,
                    if (page == 1) "Cover and summary" else if (page == 2) "Content page" else "Appendix",
                    generatedTitle = when (page) {
                        1 -> GeneratedPageTitle.Cover
                        2 -> GeneratedPageTitle.Content
                        else -> GeneratedPageTitle.Appendix
                    },
                )
            },
            createdAt = timestamp,
            documentSize = settings.documentSize,
            generatedName = GeneratedDocumentName.MockScan,
            generatedNameSuffix = timestamp.toString().takeLast(4),
        )
    }

    override suspend fun print(printer: IntegrationDevice, document: MopriaDocument, options: PrintOptions?) {
        delay(900)
    }

    /** Fixture capabilities so the Direct IPP options sheet is exercisable in Mock mode. */
    override suspend fun capabilities(printer: IntegrationDevice): PrintCapabilities = PrintCapabilities(
        copies = 1..10,
        media = listOf("iso_a4_210x297mm", "na_letter_8.5x11in"),
        sides = listOf("one-sided", "two-sided-long-edge"),
        colorModes = listOf("color", "monochrome"),
        qualities = listOf("draft", "normal", "high"),
        orientations = listOf("portrait", "landscape"),
    )
}
