package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionUnit
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Types
import com.hp.jipp.trans.IppClientTransport
import com.hp.jipp.trans.IppPacketData
import java.io.InputStream
import java.net.URI
import java.util.Locale

/** Result of submitting a document: the printer-assigned job id and the Send-Document response. */
data class IppJobSubmission(val jobId: Int, val response: IppPacket)

/**
 * IPP document formats used by this app and commonly advertised by printers (PWG/IANA IPP registry).
 * Printer support varies — IPP Everywhere mandates [PDF], but many printers also accept [JPEG],
 * [PWG_RASTER] or [URF] and not all accept PDF — so the format must be negotiated per printer.
 */
object IppDocumentFormat {
    const val PDF = "application/pdf"
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val PWG_RASTER = "image/pwg-raster"
    const val PCLM = "application/PCLm"
    const val URF = "image/urf"
    const val OCTET_STREAM = "application/octet-stream"

    /** Formats this app can produce for IPP printing, in preference order: PDF natively, else PWG-Raster
     *  (via [com.hp.jipp.pdl] rasterization), else PCLm. PWG-Raster is the IPP Everywhere mandate. */
    val producible: List<String> = listOf(PDF, PWG_RASTER, PCLM)

    /**
     * Pick the first format in [preferred] that the printer advertises as [supported] (case-insensitive),
     * or null if none match. Per the PWG IPP guide, confirm the document format against printer
     * capabilities before sending rather than assuming PDF is accepted. `application/octet-stream`
     * ("auto-detect", flagged unreliable by PWG) is deliberately NOT treated as a universal match.
     */
    fun select(supported: Collection<String>, preferred: Iterable<String>): String? {
        val lower = supported.asSequence().map { it.trim().lowercase(Locale.ROOT) }.toSet()
        return preferred.firstOrNull { it.lowercase(Locale.ROOT) in lower }
    }
}

/**
 * Direct IPP client implementing the PWG-recommended flow:
 *   Get-Printer-Attributes → (format negotiation) → Create-Job → Send-Document → Get-Job-Attributes (poll).
 *
 * jipp handles IPP binary encode/decode and auto-supplies the mandatory operation attributes
 * (`attributes-charset` "utf-8", `attributes-natural-language` "en-us") plus `printer-uri` from the
 * request factories; HTTP/TLS stays under the app's control via [IppClientTransport]
 * (see [BoundedIppTransport]).
 *
 * Per PWG/RFC 8011: job-state 3 Pending, 4 Held, 5 Processing, 6 Stopped, 7 Canceled, 8 Aborted,
 * 9 Completed; printer-state 3 idle, 4 processing, 5 stopped.
 */
