# OpenCV 背景淨化整合接手指南

更新日期：2026-09-16
狀態：Phase 0–4C 實作完成；Brother／HP eSCL 實機基準完成，Phase 5 ARM 模型／16 KB／OCR 產品 gate 待完成
主計畫：`docs/opencv-integration-plan.md`

## 1. 接手者先知道的事

目前 `main` 已完成 Phase 0–4C 的主要實作：官方 OpenCV AAR、file-first pipeline、原子寫回、`EnhancementResult`、ADF deskew／auto-crop／blank-page drop、OCR settings/backend seam、ML Kit Text Recognition v2、預設 English／繁中／簡中、區域語言選擇、本地化錯誤，以及 OCR 啟用時的自動 deskew／auto-crop、bounded sampled bitmap、結構化文字座標／confidence 與版面／數字後處理。一般 Release 只保留兩個 ARM ABI；debug 保留 emulator ABI，`zipalign -P 16` 與 API 36 emulator instrumentation 已驗證。Brother／HP eSCL 真實 payload 已完成基本掃描驗證；16 KB、真實 payload 的 OCR／OpenCV accuracy、ML Kit 模型下載／accuracy／ARM PSS gate 尚未完成。透視校正仍不在本期範圍。

本文件只提供快速接手路線。技術決策、phase gate、測試矩陣與完成條件以 `docs/opencv-integration-plan.md` 為準；若兩份文件不一致，以主計畫為準。

## 2. 必读順序

1. `AGENTS.md`：安全紅線、固定建置旗標、i18n 與實機聲明限制。
2. `docs/opencv-integration-plan.md`：完整設計與 phase gate。
3. `domain/BackgroundEnhancer.kt`：OpenCV pipeline、runtime 狀態、source-safe file flow 及 JVM reference fixture。
4. `domain/RealIntegrationProvider.kt`：scanner payload 下載與增強呼叫位置。
5. `domain/IntegrationModels.kt`：`EnhancementStrength`、`ScanSettings`。
6. `domain/SettingsStore.kt`：掃描設定持久化。
7. `domain/ScanImagePipeline.kt`、`domain/Ocr.kt`、`domain/OcrLanguagePacks.kt`、`domain/OcrPostProcessing.kt`：file-first processing、ML Kit boundary、script model 管理與結構化 OCR 版面／數字後處理。
8. `ui/MopriaViewModel.kt`、`ui/ScanScreen.kt`：preset、capabilities、進度與 UI。
9. `BackgroundEnhancerTest.kt` 與 Android instrumentation tests：色彩、source integrity、PSS 與 image processing contract。

## 3. 現況與已知風險

| 項目 | 現況 |
|---|---|
| OpenCV dependency | `org.opencv:opencv:4.14.0` 已加入；license 已記錄，release／zipalign 已驗證，16 KB emulator 尚待驗證 |
| Production pipeline | OpenCV file-first morphology close + color divide + contrast |
| Result／fallback | `Applied`／`Skipped`／`Failed`；原檔失敗路徑保留 |
| 強度 | Light / Normal / Strong |
| 檔案格式 | JPEG／PNG 嘗試處理；scanner-returned PDF 跳過 |
| 單元測試 | 以當次 Gradle 輸出為準，不在文件固定數量 |
| 2026-08-11 本機快照 | 163 JVM tests、28 個 API 36 instrumentation tests、lint、debug／release／AAB、zipalign 通過 |
| GitHub Actions | 舊版本曾因 Linux `./gradlew` exit 127 失敗；wrapper／line-ending 修復後 `main@68c2112` 已於 2026-09-16 成功 |
| 實機驗證 | Brother／HP eSCL 基本掃描已驗證；OpenCV／OCR accuracy、ARM PSS、16 KB 與更廣設備矩陣仍未完成 |
| eSCL image stream | HTTP response 直接落地 sibling file；不建立影像 ByteArray；OCR Bitmap 受 12 MP／4096 px 限制 |
| ADF／平台 processing | deskew、auto-crop、blank-page drop 已接線並有 instrumentation contract |
| OCR option | ML Kit Text Recognition v2 option、settings、capability safety、script model request、language catalog、結構化 bounds／confidence 與版面／數字 formatter；模型由 Google Play services 管理 |
| memory | `largeHeap=true`；A4 300 dpi 10 頁 absolute peak PSS 256 MB、retained delta 64 MB；PDFBox 32 MiB 後使用 cache scratch file |

