package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EsclProtocolTest {
    @Test
    fun parsesSourceProfilesRangesAndReferences() {
        val capabilities = EsclProtocol.parseCapabilities(CAPABILITIES_XML)

        assertEquals("2.97", capabilities.version)
        assertEquals(listOf("Platen", "Feeder"), capabilities.inputSources)
        assertTrue(capabilities.inputs.getValue("Feeder").selectSinglePage)
        assertTrue(capabilities.adfSimplexInput != null)
        assertTrue(capabilities.adfDuplexInput != null)

        val platen = EsclProtocol.negotiate(
            capabilities,
            ScanSettings(ScanInputSource.Flatbed, resolutionDpi = 610, colorMode = ScanColorMode.Color),
        )
        assertEquals("image/jpeg", platen.documentFormat)
        assertEquals(600, platen.resolution)
        assertEquals(600, platen.yResolution)

        val adf = EsclProtocol.negotiate(
            capabilities,
            ScanSettings(ScanInputSource.Adf, resolutionDpi = 600, colorMode = ScanColorMode.Grayscale),
        )
        assertEquals("Feeder", adf.inputSource)
        assertEquals("application/pdf", adf.documentFormat)
        assertEquals(20, adf.numberOfPages)
        assertEquals(false, adf.duplex)
    }

    @Test
    fun negotiatesAdvertisedAdfDuplexProfile() {
        val capabilities = EsclProtocol.parseCapabilities(CAPABILITIES_XML)

        val negotiated = EsclProtocol.negotiate(
            capabilities,
            ScanSettings(
                inputSource = ScanInputSource.Adf,
                adfMode = ScanAdfMode.Duplex,
                resolutionDpi = 300,
                colorMode = ScanColorMode.Grayscale,
            ),
        )

        assertEquals("Feeder", negotiated.inputSource)
        assertEquals(true, negotiated.duplex)
        assertEquals("application/pdf", negotiated.documentFormat)
    }

    @Test
    fun usesUserConfiguredAdfPageLimitAndCapsFutureProtocolVersion() {
        val capabilities = EsclProtocol.parseCapabilities(CAPABILITIES_XML).copy(version = "3.1")
        val negotiated = EsclProtocol.negotiate(
            capabilities,
            ScanSettings(ScanInputSource.Adf, resolutionDpi = 300, colorMode = ScanColorMode.Grayscale, maxPages = 7),
        )

        assertEquals("2.97", negotiated.version)
        assertEquals(7, negotiated.numberOfPages)
    }

    @Test
    fun ocrRejectsPdfOnlyScannerProfileBeforeCreatingAJob() {
        val capabilities = EsclProtocol.parseCapabilities(CAPABILITIES_XML)

        assertThrows(ScanError.OcrImageFormatUnsupported::class.java) {
            EsclProtocol.negotiate(
                capabilities,
                ScanSettings(
                    inputSource = ScanInputSource.Adf,
                    resolutionDpi = 300,
                    colorMode = ScanColorMode.Grayscale,
                    ocrMode = OcrMode.MlKit,
                ),
            )
        }
    }

    @Test
    fun ocrNegotiatesJpegWhenTheSelectedProfileSupportsIt() {
        val capabilities = EsclProtocol.parseCapabilities(CAPABILITIES_XML)

        val negotiated = EsclProtocol.negotiate(
            capabilities,
            ScanSettings(
                inputSource = ScanInputSource.Flatbed,
                resolutionDpi = 300,
                colorMode = ScanColorMode.Color,
                ocrMode = OcrMode.MlKit,
            ),
        )

        assertEquals("image/jpeg", negotiated.documentFormat)
    }

    @Test
    fun ocrFallsBackToAColorModeThatCanReturnJpeg() {
        val capabilities = EsclCapabilities(
            inputs = mapOf(
                "Platen" to EsclInputCapabilities(
                    inputSource = "Platen",
                    profiles = listOf(
                        EsclSettingProfile(
                            colorModes = setOf("Grayscale8"),
                            documentFormats = setOf("application/pdf"),
                            resolutions = listOf(
                                EsclResolutionSupport(
                                    colorMode = "Grayscale8",
                                    discreteResolutions = setOf(EsclResolution(300, 300)),
                                ),
                            ),
                        ),
                        EsclSettingProfile(
                            colorModes = setOf("RGB24"),
                            documentFormats = setOf("image/jpeg"),
                            resolutions = listOf(
                                EsclResolutionSupport(
                                    colorMode = "RGB24",
                                    discreteResolutions = setOf(EsclResolution(300, 300)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val negotiated = EsclProtocol.negotiate(
            capabilities,
            ScanSettings(
                colorMode = ScanColorMode.Grayscale,
                resolutionDpi = 300,
                ocrMode = OcrMode.MlKit,
            ),
        )

        assertEquals("image/jpeg", negotiated.documentFormat)
        assertEquals("RGB24", negotiated.colorMode)
    }

    @Test
    fun rejectsOutOfRangeAdfPageLimit() {
        val capabilities = EsclProtocol.parseCapabilities(CAPABILITIES_XML)

        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.negotiate(capabilities, ScanSettings(inputSource = ScanInputSource.Adf, maxPages = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.negotiate(capabilities, ScanSettings(inputSource = ScanInputSource.Adf, maxPages = 51))
        }
    }

    @Test
    fun buildsSchemaCorrectScanSettings() {
        val xml = EsclProtocol.buildScanSettings(
            EsclNegotiatedSettings(
                version = "2.97",
                inputSource = "Feeder",
                documentFormat = "application/pdf",
                resolution = 600,
                colorMode = "BlackAndWhite1",
                numberOfPages = 20,
            ),
        )

        assertTrue(xml.contains("xmlns:pwg=\"${EsclProtocol.PWG_NAMESPACE}\""))
        assertTrue(xml.contains("<pwg:Version>2.97</pwg:Version>"))
        assertTrue(xml.contains("<pwg:InputSource>Feeder</pwg:InputSource>"))
        assertTrue(xml.contains("<scan:DocumentFormatExt>application/pdf</scan:DocumentFormatExt>"))
        assertTrue(xml.contains("<scan:NumberOfPages>20</scan:NumberOfPages>"))
        assertTrue(!xml.contains("<scan:Duplex>"))
        assertTrue(!xml.contains("<scan:InputSource>"))
    }

    @Test
    fun emitsDuplexOnlyWhenRequested() {
        val xml = EsclProtocol.buildScanSettings(
            EsclNegotiatedSettings(
                version = "2.97",
                inputSource = "Feeder",
                documentFormat = "image/jpeg",
                resolution = 300,
                colorMode = "RGB24",
                duplex = true,
            ),
        )

        assertTrue(xml.contains("<scan:Duplex>true</scan:Duplex>"))
    }

    @Test
    fun usesLegacyDocumentFormatForVersion20() {
        val xml = EsclProtocol.buildScanSettings(
            EsclNegotiatedSettings("2.0", "Platen", "image/jpeg", 300, "RGB24"),
        )
        assertTrue(xml.contains("<pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>"))
        assertTrue(!xml.contains("DocumentFormatExt"))
    }

    @Test
    fun findsResolutionValidForDifferentXAxisAndYAxisRanges() {
        val support = EsclResolutionSupport(
            minDpi = 100,
            maxDpi = 1200,
            xRange = EsclAxisResolutionRange(75, 1200, 300, 25),
            yRange = EsclAxisResolutionRange(100, 1200, 300, 50),
        )
        assertEquals(EsclResolution(125, 150), support.nearest(130))
        assertEquals(1195, EsclAxisResolutionRange(75, 1200, 300, 10).nearest(1200))
    }

    @Test
    fun emitsDifferentXAxisAndYAxisResolutionsWhenAdvertised() {
        val xml = EsclProtocol.buildScanSettings(
            EsclNegotiatedSettings("2.97", "Platen", "image/jpeg", 300, "RGB24", yResolution = 600),
        )
        assertTrue(xml.contains("<scan:XResolution>300</scan:XResolution>"))
        assertTrue(xml.contains("<scan:YResolution>600</scan:YResolution>"))
    }

    @Test
    fun emitsRequestedPhotoScanRegion() {
        val xml = EsclProtocol.buildScanSettings(
            ScanSettings(documentSize = ScanDocumentSize.Photo4x6),
        )

        assertTrue(xml.contains("<scan:Intent>Photo</scan:Intent>"))
        assertTrue(xml.contains("<pwg:ScanRegions>"))
        assertTrue(xml.contains("<pwg:Width>1200</pwg:Width>"))
        assertTrue(xml.contains("<pwg:Height>1800</pwg:Height>"))
        assertTrue(xml.contains("<pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>"))
    }

    @Test
    fun parsesStatusWithArbitraryNamespacePrefixes() {
        val status = EsclProtocol.parseScannerStatus(
            """
            <x:ScannerStatus xmlns:x="${EsclProtocol.XML_NAMESPACE}" xmlns:q="${EsclProtocol.PWG_NAMESPACE}">
              <q:Version>2.97</q:Version><q:State>Processing</q:State><x:AdfState>ScannerAdfLoaded</x:AdfState>
              <x:Jobs><x:JobInfo><q:JobUri>/custom/ScanJobs/123</q:JobUri><q:JobUuid>123</q:JobUuid>
                <q:ImagesCompleted>2</q:ImagesCompleted><q:ImagesToTransfer>0</q:ImagesToTransfer>
                <q:JobState>Completed</q:JobState><q:JobStateReasons><q:JobStateReason>JobCompletedSuccessfully</q:JobStateReason></q:JobStateReasons>
              </x:JobInfo></x:Jobs>
            </x:ScannerStatus>
            """.trimIndent(),
        )

        assertEquals("Processing", status.state)
        assertEquals("2.97", status.version)
        assertEquals("ScannerAdfLoaded", status.adfState)
        assertEquals("Completed", status.jobFor("http://192.0.2.1/custom/ScanJobs/123")?.state)
    }

    @Test
    fun resolvesRelativeJobAndAllowsSameHostHttpsUpgrade() {
        assertEquals(
            "http://192.0.2.1:8080/custom/ScanJobs/123/NextDocument",
            EsclProtocol.nextDocumentUrl("http://192.0.2.1:8080/custom", "/custom/ScanJobs/123"),
        )
        assertEquals(
            "https://192.0.2.1/custom/ScanJobs/123",
            EsclProtocol.scanJobUrl("http://192.0.2.1/custom", "https://192.0.2.1/custom/ScanJobs/123"),
        )
    }

    @Test
    fun rejectsCrossOriginOrDowngradedJobLocation() {
        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.scanJobUrl("http://192.0.2.1/eSCL", "https://example.com/eSCL/ScanJobs/123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.scanJobUrl("https://192.0.2.1/eSCL", "http://192.0.2.1/eSCL/ScanJobs/123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.scanJobUrl("http://192.0.2.1/eSCL", "/admin/ScanJobs/123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.scanJobUrl("http://192.0.2.1/eSCL", "/eSCL/ScanJobs/123?token=unsafe")
        }
    }

    @Test
    fun rejectsMissingVersionOrWrongNamespace() {
        assertThrows(IllegalStateException::class.java) {
            EsclProtocol.parseCapabilities("<scan:ScannerCapabilities xmlns:scan=\"${EsclProtocol.XML_NAMESPACE}\"/>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.parseCapabilities("<scan:ScannerCapabilities xmlns:scan=\"urn:not-escl\"/>")
        }
    }

    @Test
    fun version21DoesNotTreatLegacyFormatAsDocumentFormatExt() {
        val capabilities = EsclProtocol.parseCapabilities(
            """
            <scan:ScannerCapabilities xmlns:scan="${EsclProtocol.XML_NAMESPACE}" xmlns:pwg="${EsclProtocol.PWG_NAMESPACE}">
              <pwg:Version>2.97</pwg:Version><scan:Platen><scan:PlatenInputCaps><scan:SettingProfiles><scan:SettingProfile>
                <scan:ColorModes><scan:ColorMode>RGB24</scan:ColorMode></scan:ColorModes>
                <scan:DocumentFormats><pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat></scan:DocumentFormats>
                <scan:SupportedResolutions><scan:DiscreteResolutions><scan:DiscreteResolution><scan:XResolution>300</scan:XResolution><scan:YResolution>300</scan:YResolution></scan:DiscreteResolution></scan:DiscreteResolutions></scan:SupportedResolutions>
              </scan:SettingProfile></scan:SettingProfiles></scan:PlatenInputCaps></scan:Platen>
            </scan:ScannerCapabilities>
            """.trimIndent(),
        )
        assertThrows(ScanError.CapabilityNotSupported::class.java) {
            EsclProtocol.negotiate(capabilities, ScanSettings())
        }
    }

    @Test
    fun rejectsDoctypeInProtocolXml() {
        assertThrows(IllegalArgumentException::class.java) {
            EsclProtocol.parseCapabilities(
                "<!DOCTYPE x [<!ENTITY bad SYSTEM \"file:///etc/passwd\">]><ScannerCapabilities>&bad;</ScannerCapabilities>",
            )
        }
    }

    companion object {
        private val CAPABILITIES_XML = """
            <scan:ScannerCapabilities xmlns:scan="${EsclProtocol.XML_NAMESPACE}" xmlns:pwg="${EsclProtocol.PWG_NAMESPACE}">
              <pwg:Version>2.97</pwg:Version>
              <scan:SettingProfiles>
                <scan:SettingProfile name="document-profile">
                  <scan:ColorModes><scan:ColorMode>BlackAndWhite1</scan:ColorMode><scan:ColorMode>Grayscale8</scan:ColorMode></scan:ColorModes>
                  <scan:DocumentFormats><scan:DocumentFormatExt>application/pdf</scan:DocumentFormatExt></scan:DocumentFormats>
                  <scan:SupportedResolutions><scan:DiscreteResolutions>
                    <scan:DiscreteResolution><scan:XResolution>300</scan:XResolution><scan:YResolution>300</scan:YResolution></scan:DiscreteResolution>
                    <scan:DiscreteResolution default="true"><scan:XResolution>600</scan:XResolution><scan:YResolution>600</scan:YResolution></scan:DiscreteResolution>
                  </scan:DiscreteResolutions></scan:SupportedResolutions>
                </scan:SettingProfile>
              </scan:SettingProfiles>
              <scan:Platen><scan:PlatenInputCaps><scan:SettingProfiles><scan:SettingProfile>
                <scan:ColorModes><scan:ColorMode>RGB24</scan:ColorMode></scan:ColorModes>
                <scan:DocumentFormats><scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt></scan:DocumentFormats>
                <scan:SupportedResolutions><scan:ResolutionRange>
                  <scan:XResolutionRange><scan:Min>75</scan:Min><scan:Max>1200</scan:Max><scan:Normal>300</scan:Normal><scan:Step>25</scan:Step></scan:XResolutionRange>
                  <scan:YResolutionRange><scan:Min>75</scan:Min><scan:Max>1200</scan:Max><scan:Normal>300</scan:Normal><scan:Step>25</scan:Step></scan:YResolutionRange>
                </scan:ResolutionRange></scan:SupportedResolutions>
              </scan:SettingProfile></scan:SettingProfiles></scan:PlatenInputCaps></scan:Platen>
              <scan:Adf><scan:AdfSimplexInputCaps><scan:SettingProfiles><scan:SettingProfile ref="document-profile"/></scan:SettingProfiles></scan:AdfSimplexInputCaps>
                <scan:AdfDuplexInputCaps><scan:SettingProfiles><scan:SettingProfile ref="document-profile"/></scan:SettingProfiles></scan:AdfDuplexInputCaps>
                <scan:AdfOptions><scan:AdfOption>SelectSinglePage</scan:AdfOption></scan:AdfOptions>
              </scan:Adf>
            </scan:ScannerCapabilities>
        """.trimIndent()
    }
}
