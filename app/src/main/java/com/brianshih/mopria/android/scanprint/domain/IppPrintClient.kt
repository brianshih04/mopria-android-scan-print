package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.IppPacket
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
    const val URF = "image/urf"
    const val OCTET_STREAM = "application/octet-stream"

    /** Formats this app can produce, in preference order (PDF first, then JPEG/PNG). */
    val producible: List<String> = listOf(PDF, JPEG, PNG)

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
        jobName: String = "Mopria Scan & Print",
    ): IppJobSubmission {
        val createRequest = IppPacket.createJob(uri)
            .putOperationAttributes(
                Types.requestingUserName.of(userAgent),
                Types.jobName.of(jobName),
            )
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
     * Negotiate then print: query the printer's supported formats, select the best match from
     * [preferred] (default what this app produces), then Create-Job + Send-Document. Returns null when
     * the printer advertises none of [preferred]; callers may then convert the document (e.g. rasterize
     * via jipp-pdl) or report an unsupported-printer error.
     */
    fun printSupported(
        uri: URI,
        document: InputStream,
        preferred: Iterable<String> = IppDocumentFormat.producible,
        jobName: String = "Mopria Scan & Print",
    ): IppJobSubmission? {
        val supported = documentFormatsSupported(getPrinterAttributes(uri))
        val format = IppDocumentFormat.select(supported, preferred) ?: return null
        return print(uri, format, document, jobName)
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