仍需注意的風險與未完成 gate：

- A4 300 dpi 與 ADF 10 頁 PSS 已在目前 emulator 通過 absolute 256 MB／retained 64 MB gate；ARM64 實機尚未量測。
- 16 KB page-size emulator／ARM 實機、ML Kit model download／accuracy／PSS、Google Play services 缺失 fallback 尚未完成；一般 release artifact 只保留兩個 ARM ABI，debug 保留 x86/x86_64。
- 實際低磁碟、取消、replace 失敗與 OpenCV 初始化失敗的 instrumentation gate 尚待補齊。
- Brother／HP 真實 scanner payload 已完成基本 eSCL 驗證；仍需以這些 payload 與彩色／多語文件完成 OpenCV／OCR accuracy、PSS 與輸出品質 gate。

## 4. 已決定的方向

### Dependency

- 使用官方 Maven Central AAR：`org.opencv:opencv:4.14.0`；license 記錄於 `docs/third-party-licenses.md`。
- 不使用 `com.quickbirdstudios:opencv:4.5.3.0`。
- Debug 保留 x86／x86_64 emulator ABI；一般 release 已只保留 arm64-v8a／armeabi-v7a，亦可用 `-PreleaseAbiSplits=true` 產生個別 ARM APK。
- Release 前必須驗證 16 KB page-size alignment 與 16 KB emulator；目前 APK alignment 已通過，16 KB emulator 尚未取得。

### API

- 將同步 `Boolean` API 改為 suspend `EnhancementResult`。
- `Applied`、`Skipped`、`Failed` 必須可區分。
- CPU 工作使用 `Dispatchers.Default`；檔案與 HTTP 使用 `Dispatchers.IO`。
- 不使用 `runBlocking`，不吞 `CancellationException` 或 `OutOfMemoryError`。

### File safety

- 永遠寫入同目錄暫存檔。
- encode、signature、decode validation 全部成功後才 atomic replace。
- 任何失敗都要證明 source SHA-256 不變。

### Fallback

- OpenCV unavailable／PDF／超大圖片：保留原圖並回傳 `Skipped`。
- UI 顯示本地化提示，不假裝增強成功。
- 純 Kotlin pipeline 暫不作 production 自動 fallback。

### Scope

- 本期做 eSCL 文件背景淨化與 file-first image operations；OCR 已接到 ML Kit 與結構化後處理，Searchable PDF 也已接入獨立 opt-in 的 PDFBox export path；真實模型 deployment、語意化表格、多語字型與 scanner payload 仍受外部 gate。
- `warpPerspective`、相機、相片匯入、四角 UI 另開後續里程碑。

## 5. 實作順序

1. **Phase 0 — Baseline 修正（已完成）**
   - 純 Kotlin pipeline 僅保留作 reference fixture，不作 production 自動 fallback。
   - 修正 RGB channel normalization、暗色內容保留、主文件過度宣稱與重複 lint suppression。

2. **Phase 1 — Dependency spike（基礎已完成）**
   - 官方 OpenCV AAR、`OpenCvRuntime` 與 instrumentation Mat smoke test 已加入。
   - Debug／Release build、兩個 ARM release ABI、debug emulator ABI、OpenCV license 與 `zipalign -P 16` 已通過；16 KB emulator／ELF runtime 尚待 gate。

3. **Phase 2 — 安全檔案層（主要實作已完成）**
   - `EnhancementResult`、suspend API、pixel limit、signature/bounds、temp encode、validation、atomic replace 已加入。
   - JVM 已驗證 PDF／unsupported format source integrity；注入式 failure matrix 尚待 instrumentation。

4. **Phase 3 — OpenCV pipeline（基礎已完成）**
   - file-first decode、downsampled morphology close、`Core.divide(..., 255.0)`、alpha preservation 與 `MatScope` 已加入。
   - OpenCV fixture、A4 300 dpi 十頁 emulator memory gate 已通過；真實 scanner fixture、ARM PSS 與畫質驗收尚待完成。

5. **Phase 4 — Provider/UI（最小接線已完成）**
   - Real provider 已攜帶每頁 enhancement result；PDF、OpenCV unavailable、ImageTooLarge 會回報 Skipped。
   - ViewModel 已接上 10 語系訊息、進度細分與 capability/preset reconciliation。

