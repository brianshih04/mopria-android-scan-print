# OpenCV 背景淨化整合開發計畫

更新日期：2026-08-11
狀態：Phase 0–4C 已實作；Phase 5 實機／模型驗證 gate 進行中（取代 `3855f1c` 版本）
適用基準：`8fe10e0` 之後的純 Kotlin `BackgroundEnhancer` 版本

## 1. 目標與非目標

本計畫處理目前 eSCL 掃描流程的 file-first 影像管線：背景淨化、ADF deskew、平台 auto-crop、ADF blank-page drop，以及可選的 Google ML Kit Text Recognition v2 OCR seam。優先解決正確性、檔案安全、記憶體與實機相容性，再談真實模型與準確率。

### 本期目標

1. 使用官方 OpenCV Android AAR 實作彩色背景估計與光照歸一化。
2. 保留藍色簽名、紅色印章、黑色文字與淡色內容，不把彩色內容壓成黑色。
3. 任何失敗都不得破壞 scanner 回傳的原始檔。
4. A4 300 dpi 文件在受控記憶體內完成處理，連續多頁不累積 native memory。
5. 明確處理 JPEG、PNG、scanner-returned PDF、OpenCV 載入失敗與超大圖片。
6. 背景淨化的狀態、進度、降級與錯誤訊息全部可本地化。
7. eSCL image payload 直接落地至暫存檔，不建立 image ByteArray；OCR 只建立受 12 MP／4096 px 限制的取樣 Bitmap。
8. ADF 可選 deskew／blank-page drop，平台可選 auto-crop；每一步失敗都保留原檔。
9. OCR option 完成 settings／capability／provider、ML Kit script clients、Google Play services model request、OCR 專用 deskew／auto-crop 與結構化結果後處理；通過模型下載、accuracy、PSS 與裝置相容性 gate 後才可宣稱可用。

### 本期非目標

- 不在本里程碑加入手機相機或相片匯入。
- 不在本里程碑加入 `warpPerspective`、四角拖曳或需要 UI 編輯的透視校正。
- 不宣稱所有 scanner、品牌或 PDF 輸出格式均已相容；未經實機驗證不做相容性聲明。
- 不以 Mock／fixture 結果宣稱 Mopria Certified。

透視校正另列於第 12 節，必須等影像來源、四角資料模型與 UI 都有明確設計後再啟動。ML Kit script model request seam 已納入 repository，但仍受模型下載、accuracy／PSS／裝置相容性 gate 約束。

## 2. 先決技術決策

### 2.1 OpenCV 套件來源

