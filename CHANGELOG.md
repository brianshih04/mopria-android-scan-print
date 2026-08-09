# Changelog

本專案依 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 的概念記錄重要變更；GitHub Release `v0.1.0` 已建立，目前合併後續變更記錄在 Unreleased。

## [Unreleased] - 2026-08-09

### Added — OpenCV Background Cleanup Foundation

- 加入官方 `org.opencv:opencv:4.14.0` Android AAR 與 OpenCV runtime smoke-test 基礎。
- 新增 `EnhancementStrength` enum：3 種背景淨化強度（Light / Normal / Strong），各有不同的對比度倍率（alpha）與白色閾值（threshold）。
- ScanScreen 新增 Light / Normal / Strong 強度選擇器（FilterChips），使用者可即時切換。
- BackgroundEnhancer 改為 file-first OpenCV morphology close + color divide + contrast pipeline，保留 PNG alpha。
- 新增 `EnhancementResult`：Applied、Skipped、Failed 可區分，並以暫存檔、輸出驗證與 atomic replace 保護原始掃描檔。
- 新增 JPEG／PNG signature、PDF／超大圖片／OpenCV unavailable 的安全降級，以及本地化狀態訊息。
- 新增 OpenCV runtime、MatScope、RGB fixture 與 source-integrity unit tests。
- 新增 3 條強度字串 × 10 種語言（enhance_light / enhance_normal / enhance_strong）。

### Added — eSCL Image Pipeline & OCR Option

- eSCL `NextDocument` 維持 stream-to-file，新增檔案優先的 ADF deskew、平台 auto-crop 與 duplex blank-page drop；失敗時保留原始檔並以 atomic replace 寫回。
- 新增 `OcrMode.MlKit` 選項、設定持久化、capability safety（不超過 300 dpi，支援時使用灰階）及本地化 skip／failure 狀態。
- 以 Google ML Kit Text Recognition v2 unbundled clients 實作 OCR；依 Latin、Chinese、Japanese、Korean script 建立 recognizer，模型準備交由 Google Play services 的 ModuleInstall 管理，不在 App 內放置 JNI、`.nb` 或自建模型下載器。
- 新增 OCR 語言 catalog 與區域分組設定：預設 English、繁中、簡中；日文、韓文與其他地區語言由使用者選擇後再提出 ML Kit script model 準備要求。官方不支援的 catalog 語言會標示為不可選取。
- OCR 啟用時自動套用 deskew／auto-crop；`OcrResult.Applied` 保留 ML Kit block／line／element／symbol 的座標、角度、語言與 confidence，並新增雙欄／寬版表格閱讀順序與保守數字格式化。OCR 與 Searchable PDF 都是預設關閉的獨立使用者選項；Searchable PDF 以 PDFBox、內建 Noto Sans TC 與按需下載的 JP／KR 地域字型、page-scoped OCR layout 及 crop／rotation 座標轉換產生不可見文字層，並以 NFKC 與明確 ToUnicode CMap 避免 CJK 相容部首破壞搜尋。沒有有效位置文字或缺少必要字型時回到普通 PDF。
- 新增三張中文樣本的正式輸出 instrumentation proof：三頁 PDF 可由 PDFBox 解析並抽取非空文字，render 後原始影像仍完整；此驗證不代表實機 OCR accuracy 或多語字型完整覆蓋。
- AndroidManifest 開啟 `android:largeHeap="true"`；OpenCV pipeline 使用 `MatScope` 明確釋放 native matrices，並以 256 MB peak／64 MB retained-PSS gate 驗證十頁 A4 300 dpi soak。

### Changed

- `ScanSettings.enhanceBackground` 型別從 `Boolean` 改為 `EnhancementStrength?`（null = 關閉）。
- `BackgroundEnhancer.enhanceImageFile()` 改為 suspend API，回傳 `EnhancementResult`。
- Document preset 預設 `EnhancementStrength.Normal`。

### Fixed — Code Review (commit f751698)