6. **Phase 4A — eSCL image operations／OCR（目前已接線）**
   - `ScanImagePipeline` 執行 blank-page drop、deskew、auto-crop，並與 enhancement 共用 source-safe temp flow。
   - `OcrMode.MlKit` 只在 <=300 dpi／grayscale-safe capability 下保留；model/runtime 缺失回報 skipped。
   - ML Kit Text Recognition v2 的 bounded sampled Bitmap、Latin／Chinese／Japanese／Korean clients 與 ModuleInstall request 已納入；模型下載、accuracy 與 ARM PSS 仍待外部 gate。

7. **Phase 4B — OCR language selection（目前已接線）**
   - 預設 English、繁中、簡中；日文、韓文及其餘 catalog 由設定頁按 Global／East Asia／Europe and the Americas 分組供使用者按需選取。
   - ML Kit script model manager 透過 Google Play services `ModuleInstallClient` 發出準備請求，不保存 URL、`.nb` 或模型 hash；JP／KR PDF 字型另以 checksum 驗證後保存於 App 私有目錄，不進主 APK。
    - Chinese、Japanese、Korean 與 Latin 由 ML Kit script clients 提供；目前不支援的 catalog 語言會顯示明確狀態，不會被當成可用模型。

8. **Phase 4C — OCR 文件品質後處理（目前已接線）**
   - OCR 啟用時 Real provider 自動把 deskew／auto-crop 納入處理設定。
   - `OcrResult.Applied` 保留 block／line／element／symbol、bounds、角度、recognized language 與 confidence。
   - 寬版表格按 y 分列、雙欄保留欄順序、數字 formatter 修正全形／混用千分位；Searchable PDF 以 page-scoped OCR layout、PDFBox invisible text layer 與 crop／rotation transform 接入，尚未做 cell-level extraction、欄位驗證或多語字型完整 coverage。

9. **Phase 5 — 實機與發布 gate**
   - A4 300 dpi、ADF 10 頁、低記憶體實機。
   - 4 KB／16 KB ARM64 環境。
   - 色彩 fixture、取消、低磁碟空間、source integrity。

每個 phase 完成後才進下一階段，不把 dependency、演算法、UI 與透視校正一次混在同一個 diff。

## 6. 最重要的實作品質門檻

- 原始掃描檔在所有失敗路徑保持不變。
- 每個 OpenCV `Mat`／kernel／channel 在成功、例外與取消時都 release。
- A4 300 dpi 單頁 peak memory 與 ADF 10 頁 soak 符合主計畫 gate。
- 600 dpi 或超過像素上限時不嘗試到 OOM；明確 Skipped。
- 藍色簽名、紅色印章、淡色筆跡不能被壓成黑色或白色。
- Light／Normal／Strong 在固定 fixture 上必須有嚴格差異。
- PDF payload 不得靜默顯示增強成功。
- 未量測前，不寫「0.5 秒」「16x」「記憶體 1/16」等完成式宣稱。

## 7. 固定驗證指令

PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --max-workers=1 --no-daemon
git diff --check
```

Dependency／Release gate：

```powershell
.\gradlew.bat :app:assembleRelease :app:bundleRelease --max-workers=1 --no-daemon
# 使用本機已安裝的 build-tools 版本；以下以 36.1.0 為例
& "$env:ANDROID_SDK_ROOT\build-tools\36.1.0\zipalign.exe" -c -P 16 -v 4 app\build\outputs\apk\release\app-release-unsigned.apk
```

不要把個人 JDK 或 Android SDK 絕對路徑寫進 repository。`local.properties` 由本機或 Android Studio 產生。

## 8. 安全紅線

OpenCV 工作不得改動既有網路安全契約：

- TLS 使用 Android 系統 trust store。
- eSCL job／redirect URL 維持同 host、同 resource root 限制。
- XML parser 維持 DOCTYPE／external entity 禁用。
- 不複製或連結 GPL ScanBridge／eSCLKt 程式碼。
- 不以 Mock／fixture 宣稱 Mopria Certified 或品牌相容。

## 9. 完成交接時應留下的證據

- dependency 與 license 決策紀錄。
- APK/AAB before/after size 與 ABI 清單。
- 16 KB zip/ELF alignment 輸出與 emulator 結果。
- 色彩 fixture before/after。
- source integrity failure tests。
- Java heap、native heap、total PSS 與 10 頁 soak 圖表／數據。
- 實際 test、lint、build 輸出；不要把舊測試數字複製到新 commit message。
- 尚未實機驗證的限制與 rollback 步驟。
