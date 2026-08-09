# Searchable PDF

本功能使用 PDFBox Android 將掃描影像與 ML Kit Text Recognition v2 的 OCR 結果合成 searchable PDF：

1. PDF page 先放入原始 JPEG。
2. OCR line 的 Unicode 文字依 ML Kit bounding box 映射到同一頁。
3. 文字使用嵌入字型並設定 PDFBox `RenderingMode.NEITHER`，因此畫面仍看到原始掃描影像，但 PDF viewer 的搜尋／文字抽取可以讀到 OCR 文字。

writer 位於 `PdfBoxSearchablePdfWriter.kt`，已接入正式 `ScanExportService`。Searchable PDF 是獨立的 opt-in：OCR 與 Searchable PDF 都預設關閉；只有使用者同時啟用 OCR 與 Searchable PDF，且至少一頁具有非空文字與有效 line bounds 時才加入文字層。只有空字串、空 layout 或無效 bounds 時會使用普通 PDF 輸出。

正式路徑會以 bounded bitmap 產生暫存 JPEG，再交給 PDFBox；PDF-backed page 會先以 `PdfRenderer` rasterize。`DocumentPageBitmapLoader` 與 OCR layout 共同套用 crop-then-rotate，並把座標縮放至嵌入 PDF 的實際 bitmap 尺寸。

writer 的 `debugOverlay` 參數會用與文字層相同的座標映射畫出紅色 OCR line bounds；三張樣本的 overlay render 已確認文字框與原始影像文字位置一致。正常 searchable PDF 使用預設的 `debugOverlay = false`，不會把紅框寫入輸出。APK 只內建 Noto Sans TC；日文／韓文地域字型由使用者要求對應語言資源後下載，匯出時只載入文件實際需要的字型。永久 instrumentation test 會確認 Latin 與中文可由 PDFBox 抽取；日／韓下載與抽取仍是實機 gate。

Noto CJK 的部分 glyph 同時對應一般漢字與 Kangxi radical／CJK compatibility code point；直接採用 PDFBox 自動產生的 reverse cmap 曾讓 `中文` 被抽取成 `中⽂`。writer 現在先完成 font subset，再依實際寫入的字元建立明確 ToUnicode CMap，並對文字使用 NFKC 正規化。instrumentation regression test 要求完整抽取 `OCR TEST` 與 `中文`，不再只驗證非空文字。

三張外部樣本（`Brian.jpg`、`b1.jpg`、`b2.jpg`）以一次性 Android instrumentation harness 執行；樣本本身不加入 repository。驗證至少包含：

- `pdftotext`／`pypdf` 可抽取每一頁的非空 OCR 文字；
- `pdfinfo` 顯示 3 頁，`pdffonts` 顯示嵌入的 Unicode 字型；
- `pdftoppm` render 三頁後人工確認影像仍完整、文字層不改變可見畫面。

OCR layout 目前只存在 process memory；`DocumentStore` 恢復的舊文件若沒有 layout 會安全地回到普通 PDF。需要 JP／KR 但對應字型尚未下載或驗證失敗時也會回到普通 PDF。仍需以含 Google Play services 的 ARM 實機與高 DPI ADF 批次測量 peak RSS／輸出時間，並補充真實日文、韓文與混合 script 文件的下載、抽取及完整字型覆蓋率驗證。