- P1: 移除不存在的 enhanceBackground 功能承諾 → 已重新實作為真實的影像處理 pipeline。
- P2: scanPreset 持久化至 SettingsStore，重啟後不再還原為 Document。
- P2: discoverDevices() 在 Real 模式下取得 scanner capabilities 並寫入 UI state。
- P2: 不支援的 DPI 改用 `nearestResolution()` 取最近值，不再取最低值。
- P2: PresetOption 改用 `Role.RadioButton` + `selectable` 語意，TalkBack 可辨識選取狀態。
- P3: PropertyEscape lint suppression 從 12 次清理為 1 次。

### Testing

- 背景淨化測試補上 RGB channel／色相、強度差異、PDF／unsupported format 及原始檔不變驗證；測試總數以當次 Gradle 輸出為準。
- 新增 deskew、auto-crop、blank-page、OCR native boundary、asset/package contract、設定持久化與 OpenCV memory soak instrumentation；最新 API 36 emulator instrumentation 22 tests 全部通過。
- Debug lint／assemble、Release assemble 與 release APK `zipalign -P 16` 驗證通過；16 KB page-size emulator、ARM model load／accuracy／PSS 與真實 scanner 仍待外部驗證。



### Added — Scan Editing & Tablet Adaptation

- 新增 `DocumentEditor` 純邏輯類：旋轉頁面（90°/180°/270°，累加 mod 360）、排序（前/後移動並重新編號）、刪除頁面（最後一頁刪除時連帶刪除文件）、裁切頁面（normalized CropRect）。
- 新增 `CropRect` data class：以 0.0–1.0 正規化座標表示裁切區域，含驗證（left < right, top < bottom, 0..1 範圍）。
- 新增 `EditScreen` composable：頁面列表含縮圖預覽（含旋轉），每頁可旋轉左/右、左/右移動、裁切（CropDialog 視覺覆蓋 + 四向滑桿）、刪除（確認對話框）。
- 新增 `WindowSizeHelper`：`isTabletLayout()` composable，偵測 ≥600dp 螢幕。
- `DocumentPage` 新增 `rotationDegrees` 和 `cropRect` 欄位。
- `ViewModel` 新增 `rotatePage`、`movePage`、`deletePage`、`cropPage` 方法。
- DocumentsScreen 新增「編輯」按鈕，進入 EditScreen。
- `MopriaApp` 平板模式：≥600dp 使用 `NavigationRail`（左側）取代 `NavigationBar`（底部），底部導航隱藏。
- HomeScreen、ScanScreen、DocumentsScreen、SupportScreens 平板模式限制最大 720dp 內容寬度。
- 新增編輯相關字串 × 10 種語言（edit_title, edit_rotate_left/right, edit_move_left/right, edit_delete_page, edit_delete_page_confirm, edit_cancel, edit_confirm, edit_no_pages）。

### Changed

- 透視校正從規劃中移除：eSCL 掃描器產生的影像已是物理擺正，不需要軟體透視校正。

### Testing

- JVM unit tests 由 99 增至 116（+17）。
- 新增 `DocumentEditorTest`（17 tests）：旋轉累加/wrap-around/無效角度、排序前後移動/邊界、刪除頁面/最後一頁/不存在頁面、裁切設定/清除/驗證。

## [Unreleased] - 2026-08-08

### Added — Architecture & Quality Refactor

