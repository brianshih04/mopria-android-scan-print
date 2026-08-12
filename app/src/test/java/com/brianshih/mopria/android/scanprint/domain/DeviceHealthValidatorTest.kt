package com.brianshih.mopria.android.scanprint.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceHealthValidatorTest {
    private val scanner = IntegrationDevice("scanner", "Scanner", DeviceKind.Scanner, "eSCL")
    private val printer = IntegrationDevice("printer", "Printer", DeviceKind.Printer, "IPP")

    @Test
    fun keepsOnlyDevicesThatAnswerCapabilities() = runBlocking {
        val result = DeviceHealthValidator.validate(
            devices = listOf(scanner, printer),
            scanProvider = scanProvider(ScannerCapabilities.DEFAULT),
            printProvider = printProvider(null),
        )

        assertEquals(listOf(scanner), result.devices)
        assertEquals(ScannerCapabilities.DEFAULT, result.scannerCapabilities)
    }

    @Test
    fun unreachableManualCandidatesAreNotReportedReady() = runBlocking {
        val result = DeviceHealthValidator.validate(
            devices = listOf(scanner, printer),
            scanProvider = scanProvider(null),
            printProvider = printProvider(null),
        )

        assertEquals(emptyList<IntegrationDevice>(), result.devices)
        assertNull(result.scannerCapabilities)
    }

    private fun scanProvider(capabilities: ScannerCapabilities?) = object : ScanAcquisitionProvider {
        override suspend fun scan(
            scanner: IntegrationDevice,
            settings: ScanSettings,
            onProgress: (ScanProgress) -> Unit,
        ): MopriaDocument = error("Not used")

        override suspend fun scannerCapabilities(scanner: IntegrationDevice) = capabilities
    }

    private fun printProvider(capabilities: PrintCapabilities?) = object : PrintProvider {
        override suspend fun print(
            printer: IntegrationDevice,
            document: MopriaDocument,
            options: PrintOptions?,
        ) = Unit

        override suspend fun capabilities(printer: IntegrationDevice) = capabilities
    }
}
