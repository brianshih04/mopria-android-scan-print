# eSCL v2.97 pull-scan compliance

> Archived snapshot（2026-08-02）：本計畫的 scope boundary 只描述該次 eSCL change。OCR、OpenCV file-first processing 與 opt-in Searchable PDF 已在後續工作合併到 `main`；目前產品範圍與未完成 gate 以 `README.md`、`HANDOFF.md`、`dev_plan.md` 為準。

## Goal

Bring the Android app's supported eSCL pull-scan workflow into alignment with the Mopria Alliance eSCL Technical Specification v2.97, verify it, then commit and push the result to `main`.

## Success criteria

- ScanSettings includes the mandatory PWG version and schema-correct namespaces and values.
- Capability selection respects input-source setting profiles, format/color constraints, discrete resolutions, and resolution ranges.
- DNS-SD discovery honors TXT `rs`, identity, version, and format fields and deduplicates HTTP/HTTPS advertisements.
- HTTP handles bounded 503 Retry-After, secure redirects, chunked transfer requests, status checks, and DELETE cancellation.
- Pull scans support both mandatory JPEG and PDF result formats without loading whole multi-page jobs into memory.
- ADF page count is user-configurable from 1 to 50; `NumberOfPages` is sent only when the device advertises `SelectSinglePage`.
- Flatbed can repeat independent Platen jobs and merge selected pages into one PDF; ADF can keep one multi-page document or split pages.
- Cross-origin redirect/job URLs remain rejected and TLS trust is never weakened to trust-all.
- Tests cover schema output, capability constraints, retry, redirect rejection, status, cancel, JPEG/PDF payloads, and relevant error paths.
- Unit tests, Android lint, debug build, and emulator smoke checks pass.
- The confidential specification PDF remains outside Git.

## Scope boundary

The app implements pull scan. Optional Push Scan, Stored Job Requests, OCR, encrypted PDF requests, ScanBufferInfo sizing, duplex UI, and vendor certification are not claimed by this change.

## Risks

- Broad protocol changes can break tolerant devices that accepted the prototype XML.
- Retrying POST or download operations incorrectly can duplicate work.
- PDF rendering can consume excessive memory.
- Redirect or Location handling can introduce SSRF or downgrade vulnerabilities.

## Verification

Run narrow protocol tests first, then the full unit suite, lint, debug build, and emulator smoke flow with a single Gradle worker.

## Completion record

Implementation and local verification completed on 2026-08-02:

- 30 JVM unit tests passed.
- Android lint completed with 0 errors and 9 non-blocking warnings.
- Debug APK assembled successfully.
- API 36 emulator passed ADF 6-page merge, Flatbed 2-page merge, document output, and print/navigation smoke checks.
- Physical cross-brand scanner/printer certification remains outside this workflow and is tracked in `dev_plan.md` and `HANDOFF.md`.