使用 OpenCV 官方 Maven Central AAR，不採用停止在 OpenCV 4.5.3.0 的第三方 wrapper。

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("org.opencv:opencv:4.14.0")
}
```

正式採用前必須先完成 dependency spike：

- Maven Central 可解析、Debug／Release 均可建置。
- 檢查 artifact license 並更新第三方授權清單。
- 列出 APK/AAB 內所有 `.so` 與 ABI。
- 在 16 KB page-size emulator 驗證啟動與核心操作。
- 使用 `zipalign -c -P 16 -v 4` 驗證 APK 內 native library alignment。
- 若 4.14.0 無法滿足專案工具鏈或 16 KB 要求，回到選型 gate，不以舊第三方 wrapper 繞過。

### 2.2 ABI 策略

目前 debug 不設 `abiFilters`，保留 x86／x86_64 emulator 驗證；一般 release 以 `abiFilters` 只保留 `arm64-v8a`／`armeabi-v7a`。`-PreleaseAbiSplits=true` 會改產生兩個 ARM per-ABI APK，Play 路徑使用 AAB。2026-08-11 的一般 release APK 與 AAB 均已確認只含兩個 ARM ABI。

### 2.3 Fallback 策略

目前純 Kotlin fallback 不可原封不動作為 production fallback，原因包括：

- 全尺寸 Bitmap 與多份 `IntArray` 有 OOM 風險。
- 現有 RGB division factor 會額外乘上灰階亮度，可能把彩色內容壓暗。
- 現有檔案處理直接覆寫原檔，失敗時可能留下空檔或部分檔案。

本期安全策略是：

1. OpenCV 可用且輸入符合限制：執行 OpenCV pipeline。
2. OpenCV 不可用、格式不支援或超過安全像素上限：保留原始檔並回傳明確的 `Skipped` 結果。
3. 掃描工作可成功完成，但 UI 必須顯示可本地化的「背景淨化未套用」訊息。
4. 純 Kotlin `processPixels` 可暫留作演算法測試與比較，不得在 production 自動執行，直到它通過與 OpenCV 相同的檔案安全、色彩與記憶體 gate。

### 2.4 ML Kit runtime 策略

- 使用 Google ML Kit Text Recognition v2 unbundled clients：Latin、Chinese、Japanese 與 Korean script model 由 Google Play services 管理。
- App 先讀取 app-private scan file 的 bounds，以 power-of-two sample 解碼受 12 MP／4096 px 限制的 Bitmap，再透過 `InputImage.fromBitmap()` 交給 recognizer；不建立 image ByteArray 或 JNI bridge。
- `ModuleInstallClient` 先檢查 script model，未安裝時發出明確 install request；模型未完成下載或 Google Play services 不可用時回傳 `Skipped`。
- `AndroidManifest.xml` 只宣告預設的 `ocr`、`ocr_chinese`；Japanese／Korean 不隨安裝預取，由使用者在設定頁選取後發出 prepare request。
- ML Kit 的 bundled／unbundled 選擇、模型下載、accuracy、PSS、無 GMS 裝置與 16 KB 實機仍屬 Phase 5 gate。

### 2.5 OCR 語言與 script model 策略

- `OcrLanguagePack` 目前以 Global、East Asia、Europe and the Americas 分組，預設只選取 English、繁中、簡中；日文、韓文與其他 catalog 語言由使用者勾選後再請求對應 script model。
- `OcrLanguagePackManager` 不保存 ML Kit 模型檔、URL、archive、hash 或 app-private model directory；它透過 `ModuleInstallClient` 向 Google Play services 發出模型準備請求。JP／KR Searchable PDF 字型則以官方 URL、大小上限與固定 SHA-256 下載到 App 私有目錄，且只在文件需要時載入。
- 繁中與簡中共用 Chinese script model；English、Spanish、Portuguese、German 與 French 共用 Latin script model；目前不支援的 Russian 會顯示明確狀態，不能被選為 active OCR language。
- OCR 掃描前只檢查 active script model；模型未完成下載時保留掃描頁並回報 `ModelsMissing`／`RuntimeUnavailable`。

## 3. API 與 Coroutine 邊界

既有 `Boolean` 回傳值無法表達成功、跳過與失敗，也不適合包住 suspend pipeline。改為明確的 suspend API：

```kotlin
sealed interface EnhancementResult {
    data object Applied : EnhancementResult
    data class Skipped(val reason: EnhancementSkipReason) : EnhancementResult
    data class Failed(val error: EnhancementError) : EnhancementResult
}

enum class EnhancementSkipReason {
    OpenCvUnavailable,
    UnsupportedPdf,
    UnsupportedFormat,
    ImageTooLarge,
}

