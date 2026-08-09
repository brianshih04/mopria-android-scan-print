# Development Handoff

更新日期：2026-08-09

Repository：`brianshih04/mopria-android-scan-print`

目標分支：`main`

## 1. 接手摘要

目前版本已完成可執行的 Android Compose App、Mock／Real 模式、eSCL v2.97 pull-scan client、Flatbed／ADF 多頁文件工作流、PDF／JPEG 文件庫、Android Print Framework 預設列印入口、opt-in Direct IPP，以及 10 種語言與系統語系 fallback。

既有 JVM／instrumentation 驗證可由下方固定指令重跑；目前仍未完成真實 eSCL scanner 與 Mopria printer 的跨品牌驗收，以及 Direct IPP 高 DPI 多頁 PWG-Raster／PCLm 的 streaming／OOM soak。請勿把 Mock／fixture 結果描述成 Mopria Certified 或廠牌相容證據。

OpenCV 與 eSCL file-first 影像管線已接入：使用官方 `org.opencv:opencv:4.14.0` AAR、原子檔案替換、ADF deskew、平台 auto-crop、blank-page drop 與背景淨化；`android:largeHeap="true"` 已設定，A4 300 dpi 十頁 OpenCV soak 以 256 MB peak／64 MB retained-PSS gate 通過。OCR option 已切換為 Google ML Kit Text Recognition v2，接入 settings、capability safety、provider pipeline、四種 script recognizer、Google Play services unbundled model request、預設 English／繁中／簡中與區域語言選擇／準備，以及 OCR 啟用時自動 deskew／auto-crop、結構化文字座標／confidence、雙欄／寬版表格與數字後處理。日文／韓文模型及 Searchable PDF 地域字型均改為使用者按需準備，JP／KR TTF 不進主 APK；Searchable PDF 透過獨立 opt-in 設定接入 PDFBox，並以 page-scoped OCR layout、bounded bitmap 與 crop／rotation 座標轉換產生不可見文字層。API 36 emulator 的三張中文樣本輸出與文字抽取已通過。實機模型／字型下載、辨識準確率／PSS、16 KB、真實 scanner、多語字型覆蓋率與大型 ADF soak 仍待驗證，細節見 `docs/opencv-integration-plan.md`、`docs/ocr-escl-image-pipeline.md` 與 `docs/searchable-pdf-poc.md`。

## 2. 快速啟動

