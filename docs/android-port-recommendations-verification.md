# Android Port Recommendations 驗證結果 — 2026-09-16

## 背景

依據 `android-port-recommendations-2026-09-16.md`（由 iOS 版 code review 推導出的 8 項跨平台建議），
逐項對照本 codebase 實際實作驗證。該文件的性質是「若 Android 版照抄 iOS 架構就會中雷」的假設清單；
實際驗證結果為 **8 項中 7 項不成立**（Android 版從實作時就已採用正確做法），#1 部分成立但已有防護網。

## 總結對照表

| # | 主題 | 文件嚴重度 | 驗證結果 | 成立？ |
|---|------|-----------|---------|--------|
| 1 | 文件庫以絕對路徑持久化 | P1 | 確實存 absolutePath，但 load() 有過濾防護 | **部分成立** |
| 2 | 多頁 PDF 組裝一次解碼全部頁面 | P0 | 已是逐頁解碼 + 每頁 recycle | 不成立 |
| 3 | OCR 前的大圖取樣 | P1 | 已是兩段式 decode（bounds → inSampleSize） | 不成立 |
| 4 | searchable PDF 原地替換非原子 | P1 | 已用 tmp + AtomicFileReplacer | 不成立 |
| 5 | 錯誤路徑不清 eSCL job | P1 | 三個 catch 路徑均有 NonCancellable 清理 | 不成立 |
| 6 | 取消後立即重掃的競態 | P1 | isBusy guard + launch 前claim slot | 不成立 |
| 7 | pullPage 不把 Completed 當終態 | P1 | 已當終態，且有實機驗證 | 不成立 |
| 8 | PDF 組裝與寫檔跑在主執行緒 | P1 | 重活全在 Dispatchers.IO | 不成立 |

---

## 逐項驗證細節

### #1 [P1] 絕對路徑持久化 — 部分成立（唯一需要記錄的項目）

**現況**：`DocumentStore.save()`（`domain/DocumentStore.kt` L41-42）確實把 `DocumentPage.imagePath`
（`File.absolutePath`）整串寫進 `document_store.json`。

**為何降級為部分成立**：

1. `load()`（L146-152）在載入時**過濾掉底層檔案已不存在的文件**——是優雅降級（該文件靜默移除），
   不是 iOS 那種「清單全滅、全部不可見」的行為。存量資料不會因路徑失效而損壞 store。
2. Android `filesDir` 在 app 更新後路徑不變（不像 iOS container），文件承認的主案在 Android 不成立。
3. 文件建議的 Room entity 修法不適用——本專案**沒有 Room**（AGENTS.md 明載無 Hilt/Room/WorkManager，
   持久化是 `org.json` 檔案）。

**剩餘風險情境**（文件列出）：work profile（`/data/user/10/...`）、應用分身/雙開、未來改 SAF/MediaStore。
目前專案未支援這些情境，實際影響低。

**處置**：降為 backlog（P3）。等確定要支援 work profile 或改用 content:// URI 時再做相對路徑遷移；
屆時一次性讀出所有 entry、`substringAfterLast('/')` 回寫檔名欄位即可，現有 load() 過濾是安全網。

### #2 [P0] PDF 組裝一次解碼全部頁面 — 不成立

**文件假設**：每頁 bitmap 同時駐留記憶體，50 頁 × 600dpi A4 達數 GB。

**實際實作**：`PdfPageRenderer.writePdf` 是逐頁 callback 模型。
`ScanExportService.drawPage` 在每頁內解碼一張 → 畫完 → `finally { bitmap.recycle() }`：

```kotlin
// ui/ScanExportService.kt L405-409
val bitmap = loadPageBitmap(page) ?: createMockPageBitmap(page)  // 每頁解一張
try {
    PdfPageRenderer.drawFittedInArea(canvas, bitmap, margin, top, bottom)
} finally {
    bitmap.recycle()  // finishPage 後立即回收
}
```

- `loadPageBitmap` 限定 `requestedWidth = 2048`（bounded decode），600dpi A4 原圖不會全尺寸進記憶體
- IPP 列印路徑 `RealIntegrationProvider.drawPrintPage`（L650-658）同樣逐頁解碼、drawFitted 後 recycle
- 掃描與組裝之間以檔案（`filesDir/scans/*.jpg`）傳遞，不是 `List<Bitmap>`——正是文件建議的形狀

**結論**：文件建議的修法就是現有代碼的結構。

### #3 [P1] OCR 前的大圖取樣 — 不成立

**文件假設**：先 `decodeFile` 全尺寸再 `createScaledBitmap`，或用 `InputImage.fromFilePath`。

**實際實作**：`domain/Ocr.kt` L114-138 `OcrBitmapLoader` 正是文件建議的兩段式 decode：