suspend fun enhanceImageFile(
    source: File,
    strength: EnhancementStrength,
): EnhancementResult
```

執行緒責任：

- HTTP、decode/encode 與檔案替換：`Dispatchers.IO`。
- OpenCV morphology/divide/convert：`Dispatchers.Default`。
- 不使用 `runBlocking`。
- 不用 `runCatching` 吞掉 `CancellationException`、`OutOfMemoryError` 或檔案寫入失敗。
- 在 decode 前、OpenCV 核心操作前後、encode 前檢查 coroutine cancellation。

`RealIntegrationProvider.scan()` 已是 suspend 路徑，由 provider 呼叫 `enhanceImageFile()`，再依 `EnhancementResult` 決定更新文件、提示降級或讓工作失敗。

## 4. 檔案安全與格式政策

### 4.1 原子寫回

禁止直接用 `FileOutputStream(source)` 覆寫 scanner 原始檔。正確流程：

1. 在 source 同目錄建立唯一暫存檔，例如 `page-1.enhancing.tmp`。
2. 將 OpenCV 結果編碼至暫存檔。
3. 檢查 encoder 回傳值、檔案長度與基本 signature。
4. 重新 decode 一次最小 bounds／header，確認輸出可讀。
5. 成功後才以同檔案系統的原子 move/replace 取代 source。
6. 任一步驟失敗：刪除暫存檔，source 保持位元級不變。

測試必須以 source SHA-256 驗證所有失敗路徑都不會改動原檔。

### 4.2 JPEG／PNG

- JPEG：保留合理品質設定並記錄二次壓縮風險；不得宣稱無損。
- PNG：保留 alpha；白化像素不得無條件改成 opaque white。
- 依 signature 判斷格式，不能只依副檔名。
- decode 失敗屬 `Failed`，不能靜默視為已套用。

### 4.3 Scanner-returned PDF

本期 MVP 不在下載後直接展開並重建 scanner-returned PDF。處理政策：

- 若 negotiated document format 是 PDF，回傳 `Skipped(UnsupportedPdf)`。
- 保留 PDF 原檔並完成掃描。
- UI 顯示可本地化提示，不能仍顯示背景淨化已成功。
- 後續若要支援，必須逐頁 `PdfRenderer` → OpenCV → `PdfDocument`，並另外通過多頁記憶體與畫質驗收。

`combineAsPdf` 是 App 的輸出組織選項，不代表 scanner payload 一定是 PDF；兩者不得混為一談。

## 5. OpenCV Pipeline 設計

### 5.1 File-first，而非 Bitmap-first

主要入口直接從檔案 decode 到 OpenCV `Mat`，避免同時持有完整 Android Bitmap 與完整 `Mat`。Android Bitmap API 只保留給 UI preview 或測試，不作 production 檔案 pipeline 的第一層。

```text
[JPEG/PNG source]
    → signature / bounds / pixel-limit validation
    → Imgcodecs.imread
    → optional alpha extraction
    → downsampled background estimation
    → full-size color normalization
    → contrast adjustment
    → alpha merge
    → encode to sibling temp file
    → validate
    → atomic replace
