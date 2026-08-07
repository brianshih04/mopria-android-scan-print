package com.brianshih.mopria.android.scanprint.domain

/**
 * Localizable direct-IPP print failures.
 *
 * Carries only structured data — never user-facing display text — so the UI layer can map each subtype
 * to a localized string resource without the domain layer depending on Android resources. Throw the
 * matching subtype from the IPP client/provider; the ViewModel maps it via [messageStringRes].
 */
sealed class PrintError(message: String) : RuntimeException(message) {
    /** Printer advertises none of [IppDocumentFormat.producible]. [advertised] is the raw `pdl` list, for logging only. */
    class UnsupportedFormat(val advertised: List<String>) : PrintError("printer supports none of producible formats; pdl=$advertised")

    /** Create-Job failed or returned no job-id. */
    class CreateJobFailed(val status: Int) : PrintError("Create-Job failed: status=$status")

    /** Send-Document failed. */
    class SendDocumentFailed(val status: Int) : PrintError("Send-Document failed: status=$status")

    /** More than one document was supplied for a printer that only accepts single-document jobs. */
    object MultipleDocumentsUnsupported : PrintError("printer does not support multiple-document jobs")

    /** Job reached the IPP `canceled` state. */
    object JobCanceled : PrintError("job canceled")

    /** Job reached the IPP `aborted` state. [reasons] is the raw `job-state-reasons`, for logging only. */
    class JobAborted(val reasons: String) : PrintError("job aborted: $reasons")

    /** Job did not reach a terminal state before the polling deadline; Cancel-Job was attempted. */
    object JobTimeout : PrintError("job timed out")

    /** A page could not be decoded/rendered into the negotiated [format] (1-based [pageNumber]). */
    class PageRenderFailed(val pageNumber: Int, val format: String) : PrintError("page $pageNumber render failed for $format")
}
