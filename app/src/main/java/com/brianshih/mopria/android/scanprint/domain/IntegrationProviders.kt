package com.brianshih.mopria.android.scanprint.domain

interface DeviceDiscovery {
    suspend fun discover(): List<IntegrationDevice>
}

interface ScanAcquisitionProvider {
    suspend fun scan(
        scanner: IntegrationDevice,
        settings: ScanSettings = ScanSettings(),
        onProgress: (ScanProgress) -> Unit = {},
    ): MopriaDocument

    /**
     * Fetch and summarize scanner capabilities for UI option filtering, or null if the scanner
     * is unreachable or capabilities cannot be parsed. Mock returns the default full set.
     */
    suspend fun scannerCapabilities(scanner: IntegrationDevice): ScannerCapabilities? = ScannerCapabilities.DEFAULT
}

interface PrintProvider {
    suspend fun print(printer: IntegrationDevice, document: MopriaDocument, options: PrintOptions? = null)

    /** Printer capability surface for Direct IPP options, or null if not discoverable (e.g. system print). */
    suspend fun capabilities(printer: IntegrationDevice): PrintCapabilities? = null
}
