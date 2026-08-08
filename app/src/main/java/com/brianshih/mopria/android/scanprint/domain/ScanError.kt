package com.brianshih.mopria.android.scanprint.domain

/**
 * Localizable eSCL scan failures.
 *
 * Mirrors [PrintError]: carries only structured data — never user-facing display text — so the UI layer
 * can map each subtype to a localized string resource without the domain layer depending on Android
 * resources. Throw the matching subtype from the eSCL client/provider; the ViewModel maps it to a
 * string resource id via [messageStringRes].
 *
 * Technical failures (XML parse errors, version validation, URL policy violations) are left as plain
 * exceptions with English-only messages; they indicate programmer or protocol errors, not actionable
 * user conditions, and fall through to a generic "scan failed" message in the ViewModel.
 */
sealed class ScanError(message: String) : RuntimeException(message) {
    /** Scanner is not idle: [state] is the raw pwg:State (e.g. "Processing", "Stopped", "Down"). */
    class ScannerNotReady(val state: String) : ScanError("scanner not idle: state=$state")

    /** ADF tray is empty or in an abnormal state. [adfState] is the raw scan:AdfState. */
    class AdfNotReady(val adfState: String) : ScanError("ADF not ready: $adfState")

    /** Scan job creation failed or an HTTP error occurred (e.g. 401, 500). [statusCode] is the HTTP code, or 0 for a non-HTTP failure. */
    class HttpError(val statusCode: Int) : ScanError("scan HTTP error: status=$statusCode")

    /** Timed out waiting for the next scanned page. */
    object DocumentTimeout : ScanError("NextDocument timed out")

    /** Scan job reached Canceled or Aborted state. [state] is the raw eSCL job state. */
    class JobAborted(val state: String) : ScanError("scan job $state")

    /** Scan job did not reach Completed before the polling deadline. */
    object JobTimeout : ScanError("scan job timed out waiting for completion")

    /** Scanner does not support the requested source/color/format/resolution combination. */
    object CapabilityNotSupported : ScanError("scanner does not support requested settings")

    /** Scan job completed but produced zero image pages. */
    object NoImages : ScanError("scan job returned no images")
}
