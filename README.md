# Mopria Android Scan & Print

一個以 Mopria 相容裝置為核心的 Android 掃描與列印 App，目標是在同一個介面中支援跨品牌印表機、掃描器及多功能事務機（MFP）。

## 專案目標

- 透過 Android 內建列印架構使用 Mopria 相容印表機。
- 列印 PDF、文件與照片。
- 從掃描器或 MFP 取得單頁或多頁文件。
- 在儲存前完成裁切、旋轉、排序及透視校正。
- 將掃描結果儲存為 PDF／JPEG／PNG、分享，或直接列印。
- 以裝置能力為準顯示紙張、雙面、色彩、解析度及耗材狀態等選項。

## 產品參考

本專案參考以下官方 Android App 的產品結構，但不複製其品牌、視覺資產或專有功能：

- Canon PRINT：大型功能入口、簡單首頁及列印預覽。
- Brother Mobile Connect：引導式裝置設定、裝置儀表板與工作紀錄。
- Brother iPrint&Scan：掃描後裁切、拉正、縮放及多格式輸出。
- FUJIFILM Print Utility V3：多裝置切換、Favorites、進階列印設定、QR／NFC 連線及透視校正。

技術行為與相容性以 Mopria 官方資料為準：

- [Mopria 繁體中文官方網站](https://mopria.org/zh-tw/)
- [從 Android 列印](https://mopria.org/zh-tw/print-from-android)
- [掃描到 Android](https://mopria.org/zh-tw/scan-to-android)

## 預定使用流程

### 第一次使用

1. App 檢查 Android 是否支援列印，以及是否有可用的列印服務。
2. App 透過 Android `NsdManager` 搜尋區域網路上的 eSCL 掃描器。
3. 列印時由 Android 系統列印介面搜尋及選擇印表機。
4. 掃描時在本 App 選擇掃描器、來源、解析度、色彩及範圍。

### 列印

1. 選擇 PDF、圖片或 Android 分享進來的文件。
2. 顯示預覽及頁面範圍。
3. 將文件與建議的列印屬性交給 Android Print Framework。
4. 在系統列印介面選擇印表機、紙張、方向、色彩、份數及雙面等選項。
5. 由 Mopria Print Service 或 Android Default Print Service 執行列印。

### 掃描

1. App 以 mDNS/DNS-SD 搜尋 `_uscan._tcp`／`_uscans._tcp` 服務，亦允許手動輸入 IP 或 URL。
2. 透過 eSCL 取得 scanner capabilities，顯示來源、色彩模式、解析度、格式及掃描範圍。
3. 建立 eSCL scan job，輪詢狀態並逐頁下載掃描內容。
4. 將原始頁面串流寫入受控儲存區，再進行裁切、旋轉、透視校正及排序。
5. 儲存為 PDF／JPEG／PNG，或交給 Android Print Framework 列印。

## 技術方向

- Kotlin
- Jetpack Compose + Material 3
- MVVM／單向資料流
- Kotlin Coroutines + Flow
- Hilt dependency injection
- Room 儲存裝置、Favorites 與工作紀錄
- Storage Access Framework 管理使用者文件
- Android Print Framework 處理列印
- Android `NsdManager` 處理 eSCL 掃描器探索
- 自建 eSCL client 處理 capabilities、scan job 與文件下載
- PDFRenderer／Android Graphics 處理預覽
- WorkManager 處理可恢復的背景工作

Android 沒有名為「Mopria API」的統一公開介面。列印端使用 Android `PrintManager`／`PrintDocumentAdapter`，由 Android Default Print Service 或 Mopria Print Service 處理印表機探索、能力協商、spool 與 IPP/IPPS 傳送。掃描端由本 App 依 [Mopria eSCL Specification](https://mopria.org/mopria-escl-specification) 實作 eSCL client；外部 Mopria Scan App 僅保留為日後可能的 fallback，不是 MVP 的主要路徑。

## Integration Modes

App 內建可切換的 `Mock Integration Mode` 與 `Real Integration Mode`。模式切換位於「設定」頁，選擇會保存在 App 的本機設定中，並立即重新搜尋裝置。

### Mock Integration Mode

- `MockIntegrationProvider` 提供一個 eSCL scanner 與一個 Mopria printer 的 deterministic fixture。
- 可執行模擬搜尋、3 頁模擬掃描、文件頁面預覽、PDF 匯出及模擬列印。
- 掃描結果可保存到公開的 `Download/Mopria Scan & Print/Scans` folder；Android 10+ 使用 MediaStore，不需要廣泛儲存空間權限。
- 列印入口可透過 Android Storage Access Framework 從手機資料夾選取一個或多個 PDF／JPEG／PNG；`UriPrintAdapter` 會把 PDF 頁面或圖片轉成 Android `PrintDocumentAdapter` 的列印串流。
- 已在 Emulator 以系統 Print Spooler 驗證 JPEG 1 頁及 PDF 3 頁列印預覽；不需要實體印表機即可驗證文件列印格式與頁數。
- 首頁、文件庫、工作紀錄及設定頁均使用同一套 `MopriaUiState`；未來只替換 provider，不改變主要 UI 工作流。
- `Mock Integration Mode` 只驗證產品流程與本機文件處理，不代表已完成 eSCL／IPP 相容性或 Mopria 認證。

### Real Integration Mode

- `RealIntegrationProvider` 使用 Android `NsdManager` 探索 `_uscan._tcp`／`_uscans._tcp` eSCL 掃描服務，以及 `_ipp._tcp`／`_ipps._tcp` 印表機服務。
- 真實探索結果會標記為非 mock 設備；若區域網路找不到設備，App 會顯示可理解的狀態與錯誤訊息，不會自動建立模擬設備。
- eSCL capabilities、scan job／檔案接收，以及實際列印 provider 仍在後續里程碑；切換至真實模式不代表目前已完成 Mopria 相容性或認證。

## 開源專案 review 與採用決策

已針對以下專案進行固定 commit 的程式碼 review：

- [Chrisimx/ScanBridge](https://github.com/Chrisimx/ScanBridge/tree/5d9e2b1ad63f95041e681ce39146dc25bc9fc648)：完整的 eSCL／AirScan 掃描產品參考。
- [HPInc/jipp](https://github.com/HPInc/jipp/tree/f3a484ff539032194f5af164c6f57fbc4370b026)：IPP 封包及 PWG Raster／PCLm 函式庫。

採用結論：

| 範圍 | 決策 |
|---|---|
| MVP 列印 | 僅使用 Android Print Framework，不直接使用 JIPP |
| MVP 掃描 | 自建 eSCL client + Android `NsdManager`，在 App 內完成探索、設定與掃描 |
| 外部 Mopria Scan | 不作為 MVP 依賴；僅保留為 fallback 研究項目 |
| 進階印表機資訊／直接 IPP | MVP 後可評估 MIT 授權的 `jipp-core`，但自行實作 Android transport 與 discovery |
| PWG Raster／PCLm | 暫不採用 `jipp-pdl`，待修正相容性問題並完成實機矩陣 |

ScanBridge 適合參考裝置探索、capability-driven UI、ADF／Platen、多頁 session、前景服務與編輯流程，但它及其 eSCLKt 依賴採 GPL-3.0-or-later。除非本專案明確決定採 GPL，否則不得複製其程式碼或直接加入該依賴。

JIPP 採 MIT 授權，`jipp-core` 的資料模型與 transport abstraction 可供第二階段使用，但它不是 Android Mopria API，不提供 mDNS 探索、HTTP transport、系統列印 UI 或掃描功能。目前 review 也確認其 IPP parser 對負的 `value-length` 缺少安全驗證（[Issue #222](https://github.com/HPInc/jipp/issues/222)），而 PCLm writer 存在 `q/Q` graphics-state 不平衡問題（[Issue #121](https://github.com/HPInc/jipp/issues/121)）。採用前必須完成修補、封裝與惡意／畸形封包測試。

## 開發環境

目前開發機已具備：

- Android SDK Platforms 33、34、35、36、36.1。
- Android Build Tools 34.0.0、35.0.0、36.0.0、36.1.0。
- Android Debug Bridge 37。
- Android Emulator 36.4.9.0，以及可用的 `Brian_Pixel_8_API_36`（API 36、x86_64、Google APIs Play Store）AVD。
- JDK 21；Android/Gradle 專案應透過 toolchain 固定實際使用的 JDK 版本。

Android Studio 強烈建議安裝，用於 Compose Preview、Logcat、Profiler、Layout Inspector 與 Emulator 管理，但命令列 Gradle 建置不以 Android Studio 為必要條件。實機驗證仍需要 Android 手機，以及位於同一網路的 Mopria 相容印表機／掃描器。

目前 scaffold 可直接以命令列建置及安裝：

```powershell
cd E:\projects\mopria-android-scan-print
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Mock provider 的 JVM 測試可用以下指令執行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --offline --console=plain
```

## 預定模組

```text
app                 Compose UI、navigation、application wiring
core:model          Device、capability、document、job 資料模型
core:designsystem   色彩、字體、元件與圖示規則
core:storage        Room、檔案與 PDF 儲存
feature:integration Android 列印服務狀態、網路與裝置整合
feature:print       文件選擇、預覽、設定與 PrintManager adapter
feature:scan        eSCL 探索、capabilities、工作執行與 ScanAcquisitionProvider
feature:editor      裁切、旋轉、透視校正與頁面排序
feature:history     最近工作、重試與錯誤資訊
```

## 完成方式

開發將依 `dev_plan.md` 的里程碑進行。每個里程碑必須同時完成：

- 功能實作與錯誤狀態。
- 單元測試及必要的 instrumentation test。
- 至少一台實體 Android 裝置驗證。
- 對應的文件更新。
- 無障礙基本檢查，包括字體縮放、content description 及觸控尺寸。

完成 MVP 的定義是：使用者可以透過 Android 系統列印介面列印 PDF／照片，在本 App 內探索 eSCL 掃描器、設定並取得單頁或多頁掃描，編輯並輸出 PDF，以及查看最近工作；列印服務缺失、掃描器離線、網路或權限拒絕和工作失敗時均有可理解且可恢復的處理方式。

## 專案狀態

目前已完成產品研究、Mopria API 邊界確認、ScanBridge／JIPP 的第一輪程式碼 review、Compose UI/UX scaffold、可切換的 Mock／Real Integration Mode，以及不依賴硬體的 Mock Integration Mode。已在 `Brian_Pixel_8_API_36` Emulator 上驗證「模擬掃描 → PDF 匯出 → 模擬列印 → 工作紀錄」及「手機資料夾 PDF／JPEG → Android 系統列印預覽」流程；Real mode 已接上 Android NSD discovery boundary，MVP 仍採「eSCL 原生掃描 + Android Print Framework 列印」。真實 eSCL capabilities／scan 與跨品牌驗收尚未完成。詳細工作拆解請參考 [`dev_plan.md`](dev_plan.md)。