class IppPrintClient(
    private val transport: IppClientTransport,
    private val userAgent: String = "MopriaScanPrint/0.1",
) {
    /** Get-Printer-Attributes; inspect via [documentFormatsSupported] / [IppPacket.getValue]. */
    fun getPrinterAttributes(uri: URI): IppPacket {
        val request = IppPacket.getPrinterAttributes(uri)
            .putOperationAttributes(Types.requestingUserName.of(userAgent))
            .build()
        return transport.sendData(uri, IppPacketData(request)).packet
    }

    /** Read `document-format-supported` (PWG) from a Get-Printer-Attributes response. */
    fun documentFormatsSupported(attributes: IppPacket): List<String> =
        attributes.getStrings(Tag.printerAttributes, Types.documentFormatSupported)

    /** Read `print-color-mode-supported` (PWG), e.g. color / monochrome / bi-level. */
    fun printColorModesSupported(attributes: IppPacket): List<String> =
        attributes.getStrings(Tag.printerAttributes, Types.printColorModeSupported)

    /** Read `printer-resolution-supported` and pick a DPI: 300 if offered, else the first, else 300. */
    fun preferredResolution(attributes: IppPacket): Int {
        val resolutions = runCatching { attributes.getValues(Tag.printerAttributes, Types.printerResolutionSupported) }.getOrDefault(emptyList())
        return resolutions.firstOrNull { it.x == 300 }?.x ?: resolutions.firstOrNull()?.x ?: 300
    }

    /** Read `pclm-strip-height-preferred` for PCLm output, falling back to 16 when absent. */
    fun pclmStripHeightPreferred(attributes: IppPacket): Int =
        runCatching { attributes.getValue(Tag.printerAttributes, Types.pclmStripHeightPreferred) }.getOrNull() ?: 16

    /** Read `sides-supported` (PWG), e.g. one-sided / two-sided-long-edge. */
    fun sidesSupported(attributes: IppPacket): List<String> =
        attributes.getStrings(Tag.printerAttributes, Types.sidesSupported)

    /** Read `copies-supported` (PWG) as an IntRange, or null if absent. */
    fun copiesRangeSupported(attributes: IppPacket): IntRange? =
        runCatching { attributes.getValue(Tag.printerAttributes, Types.copiesSupported) }.getOrNull()

    /** Read `printer-resolution-supported` (PWG) as a list of DPI values. */
    fun resolutionsSupported(attributes: IppPacket): List<Int> =
        runCatching { attributes.getValues(Tag.printerAttributes, Types.printerResolutionSupported) }.getOrDefault(emptyList()).map { it.x }

    /** Read `media-supported` (PWG), e.g. na_letter_8.5x11in / iso_a4_210x297mm. */
    fun mediaSupported(attributes: IppPacket): List<String> =
        attributes.getStrings(Tag.printerAttributes, Types.mediaSupported)

    /**
     * Submit [document] in [documentFormat] via Create-Job + Send-Document. The caller should have
     * confirmed [documentFormat] against the printer ([documentFormatsSupported] + [IppDocumentFormat.select]);
     * prefer [printSupported] which does that automatically.
     *
     * Two-step is preferred over Print-Job (PWG IPP guide) because the job-id is assigned *before* the
     * document transfers, so a separate Cancel-Job can stop a large or ongoing submission.
     */
    fun print(
        uri: URI,
        documentFormat: String,
        document: InputStream,
        options: PrintOptions = PrintOptions(),
        jobName: String = "Mopria Scan & Print",
    ): IppJobSubmission {
        val createRequest = IppPacket.createJob(uri)
            .putOperationAttributes(
                Types.requestingUserName.of(userAgent),
                Types.jobName.of(jobName),
            )
            .putJobAttributes(options.jobTemplateAttributes())
            .build()
        val createResponse = transport.sendData(uri, IppPacketData(createRequest)).packet
        require(isSuccessful(createResponse)) { "IPP Create-Job 失敗：${createResponse.status}" }
        val jobId = requireNotNull(createResponse.getValue(Tag.jobAttributes, Types.jobId)) {
            "IPP Create-Job 回應缺少 job-id"
        }

        val sendRequest = IppPacket.sendDocument(uri, jobId)
            .putOperationAttributes(
                Types.requestingUserName.of(userAgent),
                Types.documentFormat.of(documentFormat),
                Types.lastDocument.of(true),
            )
            .build()
        val sendResponse = transport.sendData(uri, IppPacketData(sendRequest, document)).packet
        return IppJobSubmission(jobId, sendResponse)
    }

    /**
     * Send [document] as-is when its format is supported (defaults to PDF, since the caller supplies a
     * single pre-rendered PDF stream). Non-PDF printers requiring rasterization must be handled by the
     * caller (see RealIntegrationProvider). Returns null when the printer advertises none of [preferred].
     */
    fun printSupported(
        uri: URI,
        document: InputStream,
        preferred: Iterable<String> = listOf(IppDocumentFormat.PDF),
        jobName: String = "Mopria Scan & Print",
    ): IppJobSubmission? {
        val supported = documentFormatsSupported(getPrinterAttributes(uri))
        val format = IppDocumentFormat.select(supported, preferred) ?: return null
        return print(uri, format, document, jobName = jobName)
    }

    /** Get-Job-Attributes for [jobId]; read job-state via [IppPacket.getValue] with [Types.jobState]. */
    fun getJobAttributes(uri: URI, jobId: Int): IppPacket {
        val request = IppPacket.getJobAttributes(uri, jobId)
            .putOperationAttributes(Types.requestingUserName.of(userAgent))
            .build()
        return transport.sendData(uri, IppPacketData(request)).packet
    }

    /** Cancel-Job for [jobId]; best-effort, callers should swallow non-fatal outcomes during cleanup. */
    fun cancelJob(uri: URI, jobId: Int): IppPacket {
        val request = IppPacket.cancelJob(uri, jobId)
            .putOperationAttributes(Types.requestingUserName.of(userAgent))
            .build()
        return transport.sendData(uri, IppPacketData(request)).packet
    }

    /** IPP successful status codes occupy the 0x0000–0x00FF range (RFC 8011 §13). */
    private fun isSuccessful(packet: IppPacket): Boolean = (packet.status.code and 0xFF00) == 0
}

/**
 * User-chosen IPP job-template options. All optional; null/1 means "let the printer decide".
 * Sent as job-template attributes on Create-Job (print-color-mode, sides, copies, printer-resolution, media).
 */
data class PrintOptions(
    val colorMode: String? = null,
    val sides: String? = null,
    val copies: Int = 1,
    val resolutionDpi: Int? = null,
    val media: String? = null,
) {
    fun jobTemplateAttributes(): List<Attribute<*>> = buildList {
        colorMode?.let { add(Types.printColorMode.of(it)) }
        sides?.let { add(Types.sides.of(it)) }
        if (copies > 1) add(Types.copies.of(copies))
        resolutionDpi?.let { add(Types.printerResolution.of(Resolution(it, it, ResolutionUnit.dotsPerInch))) }
        media?.let { add(Types.media.of(it)) }
    }
}