- 新增 `ScanError` 結構化掃描錯誤（mirrors `PrintError` pattern）：8 種子類型，ViewModel 映射至 string resources，取代直接顯示 raw exception message。
- 新增 7 條掃描錯誤字串 × 10 種語言（英/日/韓/西/葡/德/法/俄/繁中/簡中），共 70 條新翻譯。
- 新增 `PdfPageRenderer`（domain 層）：共用 PDF 頁面建立迴圈、bitmap 載入與 aspect-ratio fit-to-page，`ScanExportService` 和 `RealIntegrationProvider` 統一使用。
- 新增 `DocumentStore`：以 `org.json` 將文件列表與 Flatbed session 持久化至內部儲存，App 被系統殺掉後可恢復；啟動時自動過濾磁碟上已不存在的過期檔案引用。
- 新增 `SettingsStore`：從 ViewModel 提取所有 SharedPreferences 讀寫邏輯。
- 新增 `TempFileCleanup`：三層暫存檔清理機制（啟動時 orphan scan sweep + stale cache sweep、使用者刪除文件時、掃描失敗時）。
- 新增 `ScannerCapabilities`：Real 模式探索後取得 eSCL capability 並映射為 UI 選項集；ScanScreen 依據 caps 動態過濾解析度、掃描來源、色彩模式。
- 新增 `ScanAcquisitionProvider.scannerCapabilities()` 介面方法與 `RealIntegrationProvider` 實作。
- 新增 `MopriaUiState.scannerCapabilities` 欄位與 ViewModel 探索時自動取得。
- 新增文件刪除功能：DocumentsScreen 紅色刪除按鈕，刪除時同時清除原始掃描檔案。
- 新增 `scan_searching_devices` 字串 × 10 種語言。
- 新增 `documents_delete` 與 `event_document_deleted` 字串 × 10 種語言。
- Theme 加入 `surfaceContainerLow/Container/High/Highest` 與 `onError`，Dark Mode 配色完整定義。
- ScanScreen 解析度、來源、色彩選項改為 capability-driven（Real 模式只顯示掃描器支援的選項）。
- PageThumbnail 加入 `contentDescription` 與 `Role.Button` 語義，改善 TalkBack 無障礙支援。

### Changed — IppRasterizer Streaming & Bug Fixes

- `IppRasterizer` 改為逐頁串流：`StreamingPdfPageIterator` 每次只保留一頁 bitmap，峰值記憶體從 N 頁降至 1 頁。
- `RenderableDocument.iterator()` 每次呼叫建立新迭代器，修正 PclmWriter 內部多次遍歷（`handleSides` → `count()` → `mapPages()`）導致 PCLm 輸出空白的嚴重 bug。
- PDF 頁面渲染改用 `RENDER_MODE_FOR_PRINT`（原 `RENDER_MODE_FOR_DISPLAY`），確保列印色彩正確。
- 灰階轉換改用 `roundToInt()`（原 `toInt()` 截斷），避免系統性偏暗。
- `IppRasterizer.render()` 新增 RGBA ColorSpace 分支（4 bytes/pixel 含 alpha）。
- `IppRasterizer.rasterize()` 失敗時刪除 partial output 檔案。
- Domain 層所有硬編碼中文/英文混合錯誤訊息改為英文開發者訊息；使用者面向的錯誤改拋 `ScanError`。
- `RealIntegrationProvider.validateScannerReady()` 改拋 `ScanError.ScannerNotReady` / `ScanError.AdfNotReady`。
- `DocumentsScreen` 按鈕文字從硬編碼 "PDF"/"JPEG" 改用 string resources。

### Changed — i18n Cleanup

- 清除所有 `.kt` 原始碼中的硬編碼中文字串（EsclHttpClient、EsclProtocol、RealIntegrationProvider、IppPrintClient、ScanExportService、SystemPrintAdapter、UriPrintAdapter、ScanScreen、DocumentPageBitmapLoader）。
- ViewModel `scan()` 新增 `ScanError` catch block 與 `messageStringRes()` 映射。
- ViewModel `scan()` generic exception 不再將 raw `error.message` 顯示給使用者。

### Testing