```powershell
# 於專案根目錄執行（以下為 Windows PowerShell；macOS／Linux 改用 ./gradlew）
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
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
| `domain/IntegrationModels.kt` | 掃描設定、設備、文件頁與 UI state |
| `domain/EsclDiscovery.kt` | TXT metadata、resource root 驗證、安全服務優先 |
| `domain/EsclProtocol.kt` | XML parse、capability negotiation、ScanSettings、Job URL policy |
| `domain/EsclHttpClient.kt` | HTTP status、Retry-After、redirect、TLS、bounded streaming |
| `domain/RealIntegrationProvider.kt` | Android NSD、eSCL job lifecycle、JPEG／PDF 文件映射與 Direct IPP 列印協調 |
| `domain/MockIntegrationProvider.kt` | 不依賴硬體的 deterministic scan／print fixture |
| `domain/ScanDocumentOrganizer.kt` | Flatbed append 與 ADF split 純邏輯 |
| `domain/IppDiscovery.kt` | `_ipp/_ipps` NSD 探索、URI 與 secure candidate 選擇 |
| `domain/IppPrintClient.kt`、`IppTransport.kt` | Direct IPP capability、job lifecycle、fixed-length 傳輸與 cancel cleanup |
| `domain/IppRasterizer.kt`、`PrintRenderSizing.kt` | PWG-Raster／PCLm 產生與 bounded render 尺寸；多頁高 DPI streaming 仍待改善 |
| `ui/MopriaViewModel.kt` | discovery／scan／export／print 協調及 Flatbed session state |
| `ui/LanguageManager.kt` | 系統語系偵測、手動覆寫、中文 script／region 判斷與 English fallback |
| `ui/ScanScreen.kt` | ADF max pages、Flatbed／ADF PDF 合併設定及下一頁 dialog |
| `ui/DocumentsScreen.kt` | 掃描文件頁面、列印／分享／輸出操作 |
| `ui/DocumentPageBitmapLoader.kt` | JPEG／PNG／PDF-backed 頁面取樣／render |
| `ui/ScanExportService.kt` | MediaStore PDF／JPEG 與分享 PDF |
| `ui/SystemPrintAdapter.kt` | 掃描文件交給 Android Print Framework |

| `domain/ScanError.kt` | 結構化掃描錯誤（8 子類型），ViewModel 映射至 string resources |
| `domain/PdfPageRenderer.kt` | 共用 PDF 頁面渲染（writePdf + drawFitted） |
| `domain/DocumentStore.kt` | JSON 持久化文件列表與 Flatbed session |
| `domain/SettingsStore.kt` | SharedPreferences 讀寫，從 ViewModel 提取 |
| `domain/TempFileCleanup.kt` | 暫存檔三層清理（orphan scan + stale cache + delete） |
| `domain/ScannerCapabilities.kt` | eSCL → UI 選項映射（解析度/來源/色彩） |
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
5. POST `{root}/ScanJobs`，要求 `201` 和安全 `Location`。
6. 依 JobInfo 狀態確認工作可傳輸，重複 GET `NextDocument`。
7. `503` 遵守 bounded `Retry-After`；`404` 表示頁面結束；timeout／`410` 會回查狀態。
8. 取消、失敗或無法讓設備只送指定頁數時，以 DELETE 清理工作。
9. JPEG／PNG 形成 image page；PDF 以 `PdfRenderer` 建立每一頁的 reference。
10. `NextDocument` response 直接串流至 app files 的暫存檔；影像處理與 OCR 以檔案作為輸入，不在 production path 建立完整影像 `ByteArray` 或全尺寸 Android `Bitmap`。
11. Real provider 可在下載後依設定執行 deskew、auto-crop、blank-page drop、背景淨化與 OCR；OCR 啟用時會自動要求 deskew／auto-crop，處理失敗會保留原檔，blank page 才會刪除。OCR 結果保留座標／confidence並做保守版面／數字後處理。

ADF max pages 有兩層意義：設備支援 `SelectSinglePage` 時送出 `NumberOfPages`；不支援時 client 取到上限即停止並 DELETE job。UI 與協定層都限制 1–50。

Flatbed multi-page 是多個獨立 eSCL Platen job 的 App-level session，不是單一 eSCL job。每頁完成後由使用者換紙並選「下一頁」，最後以 Android `PdfDocument` 合併。

## 5. 規格與授權界線

- Mopria Alliance eSCL Technical Specification v2.97 PDF 只存在開發者本機，不在 Git，也不應被複製到 repository、issue 或 CI artifact。
- 公開入口可連結 [Mopria eSCL Specification](https://mopria.org/mopria-escl-specification)。
- ScanBridge／eSCLKt 為 GPL-3.0-or-later：本專案只參考可觀察行為，不複製或連結其程式碼。
- HP JIPP（MIT；`jipp-core` + `jipp-pdl`）已作為直接 IPP client 依賴納入；Real 模式列印可在設定切換「系統列印（預設）」與「直接 IPP」。Direct IPP 支援 PDF、JPEG／PNG、PWG-Raster、PCLm、capability-constrained job options、固定長度 HTTP、job polling 與 timeout cancel；圖片 renderer 使用最高 300 dpi 的 bounded pixel budget，JPEG／PNG 多頁會依 `multiple-document-jobs-supported` 選擇單一多文件 job 或逐頁單文件 jobs。找不到 IPP 印表機時會顯示明確錯誤，不會 fallback。尚未以實體印表機驗證。

## 6. 最新驗證證據

| 驗證 | 結果 |
|---|---|
| `:app:testDebugUnitTest` | passed，0 failed |
| `:app:lintDebug` | 0 errors |
| `:app:assembleDebug` | passed；APK 位於 `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:assembleRelease` | passed；R8 minify 開啟，unsigned release APK 約 154 MB（目前保留四個 ABI） |
| `:app:connectedDebugAndroidTest` | API 36 emulator：latest run 22 passed，0 failed |
| OpenCV A4 300 dpi ten-page soak | hard peak 增量 ≤256 MB、GC/idle 後 retained PSS 增量 ≤64 MB |
| Release native ABI | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`；OpenCV native library；`zipalign -c -P 16 -v 4` passed |
| 16 KB page-size device | 尚未驗證；目前 AVD 為 4 KB page size |
| GitHub Actions CI | PR #5 與合併後 `main` push 均通過 `testDebugUnitTest` + `lintDebug` + `assembleDebug`（Linux + JDK 25） |
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

