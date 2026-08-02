# Changelog

本專案依 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 的概念記錄重要變更；目前尚未建立 release tag。

## [Unreleased] - 2026-08-02

### Added

- 新增 eSCL DNS-SD TXT parser，支援 `rs`、`uuid`、`vers`、`ty`、`pdl` 與 `_uscans` 優先去重。
- 新增 namespace-aware、XXE-hardened 的 eSCL v2.97 capability/status parser。
- 新增 source-specific SettingProfile／reference、離散與 ranged X/Y resolution 協商。
- 新增 ScannerStatus／JobInfo 同步、bounded 503 `Retry-After`、逾時狀態確認與 ScanJob `DELETE` 清理。
- 新增 JPEG、PNG、PDF payload signature 驗證及 PDF-backed 多頁預覽。
- 新增 ADF 1–50 頁使用者設定。
- 新增 ADF「合併為多頁 PDF」選項；關閉時可將頁面拆成單頁文件。
- 新增 Flatbed「逐頁合併 PDF」選項及「下一頁／完成 PDF」工作流，最多 50 頁。
- 新增共用 `DocumentPageBitmapLoader`，讓文件縮圖、匯出與列印支援圖片及 PDF 頁面。
- 新增文件 merge／split 純邏輯與測試。
- 新增 `.workflow/escl-v2.97-compliance` 合規計畫、狀態與最終報告。
- 新增 `HANDOFF.md`，記錄第三方接手所需架構、驗證與剩餘風險。
- 新增 GitHub 可下載的 `release/avi-print-scan.apk` 及 7 張 API 36 emulator 主畫面 JPEG。

### Changed

- ADF eSCL `InputSource` 從非規格值 `ADF` 修正為 `Feeder`。
- eSCL 2.1+ ScanSettings 改用 `DocumentFormatExt`；2.0 才使用 legacy `DocumentFormat`。
- ScanSettings 現在包含協商後的 PWG Version、X/Y resolution 與設備支援時的 `NumberOfPages`。
- 真實 discovery 改為遵守設備自訂 resource root、IPv6 scope 與較完整的 8 秒探索窗口。
- 真實 scan 改為先檢查 scanner／ADF 狀態、等待工作可傳輸，並以 `404`／Completed 判定結束。
- HTTP response code、redirect 與 ScanJob Location policy 更嚴格；拒絕跨主機、降級、query、fragment 與 path traversal。
- HTTPS 要求 Android TLS provider 支援 TLS 1.3，且維持系統 trust store。
- Mock ADF 頁數改為使用 UI 設定，不再固定 3 頁。
- 首頁掃描摘要會顯示 ADF 頁數與 PDF 合併狀態。
- README 與開發計畫改為反映實際單 module 架構與目前驗證範圍。

### Fixed

- 修正列印頁與 Android DocumentsUI 返回後無法回到 App 首頁的導覽問題。
- 修正「掃描文件」與「文件」曾導向相同內容的資訊架構問題。
- 修正完成的 eSCL 工作已從 ScannerStatus 移除時會被誤判為逾時。
- 修正非對稱 resolution range 的最大可用值對齊問題。
- 修正多頁 PDF payload 無法以實際 PDF 頁數呈現在文件庫、列印與匯出流程的問題。
- 修正取消、錯誤或 ADF 達 client 頁數上限時可能留下 scanner job 的問題。
- 修正 MIME header 與實際 payload 不一致時仍可能被接受的問題。

### Security

- XML parser 停用 DOCTYPE、external general／parameter entities 與外部 schema/DTD 存取。
- 限制 eSCL 控制回應、錯誤回應與單一文件大小。
- 禁止 trust-all TLS 及 scanner-controlled cross-origin job／redirect URL。
- 規格 PDF 維持本機研究來源，不加入 repository。

### Verification

- 30 unit tests passed，0 failed。
- Android lint：0 errors，9 non-blocking warnings。
- Debug APK build passed。
- Release APK build passed，並以 `apksigner verify` 驗證簽章。
- API 36 emulator 通過 ADF 6 頁合併 PDF、Flatbed 2 頁逐頁合併、文件預覽／匯出、DocumentsUI 返回與列印頁返回流程。
- Smoke flow logcat 無 app `FATAL EXCEPTION`。

## [0.1.0] - Initial prototype

- 建立 Kotlin／Compose Android scaffold。
- 建立 Mock／Real integration mode、首頁、文件、紀錄與設定。
- 建立 Android Print Framework 入口與第一版 eSCL prototype。