- JVM unit tests 由 62 增至 99（+37）。
- 新增 `ScannerCapabilitiesTest`（8 tests）：`fromEscl()` capability 映射、解析度交集、來源/色彩 fallback。
- 新增 `PrintCapabilitiesTest`（7 tests）：`hasAnyOption()`、`coerceTo()` 支援值保留/不支援值丟棄/邊界值。
- 新增 `IppDocumentFormatTest`（7 tests）：格式偏好順序、case-insensitive match、octet-stream 不視為通用匹配。
- 重寫 `IppRasterizerTest`（3→13 tests）：新增 multi-pass iterator 驗證（PCLm 多頁、奇數頁）、RGB/RGBA/Grayscale 像素轉換正確性、`renderSize` 計算。
- 擴充 `EsclProtocolTest`（+7 tests）：URL 安全（query/fragment/userInfo 拒絕）、`ScanError.CapabilityNotSupported` 路徑、nearest resolution tie-breaking。
- 擴充 `ScanDocumentOrganizerTest`（+5 tests）：空文件例外、existing id 保留、多頁順序。


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
- 新增 7 張 API 36 emulator 主畫面 JPEG。
- 新增 GitHub Release `v0.1.0` 的 `avi-print-scan.apk` 測試資產；APK 不存放在 repository。
- 新增 10 種語言資源：English、日本語、한국어、Español、Português、Deutsch、Français、Русский、繁體中文、简体中文。
- 新增依 Android 系統語系自動選擇、未支援語系 fallback English，以及設定頁手動語言覆寫。
- 新增 `LanguageManager` 語系判斷單元測試。
- 新增 GitHub Actions CI workflow（push／PR 自動跑 unit test、lint、`assembleDebug`）。
- 新增直接 IPP 列印 client（`IppPrintClient` + `IppDocumentFormat` + `BoundedIppTransport`）與 `jipp-core`／`jipp-pdl` 依賴，實作 Get-Printer-Attributes → Create-Job → Send-Document → Get-Job-Attributes polling → Cancel-Job timeout cleanup；支援 PDF／JPEG／PNG／PWG-Raster／PCLm，並使用 fixed-length HTTP body。
- 新增 `IppDiscovery` 探索 `_ipp/_ipps`、`RealIntegrationProvider.print` 的 PDF raster 化、解析度／色彩／PCLm strip-height 協商，以及 capability-constrained job options。
- 新增「列印方式」設定（系統列印／直接 IPP）；**預設系統列印**，Direct IPP 為 opt-in。找不到 IPP 印表機時顯示明確錯誤，不會默默切換到系統列印。

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
- 恢復 Direct IPP 所需的 `androidTest` dependencies，並加入列印方式 persistence、MainActivity smoke 與 rasterizer smoke coverage。
- Direct IPP 圖片列印改以紙張 point size 與協商 DPI 計算像素預算，來源 bitmap 最高 300 dpi，避免先降為約 72 dpi 後再放大列印。
- JPEG／PNG 多頁列印會解析 `multiple-document-jobs-supported`；不支援多文件 job 時，改為逐頁建立單文件 job。
- PR #5 已將 Direct IPP reliability fixes 與文件同步合併到 `main`。

### Removed

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
- 修正 GitHub Actions Android SDK 安裝使用錯誤的 `platforms;android-37` package id，改用 `platforms;android-37.0`。
- 修正 Direct IPP 列印前置 discovery／capability 例外可能留下 busy 狀態或成為未捕捉 coroutine exception。

### Security

- XML parser 停用 DOCTYPE、external general／parameter entities 與外部 schema/DTD 存取。
- 限制 eSCL 控制回應、錯誤回應與單一文件大小。
- 禁止 trust-all TLS 及 scanner-controlled cross-origin job／redirect URL。
- 規格 PDF 維持本機研究來源，不加入 repository。

### Verification

- 62 unit tests passed，0 failed。
- Android lint：0 errors，0 warnings。
- Debug APK build passed。
- Release APK build passed；本機 Gradle release 輸出為 unsigned，GitHub Release `v0.1.0` 的測試 APK 另以本機 debug keystore 簽署，產品發布仍需正式簽章。
- GitHub Actions CI 通過；PR #5 與 merge commit `b00fa09` 的 `main` push 均完成 `testDebugUnitTest` + `lintDebug` + `assembleDebug`（Linux + JDK 25）。
- API 36 emulator 通過 ADF 6 頁合併 PDF、Flatbed 2 頁逐頁合併、文件預覽／匯出、DocumentsUI 返回與列印頁返回流程。
- API 36 emulator 語系 smoke 通過：系統 English、設定頁 10 語言清單、Japanese 與簡體中文手動切換。
- API 36 emulator instrumentation tests：5 passed，0 failed。
- Smoke flow logcat 無 app `FATAL EXCEPTION`。

## [0.1.0] - Initial prototype

- 建立 Kotlin／Compose Android scaffold。
- 建立 Mock／Real integration mode、首頁、文件、紀錄與設定。
- 建立 Android Print Framework 入口與第一版 eSCL prototype。
