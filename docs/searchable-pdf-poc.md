# Searchable PDF

更新日期：2026-08-11

狀態：proof-of-concept 已接入正式 opt-in export path 並合併至 `main`；產品級 ARM OCR／多語字型與真實 scanner gate 尚未完成。

本功能使用 PDFBox Android 將掃描影像與 ML Kit Text Recognition v2 的 OCR 結果合成 searchable PDF：

1. PDF page 先放入原始 JPEG。
2. OCR line 的 Unicode 文字依 ML Kit bounding box 映射到同一頁。
3. 文字使用嵌入字型並設定 PDFBox `RenderingMode.NEITHER`，因此畫面仍看到原始掃描影像，但 PDF viewer 的搜尋／文字抽取可以讀到 OCR 文字。

writer 位於 `PdfBoxSearchablePdfWriter.kt`，已接入正式 `ScanExportService`。Searchable PDF 是獨立的 opt-in：OCR 與 Searchable PDF 都預設關閉。使用者選擇 Searchable PDF 後，每一頁都必須有 OCR 執行結果，日文／韓文頁面也必須先安裝並驗證所需字型；缺少先決條件時匯出會回報本地化錯誤，不會靜默產生普通 PDF。空白頁可以保留空 layout，但只要原始 layout 有有效位置文字，writer 就必須實際寫入至少一行文字層。

正式路徑會以 bounded bitmap 產生暫存 JPEG，再交給 PDFBox；PDFBox 使用 32 MiB main-memory threshold，超過後將 scratch data 放到 App cache。一般 PDF-backed page 仍可由 `PdfRenderer` rasterize，但 eSCL OCR 工作只協商 JPEG；scanner profile 只有 PDF 時會在建立工作前拒絕 OCR。`DocumentPageBitmapLoader` 與 OCR layout 共同套用 crop-then-rotate，crop 永遠以未旋轉 source-space 儲存；旋轉後的裁切 UI 會在 display/source 座標間做雙向映射。

writer 的 `debugOverlay` 參數會用與文字層相同的座標映射畫出紅色 OCR line bounds；三張樣本的 overlay render 已確認文字框與原始影像文字位置一致。正常 searchable PDF 使用預設的 `debugOverlay = false`，不會把紅框寫入輸出。APK 只內建 Noto Sans TC；日文／韓文地域字型由使用者要求對應語言資源後下載，來源固定至 Noto CJK commit 並驗證大小與 SHA-256，匯出時只載入裁切後仍需要的字型。永久 instrumentation test 會確認 Latin 與中文可由 PDFBox 抽取；日／韓下載與抽取仍是實機 gate。

Noto CJK 的部分 glyph 同時對應一般漢字與 Kangxi radical／CJK compatibility code point；直接採用 PDFBox 自動產生的 reverse cmap 曾讓 `中文` 被抽取成 `中⽂`。writer 現在先完成 font subset，再依實際寫入的字元建立明確 ToUnicode CMap，並對文字使用 NFKC 正規化。instrumentation regression test 要求完整抽取 `OCR TEST` 與 `中文`，不再只驗證非空文字。

三張外部樣本（`Brian.jpg`、`b1.jpg`、`b2.jpg`）以一次性 Android instrumentation harness 執行；樣本本身不加入 repository。驗證至少包含：

- `pdftotext`／`pypdf` 可抽取每一頁的非空 OCR 文字；
- `pdfinfo` 顯示 3 頁，`pdffonts` 顯示嵌入的 Unicode 字型；
- `pdftoppm` render 三頁後人工確認影像仍完整、文字層不改變可見畫面。

OCR layout 會以 versioned、gzip 壓縮的 per-page sidecar 原子寫入 App 私有目錄，`DocumentStore` 在 process death 後可恢復文字與完整 block／line／element／symbol 座標。舊文件或損毀 sidecar 沒有 layout 時，Searchable PDF 匯出會要求重新 OCR。API 36 instrumentation 另以 50 頁文件檢查 PDF page count 與 absolute peak PSS 256 MB gate。仍需以含 Google Play services 的 ARM 實機測量模型下載、OCR cold/warm latency、PSS 與輸出時間，並補充真實日文、韓文與混合 script 文件的下載、抽取及完整字型覆蓋率驗證。

2026-08-11 本機驗證為 163 JVM tests、28 個 API 36 instrumentation tests；release APK／AAB 只含 `arm64-v8a`、`armeabi-v7a` 且 zipalign 通過。當時 GitHub Actions 受 `./gradlew` exit 127 阻擋；wrapper／line-ending 修復後，`main@68c2112` 已於 2026-09-16 通過 CI。
