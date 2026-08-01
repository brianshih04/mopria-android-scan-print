# Development Plan

## 1. 目標與原則

本專案要提供跨品牌的 Android 掃描與列印入口。列印能力與印表機選擇交給 Android Print Framework；掃描則由 App 透過 eSCL 直接探索及操作相容掃描器。App 同時負責文件選擇、預覽、編輯、輸出與工作紀錄。

優先原則：

1. 連線可靠，錯誤可診斷。
2. 掃描與列印的主要流程保持短而清楚。
3. 進階設定不阻礙一般使用者。
4. 文件預設留在本機，不以雲端上傳作為必要條件。
5. 協定與廠商整合封裝在 adapter 後方，避免 UI 綁定單一 SDK。

## 2. MVP 範圍

### 必須完成

- PDF 與 JPEG／PNG 列印。
- App 文件預覽，以及系統列印介面的紙張、份數、方向、色彩、雙面及頁面範圍。
- 單頁與多頁掃描。
- 在本 App 內依 eSCL capabilities 選擇掃描解析度、色彩模式、來源、範圍及輸出格式。
- 頁面裁切、旋轉、排序、刪除及透視校正。
- 合併並輸出 PDF。
- 掃描後直接列印。
- 最近工作與明確的成功／失敗狀態。
- 常用列印／掃描設定 Favorites。

### MVP 之後

- OCR 與可搜尋 PDF。
- 雲端同步。
- 遠端列印。
- 企業帳號、PIN、安全列印及 accounting。
- NFC／QR 快速加入裝置。
- App 內自訂裝置探索、手動 IP 與即時裝置儀表板。
- 耗材採購與廠商服務入口。
- Fax、Scan to Email、Scan to Folder。

## 3. 第一個技術驗證：Mopria 邊界

