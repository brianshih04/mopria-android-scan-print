package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsclDiscoveryTest {
    @Test
    fun parsesRequiredTxtMetadataAndCustomRoot() {
        val metadata = EsclDiscovery.parse(
            mapOf(
                "rs" to "scanner/api".toByteArray(),
                "uuid" to "device-123".toByteArray(),
                "vers" to "2.97".toByteArray(),
                "ty" to "Office Scanner".toByteArray(),
                "pdl" to "application/pdf,image/jpeg".toByteArray(),
            ),
        )
        assertEquals("scanner/api", metadata?.resourcePath)
        assertEquals(listOf("application/pdf", "image/jpeg"), metadata?.documentFormats)
        assertTrue(EsclDiscovery.identity("Scanner", checkNotNull(metadata)).contains("device-123"))
    }

    @Test
    fun rejectsUnsafeResourceRoot() {
        assertNull(EsclDiscovery.parse(mapOf("rs" to "../admin".toByteArray())))
        assertNull(EsclDiscovery.parse(mapOf("rs" to "eSCL?token=x".toByteArray())))
        assertNull(EsclDiscovery.parse(mapOf("rs" to "%2e%2e/admin".toByteArray())))
    }

    @Test
    fun secureAndInsecureServicesDeduplicateByUuidEvenWhenNamesDiffer() {
        val metadata = checkNotNull(EsclDiscovery.parse(mapOf("uuid" to "DEVICE-123".toByteArray())))
        assertEquals(
            EsclDiscovery.identity("Office Scanner", metadata),
            EsclDiscovery.identity("Office Scanner Secure", metadata),
        )
    }

    @Test
    fun prefersSecureAdvertisement() {
        val insecure = IntegrationDevice("1", "Scanner", DeviceKind.Scanner, "eSCL", secure = false)
        val secure = insecure.copy(id = "2", protocol = "eSCL TLS", secure = true)
        assertEquals(secure, EsclDiscovery.preferred(insecure, secure))
        assertEquals(secure, EsclDiscovery.preferred(secure, insecure))
    }
}
