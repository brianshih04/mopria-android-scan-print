# Mopria Android Scan & Print

Kotlin／Jetpack Compose Android App，透過 eSCL（AirScan）掃描文件，並透過 Android Print Framework 列印手機檔案或掃描結果。專案目前版本為 `0.1.0`，`minSdk 28`、`targetSdk 36`。

> 目前狀態（2026-08-02）：Mock 模式、eSCL pull-scan client、Flatbed／ADF 多頁工作流、PDF／JPEG 文件庫及 Android 系統列印入口均已完成並通過模擬器驗證。真實掃描器與印表機的跨品牌驗收仍待實體硬體。

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

## 專案結構

```text
app/src/main/java/.../domain/
  EsclDiscovery.kt            DNS-SD TXT 正規化與安全服務去重
  EsclProtocol.kt             capability/status XML、協商與 URL policy
  EsclHttpClient.kt           bounded HTTP、重試、TLS、串流下載
  RealIntegrationProvider.kt  Android NSD 與真實 eSCL 工作流程
  MockIntegrationProvider.kt  模擬掃描器／印表機 fixture
  ScanDocumentOrganizer.kt    Flatbed 合併與 ADF 拆分規則

app/src/main/java/.../ui/
  MopriaViewModel.kt          App 狀態與掃描／匯出／列印協調
  ScanScreen.kt               Flatbed／ADF、頁數與 PDF 合併設定
  DocumentPageBitmapLoader.kt JPEG／PNG／PDF-backed 頁面載入
  ScanExportService.kt        PDF／JPEG 匯出與分享檔案
  SystemPrintAdapter.kt       掃描文件列印 adapter
  UriPrintAdapter.kt          手機 PDF／圖片列印 adapter
```

目前採單一 `app` module；尚未加入 README 舊版規劃中的 Hilt、Room 或 WorkManager。

## 開發環境與建置

需要 JDK 17+、Android SDK 36 及可用的 Android SDK license。Android Studio 建議用於 Compose Preview、Logcat、Profiler 與 Emulator，但命令列即可建置。

```powershell
cd E:\Projects\mopria-android-scan-print
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## GitHub 可下載成果

- 可安裝測試 APK：[avi-print-scan.apk](release/avi-print-scan.apk)（release build，以本機 debug keystore 簽署，供開發／emulator 測試；正式發布前需改用產品簽章）。
- APK SHA-256：`384637E3FC34A25C324E1163C44BDBCD19D42D6B75624CA857DFA8EFE0E0E272`
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

- Unit tests：30 passed，0 failed。
- Android lint：0 errors，9 warnings（dependency update 與 Kotlin extension 建議；無 blocker）。
- `:app:assembleDebug`：passed。
- `git diff --check`：passed。
- Emulator：`Brian_Pixel_8_API_36`／API 36。
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

詳細規劃與接手資訊：[`dev_plan.md`](dev_plan.md)、[`CHANGELOG.md`](CHANGELOG.md)、[`HANDOFF.md`](HANDOFF.md)。

## 產品與開源參考

- [Mopria 繁體中文官方網站](https://mopria.org/zh-tw/)
- [Canon PRINT](https://play.google.com/store/apps/details?id=jp.co.canon.bsd.ad.pixmaprint&hl=zh_TW)
- Brother Mobile Connect／Brother iPrint&Scan
- FUJIFILM Print Utility
- [Chrisimx/ScanBridge](https://github.com/Chrisimx/ScanBridge/tree/5d9e2b1ad63f95041e681ce39146dc25bc9fc648)（GPL-3.0-or-later；只參考行為，不複製程式碼）
- [HPInc/jipp](https://github.com/HPInc/jipp/tree/f3a484ff539032194f5af164c6f57fbc4370b026)（MIT；目前不納入 MVP）