1. `inJustDecodeBounds = true` 先拿邊界（不解碼像素）
2. `OcrImageSizing.sampleSize()` 依 `MAX_OCR_PIXELS = 12M` / `MAX_OCR_LONG_EDGE = 4096` 算 `inSampleSize`
3. 才以 `inSampleSize` 真正解碼（ARGB_8888 上限約 48 MiB）
4. OOM fallback 到 `ImageTooLarge` skip（L135-137）

codebase 中沒有任何「先全解碼再縮圖」或 `InputImage.fromFilePath` 的路徑。
OCR 全程跑在 `withContext(Dispatchers.IO)`，ML Kit Task 以自製 `awaitMlKitTask` suspend bridge 收，
取消時保留 bitmap/recognizer 直到 native 推論完成（L220-230）。

### #4 [P1] searchable PDF 原地替換非原子 — 不成立

**實際實作**：`AtomicFileReplacer.replace()`（`domain/BackgroundEnhancer.kt` 檔尾）用
`Files.move(ATOMIC_MOVE + REPLACE_EXISTING)`，`AtomicMoveNotSupportedException` 時包成明確 IOException。

所有會改寫掃描原稿的路徑都走「同目錄 tmp → 原子替換」：
- `DocumentStore.writeTextAtomically` / `writeOcrSidecar`
- `BackgroundEnhancer.enhanceImageFile`（EnhancementFileReplacer = AtomicFileReplacer）
- `ScanImagePipeline` 的 source-safe replace

不存在先 delete 再 move、或直接 FileOutputStream 覆寫原稿的路徑。

### #5 [P1] 錯誤路徑不清 eSCL job — 不成立

**實際實作**：`RealIntegrationProvider.scan()` 的三個 catch 路徑（EsclHttpException /
EsclNextDocumentTimeoutException / 一般 Exception，L448/458/464/470）全部是文件建議的 exact pattern：

```kotlin
withContext(NonCancellable + Dispatchers.IO) { runCatching { httpClient.cancelScanJob(baseUrl, location) } }
```

`NonCancellable`（文件警告的「finally 裡 suspend 呼叫在協程已取消時會立刻丟 CancellationException」陷阱）
與 `runCatching`（已完成 job 回 404/409 冪等吞掉）都已正確處理。`finishScanJob` 在 happy path 也會清 Active job。

### #6 [P1] 取消後立即重掃的競態 — 不成立

**實際實作**：`MopriaViewModel.scan()`（L380-385）：

```kotlin
fun scan() {
    if (_uiState.value.isBusy) return
    // Claim the scan slot before launching so a second tap cannot start another coroutine
    // while optional ML Kit modules are being requested.
    _uiState.update { it.copy(isDiscovering = true) }
    ...
}
```

- `isBusy`（isDiscovering || activeJobId != null）guard 在 launch **前** claim slot，第二次 tap 進不來
- App 沒有 user-facing 的 cancelScan 入口，「取消後立即重掃」路徑不存在
- `CancellationException` 在多處（L162/334/589/842/897）分開 catch 且 rethrow，不會被 `catch (Exception)` 吃掉
- `continueFlatbedScan` 同樣先檢查 `state.isBusy`

### #7 [P1] pullPage 不把 Completed 當終態 — 不成立

**實際實作**（`RealIntegrationProvider` 頁面迴圈 L246-287）：

- NextDocument timeout 時查 `jobTransferState`：`Completed -> break@pageLoop`（L249）
- HTTP 410 Gone + job Completed → break（L265）
- NextDocument 404 時只在 ADF 仍 `ScannerAdfLoaded/Processing` 才 retry，上限
  `NEXT_DOCUMENT_STATUS_RETRIES = 3` 次；ADF 空則立即 break——「Completed 之後不可能有新頁」的原則已實作

**實機驗證**：2026-08-13 於 Brother MFC-L2715DW（見 `docs/adf-resolution-investigation.md`）實測
ADF 單頁掃描：最後一頁後 NextDocument 回 404 + ADF `ScannerAdfEmpty` → 立即結束，無空轉。

### #8 [P1] PDF 組裝與寫檔跑在主執行緒 — 不成立

**實際實作**：

- `MopriaViewModel.scan()` → `viewModelScope.launch(Dispatchers.IO)`（L120）
- `ScanExportService.save()` 本身是 `suspend + withContext(Dispatchers.IO)`（L56-57）
- `saveScan` 的 Main dispatcher launch 只做狀態更新與事件發射，PDF 組裝在 IO 內完成
- `RealIntegrationProvider.scan()` 同樣 `withContext(Dispatchers.IO)`

符合文件建議的「viewModelScope 只做狀態更新，重活 withContext 指定 dispatcher」規範。

---

## 處置建議

1. **#1 相對路徑化**：降為 backlog（P3），等支援 work profile / SAF 時再做；現有 load() 過濾已是安全網。
2. 其餘 7 項無需任何代碼變更——Android 版實作時即已符合建議。
3. 本文件與原始建議清單一併歸檔，作為「Android 版已通過的檢核清單」供日後跨版對照。
