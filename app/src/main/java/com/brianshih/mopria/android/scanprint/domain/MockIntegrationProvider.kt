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

    override suspend fun discover(): List<IntegrationDevice> {
        delay(650)
        return devices
    }

    override suspend fun scan(scanner: IntegrationDevice): MopriaDocument {
        delay(850)
        val timestamp = System.currentTimeMillis()
        return MopriaDocument(
            id = "scan-$timestamp",
            name = "模擬掃描文件 ${timestamp.toString().takeLast(4)}",
            sourceLabel = "${scanner.name} · 模擬 eSCL",
            pages = listOf(
                DocumentPage("$timestamp-page-1", 1, "封面與摘要"),
                DocumentPage("$timestamp-page-2", 2, "內容頁"),
                DocumentPage("$timestamp-page-3", 3, "附錄"),
            ),
            createdAt = timestamp,
        )
    }

    override suspend fun print(printer: IntegrationDevice, document: MopriaDocument) {
        delay(900)
    }
}
