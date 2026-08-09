# UI/UX and Code Review Report

Date: 2026-08-02  
Historical implementation branch: `agent/real-integration-ui`; integrated result now lives on `main`.

## Outcome

The app now uses an icon-first, widget-dashboard home inspired by the supplied mobile dashboard reference. Scan is the dominant action, print and device readiness are square widgets, and recent work is compact. Supporting screens use the same Material 3 system with short labels, persisted scan settings, accessible semantics, dark mode, and large-text tolerance.

## UI changes

| Before | After | Why |
|---|---|---|
| Repeated top bar, status banner, action cards, and explanatory paragraphs | App-local dashboard header plus one scan hero, two square widgets, and a compact recent card | Makes scan and print recognizable at a glance and matches the supplied widget reference |
| Text-heavy instructions on primary screens | Icons, numeric settings, short labels, and one-line status | Lowers reading effort while preserving essential context |
| Scan settings and documents embedded in one large source file | Dedicated Home, Documents, Support, and Theme files | Reduces UI coupling and makes each flow easier to review and maintain |
| Device readiness expressed mainly as prose | Circular scanner/print indicators and concise readiness label | Communicates state spatially and remains accessible through content descriptions |
| Generic app appearance | Indigo/teal light and dark schemes, rounded shape system, stronger type hierarchy, custom launcher icon | Gives the prototype a coherent modern product identity |

## Material code-review findings fixed

| Area | Finding | Resolution |
|---|---|---|
| Print architecture | Real print was gated by the app's own IPP discovery | Real documents now go directly to Android `PrintManager`; Android Print Service owns printer discovery and IPP/IPPS |
| Print adapters | Rendering ran in adapter callbacks without complete page-range/media-size handling | Rendering runs on IO coroutines, honors requested `PageRange` and media size, and cancels worker scopes in `onFinish` |
| Image memory | Full-resolution image decoding could exhaust memory | Shared sampled bitmap loader limits render dimensions; scan export preserves original JPEG streams when possible |
| eSCL download | Full scan payloads could accumulate in memory | `NextDocument` streams to a partial file, enforces response limits, atomically promotes valid pages, and removes partial files on failure |
| eSCL job URL | Scanner-controlled `Location` could redirect to another origin | URL resolution now requires the scanner's scheme, host, and effective port; regression test added |
| Capabilities | Requested ADF source/color could differ from the actual fallback sent | Provider records and submits the actual supported source and color fallback |
| Cancellation | Coroutine cancellation could be reported as a generic failure or leave busy state behind | Cancellation is rethrown separately and jobs transition to `Cancelled` with active state cleared |
| Discovery lifecycle | NSD listeners could survive failed start/cancellation paths | Listener registration and completion are guarded and cleaned up consistently |
| Storage/privacy | Android 9 export permission and scan backup behavior were incomplete | Added scoped Android 9 permission flow and backup/data-transfer exclusions for scan files |
| App packaging | Launcher icon was absent | Added an icon matching the new scan/print visual language |

## Verification

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --offline --console=plain`: passed.
- Unit tests: 9 passed, 0 failed, 0 skipped.
- Android lint: 0 errors, 5 dependency-update warnings.
- `git diff --check`: passed.
- Emulator: `Brian_Pixel_8_API_36`, API 36, final debug APK installed successfully.
- Home smoke test: scan hero, print widget, device widget, mode status, and bottom navigation present.
- Accessibility/layout smoke test: light mode, dark mode, font scale 1.0 and 1.3; principal controls remained visible and tappable.
- Flow smoke test: persisted ADF / 600 dpi / grayscale settings, mock scan completed with 3 pages, recent job showed completed state.
- Real-mode smoke test: no scanner produced the expected no-device state and did not fall back to mock.
- Runtime log check: no fatal exception, OOM, or app ANR observed during the final smoke tests.

## Remaining physical-device risks

- Cross-brand discovery, capabilities, Flatbed/ADF behavior, and disconnection recovery still require Canon/Brother/Fujifilm or other eSCL hardware on the same Wi-Fi.
- Actual print completion, cancellation, duplex, paper, and color behavior require Android Print Service plus physical printers.
- `_uscan` commonly exposes cleartext local HTTP. The app's eSCL client only uses NSD-resolved scanner endpoints and rejects cross-origin job locations, but transport confidentiality is not guaranteed until `_uscans` certificate/TOFU handling is implemented.
- Android target SDK 37 will require migration to the new local-network permission model.
- Capability-driven option filtering, manual IP/URL, background/resumable scanning, large-document soak tests, and TalkBack manual testing remain future milestones.

## 2026-08-02 follow-up

The subsequent eSCL v2.97 pass retained the redesigned UI and added a user-configurable ADF limit (1–50), optional ADF multi-page PDF assembly, and a Flatbed next-page／finish-PDF dialog. The latest baseline is 30 unit tests, 0 lint errors, successful debug assembly, ADF 6-page and Flatbed 2-page emulator flows, and successful DocumentsUI／print-page return navigation. See `.workflow/escl-v2.97-compliance/final-report.md` for the protocol-focused report.
