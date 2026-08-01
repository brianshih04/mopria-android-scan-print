package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EsclProtocolTest {
    @Test
    fun parsesNamespacedScannerCapabilities() {
        val capabilities = EsclProtocol.parseCapabilities(
            """
            <scan:ScannerCapabilities xmlns:scan="${EsclProtocol.XML_NAMESPACE}">
              <scan:SupportedDocumentFormats>image/jpeg</scan:SupportedDocumentFormats>
              <scan:DocumentFormatExt>application/pdf</scan:DocumentFormatExt>
              <scan:ColorModes><scan:ColorMode>RGB24</scan:ColorMode><scan:ColorMode>Grayscale8</scan:ColorMode></scan:ColorModes>
              <scan:SupportedResolutions><scan:XResolution>300</scan:XResolution><scan:XResolution>600</scan:XResolution></scan:SupportedResolutions>
              <scan:InputSource>Platen</scan:InputSource>
            </scan:ScannerCapabilities>
            """.trimIndent(),
        )

        assertTrue(capabilities.documentFormats.contains("application/pdf"))
        assertEquals(listOf("RGB24", "Grayscale8"), capabilities.colorModes)
        assertEquals(listOf(300, 600), capabilities.resolutions)
        assertEquals(listOf("Platen"), capabilities.inputSources)
    }

    @Test
    fun buildsScanSettingsAndResolvesNextDocumentUrl() {
        val settings = EsclProtocol.buildScanSettings(
            ScanSettings(
                inputSource = ScanInputSource.Adf,
                colorMode = ScanColorMode.Grayscale,
                resolutionDpi = 600,
            ),
        )

        assertTrue(settings.contains("<scan:InputSource>ADF</scan:InputSource>"))
        assertTrue(settings.contains("Grayscale8"))
        assertTrue(settings.contains("<scan:XResolution>600</scan:XResolution>"))
        assertEquals(
            "http://192.0.2.1:80/eSCL/ScanJobs/123/NextDocument",
            EsclProtocol.nextDocumentUrl("http://192.0.2.1:80/eSCL", "/eSCL/ScanJobs/123"),
        )
    }

    @Test
    fun detectsPlatenAndAdfCapabilityContainers() {
        val capabilities = EsclProtocol.parseCapabilities(
            """
            <scan:ScannerCapabilities xmlns:scan="${EsclProtocol.XML_NAMESPACE}">
              <scan:Platen><scan:PlatenInputCaps /></scan:Platen>
              <scan:Adf><scan:AdfSimplexInputCaps /></scan:Adf>
            </scan:ScannerCapabilities>
            """.trimIndent(),
        )

        assertTrue(capabilities.inputSources.contains("Platen"))
        assertTrue(capabilities.inputSources.contains("ADF"))
    }
}
