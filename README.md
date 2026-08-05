# Mopria Android Scan & Print

Kotlin／Jetpack Compose Android App，透過 eSCL（AirScan）掃描文件，並透過 Android Print Framework 列印手機檔案或掃描結果。專案目前版本為 `0.1.0`，`minSdk 28`、`targetSdk 36`。

> 目前狀態（2026-08-02）：Mock 模式、eSCL pull-scan client、Flatbed／ADF 多頁工作流、PDF／JPEG 文件庫、Android 系統列印入口及 10 種語言 UI 均已完成並通過建置／測試驗證。真實掃描器與印表機的跨品牌驗收仍待實體硬體。

## Android／Mopria 技術邊界

Android 沒有一個同時提供 Mopria 掃描與列印的公開「Mopria API」。本專案分成兩條整合路徑：

| 功能 | 實作方式 | App 負責範圍 |
|---|---|---|
| 掃描 | eSCL pull scan + Android `NsdManager` | DNS-SD 探索、capability 協商、工作生命週期、頁面下載與文件輸出 |
| 列印 | Android `PrintManager` + `PrintDocumentAdapter` | 文件選擇、內容轉換、預覽入口與工作狀態 |
| 印表機連線 | Android Default Print Service／Mopria Print Service | 印表機探索、IPP/IPPS、紙張、色彩、雙面與 spool |

因此，eSCL／AirScan 是掃描協定；Mopria Print Service 背後通常使用 IPP/IPPS，但本 App 不自行實作直接 IPP 列印。

## 已完成的使用者功能

- 現代化、icon-first 的 Compose／Material 3 主畫面。
- 可切換「模擬模式」與「真實模式」，選擇與掃描設定會保存在本機。
- 支援 English、日本語、한국어、Español、Português、Deutsch、Français、Русский、繁體中文、简体中文；預設依 Android 系統語系選擇，未支援的系統語系 fallback English，使用者也能在設定中手動覆寫。
- 「掃描文件」進入專用掃描設定；「文件」只顯示既有掃描結果。
- Flatbed：
  - 一般單頁掃描。
  - 開啟「逐頁合併 PDF」後，每頁完成可選「下一頁」或「完成 PDF」。
  - 最多累積 50 頁，完成後匯出為單一 multi-page PDF。
- ADF：
  - 使用者可設定 1–50 頁上限，不再使用固定值。
  - 開啟「合併為多頁 PDF」時建立一份多頁文件並匯出單一 PDF。
  - 關閉時將 ADF 頁面拆成個別單頁文件。
- 掃描可設定 Flatbed／ADF、150／300／600 dpi、彩色／灰階／黑白；真實模式會依設備 capability 選擇最接近且有效的組合。
- 文件庫顯示實際 JPEG、PNG 或 PDF-backed 頁面縮圖，可放大預覽。
- 文件可輸出 PDF／JPEG、透過 Android Sharesheet 分享，或直接送往系統列印預覽。
- 手機資料夾可選一個或多個 PDF／JPEG／PNG 列印；取消系統選檔會回到 App 列印頁，列印頁可回首頁。
- Android 10+ 透過 MediaStore 寫入 `Download/Mopria Scan & Print/Scans`；Android 9 僅在需要時要求舊版儲存權限。

## 多國語言

App 使用 Android resource qualifiers 管理翻譯，不在 Compose 畫面中硬編碼可見文字。啟動時會依序檢查使用者手動選擇與 Android 系統語系；系統語系不在支援清單時使用 English。手動選擇會在設定頁立即套用並重新建立 Activity，選擇「依系統設定」即可恢復自動判斷。

支援語系與資源目錄如下：

| 語系 | Android resource |
|---|---|
| English | `res/values/` |
| 日本語 | `res/values-ja/` |
| 한국어 | `res/values-ko/` |
| Español | `res/values-es/` |
| Português | `res/values-pt/` |
| Deutsch | `res/values-de/` |
| Français | `res/values-fr/` |
| Русский | `res/values-ru/` |
| 繁體中文 | `res/values-zh-rTW/` |
| 简体中文 | `res/values-zh-rCN/` |

語系選擇器的判斷與 fallback 位於 `ui/LanguageManager.kt`；`MainActivity` 會在 `attachBaseContext` 套用 locale，ViewModel 產生的背景事件也使用相同設定。

## eSCL v2.97 實作範圍

目前 pull-scan client 依 Mopria eSCL v2.97 的相關章節完成下列行為：

