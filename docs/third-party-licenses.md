# Third-party licenses

本文件記錄目前 OpenCV 與 OCR 整合使用的第三方元件；版本與驗證狀態以 `app/build.gradle.kts`、OpenCV 整合計畫及 ML Kit 官方文件為準。

更新日期：2026-08-11

## OpenCV Android AAR 4.14.0

- Maven coordinate：`org.opencv:opencv:4.14.0`
- License：Apache License 2.0
- License source：[OpenCV LICENSE](https://github.com/opencv/opencv/blob/4.x/LICENSE)
- Project source：[OpenCV](https://opencv.org/)

一般 release artifact 只包含 `arm64-v8a`、`armeabi-v7a`；debug 保留 emulator 使用的 `x86`、`x86_64`。Release 已通過 `zipalign -P 16`，16 KB page-size runtime 與 ARM 實機仍待驗證。

## PDFBox Android 2.0.27.0

- Maven coordinate：`com.tom-roush:pdfbox-android:2.0.27.0`
- License：Apache License 2.0
- Project source：[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
- Upstream basis：[Apache PDFBox 2.0.27](https://pdfbox.apache.org/)

使用者明確選取 Searchable PDF 且每頁都有 OCR 結果時，將 bounded page image 與 ML Kit OCR 的 Unicode／座標結果寫成 PDFBox 的不可見文字層；未選取時使用既有 Android PDF renderer，已選取但缺 OCR layout／必要字型時則明確失敗，不會靜默降級。

## Noto Sans CJK variable fonts

- Bundled asset：`app/src/main/assets/ocr/fonts/NotoSansTC-VF.ttf`
- License：SIL Open Font License 1.1
- Packaged license：`app/src/main/assets/ocr/fonts/OFL-1.1.txt`
- Source：[`notofonts/noto-cjk`](https://github.com/notofonts/noto-cjk)
- Bundled Traditional Chinese font：`Sans/Variable/TTF/Subset/NotoSansTC-VF.ttf`（SHA-256 `C6481C5D93420AEAC11087367DAE56AE7492EB1989CE42F4CA5D5AA5E2DB3116`）
- On-demand Japanese font：`Sans/Variable/TTF/Subset/NotoSansJP-VF.ttf`（SHA-256 `F4B373B226668EE33A6E54B02823DCD2D1209F17159F777421AE8C2275160369`）
- On-demand Korean font：`Sans/Variable/TTF/Subset/NotoSansKR-VF.ttf`（SHA-256 `9E1D729E7E2B36F9EF439DA102F8C134C10AABE46F1C843BF0ACA5C043B86F76`）
- 用途：嵌入 searchable PDF 的 Unicode 文字層。TC 隨 APK 提供；JP／KR 只在使用者要求對應語言資源時從官方 repository 的固定 commit 以 HTTPS 串流下載、驗證大小與 SHA-256，並寫入 App 私有目錄。匯出時只載入裁切後實際需要的地域字型，並以明確 ToUnicode CMap 保存正規化的搜尋碼位；缺少必要字型時明確要求下載，不會靜默降級。

## Google ML Kit Text Recognition v2

App 使用 Google Play services 的 unbundled clients，模型由 Google Play services 下載與管理，不把模型檔或第三方 OCR runtime 打包進 repository：

- `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1`
- `com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1`
- `com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1`
- `com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1`
- API／模型安裝說明：[ML Kit Text Recognition v2 Android guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- 模型安裝路徑：[ML Kit model installation paths](https://developers.google.com/ml-kit/tips/installation-paths)
- 服務條款：[Google ML Kit terms](https://developers.google.com/ml-kit/terms)

因為使用 unbundled 模式，App 不維護 `.nb`、模型 archive、模型 hash 或自有 CDN；模型版本更新由 Google Play services 管理。實際辨識準確率、PSS、下載失敗與無 Google Play services 裝置仍須實機驗證。
