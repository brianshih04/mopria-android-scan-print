package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.JobState
import com.hp.jipp.model.Types
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Result of submitting a document: the printer-assigned job id and the final Send-Document response. */
data class IppJobSubmission(val jobId: Int, val response: IppPacket)

/**
 * One or more rendered files ready to submit as a single IPP job in [format].
 *
 * PDF documents are a single multi-page file (`files.size == 1`). Image formats (JPEG/PNG) are one file
 * per page; each page is sent as a separate Send-Document and only the final one carries
 * `last-document=true` so the printer finalizes the job. The caller owns these files and must call
 * [delete] after submission (success or failure).
 */
data class RenderedPrintDocument(
    val format: String,
    val files: List<File>,
) {
    init {
        require(files.isNotEmpty()) { "RenderedPrintDocument 必須包含至少一個檔案" }
    }

    /** Best-effort delete of all temp files; safe to call from a `finally` block. */
    fun delete() {
        files.forEach { runCatching { it.delete() } }
    }
}

/**
 * Length-aware IPP-over-HTTP transport.
 *
 * Carries jipp [IppPacket]s over HTTP with the app's transport discipline. The document [send] variant
 * takes the exact [contentLength] so requests use fixed-length streaming (`Content-Length`), which every
 * IPP printer accepts — unlike chunked transfer-encoding, which some firmware rejects (see
 * DIRECT_IPP_FOLLOWUPS.md P2.8). Implemented by [BoundedIppTransport].
 */
interface IppTransport {
    /** Send a control request (no document body); returns the parsed response packet. */
    fun send(uri: URI, packet: IppPacket): IppPacket

    /** Send a request carrying a document body of exactly [contentLength] bytes. */
    fun send(uri: URI, packet: IppPacket, document: InputStream, contentLength: Long): IppPacket
}

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

    /**
     * Formats this app can currently render and submit, in preference order.
     *
     * `RealIntegrationProvider.renderPrintDocument` produces each: PDF via Android `PdfDocument` (single
     * multi-page file), JPEG/PNG via per-page bitmap compress. Keep this list in sync with the renderer —
     * a format here without a matching renderer would send mislabeled bytes. See DIRECT_IPP_FOLLOWUPS.md.
     */
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
 * request factories; HTTP/TLS stays under the app's control via [IppTransport] (see [BoundedIppTransport]).
 *
 * Per PWG/RFC 8011: job-state 3 Pending, 4 Held, 5 Processing, 6 Stopped, 7 Canceled, 8 Aborted,
 * 9 Completed; printer-state 3 idle, 4 processing, 5 stopped.
 */
