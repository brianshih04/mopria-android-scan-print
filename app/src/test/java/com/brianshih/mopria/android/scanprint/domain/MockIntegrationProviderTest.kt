package com.brianshih.mopria.android.scanprint.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockIntegrationProviderTest {
    private val provider = MockIntegrationProvider()

    @Test
    fun discoverReturnsOneScannerAndOnePrinter() = runBlocking {
        val devices = provider.discover()

        assertEquals(2, devices.size)
        assertEquals(1, devices.count { it.kind == DeviceKind.Scanner })
        assertEquals(1, devices.count { it.kind == DeviceKind.Printer })
        assertTrue(devices.all { it.isMock })
    }

    @Test
    fun flatbedScanReturnsOnePage() = runBlocking {
        val scanner = provider.discover().first { it.kind == DeviceKind.Scanner }
        val document = provider.scan(scanner, ScanSettings(inputSource = ScanInputSource.Flatbed))

        assertEquals(1, document.pages.size)
        assertTrue(document.sourceLabel.contains("Platen"))
        assertEquals(listOf(1), document.pages.map { it.pageNumber })
    }

    @Test
    fun adfScanReturnsMultiplePagesWithSourceLabel() = runBlocking {
        val scanner = provider.discover().first { it.kind == DeviceKind.Scanner }
        val document = provider.scan(scanner, ScanSettings(inputSource = ScanInputSource.Adf, maxPages = 4))

        assertEquals(4, document.pages.size)
        assertTrue(document.sourceLabel.contains("Feeder"))
        assertEquals(listOf(1, 2, 3, 4), document.pages.map { it.pageNumber })
    }

    @Test
    fun printProviderAcceptsMockDocument() = runBlocking {
        val scanner = provider.discover().first { it.kind == DeviceKind.Scanner }
        val printer = provider.discover().first { it.kind == DeviceKind.Printer }
        val document = provider.scan(scanner, ScanSettings(inputSource = ScanInputSource.Adf))

        provider.print(printer, document)
    }
}