```

### 5.2 背景估計與彩色歸一化

1. 對 RGB/BGR 彩色影像建立縮小版本。
2. 在縮小版本執行 `Imgproc.morphologyEx(..., MORPH_CLOSE, ...)`。
3. 將背景模型升採樣，或按 tile 取得對應背景區塊。
4. 使用 `Core.divide(sourceColor, background, normalized, 255.0)`。
5. 使用 `convertTo` 套用各強度的 alpha/beta。
6. 還原 alpha，禁止把透明像素無條件轉成不透明白色。

不再使用目前純 Kotlin 的 `origGray / background` factor。

### 5.3 EnhancementStrength

參數不是先寫死再宣稱有效；先建立有代表性的 fixture corpus，調參後固定：

| 強度 | 目標行為 |
|---|---|
| Light | 去除輕微陰影，最大程度保留淡色筆跡 |
| Normal | Document preset 預設，背景明顯變白且彩色印章不失真 |
| Strong | 更積極白化，UI 明示可能損失極淡內容 |

每種強度至少包含：kernel 尺寸、downsample factor、alpha、beta，以及嚴格遞增的測試 fixture。不能用 `>=` 讓三種輸出完全相同仍通過。

### 5.4 OpenCV Runtime 狀態

建立單一 `OpenCvRuntime` 管理初始化結果：

- App 啟動可預先初始化，但 pipeline 必須能安全地 lazy-check。
- 狀態為 `Available`／`Unavailable(errorType)`，不能只寫 Log。
- 初始化失敗不反覆重試或在每頁重新呼叫。
- 不把原始 native exception 直接顯示給使用者；轉成可本地化 error type。

## 6. Native Memory 設計

### 6.1 資源生命週期

每個 `Mat`、kernel、split channel 與 perspective matrix 都必須在例外與取消路徑釋放。禁止把一串 `.release()` 放在成功路徑尾端。

建議建立小型資源容器：

```kotlin
class MatScope : AutoCloseable {
    private val mats = ArrayDeque<Mat>()
    fun own(mat: Mat): Mat = mat.also(mats::addFirst)
    override fun close() = mats.forEach(Mat::release)
}
```

實作時仍需確認：

- `Core.split()` 產生的每個 channel 都註冊。
- 移出 list 的 alpha channel 仍由 scope 持有。
- 任何 clone、kernel、transform matrix、temporary destination 都註冊。
- 回傳前若結果需要離開 scope，必須清楚轉移 ownership。

### 6.2 記憶體上限

不再以「native heap 管理」當作低記憶體保證；Android process limit 同樣受到 native allocation 影響。

初始產品限制：

- 背景淨化只處理不超過 `MAX_ENHANCEMENT_PIXELS` 的影像；初始候選值 12 MP，必須由 profiler 決定最終值。
- 超過上限回傳 `Skipped(ImageTooLarge)`，保留原檔並提示使用者。
- Document preset 預設 300 dpi；600 dpi 加背景淨化必須被 UI 阻止或明確降級，不能嘗試後才 OOM。

A4 300 dpi 驗收預算：

- 記錄 Java heap、native heap 與 total PSS。
- 單頁處理的 absolute peak PSS 硬上限為 256 MB；另保留相對 baseline 增量作診斷。`android:largeHeap` 只能提供較寬鬆的 process heap 空間，不能取代 absolute PSS gate。
- 連續 10 頁後，處理完成且 GC/idle 後 retained PSS 增量不超過 64 MB；這是針對 OpenCV allocator warm cache 的 bounded gate，不把 native allocation 誤當作 Java heap。
- 若無法達成，需改為 tile/strip pipeline；不能只提高 heap 或吞掉 `OutOfMemoryError`。

## 7. UI、狀態與能力一致性

### 7.1 初始 preset

全新安裝時 UI 顯示的 preset 必須與實際 `ScanSettings` 一致：

- 若預設選取 Document，初始設定必須是 300 dpi、Color、PDF organize、Normal enhancement。
- 或將 preset 初始狀態改為未選取／Custom。

不能顯示 Document 已選取，但 `combineAsPdf=false`、`enhanceBackground=null`。

### 7.2 Scanner capabilities

取得 real scanner capabilities 後，必須同步 reconcile 現有設定：

- input source、resolution、color mode 都必須落在 supported set。
- 使用 scan() 內建 discovery 的路徑也要取得 capabilities；不能只在手動 Find devices 時更新。
- 若設定被調整，UI summary、preset 與持久化資料一起更新。

### 7.3 進度與錯誤

新增可觀察狀態，例如 `isEnhancing` 或明確 job detail：

- 下載完成後顯示背景處理中。
- `Applied`：正常完成。
- `Skipped`：掃描成功，但顯示原因。
- `Failed`：原檔仍完整；依產品決策讓掃描成功保留原圖，或將 job 標示失敗。

所有新增文字同步 10 語系，不在 provider 硬編碼使用者可見字串。

### 7.4 eSCL image pipeline

- `EsclHttpClient.fetchNextDocument()` 只負責 bounded stream-to-file；control XML 才可使用小型 ByteArray。
- Real provider 依序執行 processing、enhancement、OCR；ADF blank page drop 會刪除已判定的頁檔並從文件移除。
- deskew 使用水平形態學膨脹後的輪廓 `minAreaRect` 中位角度；角度超過安全範圍時保留原圖。
- auto-crop 只在可信紙張輪廓有明顯 margin 時套用；找不到輪廓不應裁掉內容。
- JPEG／PNG 一律 sibling temp + output validation + atomic replace；PDF 與未知格式只回報 Skipped。

### 7.5 OCR option

- `OcrMode.MlKit` 啟用時由 capability reconciliation 優先選 <=300 dpi 與 Grayscale8。
- 沒有 <=300 dpi capability 時停用 OCR 並通知使用者，不發送未協商的 eSCL 設定。
- `MlKitOcrEngine` 先讀影像 bounds，以 12 MP／4096 px 長邊限制取樣 ARGB bitmap，再透過 Task boundary 呼叫 ML Kit；模型或 Google Play services 缺失時回報 `ModelsMissing`／`RuntimeUnavailable`。
- OCR 啟用時 eSCL 只協商 `image/jpeg`；選定 source／color profile 只有 PDF 時，在建立 ScanJob 前回報 `OcrImageFormatUnsupported`。
- 不把模型下載 request 當成辨識成功，也不以 mock text 作 accuracy 證據。
- OCR 啟用時 Real provider 自動要求 deskew／auto-crop；`OcrResult.Applied` 保留 block／line／element／symbol 的座標、角度、語言與 confidence。
- `OcrTextFormatter` 對有座標的寬版表格按列輸出、對明顯雙欄保留欄順序，並以保守規則修正全形與混用千分位數字；這不等於語意化 cell extraction。Searchable PDF 另由獨立 opt-in 的 PDFBox export path 處理。

## 8. 實作階段與 Gate

### Phase 0：修正 baseline 與文件

- 修正純 Kotlin division 測試，驗證 RGB channel／色相，而非只驗 luminance。
- 將 production fallback 改成安全的 `Skipped`，停止執行不安全的 Kotlin 全圖處理。
- 修正初始 Document preset 與設定失配。
- README／HANDOFF／CHANGELOG 將目前實作描述為 Box Blur 近似；移除未量測的 16x、記憶體 1/16 宣稱。
- 移除重複 `PropertyEscape` suppression。

Gate：全部既有 unit tests、lint、assembleDebug 通過，原始掃描流程無回歸；測試數量以當次 Gradle 輸出為準。

### Phase 1：Dependency spike

- 加入官方 OpenCV AAR。
- 建立 `OpenCvRuntime` 最小初始化。
- 驗證 Debug／Release、APK/AAB 大小、ABI、16 KB alignment 與 16 KB emulator 啟動。
- 記錄 dependency、license 與 rollback diff。

Gate：沒有程式功能接線前，先確認 release artifact 可安裝、可初始化、可執行一個小型 Mat operation。

### Phase 2：安全檔案處理層

- 導入 `EnhancementResult` 與 suspend API。
- 完成 signature/bounds 驗證、pixel limit、temp encode、output validation、atomic replace。
- 可注入 decoder/encoder/file replacer，讓失敗路徑可測。

Gate：decode、encode、磁碟不足、取消與 replace 失敗時，source SHA-256 全部不變。

### Phase 3：OpenCV 色彩管線

- 實作 file-first morphology close + color divide + contrast。
- 實作 alpha 保存。
- 導入 exception-safe `MatScope`。
- 調整 Light／Normal／Strong 參數。

Gate：fixture corpus 的背景、文字、淡色筆跡、藍色簽名與紅色印章均符合品質門檻；三種強度有嚴格可觀察差異。

### Phase 4：Provider 與 UI 整合

- `RealIntegrationProvider` 在下載後呼叫 suspend enhancement。
- 處理 JPEG／PNG Applied、PDF Skipped、超大圖片 Skipped、OpenCV unavailable Skipped。
- 加入 job progress 與 10 語系訊息。
- reconcile capabilities 與初始 preset。

Gate：所有結果都不會靜默假裝增強成功，Mock 結果不被用作實機相容證據。

### Phase 4A：eSCL image operations 與 OCR option

- 接上 file-first processing progress、deskew、auto-crop、ADF blank-page drop。
- 加入 OCR settings persistence、300 dpi／grayscale capability safety、ML Kit Text Recognition v2 backend、script model clients 與 model-missing/runtime-unavailable result。
- 補齊 OpenCV instrumentation fixtures、blank-page、crop、deskew、source-integrity 與 OCR contract tests。

Gate：沒有 image ByteArray／無界 full-resolution Bitmap 中轉；所有 processing/OCR skip/failure 都保留原檔並有本地化訊息；model missing/runtime unavailable 不會阻斷掃描；ML Kit 模型下載與 accuracy 仍需實機 gate。

### Phase 4B：OCR language-pack selection

- 預設只選取 English、繁中、簡中；日文、韓文與其他區域 catalog 語言由使用者按需選取，並保存 selected／active 狀態。
- 透過 Google Play services `ModuleInstallClient` 發出 Latin／Chinese／Japanese／Korean script model request；不自行保存或驗證 `.nb` artifact。

Gate：語言選取、active 語言與模型 request 狀態可恢復；模型尚未完成下載、Google Play services 不可用或 ML Kit 不支援的語言不能被描述為 OCR 已可用。

### Phase 4C：OCR 文件品質後處理

- OCR 啟用時自動串接 deskew／auto-crop，避免使用者忘記開啟影像前處理而把傾斜文件直接送入辨識。
- 將 ML Kit 的 block／line／element／symbol 階層與 bounds、corner points、angle、confidence 映射至 Android-independent `OcrTextLayout`。
- 以座標保留單欄、雙欄與寬版表格的閱讀順序；對 OCR 數字做保守的全形／千分位格式化，避免改寫一般小數。
- 以 `Brian.jpg`、`b1.jpg`、`b2.jpg` 做 API 36 emulator sample smoke test，確認三張圖片可回傳 `Applied` 且結果保留座標／confidence；該測試不作產品 accuracy gate。

Gate：structured result、formatter、Searchable PDF opt-in、嵌入文字抽取與 crop／rotation 座標轉換有覆蓋；任何 processing／OCR failure 仍保留 source；未完成 cell-level table extraction、語意驗證或真實 scanner／多語字型驗證前，不宣稱表格欄位或產品 accuracy 已正確。

### Phase 5：實機、壓力與發布驗證

- ARM64 4 KB 與 16 KB page-size emulator。
- 至少一台低記憶體實機與一台主流 ARM64 實機。
- A4 300 dpi 單頁與 ADF 10 頁 soak。
- JPEG、PNG alpha、scanner-returned PDF、12 MP 邊界、取消與低磁碟空間。
- Android Profiler 記錄 Java/native/PSS；保存測試方法與數據，不只寫結論。

Gate：符合第 10 節完成條件後才能預設啟用。

## 9. 測試計畫

### JVM tests

- `EnhancementStrength` 參數映射。
- preset/default/settings persistence。
- `EnhancementResult` 到 UI/domain error 的映射。
- 原子替換協調邏輯與失敗回復。
- RGB fixture：檢查 channel／色相／chroma，不只檢查 luminance。
- 強度 fixture：`Strong > Normal > Light` 必須是嚴格差異。

### Android instrumentation tests

- OpenCV 初始化與最小 Mat operation。
- 真實 Bitmap/Mat conversion 或 file-first decode/encode。
- PNG alpha preservation。
- temp file 驗證與 atomic replace。
- cancellation 與 Activity/ViewModel lifecycle。

### 實機測試

- 真實 eSCL JPEG、PNG 與 PDF payload。
- 藍色簽名、紅色印章、鉛筆字、陰影、摺痕、全黑標題。
- ADF 多頁與連續掃描。
- OpenCV 初始化失敗模擬與原圖保留。
- eSCL JPEG stream-to-file、ADF deskew、auto-crop、blank-page drop、OCR model missing 與 cancellation。

## 10. 完成條件

以下全部完成才可將 OpenCV 背景淨化視為 production-ready：

- [x] 使用官方 OpenCV AAR，版本與 license 已記錄。
- [x] Debug／Release 可建置，APK `zipalign -P 16` 已通過。
- [ ] 16 KB page-size emulator／ELF runtime 尚待驗證。
- [x] 不直接覆寫 source；目前 JVM／instrumentation failure paths 皆保留 source integrity。
- [x] OpenCV unavailable、PDF、超大圖片都有明確 Skipped 行為與本地化訊息。
- [x] 純 Kotlin 不安全 pipeline 不再作 production 自動 fallback。
- [x] 每個 Mat／kernel／channel 在成功、例外、取消路徑都釋放。
- [x] A4 300 dpi 單頁峰值與 10 頁 emulator soak 符合第 6.2 節記憶體 gate。
- [ ] 效能在指定 baseline device 實測：目標 median ≤ 1 秒、p95 ≤ 1.5 秒；未量測前不寫成已達成。
- [x] Light／Normal／Strong 在 JVM／instrumentation 固定 fixture 上有嚴格差異。
- [x] 藍色簽名與紅色印章 fixture 保留主色且不變白。
- [ ] 淡色鉛筆與真實紙張畫質仍待實機 fixture。
- [x] 初始 preset、scanner capabilities、UI summary 與實際 settings 一致。
- [x] 專案標準 unit test、lint、assembleDebug 與 `git diff --check` 全部通過。
- [x] Release artifact、APK/AAB 大小與 rollback 步驟已記錄；真實簽章仍待發布流程。
- [x] README、HANDOFF、CHANGELOG 只描述已驗證能力，保留未實機驗證限制。
- [x] eSCL image pipeline 已驗證不建立 image ByteArray；ML Kit Bitmap 有 12 MP／4096 px 硬上限。
- [x] deskew、auto-crop、blank-page drop 的 instrumentation contract 通過且 failure 保留 source。
- [x] OCR settings/capability/ML Kit seam、script model catalog 與 Gradle dependency build 通過；license/source 已記錄。
- [x] 預設 English／繁中／簡中、區域選擇、active language 與 Google Play services model request flow 已接線；日文／韓文按需準備。
- [x] OCR 啟用時自動執行 deskew／auto-crop，`Applied` 保留 ML Kit 階層座標與 confidence，並完成寬版表格／雙欄／數字格式化的 JVM 規則測試。
- [x] Searchable PDF opt-in、PDFBox mixed/temp invisible text layer、嵌入 Noto 字型、持久化 page-scoped OCR layout 與 crop／rotation 座標轉換；缺少 layout／字型時明確失敗；三張中文樣本在 API 36 emulator 通過輸出／抽取 smoke。
- [ ] 語意化表格 cell extraction、欄位／數值驗證、日文／韓文字型 coverage 與真實 scanner payload 驗證。
- [ ] ARM 真實 ML Kit model download、accuracy、cold/warm latency、PSS、取消與 16 KB gate。
- [ ] 無 Google Play services 裝置的 fallback／bundled model 決策與實機驗證。

## 11. 驗證指令

PowerShell on Windows；專案既定旗標不可省略：

```powershell
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --max-workers=1 --no-daemon
git diff --check
```

Dependency spike 與發布 gate 另加：

```powershell
.\gradlew.bat :app:assembleRelease :app:bundleRelease --max-workers=1 --no-daemon
# 使用本機已安裝的 build-tools 版本；以下以 36.1.0 為例
& "$env:ANDROID_SDK_ROOT\build-tools\36.1.0\zipalign.exe" -c -P 16 -v 4 app\build\outputs\apk\release\app-release-unsigned.apk
```

若 `ANDROID_SDK_ROOT` 未設定，使用 `local.properties` 中實際 SDK 路徑；不可把個人絕對路徑提交進 repository。

2026-08-11 本機 `main` 快照：163 JVM tests、28 個 API 36 instrumentation tests、lint、debug／release APK、AAB、兩 ARM ABI 與 zipalign 通過。GitHub Actions 同一版在 Gradle 前因 Linux runner 無法執行 `./gradlew`（exit 127）失敗；修復 CI 前不可將 phase gate 描述為全綠。

## 12. 後續里程碑：透視校正

只有在背景淨化 production gate 完成後，才另開 perspective correction 計畫。該計畫至少要先回答：

1. 影像來自相機、相片匯入，還是 scanner page editor？
2. 四角座標使用原始像素或 normalized coordinates？
3. 如何處理 rotation、既有 `CropRect` 與 perspective transform 的順序？
4. 四角拖曳 UI 如何支援縮放、螢幕旋轉、TalkBack 與平板？
5. transformed page 如何持久化、匯出與避免 OOM？

在這些問題未定義前，不把 `warpPerspective` 放進本期完成條件。

## 13. 參考來源

- [OpenCV Android development documentation](https://docs.opencv.org/4.14.0/d5/df8/tutorial_dev_with_OCV_on_Android.html)
- [OpenCV official releases](https://github.com/opencv/opencv/releases)
- [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes)
- [Android zipalign](https://developer.android.com/tools/zipalign)
