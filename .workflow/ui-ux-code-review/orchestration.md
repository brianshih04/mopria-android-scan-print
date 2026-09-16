# Orchestration

> Archived snapshot（2026-08-02）：以下保留 UI／UX pass 的原始執行順序。2026-08-11 的 `main` 已在此 UI 架構上加入 Direct IPP、OpenCV、Google ML Kit OCR 與 Searchable PDF；最新狀態請見 `README.md`、`HANDOFF.md`、`dev_plan.md`。

Status: completed. This file is retained as the historical execution record for the UI/UX pass.

1. Capture the current UI and inspect all production and test sources.
2. Record UX and code findings by severity and decide which are in scope.
3. Build a compact Material 3 design system and redesign app shell, home, documents, history, and settings.
4. Fix accepted domain, lifecycle, networking, storage, and accessibility findings.
5. Add focused tests for changed behavior, then run unit tests, lint, and debug build.
6. Install the APK on `Brian_Pixel_8_API_36`, exercise Mock and Real states, and inspect screenshots/UI hierarchy/logcat.
7. Write the final report with accepted fixes, remaining physical-device risks, and verification evidence.

No subagents are used because this goal did not authorize delegated work. Review packets are performed as isolated passes in the current task.

The later eSCL compliance pass reused the same app shell and verified that scan settings, Flatbed next-page dialog, ADF page slider, documents, print picker return, and home return continue to work on the API 36 emulator.
