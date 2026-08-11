# eSCL v2.97 Compliance Workflow Report

> Archived snapshot（2026-08-02）：本文件記錄當時的 eSCL compliance pass，不是目前功能清單或最新測試報告。2026-08-11 的 `main` 已另外包含 Direct IPP、OpenCV 影像管線、Google ML Kit OCR 與 opt-in Searchable PDF；現況以 repository root 的 `README.md`、`HANDOFF.md` 與 `dev_plan.md` 為準。

Date: 2026-08-02
Target branch: `main`

## Outcome

The supported pull-scan surface now aligns with the reviewed Mopria eSCL v2.97 requirements for XML version/namespaces, source profiles, format and resolution negotiation, DNS-SD metadata, scanner/job status, bounded HTTP retry and streaming, secure URL handling, cancellation, and JPEG/PDF page mapping.

ADF page count is user-configurable from 1 to 50. Devices advertising `SelectSinglePage` receive `NumberOfPages`; other devices are stopped at the client limit and the remaining job is deleted. Flatbed can run repeated one-page Platen jobs and merge them into a single multi-page PDF. ADF output can be combined or split according to the user's setting.

## Material corrections

- Replaced the invalid ADF source value with PWG `Feeder`.
- Required valid PWG Version and namespace-aware capability/status parsing.
- Used `DocumentFormatExt` for eSCL 2.1+ and source-specific SettingProfiles.
- Added X/Y discrete and range resolution negotiation.
- Honored DNS-SD TXT resource root and preferred `_uscans` for duplicate devices.
- Required `201 + Location`, restricted job URLs, handled status/retry/end/cancel, and removed trust-all behavior.
- Added bounded JPEG/PNG/PDF streaming and PDF page rendering.
- Added configurable ADF and interactive Flatbed multi-page PDF workflows.

## Verification

- Unit: 30 passed, 0 failed.
- Lint: 0 errors, 9 warnings.
- Assemble debug: passed.
- Diff whitespace check: passed.
- Emulator: ADF 6-page combined PDF, Flatbed 2-page combined PDF, document previews, export, system file picker return, and print-page return passed.
- Runtime: no app fatal exception or OOM in the smoke flow.

## Declared boundary

Push Scan, Stored Jobs, OCR, encrypted PDF requests, ScanBufferInfo, duplex UI, credentials, manual endpoints, raw RFC 2817 in-connection Upgrade, direct IPP, physical-device certification, and Mopria certification are not claimed.

The confidential source specification PDF remained outside Git. Remaining physical-device risks and the next validation matrix are documented in `HANDOFF.md` and `dev_plan.md`.
