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
    fun scanReturnsThreePagesWithSourceLabel() = runBlocking {
        val scanner = provider.discover().first { it.kind == DeviceKind.Scanner }
        val document = provider.scan(scanner)

        assertEquals(3, document.pages.size)
        assertEquals("模擬 eSCL", document.sourceLabel.substringAfter("· "))
        assertEquals(listOf(1, 2, 3), document.pages.map { it.pageNumber })
    }

    @Test
    fun printProviderAcceptsMockDocument() = runBlocking {
        val scanner = provider.discover().first { it.kind == DeviceKind.Scanner }
        val printer = provider.discover().first { it.kind == DeviceKind.Printer }
        val document = provider.scan(scanner)

        provider.print(printer, document)
    }
}
