package com.brianshih.mopria.android.scanprint.domain

interface DeviceDiscovery {
    suspend fun discover(): List<IntegrationDevice>
}

interface ScanAcquisitionProvider {
    suspend fun scan(scanner: IntegrationDevice, settings: ScanSettings = ScanSettings()): MopriaDocument
}

interface PrintProvider {
    suspend fun print(printer: IntegrationDevice, document: MopriaDocument, options: PrintOptions? = null)

    /** Printer capability surface for Direct IPP options, or null if not discoverable (e.g. system print). */
    suspend fun capabilities(printer: IntegrationDevice): PrintCapabilities? = null
}
