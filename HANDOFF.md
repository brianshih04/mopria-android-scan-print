# Development Handoff

更新日期：2026-09-17

Repository：`brianshih04/mopria-android-scan-print`

目標分支：`main`

## 1. 接手摘要

目前 `main` 已完成可執行的 Android Compose App、Mock／Real 模式、eSCL v2.97 pull-scan client、Flatbed／ADF 多頁文件工作流、capability-aware ADF 單面／雙面選擇、OpenCV file-first 影像處理、opt-in ML Kit OCR／Searchable PDF、PDF／JPEG 文件庫、Android Print Framework 預設列印入口、opt-in Direct IPP，以及 10 種語言與系統語系 fallback。ADF 雙面目前只有自動測試與 capability UI 驗證，尚未以廣告 `AdfDuplexInputCaps` 的實體 MFP 驗收。OCR feature branch 已 fast-forward 合併；合併前的 `main` 保留於 `main-backup`。

既有 JVM／instrumentation 驗證可由下方固定指令重跑。Brother MFC-L2715DW 與 HP LaserJet Pro MFP 3104fdw 已完成基本實體 eSCL 掃描驗證；Brother 另已透過 Android Default Print Service（Mopria，手動 IP 加入）完成一次系統列印。HP 與 Brother 的 Direct IPP 均已完成（Brother 於 2026-09-18 修復 HTTP/1.1 掛起問題後通過）；Direct IPP 的 IPPS、高 DPI 多頁 PWG-Raster／PCLm streaming／OOM soak 與跨品牌完整驗收仍未完成。請勿把 Mock／fixture 結果描述成 Mopria Certified 或廠牌相容證據。

2026-08-12 產品測試後已修正 System Print Activity context、輸出紙張尺寸、Direct IPP bitmap 額外縮小、options sheet navigation inset、手動 endpoint 健康檢查、PDF metadata 重疊與 Mock crop／rotation。System Print 已在 API 36 實際開啟 Print Spooler；A4 MediaBox 與上述 UI 邊界有 instrumentation coverage。2026-09-17 實機矩陣：Brother MFC-L2715DW 透過 Android Default Print Service（手動 IP）列印工作為 `Completed`；HP LaserJet Pro MFP 3104fdw Direct IPP 工作為 `Completed`；Brother Direct IPP 工作當時為 `Failed`——2026-09-18 已確認根因為 Brother IPP 服務無法完成 HTTP/1.1 回應（`BoundedIppTransport` 已改用 raw-socket HTTP/1.0 並實測 `Completed`），前述 PWG-Raster resolution negotiation 疑點並非原因。~~Brother MFC-L2715DW 的 eSCL ADF 不取紙仍未解決~~ → **已解決（2026-09-16）**：根因為 POST ScanJobs Content-Type 需為 `application/xml`（`text/xml` 觸發 Brother 降級 fallback），修復後 ADF 取紙與解析度全部正常，詳見 `docs/brother-contenttype-rootcause.md`。

OpenCV 與 eSCL file-first 影像管線已接入：使用官方 `org.opencv:opencv:4.14.0` AAR、原子檔案替換、ADF deskew、平台 auto-crop、blank-page drop 與背景淨化；`android:largeHeap="true"` 已設定，A4 300 dpi 十頁 OpenCV soak 以 absolute 256 MB peak／64 MB retained-PSS gate 驗證。OCR option 使用 Google ML Kit Text Recognition v2，接入 settings、capability safety、四種 script recognizer、Google Play services unbundled model request、預設 English／繁中／簡中與區域語言選擇；影像以 12 MP／4096 px 上限取樣，OCR 掃描只協商 JPEG，PDF-only profile 會在建立工作前回報。日文／韓文模型及 Searchable PDF 地域字型均由使用者按需準備，JP／KR TTF 不進主 APK；字型 URL 固定至 Noto CJK commit 並驗證 SHA-256。Searchable PDF 透過獨立 opt-in 設定接入 PDFBox mixed/temp storage，以 page-scoped OCR layout、bounded bitmap 與 crop／rotation 座標轉換產生不可見文字層；layout 以壓縮 sidecar 原子保存，缺少 OCR／字型時明確失敗，不會靜默降級。API 36 emulator 的三張中文樣本輸出與文字抽取已通過。實機模型下載、辨識準確率／PSS、16 KB、真實 scanner 與多語字型覆蓋率仍待驗證，細節見 `docs/opencv-integration-plan.md`、`docs/ocr-escl-image-pipeline.md` 與 `docs/searchable-pdf-poc.md`。

