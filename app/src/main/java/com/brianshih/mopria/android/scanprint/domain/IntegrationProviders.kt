package com.brianshih.mopria.android.scanprint.domain

interface DeviceDiscovery {
    suspend fun discover(): List<IntegrationDevice>
}

interface ScanAcquisitionProvider {
    suspend fun scan(scanner: IntegrationDevice, settings: ScanSettings = ScanSettings()): MopriaDocument
}

interface PrintProvider {
    suspend fun print(printer: IntegrationDevice, document: MopriaDocument, options: PrintOptions? = null)
}