- 解析 namespace-aware 的 `ScannerCapabilities`／`ScannerStatus`，要求合法的 PWG `Version`、狀態與 root namespace，並停用外部 XML entity。
- 依 Platen／Feeder 各自的 `SettingProfile`、`ref`、色彩、格式、離散解析度與 X/Y range 協商設定。
- eSCL 2.1+ 使用 `DocumentFormatExt`；2.0 相容設備才使用 legacy `DocumentFormat`。
- ADF 使用規格值 `Feeder`；設備支援 `SelectSinglePage` 時送出使用者設定的 `NumberOfPages`。
- 探索 `_uscan._tcp` 與 `_uscans._tcp`，解析 TXT `rs`、`uuid`、`vers`、`ty`、`pdl`，驗證 resource root 並優先保留同一設備的安全服務。
- 支援設備自訂 eSCL resource root、IPv4 與具 scope 的 IPv6 host。
- 建立工作要求 `201 Created` 與 `Location`；工作 URL 只允許同一 host／root 與 HTTP→HTTPS 升級。
- 讀取 `Pending`／`Processing`／`Completed`／`Canceled`／`Aborted` 狀態，處理 `404` 結束、`410`、`503 Retry-After`、逾時後狀態確認與 `DELETE` 清理。
- `NextDocument` 宣告 `TE: chunked`，將頁面串流到受控檔案並限制控制回應、錯誤內容及單一文件大小。
- 接受 JPEG、PDF、PNG，並比對 MIME type 與檔案 signature；PDF 以 `PdfRenderer` 對應實際頁數。
- HTTPS 使用 Android 系統 trust store，不使用 trust-all；TLS provider 必須具備 TLS 1.3。

刻意未宣稱的範圍：Push Scan、Stored Job Requests、OCR／可搜尋 PDF、加密 PDF request、ScanBufferInfo、ADF duplex UI、使用者認證輸入、手動 IP／URL、直接 IPP、Mopria 認證。`426` 可升級為同 host HTTPS；同一 TCP connection 內的 RFC 2817 raw Upgrade 不在目前支援範圍。

