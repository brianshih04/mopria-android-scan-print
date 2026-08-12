package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDeviceAddressTest {

    @Test
    fun buildsScannerAndPrinterCandidatesForAnIpAddress() {
        val candidates = ManualDeviceAddress.candidates(" 10.1.121.175 ")

        assertEquals(2, candidates.size)
        val scanner = candidates.first { it.kind == DeviceKind.Scanner }
        val printer = candidates.first { it.kind == DeviceKind.Printer }
        assertEquals("10.1.121.175", scanner.host)
        assertEquals(80, scanner.port)
        assertEquals("eSCL", scanner.resourcePath)
        assertEquals(631, printer.port)
        assertEquals("ipp/print", printer.resourcePath)
        assertTrue(candidates.none { it.isMock })
    }

    @Test
    fun acceptsLocalHostnames() {
        assertEquals("scanner.local", ManualDeviceAddress.normalize("scanner.local"))
    }

    @Test
    fun rejectsUrlsPortsPathsAndInvalidHostLabels() {
        listOf(
            "http://10.1.121.175",
            "10.1.121.175:631",
            "10.1.121.175/eSCL",
            "10.1.121.175?query",
            "bad..host",
            "-bad-host",
            "",
        ).forEach { value ->
            assertNull(value, ManualDeviceAddress.normalize(value))
            assertTrue(ManualDeviceAddress.candidates(value).isEmpty())
        }
    }
}
