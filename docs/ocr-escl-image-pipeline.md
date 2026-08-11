# OCR 與 eSCL 影像管線

更新日期：2026-08-11

狀態：已合併至 `main`；本機 unit／API 36 instrumentation／release gate 通過，ARM 模型／accuracy、16 KB 與真實 scanner 仍待外部驗證。

## 目前已落地的流程

eSCL `NextDocument` 的 HTTP response 由 `EsclHttpClient` 以 bounded `InputStream.copyTo` 直接寫入 `filesDir/scans` 的暫存檔，再以同目錄檔案 rename 成頁面檔。掃描影像不會先進入 `ByteArray`；OpenCV 不建立 Android Bitmap，ML Kit OCR 只建立受 12 MP／4096 px 長邊限制的取樣 Bitmap。

檔案落地後，Real provider 可依 `ScanSettings` 執行：

- ADF blank-page drop：灰階、反向閾值與 `countNonZero` 的低墨水比例判定。
- deskew：灰階、反向 Otsu、水平膨脹、`findContours`／`minAreaRect` 中位角度，再以白色 border 的 `warpAffine` 旋轉。
- auto-crop：Canny 邊緣、膨脹與最大紙張輪廓的 bounding rectangle；找不到可信紙張輪廓時保留原圖。
- background cleanup：既有 OpenCV pipeline。
- OCR：在影像處理完成後呼叫 `OcrEngine`；production 先讀檔案 bounds、計算 power-of-two sample，再使用 Google ML Kit Text Recognition v2 的 bounded Bitmap API。辨識文字才會回到掃描流程，原始影像不會轉成 Kotlin `ByteArray`。OCR eSCL 工作只協商 JPEG，PDF-only profile 會在建立工作前回報。

所有會改檔的步驟都寫到 sibling temporary file，通過 decode／format／bounds 驗證後才做 atomic replace；失敗或取消不改動 source。OpenCV `Mat` 由 `MatScope` 明確持有並在成功、例外與 cancellation 路徑 release。

## OCR 的安全硬體設定

啟用 ML Kit OCR option 時，capability reconciliation 優先選擇不超過 300 dpi 的 scanner resolution，並在 scanner 支援時要求 `Grayscale8`，以控制掃描與 OCR 的記憶體峰值。若 scanner 沒有 300 dpi 以下的 capability，OCR option 會被停用並通知使用者；不會偷偷發送不被 scanner 支援的 eSCL 設定。

`android:largeHeap="true"` 已開啟，但它只是提高 Android 可分配 heap 的機會，不是 256 MB PSS 保證，也不能取代 native allocation gate。A4 300 dpi 十頁 soak 的 hard peak gate 是 256 MB，並另設 bounded retained-PSS gate。

## ML Kit Text Recognition v2

App 使用 unbundled ML Kit clients：Latin、Chinese、Japanese 與 Korean script model 由 Google Play services 管理。`AndroidManifest.xml` 只宣告安裝時預取的 `ocr`、`ocr_chinese`；Japanese／Korean 由使用者選取後，設定頁的「準備」按鈕透過 `ModuleInstallClient` 發出模型請求，並下載 Searchable PDF 所需的官方 Noto JP／KR 字型。

這個方案不需要 App 維護 `.nb`、模型 URL、轉換工具、模型 SHA-256 或 app-private model directory。ML Kit 模型尚未安裝、Google Play services 不可用或下載尚未完成時，OCR 會回傳明確的 `Skipped`，掃描頁仍保留。只有 JP／KR PDF 字型由 App 以固定官方 URL、20 MB 上限與 SHA-256 驗證管理，且不會進入主 APK。

## OCR 結果後處理與文件版面

OCR 啟用時，Real provider 會自動把 `deskew` 與 `autoCrop` 納入檔案優先的影像處理設定；若 OpenCV 不可用或找不到可信輪廓，既有 source-safe pipeline 會保留原圖，不會因為 OCR 而破壞掃描頁。

`OcrResult.Applied` 不再只有扁平文字，還保留 ML Kit 的 block → line → element → symbol 階層、bounding box、corner points、rotation angle、recognized language 與 confidence。這些資料讓後續 UI overlay、低信心重試、表格切格與 searchable PDF 可以在不重新辨識的情況下使用。

目前的 formatter 採保守策略：寬版且具有足夠座標行的文件按 y 座標分列、按 x 座標排序並以 tab 分隔欄位；一般單欄文件維持由上到下、由左到右；偵測到明顯雙欄 gutter 時保留左欄再右欄的閱讀順序。數字只在看起來像數字時修正全形標點、混用千分位分隔符與 OCR 插入的空白，像 `4.42%` 的小數不會被改成千分位。

這些是版面與字串品質改善，不等於語意化表格辨識或會計數值驗證；目前仍不會自動推斷欄名、儲存格或公式。

## OCR 語言與 script model

設定頁會把 catalog 按區域分組，讓使用者選取要保留的語言與下一次掃描的 active 語言。目前 catalog 涵蓋 English、繁中、簡中、日文、韓文、西班牙文、葡萄牙文、德文、法文與俄文。

全新安裝的預設選取是 English、繁體中文與簡體中文。日文、韓文及其他地區語言需要使用者自行選取與準備；繁中與簡中共用 ML Kit Chinese script model，English、Spanish、Portuguese、German 與 French 共用 Latin script model。俄文目前在設定頁顯示為 ML Kit Text Recognition v2 不支援，不會被選為可用模型。

ML Kit 模型由 Google Play services 下載與更新，不由 App 直接保存或替換。沒有 Google Play services 的裝置目前不會宣稱 OCR 可用；若未來需要這類裝置，必須另行評估 bundled ML Kit 或其他 OCR backend。

## 尚未完成的驗收 gate

1. 真實 ARM 裝置上的 ML Kit Chinese／Japanese／Korean／Latin accuracy、cold/warm latency、PSS 與取消壓力測試。
2. Google Play services 缺失、模型下載中、JP／KR 字型下載或 checksum 失敗與低磁碟空間的完整 UI／instrumentation 覆蓋。
3. 以真實 eSCL scanner payload 驗證 deskew／auto-crop 與不同紙張品質；目前 `Brian.jpg`、`b1.jpg`、`b2.jpg` 只在 API 36 emulator 做過 ML Kit sample smoke test，不是產品準確率證據。
4. 語意化表格 cell extraction、欄位驗證，以及日文／韓文等多語字型 coverage 與真實 scanner payload 的 Searchable PDF 驗收；目前 Searchable PDF 已是獨立 opt-in，OCR layout 除了 page-scoped `DocumentPage.ocrResult`，也會以 gzip sidecar 原子保存。process death 或 sidecar 損毀後缺少 layout 時會要求重新 OCR，不會靜默輸出普通 PDF。

2026-08-11 的本機 `main` 快照為 163 JVM tests、28 個 API 36 instrumentation tests，包含 50 頁 Searchable PDF 與 256 MB absolute PSS gate；GitHub Actions 同一版本因 Linux runner 無法執行 `./gradlew`（exit 127）而在 Gradle 前失敗，CI 尚未完成驗收。

官方參考：

- [ML Kit Text Recognition v2 Android guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit Text Recognition v2 supported languages](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- [ML Kit model installation paths](https://developers.google.com/ml-kit/tips/installation-paths)
- [Google Play services ModuleInstall API](https://developers.google.com/android/guides/module-install-apis)