- 尚未以實體 scanner 驗證廠商 capability 差異、TLS 憑證、ADF 空紙／卡紙與 job retention。
- 尚未實作 401 credential UI；目前會顯示 challenge 資訊並失敗。
- `426` 可切換同 host HTTPS，但沒有 RFC 2817 同一 TCP connection raw Upgrade。
- 掃描設定 UI 仍固定 150／300／600 dpi；provider 會協商最接近能力，下一步應改為 capability-driven UI。
- Flatbed session、文件與工作主要存在記憶體；process death 不可恢復，raw scan 檔案也尚無定期清理策略。
- ML Kit Text Recognition v2 使用 unbundled script clients，由 Google Play services 管理模型準備；目前尚未以含 Google Play services 的 ARM 實機驗證模型下載、accuracy、cold/warm latency 或 PSS，因此不宣稱 OCR 已達產品準確率或 Mopria certification。Searchable PDF 已可由使用者明確選取，輸出路徑與三張 sample smoke 已在 API 36 emulator 驗證，但不代表產品 accuracy 或跨語言字型 coverage。
- OCR 目前只做版面排序與字串數字格式化；尚未做語意化表格 cell extraction、欄位／數值驗證。`Brian.jpg`、`b1.jpg`、`b2.jpg` 的 sample smoke 僅在 API 36 emulator 執行，不是產品 accuracy 證據。
- 尚未以 16 KB page-size emulator 或 ARM 實機執行 OpenCV／ML Kit soak；目前 instrumentation 只驗證 OCR invalid-source 邊界與不觸發模型的安全降級。
- 文件輸出使用 Android `PdfDocument` 重繪頁面，不保留原始 PDF 的文字／向量語意。
- 50 頁是 App 安全上限；大型高 dpi 掃描仍需實機 soak 與儲存空間檢查。
- Direct IPP 的 `IppRasterizer` 仍可能同時保留多個高 DPI page bitmap；在完成逐頁／swath streaming 與 soak 前，不應宣稱大型多頁工作已具產品級穩定性。

## 8. Android 平台注意事項

目前 `targetSdk 36`。依 Android 官方文件，target SDK 36 仍透過既有 `INTERNET` 權限存取 local network；`ACCESS_LOCAL_NETWORK` 是 target SDK 37+ 的遷移要求，請勿在 target 36 提前加入無效權限。升級時評估 system picker 路徑及完整 local-network permission 流程：

- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)

## 9. 建議接手順序

1. 先在 `main` 重新跑三個驗證指令並確認工作樹乾淨。
2. 接第一台真實 eSCL MFP；優先保存去識別的 TXT、Capabilities、Status 與 HTTP status sequence fixture。
3. 驗證 Flatbed JPEG、ADF PDF、`SelectSinglePage` true／false、取消與 503。
4. 將真實 capability 注入 ViewModel，讓 UI 動態限制來源／解析度／色彩。
5. 設計持久化 job/document model 與 raw scan retention，再處理 background／process death。
6. 以至少兩個印表機品牌同時驗證 Android Print Service 與 Direct IPP（IPP/IPPS、格式、capability、job lifecycle、TLS 與高 DPI 多頁 soak），完成硬體 matrix 後才準備 Beta 宣稱。

詳細產品範圍見 [`README.md`](README.md)，里程碑見 [`dev_plan.md`](dev_plan.md)，本輪變更見 [`CHANGELOG.md`](CHANGELOG.md)。