本節以 Mopria 官方繁體中文資料為準：[官方首頁](https://mopria.org/zh-tw/)、[從 Android 列印](https://mopria.org/zh-tw/print-from-android)、[掃描到 Android](https://mopria.org/zh-tw/scan-to-android)。競品 App 僅作 UX 參考，不作為 Mopria API 或相容性的證據。

### 列印

先以 Android `PrintManager` 和 `PrintDocumentAdapter` 完成。Mopria Print Service／Android Default Print Service 負責印表機探索、能力設定及工作傳送。

一般 App 不自行列舉或選擇 Mopria 印表機。若未來需要 Canon／Brother 式裝置儀表板，必須另行驗證 IPP／mDNS 或 Print Service 實作範圍，不視為 Android Print Framework 已提供的能力。

驗證項目：

- PDF 與 bitmap 的縮放和頁面尺寸。
- Android 系統列印 UI 可提供的能力集合。
- 工作取消、服務缺失、印表機離線與 Wi-Fi Direct 情境。
- App 是否需要額外的裝置探索，只用於首頁狀態而非取代系統列印服務。

### 掃描

掃描 MVP 直接實作 eSCL。開始正式 UI 前完成一個 time-boxed spike：

1. 接受並保存 Mopria eSCL Specification 的適用授權紀錄，以 clean-room 方式實作，不複製 GPL 專案程式碼。
2. 使用 Android `NsdManager` 探索 `_uscan._tcp` 與 `_uscans._tcp`，並支援手動 IP／URL。
3. 完成 `ScannerCapabilities`、`ScannerStatus`、建立 `ScanJobs`、輪詢、`NextDocument` 與取消／清理的最小 client。
4. 驗證 HTTP、IPPS、自簽憑證、IPv4、IPv6、Platen、ADF、duplex、單頁與多頁。
5. 驗證不完整／錯誤 capability XML、逾時、503、掃描器離線、程序死亡及部分頁面完成等情境。
6. 將掃描內容逐頁串流寫入 app-controlled storage，不在記憶體累積完整多頁文件。

輸出 ADR-002，第一版預設採用：

- `EsclScanProvider`：MVP 主要實作。
- `ExternalMopriaScanProvider`：僅保留為可選 fallback，不作為 MVP 依賴。

實作產品可以描述為支援 eSCL／AirScan 相容掃描器；未完成 Mopria 認證前，不宣稱產品為 Mopria Certified。

### 開源 repo review 結論

Review 固定在以下版本，後續升級必須重新檢查差異：

- ScanBridge：`5d9e2b1ad63f95041e681ce39146dc25bc9fc648`
- JIPP：`f3a484ff539032194f5af164c6f57fbc4370b026`

#### ScanBridge

可參考的設計：

- `_uscan._tcp`／`_uscans._tcp` 探索與手動 URL fallback。
- scanner capabilities、ADF／Platen、解析度、色彩與格式的正規化。
- 掃描 session/page 資料模型、前景工作、多頁編輯及 PDF／ZIP 輸出 UX。

不得直接沿用的部分：

- 專案與 eSCLKt 依賴為 GPL-3.0-or-later；本專案未決定採 GPL 前，不複製程式碼、不加入 binary dependency。
- 工作佇列為記憶體 Channel，且 enqueue 與 foreground service 啟動存在 race；本專案必須先持久化工作，再排程執行。
- 掃描文件位於 `filesDir`，卻允許 Android backup；本專案必須明確排除文件、暫存與工作資料。
- PDF 匯出在 Main dispatcher 執行，分段檔名在 10 個以上 chunks 時不一致；本專案採串流／IO dispatcher，且以單一產生的檔案清單為準。
- trust-all TLS、全域 cleartext、未關閉 HTTP client 與 mDNS executor 不得照搬。

#### JIPP

- `jipp-core` 為 MIT 授權，可列入 MVP 後的直接 IPP spike。
- JIPP 不提供 Android discovery 或正式 HTTP transport；需自行以 `NsdManager` 與 OkHttp/Ktor 實作。
- 採用前必須修正或隔離負 `value-length` 造成的 `NegativeArraySizeException`（[Issue #222](https://github.com/HPInc/jipp/issues/222)）。
- `jipp-pdl` 的 PCLm content stream 有 `q/Q` 不平衡問題（[Issue #121](https://github.com/HPInc/jipp/issues/121)），PWG reader 亦需補強 partial-read、記憶體與大型頁面測試，因此不列入 MVP。
- Sample transport 的 trust-all self-signed 模式、缺少 read timeout 與完整 response buffering 不得帶入產品。

#### 架構決策

1. MVP 不依賴 ScanBridge、eSCLKt、JIPP 或其他 GPL 掃描協定 library；eSCL client 依正式規格 clean-room 自建。
2. MVP 列印使用 Android Print Framework；MVP 掃描使用 `EsclScanProvider`。
3. eSCL 屬 MVP，直接 IPP 屬第二階段；兩者分別建立 ADR、license review、threat model 與設備相容性矩陣。
4. 若採用 `jipp-core`，維護一層本專案控制的 parser boundary、transport、timeout、取消與憑證信任策略。

#### Review 測試基線

- ScanBridge `core:jvmTest`：14 tests passed；範圍僅涵蓋 IPv4、IPv6 與紙張格式，不能視為掃描工作、前景服務或 PDF 匯出已被驗證。
- JIPP `jipp-pdl`：45 tests，44 passed、1 skipped。
- JIPP `jipp-core`：180 tests，178 passed、1 skipped、1 因 Mockito 2.15 不相容 JDK 21 而失敗。
- JIPP 預設 source build 在 JDK 21 會遇到 Java target 21／Kotlin target 1.8 不一致，以及 test `-Werror` 對新版 JDK deprecation 報錯；採用 Maven artifact 不等於 upstream source build 可重現，若建立 fork 必須先現代化 toolchain 與測試依賴。
- 當時 `adb devices -l` 沒有連接中的裝置，因此以上不包含 Android instrumentation 或實機列印／掃描驗證。

## 4. UX 與資訊架構

### 底部導覽

- 首頁
- 文件
- 工作紀錄
- 設定

### 首頁

- 列印可用性卡片：Android 列印功能與列印服務狀態。
- 掃描可用性卡片：網路狀態、已找到的 eSCL 掃描器與手動加入入口。
- 快速操作：文件列印、相片列印、掃描文件、掃描相片、掃描後複印。
- 最近工作。

### 設計參考的取捨

- 採 Canon／Brother 的簡短首頁與大型操作入口。
- 採 Brother 的引導式裝置加入、狀態及歷史紀錄。
- 採 Fujifilm 的多裝置、Favorites、進階設定與文件透視校正。
- 不加入品牌限定促銷、耗材訂閱或專有雲端服務。

### UI/UX prototype（已完成第一版）

- Compose single-activity app shell，使用 Material 3 色彩、卡片、狀態 chip 與底部導覽。
- 首頁提供「掃描文件」、「列印文件」、「裝置狀態」及「最近工作」四個工作區塊。
- 文件庫、工作紀錄、設定頁先提供可理解的空狀態與協定邊界說明。
- Mock mode 的掃描、列印與搜尋會透過 provider 執行；Real mode 會透過真實 provider 搜尋區域網路，並以 Snackbar 顯示完成、找不到設備或尚未完成的協定功能。
- 已加入基本 content description、可見文字與 Material touch target；真實字體縮放、TalkBack 與顏色對比仍列入 M1 驗收。

### Integration Modes（已完成第一版）

- `MockIntegrationProvider` 提供 deterministic scanner／printer fixture，隔離實體設備依賴。
- `MopriaViewModel` 管理 discovery、scan、export、print 與 job state，不讓 UI 直接依賴協定或 Android service。
- 設定頁可切換 `IntegrationMode.Mock`／`IntegrationMode.Real`；選擇會保存到 SharedPreferences，切換後清空舊裝置並重新探索。
- `RealIntegrationProvider` 已接上 Android `NsdManager`，探索 `_uscan._tcp`／`_uscans._tcp`、`_ipp._tcp`／`_ipps._tcp`，真實結果標記為 `isMock = false`。
- Emulator 已實際跑通「模擬搜尋／掃描 3 頁 → 文件庫預覽 → PDF 匯出 → 模擬列印 → 工作紀錄」流程。
- PDF／JPEG 由 Android `PdfDocument`／Bitmap 產生並寫入公開 `Download/Mopria Scan & Print/Scans` folder；App 啟動時會透過 MediaStore 重新索引已保存文件。
- 文件列印已接上 Android Storage Access Framework；可從手機資料夾選取 PDF／JPEG／PNG，透過 `UriPrintAdapter` 進入 `PrintManager`／系統 Print Spooler 預覽。
- Emulator 已驗證手機 JPEG 1 頁與 PDF 3 頁的系統列印預覽；這仍不等同實際印表機傳送成功。
- 目前 mock mode 的輸出內容是測試 fixture，不可視為真實 scanner image 或印表機接受工作的證據。
- Real mode 的 eSCL capabilities、scan job／檔案接收與實際列印 provider 尚未完成；這些工作會沿用相同的 provider 介面與 UI state。

## 5. 資料與介面設計

核心模型至少包含：

```kotlin
Document(id, pages, mimeType, localUri, createdAt)
Job(id, type, targetLabel, documentId, state, progress, error, createdAt)
Preset(id, type, name, settings)
IntegrationStatus(printingSupported, printServiceAvailable, mopriaScanAvailable)
```

核心邊界：

```kotlin
interface PrintProvider
interface ScanAcquisitionProvider
interface DocumentRepository
interface JobRepository
```

`feature:*` 模組只能依賴這些介面，不直接呼叫 Mopria、廠商 SDK 或 Android service implementation。

## 6. 里程碑

### M0：技術 spike 與決策

- 建立 Android scaffold、CI 與基本模組。
- 固定 Gradle、Android Gradle Plugin、Kotlin、JDK toolchain 與 compile/target SDK。
- 完成 eSCL discovery、capabilities 與單頁 scan sample。
- 完成 Android Print Framework sample。
- 建立 ADR-001（Android Print Framework）、ADR-002（eSCL scan）與 ADR-003（直接 IPP，deferred）。
- 建立第三方授權清單、風險清單及測試設備清單。

驗收：至少在一台實體 Android 裝置上開啟系統列印流程；至少一台實體 eSCL 掃描器完成 discovery、capability query 與單頁掃描。

### M1：App shell 與系統整合狀態

- Material 3 design system。
- Navigation 與首頁空狀態。
- 偵測 Android 列印能力與可用列印服務。
- 探索 eSCL 掃描器、顯示裝置狀態，並提供手動 IP／URL 加入。
- Room 儲存最近文件、工作與 App presets。
- 服務缺失、取消及權限拒絕處理。

驗收：在有／無列印服務及有／無可發現 eSCL 掃描器的測試網路上，首頁均能呈現正確狀態與下一步。

### M2：列印 MVP

- Storage Access Framework 文件選擇。
- PDF／圖片預覽。
- Android Print Framework adapter。
- 支援 Android 分享至本 App。
- 工作狀態與最近列印紀錄。

驗收：可對至少兩個不同品牌的 Mopria 印表機列印 PDF 與照片，並可取消工作。

### M3：掃描 MVP

- 實作 `EsclScanProvider` 與 capability normalization。
- 支援 Platen／ADF、解析度、色彩模式、輸出格式、掃描範圍及設備支援時的 duplex。
- 單頁、多頁、取消與錯誤恢復。
- `NextDocument` 逐頁串流至受控儲存區，記錄來源、頁序與工作狀態。
- 最近掃描紀錄。

驗收：可從至少兩個不同品牌的 Mopria 相容 MFP 取得掃描內容並輸出檔案。

### M4：文件編輯與輸出

- 裁切、旋轉、刪除、拖曳排序。
- 四角透視校正。
- PDF／JPEG／PNG 輸出。
- 掃描後直接列印。
- Presets／Favorites。

驗收：10 頁掃描文件可編輯、重排並輸出單一 PDF；App 被切到背景後不會遺失工作。

### M5：品質與 Beta

- 手機、平板與深色模式。
- TalkBack、字體放大、橫向模式。
- 效能、記憶體、檔案清理及隱私檢查。
- Crash reporting 與可匿名化的診斷資訊。
- Play Store listing、隱私權政策及 Beta 發布。

驗收：核心測試矩陣通過，沒有 blocker／critical issue，且掃描或列印失敗不會造成文件遺失。

### 6.1 目前執行狀態（2026-08-01）

已完成：

- Android app scaffold：`com.brianshih.mopria.android.scanprint`、`minSdk 28`、`compile/targetSdk 36`、Java/Kotlin JVM 17、Gradle wrapper 9.3.1。
- Compose UI/UX 與 Integration Modes：首頁、文件、工作紀錄、設定四個入口、可切換的 Mock／Real 模式，以及可操作的掃描／匯出／列印／裝置搜尋流程。
- Real mode provider scaffold：`RealIntegrationProvider` 已使用 Android `NsdManager` 探索 eSCL／IPP service type；尚未宣稱完成 capability query 或實際 scan／print。
- Android Emulator 驗證：既有 `Brian_Pixel_8_API_36` AVD（API 36、x86_64、Google APIs Play Store）已啟動，Debug APK 已成功安裝並開啟 `MainActivity`。
- 可重現建置：`.\gradlew.bat :app:assembleDebug --no-daemon --offline --console=plain` 已成功。
- Emulator workflow evidence：已在固定 folder 產生 1 個 PDF 與 3 個 JPEG，重啟 App 後文件庫仍能讀回它們，且工作紀錄頁看到掃描、匯出及列印完成項目；logcat 無 fatal crash。
- provider unit tests：`app:testDebugUnitTest` 已成功，涵蓋 mock discovery、三頁 scan fixture 與 print provider contract。

尚未完成：

- 真實 eSCL capability query、scan job 與檔案接收；`NsdManager` 的 service discovery boundary 已接線，但仍需實體設備驗證。
- 真實 `PrintDocumentAdapter` 的系統列印狀態回報、取消與工作完成持久化。
- 真實裝置、網路與跨品牌印表機／掃描器驗收。

## 7. 測試策略

### 自動測試

- ViewModel、capability mapping、preset validation 單元測試。
- Fake provider 驗證成功、離線、取消、逾時及部分能力情境。
- Room migration 與 repository 測試。
- Compose navigation、空狀態及錯誤狀態測試。
- PDF 頁數、尺寸、方向及影像輸出的 golden／fixture 測試。
- 工作必須先持久化再啟動背景執行的 race test。
- 程序死亡、服務重建、重複 start、取消與中斷後恢復測試。
- 10 個以上 PDF chunks、大量頁面、低記憶體與分享 URI 權限測試。
- 畸形 IPP／eSCL 回應、partial read、過大 response、timeout 與取消測試。
- Android backup/data extraction 規則測試，確認掃描內容不進入 cloud backup。

### 實體設備矩陣

- Android 12、目前主流版本及最新版本。
- 至少一台 Pixel／接近原生 Android 裝置。
- 至少一台 Samsung 裝置。
- Canon、Brother、Fujifilm 各至少一台可用裝置；MVP 驗收至少跨兩個品牌。
- 同一 Wi-Fi、Guest network、Wi-Fi Direct、裝置離線及弱網路。

## 8. 安全與隱私

- 文件預設只存在本機，未經使用者操作不得上傳。
- 使用 content URI，不要求廣泛儲存空間權限。
- Cache 中的掃描原稿應定期清理；未完成工作需可恢復且具保存期限。
- `backup_rules.xml` 與 `data_extraction_rules.xml` 必須排除文件、縮圖、匯出暫存與敏感 Room DB；是否允許 device-to-device transfer 另行決定。
- 分享 FileProvider URI 必須加入 read grant，必要時同時設定 `ClipData`，並以 chooser 啟動。
- 本地 HTTP 只能限定在列印／掃描裝置連線場景；HTTPS 自簽憑證採 TOFU／指紋確認，不提供永久 trust-all 預設值。
- 共用並正確關閉 HTTP client、mDNS callback executor、輸入流與 PDF 資源。
- 診斷紀錄不得包含文件內容、完整檔名、認證資訊或不必要的 IP 歷史。
- 若支援手動帳密、PIN 或企業 accounting，使用 Android Keystore 加密。
- 清楚說明區域網路與附近裝置權限用途。

## 9. 主要風險

| 風險 | 影響 | 處理方式 |
|---|---|---|
| eSCL 設備實作差異 | capability、狀態與回傳格式可能不完整或不一致 | 正規化模型、fixture、跨品牌實機矩陣與手動 URL fallback |
| 系統列印 UI 因裝置／服務而不同 | 選項與體驗不完全一致 | 以系統能力為準、提供前置說明與實機矩陣 |
| Android Print Framework 隱藏部分進階功能 | App 無法提供完整自訂 UI | MVP 採系統 UI；企業功能列入後續評估 |
| 大型多頁掃描造成記憶體不足 | Crash 或文件遺失 | 串流寫檔、縮圖、分頁處理、WorkManager |
| 系統服務找不到裝置 | 使用者無法開始工作 | 導向系統／Mopria 手動 IP、診斷與 Wi-Fi Direct 指引 |
| ScanBridge／eSCLKt 為 GPL | 直接複製或連結可能改變產品授權義務 | 僅參考行為；採用前完成正式 license review |
| JIPP parser 對畸形封包不夠防禦 | 惡意或異常設備可中止工作 | MVP 不使用；採用時 patch、限制大小並建立 parser boundary |
| JIPP PCLm／PWG 相容性與記憶體問題 | 嚴格印表機拒絕或 Android OOM | `jipp-pdl` 延後，先使用系統 Print Service |

## 10. Definition of Done

一項功能只有在以下條件都成立時才算完成：

- 正常流程與主要錯誤流程已實作。
- UI 狀態可恢復，旋轉或背景切換不遺失必要資料。
- 自動測試通過，並有實體裝置驗證紀錄。
- 不記錄或外傳文件內容。
- 已補上使用者文字、無障礙資訊與文件。
- PR 經 review，CI 通過後才合併至 `main`。

## 11. 下一步

1. 下載並接受 Mopria eSCL Specification，建立 clean-room 實作與授權紀錄。
2. 完成 eSCL capabilities、HTTP job 建立、單頁／多頁 scan 與檔案接收 spike。
3. 完成真實 Android Print Framework 的服務狀態、取消與工作完成持久化。
4. 寫入 ADR-001（列印）、ADR-002（eSCL 掃描）、ADR-003（直接 IPP，deferred）。
5. 將首頁的裝置狀態替換成實際 `IntegrationStatus`，並加入真實錯誤／逾時／重試處理。
6. 確認第一批 Canon、Brother、Fujifilm 或其他 eSCL／Mopria 相容實體測試設備型號。