## 2. 快速啟動

```powershell
# 於專案根目錄執行（以下為 Windows PowerShell；macOS／Linux 改用 ./gradlew）
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --max-workers=1 --no-daemon
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.brianshih.mopria.android.scanprint/.MainActivity
```

已使用的 AVD：`Brian_Pixel_8_API_36`。本機若沒有 SDK 設定，建立未追蹤的 `local.properties` 指向 Android SDK。

## 2.1 GitHub 交付產物

- 測試 APK（GitHub Release v0.1.0）：[`avi-print-scan.apk`](https://github.com/brianshih04/mopria-android-scan-print/releases/download/v0.1.0/avi-print-scan.apk)
- API 36 emulator 主畫面 JPEG：[`docs/screenshots/main-screens/`](docs/screenshots/main-screens/)
- APK 使用本機 debug keystore 簽署，適合開發與 emulator 安裝；正式發布前必須換成產品簽章。

## 3. 重要程式位置

| 檔案 | 責任 |
|---|---|
| `domain/IntegrationModels.kt` | 掃描設定、設備、文件頁、輸出紙張尺寸與 UI state |
| `domain/EsclDiscovery.kt` | TXT metadata、resource root 驗證、安全服務優先 |
| `domain/EsclProtocol.kt` | XML parse、capability negotiation、ScanSettings、Job URL policy |
| `domain/EsclHttpClient.kt` | HTTP status、Retry-After、redirect、TLS、bounded streaming |
| `domain/RealIntegrationProvider.kt` | Android NSD、eSCL job lifecycle、JPEG／PDF 文件映射與 Direct IPP 列印協調 |
| `domain/MockIntegrationProvider.kt` | 不依賴硬體的 deterministic scan／print fixture |
| `domain/ScanDocumentOrganizer.kt` | Flatbed append 與 ADF split 純邏輯 |
| `domain/IppDiscovery.kt` | `_ipp/_ipps` NSD 探索、URI 與 secure candidate 選擇 |
| `domain/IppPrintClient.kt`、`IppTransport.kt` | Direct IPP capability、job lifecycle、fixed-length 傳輸與 cancel cleanup |
| `domain/IppRasterizer.kt`、`PrintRenderSizing.kt` | PWG-Raster／PCLm 產生、bounded render 尺寸與逐頁 bitmap streaming；swath／實體高 DPI soak 仍待驗證 |
| `ui/MopriaViewModel.kt` | discovery／scan／export／print 協調及 Flatbed session state |
| `ui/LanguageManager.kt` | 系統語系偵測、手動覆寫、中文 script／region 判斷與 English fallback |
| `ui/ScanScreen.kt` | ADF 單雙面／max pages、Flatbed／ADF PDF 合併設定及下一頁 dialog |
| `ui/DocumentsScreen.kt` | 掃描文件頁面、列印／分享／輸出操作 |
| `ui/DocumentPageBitmapLoader.kt` | JPEG／PNG／PDF-backed 頁面取樣／render |
| `ui/ScanExportService.kt`、`domain/PdfBoxSearchablePdfWriter.kt` | MediaStore PDF／JPEG、分享 PDF，以及 opt-in invisible-text Searchable PDF |
| `ui/SystemPrintAdapter.kt` | 掃描文件交給 Android Print Framework；Activity locale override 必須保留 system-service identity |

| `domain/ScanError.kt` | 結構化掃描錯誤，ViewModel 映射至 string resources；包含 OCR raster-format negotiation error |
| `domain/PdfPageRenderer.kt` | 共用 PDF 頁面渲染（writePdf + drawFitted） |
| `domain/DocumentStore.kt` | JSON 持久化文件列表與 Flatbed session；OCR layout 使用壓縮 sidecar |
| `domain/SettingsStore.kt` | SharedPreferences 讀寫，從 ViewModel 提取 |
| `domain/TempFileCleanup.kt` | 暫存檔三層清理（orphan scan + stale cache + delete） |
| `domain/ScannerCapabilities.kt` | eSCL → UI 選項映射（解析度/來源/ADF 單雙面/色彩） |
| `domain/BackgroundEnhancer.kt` | OpenCV file-first 光照正規化、結果狀態、pixel limit 與原子檔案替換 |
| `domain/ScanImagePipeline.kt` | eSCL 檔案處理、ADF deskew、auto-crop、blank-page drop 與 source-safe replace |
| `domain/Ocr.kt` | ML Kit OCR 結果模型、script recognizer 對應、Google Play services model request 與安全降級 |
| `domain/OcrLanguagePacks.kt` | OCR 語言 catalog、區域分組、預設語言與 ML Kit script model 狀態 |
| `domain/OcrPostProcessing.kt` | OCR block／line／element／symbol 座標與 confidence、雙欄／寬版表格排序、數字格式化 |
| `app/build.gradle.kts`、`app/src/main/AndroidManifest.xml` | ML Kit unbundled dependencies 與 script model metadata |
| `domain/DocumentEditor.kt` | 純邏輯：旋轉、排序、刪除、裁切頁面 |
| `ui/EditScreen.kt` | 掃描後編輯畫面（旋轉/排序/裁切/刪除） |
| `ui/WindowSizeHelper.kt` | 平板偵測（≥600dp → isTabletLayout） |
| `ui/UriPrintAdapter.kt` | 手機 PDF／JPEG／PNG 交給 Android Print Framework |

Package root：`app/src/main/java/com/brianshih/mopria/android/scanprint/`。

## 3.1 語系行為

- 預設使用 Android 系統語系；支援 English、日、韓、西班牙、葡萄牙、德、法、俄、繁中、簡中。
- `zh-TW`、`zh-HK`、`zh-MO` 或 `Hant` 會使用繁體中文；`zh-CN`、`zh-SG`、`zh-MY` 或 `Hans` 會使用簡體中文。
- 不在支援清單的系統語系使用 English。
- 設定頁的手動選擇儲存在 `mopria_settings/language_tag`；選擇「依系統設定」會移除覆寫值。
- 新增翻譯時，先在 `res/values/strings.xml` 加入英文 fallback，再同步所有語系目錄；不要在 Compose、ViewModel 或 domain provider 新增可見硬編碼文字。

## 4. eSCL 行為摘要

1. `NsdManager` 同時探索 `_uscan._tcp.` 與 `_uscans._tcp.`。
2. `EsclDiscovery` 解析 TXT，拒絕不安全 `rs`，以 UUID + root 去重並偏好 TLS。
3. Scan 前 GET `ScannerStatus`，Scanner 必須 Idle；ADF 若有狀態則需 Loaded／Processing。
4. GET `ScannerCapabilities`，依來源、SettingProfile、格式、色彩及 X/Y resolution 協商。
5. ADF 分別解析 `AdfSimplexInputCaps`／`AdfDuplexInputCaps`；UI 只允許 capability 支援的模式，雙面 job 使用 duplex profile 並送出 `scan:Duplex=true`。
6. POST `{root}/ScanJobs`，要求 `201` 和安全 `Location`。
7. 依 JobInfo 狀態確認工作可傳輸，重複 GET `NextDocument`。
8. `503` 遵守 bounded `Retry-After`；`404` 表示頁面結束；timeout／`410` 會回查狀態。
9. 取消、失敗或無法讓設備只送指定頁數時，以 DELETE 清理工作。
10. JPEG／PNG 形成 image page；PDF 以 `PdfRenderer` 建立每一頁的 reference。
11. `NextDocument` response 直接串流至 app files 的暫存檔；影像處理以檔案作為輸入，OCR 僅建立受 12 MP／4096 px 限制的取樣 Bitmap，不建立影像 `ByteArray` 或無界全尺寸 Bitmap。
12. OCR 開啟時格式協商只接受 JPEG；選定 source／color profile 只有 PDF 時，建立 ScanJob 前回報本地化錯誤。
13. Real provider 可在下載後依設定執行 deskew、auto-crop、blank-page drop、背景淨化與 OCR；OCR 啟用時會自動要求 deskew／auto-crop，處理失敗會保留原檔，blank page 才會刪除。OCR 結果保留座標／confidence並做保守版面／數字後處理。

ADF max pages 有兩層意義：設備支援 `SelectSinglePage` 時送出 `NumberOfPages`；不支援時 client 取到上限即停止並 DELETE job。UI 與協定層都限制 1–50。

Flatbed multi-page 是多個獨立 eSCL Platen job 的 App-level session，不是單一 eSCL job。每頁完成後由使用者換紙並選「下一頁」，最後以 Android `PdfDocument` 合併。

## 5. 規格與授權界線

- Mopria Alliance eSCL Technical Specification v2.97 PDF 只存在開發者本機，不在 Git，也不應被複製到 repository、issue 或 CI artifact。
- 公開入口可連結 [Mopria eSCL Specification](https://mopria.org/mopria-escl-specification)。
- ScanBridge／eSCLKt 為 GPL-3.0-or-later：本專案只參考可觀察行為，不複製或連結其程式碼。
- HP JIPP（MIT；`jipp-core` + `jipp-pdl`）已作為直接 IPP client 依賴納入；Real 模式列印可在設定切換「系統列印（預設）」與「直接 IPP」。Direct IPP 支援 PDF、JPEG／PNG、PWG-Raster、PCLm、capability-constrained job options、固定長度 HTTP、job polling 與 timeout cancel；圖片 renderer 使用最高 300 dpi 的 bounded pixel budget，JPEG／PNG 多頁會依 `multiple-document-jobs-supported` 選擇單一多文件 job 或逐頁單文件 jobs。找不到 IPP 印表機時會顯示明確錯誤，不會 fallback。2026-09-17 實機驗證為 HP Direct IPP `Completed`、Brother Direct IPP `Failed`（當時誤判為 PWG-Raster resolution mismatch）；2026-09-18 確認真正根因為 Brother debut/1.30 IPP 不回應 HTTP/1.1——transport 改為 HTTP/1.0 後 Brother Direct IPP 亦 `Completed`，詳見 `docs/brother-contenttype-rootcause.md`。

## 6. 最新驗證證據

| 驗證 | 結果 |
|---|---|
| `:app:testDebugUnitTest` | 2026-09-17 本機 180 passed，0 failed |
| `:app:lintDebug` | 2026-09-17 0 errors、12 warnings |
| `:app:assembleDebug` | 2026-09-17 passed；APK 位於 `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:assembleRelease` | passed；R8 minify 開啟，兩個 ARM ABI 的 unsigned release APK 約 60.4 MB |
| `:app:connectedDebugAndroidTest` | 2026-09-17 API 36 emulator 34 passed，0 failed |
| Brother MFC-L2715DW／Android Default Print Service | API 36 emulator 手動加入 `10.1.121.175`；system print job 與 App History `Completed`，Brother queue 0／idle |
| HP LaserJet Pro MFP 3104fdw／Direct IPP | `10.1.121.182`；App History 與 HP eWS job `Completed` |
| Brother MFC-L2715DW／Direct IPP | `10.1.121.175`；2026-09-17 曾 `Failed`（HTTP/1.1 掛起），2026-09-18 transport 改 HTTP/1.0 後 App History `Completed`，實體出紙 |
| OpenCV A4 300 dpi ten-page soak | absolute peak PSS ≤256 MB、GC/idle 後 retained PSS 增量 ≤64 MB |
| Release native ABI | `arm64-v8a`、`armeabi-v7a`；加上 `-PreleaseAbiSplits=true` 可產生個別 APK；`zipalign -c -P 16 -v 4` passed |
| 16 KB page-size device | 尚未驗證；目前 AVD 為 4 KB page size |
| GitHub Actions CI | 舊 `main@f5dca33` 曾因 `./gradlew` exit 127 失敗；wrapper／line-ending 修復後 `main@68c2112` 已於 2026-09-16 成功 |
| `LanguageManagerTest` | 覆蓋 10 種可選語系、未知 tag、繁／簡中文 script 與 region 判斷 |
| `git diff --check` | passed |
| `DIRECT_IPP_FOLLOWUPS.md` | Direct IPP review findings、接手順序與驗收條件 |
| ADF emulator | max pages 改為 6；合併選項產生一份 6 頁文件及 PDF |
| Flatbed emulator | 2 個獨立 scan job；dialog 顯示第 1／2 頁；完成後一份 2 頁 PDF |
| DocumentsUI／Print | 系統選檔返回 App 列印頁，再回首頁 |
| Runtime | smoke flow 無 app fatal exception／OOM |

APK SHA-256：release APK 每次建置後需重新計算；GitHub Release 的舊 SHA-256 不代表目前合併版本。

語系 emulator smoke：清除 App data 後使用系統 en-US 驗證 English；設定頁可開啟 10 語言選單；手動選擇日本語與简体中文後，設定頁標題、操作說明與底部導覽即時更新。

Lint 目前無 error；Kotlin compiler 仍有既有 `EditScreen` rotate icon deprecation warnings，無 correctness／security error。Emulator screenshots 位於 ignored 的 `app/build/reports/emulator-smoke/`，不提交 Git。

## 7. 已知限制與風險

- Brother／HP 的基本實體 eSCL scanner／ADF 路徑已驗證；仍未完成更廣泛的廠商 capability 差異、TLS 憑證、ADF 空紙／卡紙與 job retention 驗收。
- 尚未實作 401 credential UI；目前會顯示 challenge 資訊並失敗。
- `426` 可切換同 host HTTPS，但沒有 RFC 2817 同一 TCP connection raw Upgrade。
- 掃描設定 UI 顯示 150／300／600 dpi 的產品選項，Real provider 會依 scanner capability 過濾／協商；仍需真實設備驗證非標準 resolution profile 的 UX。
- 文件 metadata、Flatbed session 與 OCR layout 可由內部 JSON／gzip sidecar 在 process death 後恢復，且讀寫在序列化的背景 I/O 執行；工作紀錄與進行中的網路工作仍只存在記憶體，raw scan 仍需產品化的保留期限策略。
- ML Kit Text Recognition v2 使用 unbundled script clients，由 Google Play services 管理模型準備；目前尚未以含 Google Play services 的 ARM 實機驗證模型下載、accuracy、cold/warm latency 或 PSS，因此不宣稱 OCR 已達產品準確率或 Mopria certification。Searchable PDF 已可由使用者明確選取，輸出路徑與三張 sample smoke 已在 API 36 emulator 驗證，但不代表產品 accuracy 或跨語言字型 coverage。
- OCR 目前只做版面排序與字串數字格式化；尚未做語意化表格 cell extraction、欄位／數值驗證。`Brian.jpg`、`b1.jpg`、`b2.jpg` 的 sample smoke 僅在 API 36 emulator 執行，不是產品 accuracy 證據。
- 尚未以 16 KB page-size emulator 或 ARM 實機執行 OpenCV／ML Kit soak；目前 instrumentation 只驗證 OCR invalid-source 邊界與不觸發模型的安全降級。
- 一般 PDF 輸出使用 Android `PdfDocument` 重繪頁面，不保留原始 PDF 的文字／向量語意；只有使用者同時啟用 OCR 與 Searchable PDF 時改走 PDFBox invisible text layer。
- 50 頁是 App 安全上限；大型高 dpi 掃描仍需實機 soak 與儲存空間檢查。
- Direct IPP 的 `IppRasterizer` 已逐頁持有 bitmap，但在完成實體高 DPI 多頁 soak（必要時再做 swath streaming）前，不應宣稱大型多頁工作已具產品級穩定性。
- Brother MFC-L2715DW 的 Direct IPP 已於 2026-09-18 修復並實測通過：根因為 Brother IPP 服務無法完成 HTTP/1.1 回應（transport 已改 raw-socket HTTP/1.0），先前的 PWG-Raster resolution negotiation 假設已證實非原因。

## 8. Android 平台注意事項

目前 `targetSdk 36`。依 Android 官方文件，target SDK 36 仍透過既有 `INTERNET` 權限存取 local network；`ACCESS_LOCAL_NETWORK` 是 target SDK 37+ 的遷移要求，請勿在 target 36 提前加入無效權限。升級時評估 system picker 路徑及完整 local-network permission 流程：

- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)

## 9. 建議接手順序

1. 先在 `main` 重新跑三個驗證指令並確認工作樹乾淨。
2. 以 Brother／HP 已驗證結果為基準，保存可提交且去識別的 TXT、Capabilities、Status 與 HTTP status sequence fixture。
3. 擴充 Flatbed JPEG、ADF PDF、`SelectSinglePage` true／false、取消、503、卡紙與斷線回歸。
4. 以更多真實 capability 驗證 ViewModel 對來源／解析度／色彩的動態限制與 fallback UX。
5. 設計持久化 job model 與 raw scan retention，再處理進行中工作在 background／process death 後的恢復策略。
6. 補 IPPS、格式、job lifecycle、TLS 與高 DPI 多頁 soak；完成至少兩品牌硬體 matrix 後才準備 Beta 宣稱。（Brother Direct IPP 已於 2026-09-18 修復並通過。）

詳細產品範圍見 [`README.md`](README.md)，里程碑見 [`dev_plan.md`](dev_plan.md)，本輪變更見 [`CHANGELOG.md`](CHANGELOG.md)。
