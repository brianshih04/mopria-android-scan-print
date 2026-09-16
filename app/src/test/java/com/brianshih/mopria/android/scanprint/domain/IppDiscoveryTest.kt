package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IppDiscoveryTest {

    @Test
    fun parsesIppTxtRecords() {
        val attributes = mapOf(
            "rp" to "ipp/print".toByteArray(),
            "ty" to "HP Color LaserJet".toByteArray(),
            "pdl" to "application/pdf,image/jpeg".toByteArray(),
            "uuid" to "urn:uuid:12345".toByteArray(),
            "vers" to "2.0".toByteArray(),
        )
        val metadata = IppDiscovery.parse(attributes)
        assertEquals("ipp/print", metadata?.resourcePath)
        assertEquals("HP Color LaserJet", metadata?.displayName)
        assertEquals(listOf("application/pdf", "image/jpeg"), metadata?.documentFormats)
        assertEquals("urn:uuid:12345", metadata?.uuid)
        assertEquals("2.0", metadata?.version)
    }

    @Test
    fun defaultsResourcePathWhenRpMissing() {
        val metadata = IppDiscovery.parse(mapOf("ty" to "Printer".toByteArray()))
        assertEquals(IppDiscovery.DEFAULT_RESOURCE_PATH, metadata?.resourcePath)
    }

    @Test
    fun rejectsUnsafeResourcePath() {
        assertNull(IppDiscovery.parse(mapOf("rp" to "../etc".toByteArray())))
        assertNull(IppDiscovery.parse(mapOf("rp" to "a?b".toByteArray())))
    }

    @Test
    fun identityUsesUuidWhenPresent() {
        val metadata = IppDiscovery.parse(mapOf("rp" to "ipp/print".toByteArray(), "uuid" to "URN:UUID:ABC".toByteArray()))!!
        assertEquals("urn:uuid:abc:ipp/print", IppDiscovery.identity("MyPrinter", metadata))
    }

    @Test
    fun identityFallsBackToServiceName() {
        val metadata = IppDiscovery.parse(mapOf("rp" to "ipp/print".toByteArray()))!!
        assertEquals("myprinter:ipp/print", IppDiscovery.identity("MyPrinter", metadata))
    }

    @Test
    fun prefersTlsCandidate() {
        val insecure = device(secure = false)
        val secure = device(secure = true)
        assertEquals(secure, IppDiscovery.preferred(insecure, secure)) // upgrade to TLS
        assertEquals(secure, IppDiscovery.preferred(secure, insecure)) // never downgrade from TLS
        assertEquals(insecure, IppDiscovery.preferred(null, insecure)) // first candidate wins
    }

    @Test
    fun buildsPrinterUriWithDefaultPort() {
        assertEquals(
            "ipps://192.168.1.5:631/ipp/print",
            IppDiscovery.printerUri("192.168.1.5", 631, "ipp/print", secure = true).toString(),
        )
        assertEquals(
            "ipp://printer.local:631/ipp/print",
            IppDiscovery.printerUri("printer.local", 0, "ipp/print", secure = false).toString(),
        )
    }

    private fun device(secure: Boolean) = IntegrationDevice(
        id = "x",
        name = "n",
        kind = DeviceKind.Printer,
        protocol = "ipp",
        secure = secure,
    )
}