class IppPrintClient(
    private val transport: IppTransport,
    private val userAgent: String = "MopriaScanPrint/0.1",
) {
    /** Get-Printer-Attributes; inspect via [documentFormatsSupported] / [IppPacket.getValue]. */
    fun getPrinterAttributes(uri: URI): IppPacket {
        val request = IppPacket.getPrinterAttributes(uri)
            .putOperationAttributes(Types.requestingUserName.of(userAgent))
            .build()
        return transport.send(uri, request)
    }

    /** Read `document-format-supported` (PWG) from a Get-Printer-Attributes response. */
    fun documentFormatsSupported(attributes: IppPacket): List<String> =
        attributes.getStrings(Tag.printerAttributes, Types.documentFormatSupported)

    /**
     * Parse the printable-option capabilities (copies, media, sides, color, quality, orientation) from a
     * Get-Printer-Attributes response. Missing attributes yield empty lists / copies fixed at 1, so the
     * caller can treat absent advertisements as "not configurable" without extra null handling.
     */
    fun parseCapabilities(attributes: IppPacket): PrintCapabilities = PrintCapabilities(
        copies = attributes.getValue(Tag.printerAttributes, Types.copiesSupported) ?: (1..1),
        media = attributes.getStrings(Tag.printerAttributes, Types.mediaSupported),
        sides = attributes.getStrings(Tag.printerAttributes, Types.sidesSupported),
        colorModes = attributes.getStrings(Tag.printerAttributes, Types.printColorModeSupported),
        // Enum sets: getStrings returns "name(code)"; read the typed list and take the keyword name.
        qualities = attributes.getValues(Tag.printerAttributes, Types.printQualitySupported).map { it.name },
        orientations = attributes.getValues(Tag.printerAttributes, Types.orientationRequestedSupported).map { it.name },
    )

    /**
     * Query the printer and return the first [preferred] format it advertises, or null if none match.
     * Pair with [send]: the caller renders the document into the negotiated format, then submits via
     * [send] so the bytes always match the declared `document-format`. Default [preferred] is
     * [IppDocumentFormat.producible].
     */
    fun selectProducibleFormat(
        uri: URI,
        preferred: Iterable<String> = IppDocumentFormat.producible,
    ): String? {
        val supported = documentFormatsSupported(getPrinterAttributes(uri))
        return IppDocumentFormat.select(supported, preferred)
    }

    /**
     * Submit [document] via Create-Job + one Send-Document per file. Each file is sent in
     * [RenderedPrintDocument.format]; only the final Send-Document carries `last-document=true`, so a
     * multi-page image job (one file per page) finalizes correctly while a single-file PDF job submits
     * in one step. Create-Job or any Send-Document failure throws so a caller can stop early; a
     * separate Cancel-Job ([cancelJob]) is the caller's responsibility on partial failure.
     *
     * Two-step Create-Job + Send-Document (rather than Print-Job) is preferred per the PWG IPP guide
     * because the job-id is assigned *before* the document transfers, so Cancel-Job can stop a large
     * or ongoing submission.
     */
    fun send(
        uri: URI,
        document: RenderedPrintDocument,
        jobName: String = "Mopria Scan & Print",
    ): IppJobSubmission {
        val createRequest = IppPacket.createJob(uri)
            .putOperationAttributes(
                Types.requestingUserName.of(userAgent),
                Types.jobName.of(jobName),
            )
            .build()
        val createResponse = transport.send(uri, createRequest)
        if (!isSuccessful(createResponse)) throw PrintError.CreateJobFailed(createResponse.status.code)
        val jobId = createResponse.getValue(Tag.jobAttributes, Types.jobId)
            ?: throw PrintError.CreateJobFailed(createResponse.status.code)

        var lastResponse: IppPacket? = null
        document.files.forEachIndexed { index, file ->
            val isLast = index == document.files.lastIndex
            val sendRequest = IppPacket.sendDocument(uri, jobId)
                .putOperationAttributes(
                    Types.requestingUserName.of(userAgent),
                    Types.documentFormat.of(document.format),
                    Types.lastDocument.of(isLast),
                )
                .build()
            val response = file.inputStream().use { stream ->
                transport.send(uri, sendRequest, stream, file.length())
            }
            if (!isSuccessful(response)) throw PrintError.SendDocumentFailed(response.status.code)
            lastResponse = response
        }
        return IppJobSubmission(jobId, requireNotNull(lastResponse))
    }

    /** Get-Job-Attributes for [jobId]; read job-state via [IppPacket.getValue] with [Types.jobState]. */
    fun getJobAttributes(uri: URI, jobId: Int): IppPacket {
        val request = IppPacket.getJobAttributes(uri, jobId)
            .putOperationAttributes(Types.requestingUserName.of(userAgent))
            .build()
        return transport.send(uri, request)
    }

    /** Cancel-Job for [jobId]; best-effort, callers should swallow non-fatal outcomes during cleanup. */
    fun cancelJob(uri: URI, jobId: Int): IppPacket {
        val request = IppPacket.cancelJob(uri, jobId)
            .putOperationAttributes(Types.requestingUserName.of(userAgent))
            .build()
        return transport.send(uri, request)
    }

    /**
     * Poll Get-Job-Attributes until [jobId] reaches a terminal state.
     *
     * Returns the Completed response on success. `canceled`/`aborted` throw so the caller can surface a
     * failure (the response's `job-state-reasons` is included for `aborted`). `pending`, `pendingHeld`,
     * `processing` and `processingStopped` keep polling; an HTTP/IPP error from the printer propagates as
     * a failure rather than being mistaken for completion. On [totalTimeoutMs] the job is cancelled
     * best-effort via [cancelJob] under [NonCancellable] (so cleanup still runs) and the call throws.
     *
     * Run on a dispatcher suited to blocking HTTP (the IO dispatcher from `RealIntegrationProvider`).
     */
    suspend fun awaitJobCompletion(
        uri: URI,
        jobId: Int,
        pollIntervalMs: Long = 500L,
        totalTimeoutMs: Long = 120_000L,
    ): IppPacket {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val attributes = getJobAttributes(uri, jobId)
            when (val state = attributes.getValue(Tag.jobAttributes, Types.jobState)) {
                JobState.completed -> return attributes
                JobState.canceled -> throw PrintError.JobCanceled
                JobState.aborted -> throw PrintError.JobAborted(
                    attributes.getStrings(Tag.jobAttributes, Types.jobStateReasons).joinToString(),
                )
                null -> throw PrintError.CreateJobFailed(attributes.status.code)
                else -> Unit // pending / pendingHeld / processing / processingStopped → keep polling
            }
            delay(pollIntervalMs)
        }
        withContext(NonCancellable) { runCatching { cancelJob(uri, jobId) } }
        throw PrintError.JobTimeout
    }

    /** IPP successful status codes occupy the 0x0000–0x00FF range (RFC 8011 §13). */
    private fun isSuccessful(packet: IppPacket): Boolean = (packet.status.code and 0xFF00) == 0
}
