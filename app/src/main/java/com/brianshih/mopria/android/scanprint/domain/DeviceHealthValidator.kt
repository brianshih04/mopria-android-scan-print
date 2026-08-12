package com.brianshih.mopria.android.scanprint.domain

import kotlinx.coroutines.CancellationException

data class ValidatedDevices(
    val devices: List<IntegrationDevice>,
    val scannerCapabilities: ScannerCapabilities?,
)

/** Keeps only devices that answer the protocol-level capability request used by the app. */
object DeviceHealthValidator {
    suspend fun validate(
        devices: List<IntegrationDevice>,
        scanProvider: ScanAcquisitionProvider,
        printProvider: PrintProvider,
    ): ValidatedDevices {
        var firstScannerCapabilities: ScannerCapabilities? = null
        val reachable = buildList {
            devices.forEach { device ->
                val available = try {
                    when (device.kind) {
                        DeviceKind.Scanner -> scanProvider.scannerCapabilities(device)?.also { capabilities ->
                            if (firstScannerCapabilities == null) firstScannerCapabilities = capabilities
                        } != null
                        DeviceKind.Printer -> printProvider.capabilities(device) != null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                if (available) add(device)
            }
        }
        return ValidatedDevices(reachable, firstScannerCapabilities)
    }
}
