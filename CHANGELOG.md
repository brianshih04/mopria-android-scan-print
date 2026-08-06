# Changelog

本專案依 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 的概念記錄重要變更；目前尚未建立 release tag。

## [Unreleased] - 2026-08-05

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
- 新增 10 種語言資源：English、日本語、한국어、Español、Português、Deutsch、Français、Русский、繁體中文、简体中文。
- 新增依 Android 系統語系自動選擇、未支援語系 fallback English，以及設定頁手動語言覆寫。
- 新增 `LanguageManager` 語系判斷單元測試。
- 新增 GitHub Actions CI workflow（push／PR 自動跑 unit test、lint、`assembleDebug`）。
- 新增直接 IPP 列印 client（`IppPrintClient` + `IppDocumentFormat` + `BoundedIppTransport`）與 `jipp-core` 依賴,實作 PWG 標準流程 Get-Printer-Attributes → Create-Job → Send-Document → Get-Job-Attributes → Cancel-Job,並支援 PDF／JPEG／PNG／PWG-Raster／URF 多格式協商。**已接線**:`IppDiscovery` 探索 `_ipp/_ipps` 印表機、`RealIntegrationProvider.print` 直接送件、ViewModel Real 模式路由(找不到 IPP 印表機時 fallback 系統列印);尚未以實體印表機驗證,capability 驅動列印選項 UI 仍待補。

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
- UI、ViewModel 事件、文件 metadata 與設定頁改用 Android string resources，語言切換不需修改程式碼。
- 工具鏈升級為 AGP 9.3.1、Gradle 9.5.0、JDK 25 daemon（對齊最新 Android Studio）。
- `compileSdk` 由 36 升至 37；`core-ktx`、`activity-compose`、`lifecycle-*` 升至最新。
- Release build 啟用 R8 minify 與 resource shrinking；release APK 由 ~42 MB 縮至 ~2.2 MB。
- `gradlew` 補回 Linux 執行權限（原由 Windows commit 丟失）。

### Removed

- 移除未使用的 `androidTest` 依賴（espresso、`androidx.test.ext:junit`、Compose ui-test）與 8 條未引用的 string resources。
- 測試 APK 不再 commit 進 repository，改由 [GitHub Release v0.1.0](https://github.com/brianshih04/mopria-android-scan-print/releases/tag/v0.1.0) 發布。

### Fixed

- 修正列印頁與 Android DocumentsUI 返回後無法回到 App 首頁的導覽問題。
- 修正「掃描文件」與「文件」曾導向相同內容的資訊架構問題。
- 修正完成的 eSCL 工作已從 ScannerStatus 移除時會被誤判為逾時。
- 修正非對稱 resolution range 的最大可用值對齊問題。
- 修正多頁 PDF payload 無法以實際 PDF 頁數呈現在文件庫、列印與匯出流程的問題。
- 修正取消、錯誤或 ADF 達 client 頁數上限時可能留下 scanner job 的問題。
- 修正 MIME header 與實際 payload 不一致時仍可能被接受的問題。
- 清除所有 lint 與 Kotlin compiler warnings（lint 36 → 0；compiler → 0）。
- 修正 eSCL `Retry-After` 對 null／空白值的解析；修正文件中的機器專屬路徑與失效連結。

### Security

- XML parser 停用 DOCTYPE、external general／parameter entities 與外部 schema/DTD 存取。
- 限制 eSCL 控制回應、錯誤回應與單一文件大小。
- 禁止 trust-all TLS 及 scanner-controlled cross-origin job／redirect URL。
- 規格 PDF 維持本機研究來源，不加入 repository。

### Verification

- 34 unit tests passed，0 failed。
- Android lint：0 errors，0 warnings。
- Debug APK build passed。
- Release APK build passed，並以 `apksigner verify` 驗證簽章。
- GitHub Actions CI 通過（`testDebugUnitTest` + `lintDebug` + `assembleDebug`，Linux + JDK 25）。
- API 36 emulator 通過 ADF 6 頁合併 PDF、Flatbed 2 頁逐頁合併、文件預覽／匯出、DocumentsUI 返回與列印頁返回流程。
- API 36 emulator 語系 smoke 通過：系統 English、設定頁 10 語言清單、Japanese 與簡體中文手動切換。
- Smoke flow logcat 無 app `FATAL EXCEPTION`。

## [0.1.0] - Initial prototype

- 建立 Kotlin／Compose Android scaffold。
- 建立 Mock／Real integration mode、首頁、文件、紀錄與設定。
- 建立 Android Print Framework 入口與第一版 eSCL prototype。