Mopria 規格 PDF 是本機、受限制的研究來源，不會複製到此 repository。公開參考入口：[Mopria eSCL Specification](https://mopria.org/mopria-escl-specification)。

## 專案檔案內容

本專案目前是單一 Android `app` module，主要檔案如下：

```text
MopriaAndroidScanPrint/
├─ app/
│  ├─ build.gradle.kts                 Android module、SDK、Compose 與測試依賴
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml         網路、儲存、FileProvider 與啟動 Activity
│     │  ├─ java/com/.../MainActivity.kt App 入口與 Compose content
│     │  ├─ java/com/.../domain/        eSCL、探索、掃描工作與文件模型
│     │  ├─ java/com/.../ui/            Compose 畫面、ViewModel、匯出與列印
│     │  └─ res/                        App icon、多國語言字串、theme、備份與網路設定
│     └─ test/                           domain 純邏輯與協定單元測試
├─ docs/screenshots/main-screens/       API 36 emulator 主畫面 JPEG
├─ （APK 由 GitHub Release v0.1.0 發布）  可安裝測試 APK（不在 repo 內）
├─ .workflow/                           規格合規與 UI review 工作紀錄
├─ build.gradle.kts                     root Gradle plugin 設定
├─ settings.gradle.kts                  module、plugin 與 Maven repository 設定
├─ gradle.properties                    Gradle／Android 建置參數
├─ gradlew、gradlew.bat                 Gradle Wrapper 啟動腳本
├─ dev_plan.md                          開發範圍、里程碑與驗收計畫
├─ CHANGELOG.md                         版本變更與驗證紀錄
├─ HANDOFF.md                           第三方接手、建置與風險說明
├─ README.md                            專案總覽、架構與開發方式
└─ userguide.md                         使用者操作指南
```

### Android 設定與資源

| 路徑 | 內容 |
|---|---|
| `app/build.gradle.kts` | `compileSdk/targetSdk 36`、`minSdk 28`、Kotlin JVM 17、Jetpack Compose、Lifecycle 與測試依賴。 |
| `AndroidManifest.xml` | `INTERNET`、網路狀態與 Android 9 以下儲存權限；宣告 `MainActivity` 及用於分享檔案的 `FileProvider`。 |
| `res/values/strings.xml`、`themes.xml` | App 名稱、Material theme 與基本 UI 資源。 |
| `res/drawable/ic_launcher.xml` | App launcher icon。 |
| `res/xml/network_security_config.xml` | eSCL HTTP／HTTPS 的 Android 網路安全政策。 |
| `res/xml/file_paths.xml` | `FileProvider` 可分享的暫存檔路徑。 |
| `res/xml/backup_rules.xml`、`data_extraction_rules.xml` | Android 備份與資料擷取規則。 |

### 核心 domain 層

| 檔案 | 責任 |
|---|---|
| `IntegrationModels.kt` | 掃描設定、設備、工作、文件頁面與 `MopriaUiState` 資料模型。 |
| `IntegrationProviders.kt` | Mock／Real integration provider 的共同介面。 |
| `EsclDiscovery.kt` | 透過 Android `NsdManager` 探索 `_uscan._tcp`／`_uscans._tcp`、解析 TXT 與驗證 resource root。 |
| `EsclProtocol.kt` | namespace-aware XML capability/status parser、設定協商、ScanJob request 與 URL policy。 |
| `EsclHttpClient.kt` | bounded HTTP、TLS、redirect、Retry-After、重試與串流下載。 |
| `RealIntegrationProvider.kt` | 真實設備 discovery、eSCL scan job lifecycle、JPEG／PDF payload 下載。 |
| `MockIntegrationProvider.kt` | 不連接硬體的 deterministic scanner／printer fixture。 |
| `ScanDocumentOrganizer.kt` | Flatbed 逐頁合併與 ADF 多頁合併／拆分規則。 |

### UI 與輸出層

| 檔案 | 責任 |
|---|---|
| `MopriaApp.kt` | App destination、TopAppBar、底部導覽、Android file picker 與 system print launcher。 |
| `LanguageManager.kt` | 系統語系偵測、10 種語言手動覆寫與 English fallback。 |
| `MopriaViewModel.kt` | 模式切換、discovery、scan、文件儲存、匯出、分享與列印狀態協調。 |
| `HomeScreen.kt` | 首頁掃描、列印、裝置與最近工作卡片。 |
| `ScanScreen.kt` | Flatbed／ADF、dpi、色彩、ADF 頁數上限與 PDF 合併設定。 |
| `DocumentsScreen.kt` | 文件縮圖／預覽，以及列印、分享、PDF、JPEG 操作。 |
| `PrintScreen.kt` | 手機文件列印入口與已掃描文件入口。 |
| `SupportScreens.kt` | 工作紀錄與設定頁面。 |
| `DocumentPageBitmapLoader.kt`、`DocumentPreviewLoader.kt`、`BitmapLoader.kt` | JPEG／PNG／PDF 頁面載入、縮圖與預覽。 |
| `ScanExportService.kt` | PDF／JPEG 寫入 Download 資料夾及分享 URI。 |
| `SystemPrintAdapter.kt`、`UriPrintAdapter.kt` | 將掃描文件或手機 URI 接到 Android `PrintManager`。 |
| `theme/Theme.kt` | Material 3 色彩、排版與 Compose theme。 |

### 測試、文件與產物

- `app/src/test/.../domain/`、`app/src/test/.../ui/`：測試 discovery、eSCL XML／HTTP、Mock provider、文件合併規則與語系 fallback；不需連接真實設備即可執行。
- `docs/screenshots/main-screens/`：首頁、Flatbed、ADF、文件、列印檔案選擇器、紀錄與設定的 JPEG 參考畫面。
- GitHub Release [v0.1.0](https://github.com/brianshih04/mopria-android-scan-print/releases/tag/v0.1.0)（asset `avi-print-scan.apk`）：以本機 debug keystore 簽署的 release build，供開發／emulator 測試；正式發布必須換產品簽章。
- `app/build/`：Gradle 產生的暫存、測試報告與 APK 輸出，通常被 `.gitignore` 忽略，不應手動提交。

目前未加入 Hilt、Room 或 WorkManager；文件資料與設定仍由目前的 ViewModel／本機儲存流程管理。

## 開發環境與建置

需要 JDK 17+、Android SDK 36 及可用的 Android SDK license。Android Studio 建議用於 Compose Preview、Logcat、Profiler 與 Emulator，但命令列即可建置。

```powershell
# 於專案根目錄執行（以下為 Windows PowerShell；macOS／Linux 改用 ./gradlew）
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## GitHub 可下載成果

- 可安裝測試 APK：[avi-print-scan.apk](https://github.com/brianshih04/mopria-android-scan-print/releases/download/v0.1.0/avi-print-scan.apk)（release build，以本機 debug keystore 簽署，供開發／emulator 測試；正式發布前需改用產品簽章）。
- APK SHA-256：`BE82F63EFDB607C60A4C081C8BE78B15252B374652FD0C43081780F4A99AF9AD`
- 主畫面 JPEG：[`docs/screenshots/main-screens`](docs/screenshots/main-screens/)
  - [首頁](docs/screenshots/main-screens/01-home.jpg)
  - [Flatbed 掃描設定](docs/screenshots/main-screens/02-scan-flatbed.jpg)
  - [ADF 掃描設定](docs/screenshots/main-screens/03-scan-adf.jpg)
  - [文件庫](docs/screenshots/main-screens/04-documents.jpg)
  - [列印檔案選擇器](docs/screenshots/main-screens/05-print-file-picker.jpg)
  - [工作紀錄](docs/screenshots/main-screens/06-history.jpg)
  - [設定](docs/screenshots/main-screens/07-settings.jpg)

截圖來自 API 36 Android emulator；「列印檔案選擇器」是 Android DocumentsUI，其他畫面為本 App UI。

## 最新驗證紀錄

2026-08-02 的最終本機驗證：

- Unit tests：34 passed，0 failed。
- Android lint：0 errors，34 warnings（dependency update、Kotlin annotation／extension 與 pluralization 建議；無 blocker）。
- `:app:assembleDebug`：passed。
- `:app:assembleRelease`：passed；release APK 已簽署並通過 `apksigner verify`。
- `git diff --check`：passed。
- Emulator：`Brian_Pixel_8_API_36`／API 36。
- 語系 smoke：清除資料後系統 en-US 顯示 English；設定頁列出 10 種語言；手動切換 Japanese 與 Simplified Chinese 後設定頁與導覽文字更新。
- ADF mock：將頁數改為 6，驗證合併為一份 6 頁 PDF 文件及公開 Download 匯出。
- Flatbed mock：連續掃描 2 頁，驗證「下一頁」／「完成 PDF」與 2 頁文件結果。
- 列印導覽：App 列印頁 → Android DocumentsUI → 返回列印頁 → 返回首頁。
- 文件頁：實際縮圖、PDF／JPEG、列印與分享入口存在。
- Runtime log：smoke flow 無 `FATAL EXCEPTION`／app OOM。

這些結果證明模擬流程、協定 fixture 與 Android 整合可執行；不等同 Canon、Brother、Fujifilm 或其他實體裝置已通過相容性驗證。

## 下一階段

1. 以至少兩個品牌的 eSCL MFP 驗證 `_uscan`／`_uscans`、自訂 `rs`、Flatbed、ADF、PDF／JPEG 與斷線清理。
2. 以至少兩個品牌的 Mopria 印表機驗證 Android Print Service 的成功、取消、離線、紙張、色彩與雙面。
3. 將真實 capability 反映為動態 UI 選項，加入手動 IP／URL 與認證流程。
4. 加入工作持久化、程序死亡恢復、暫存檔保留期限與大型文件 soak test。
5. target SDK 37 時依 Android 官方 local-network permission／picker 模型遷移；target 36 目前不應提前宣告 `ACCESS_LOCAL_NETWORK`。參考：[Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)。

詳細規劃、使用方式與接手資訊：[`dev_plan.md`](dev_plan.md)、[`userguide.md`](userguide.md)、[`CHANGELOG.md`](CHANGELOG.md)、[`HANDOFF.md`](HANDOFF.md)。

## 產品與開源參考

- [Mopria 繁體中文官方網站](https://mopria.org/zh-tw/)
- [Canon PRINT](https://play.google.com/store/apps/details?id=jp.co.canon.bsd.ad.pixmaprint&hl=zh_TW)
- Brother Mobile Connect／Brother iPrint&Scan
- FUJIFILM Print Utility
- [Chrisimx/ScanBridge](https://github.com/Chrisimx/ScanBridge/tree/5d9e2b1ad63f95041e681ce39146dc25bc9fc648)（GPL-3.0-or-later；只參考行為，不複製程式碼）
- [HPInc/jipp](https://github.com/HPInc/jipp/tree/f3a484ff539032194f5af164c6f57fbc4370b026)（MIT；目前不納入 MVP）
