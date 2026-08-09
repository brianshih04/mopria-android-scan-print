# OpenCV 影像處理管線整合計畫

更新日期：2026-08-09
狀態：規劃中
關聯 commit：`8fe10e0`（純 Kotlin BackgroundEnhancer）、`aad08ab`（初版計畫）

## 1. 目標

將目前的背景淨化功能從**純 Kotlin Box Blur 近似**升級為完整的 **OpenCV 影像處理管線**，涵蓋：

1. **彩色歸一化除法**（漂白去陰影）— `morphologyEx(MORPH_CLOSE)` + `Core.divide`
2. **透視變形校正**（Deskew / Warp）— `warpPerspective`（使用者指定四角或自動偵測）
3. **自動裁切偵測**（未來）— `Canny` + `findContours` + `approxPolyDP`

管線設計原則：A4 300 DPI（2480×3508）在 0.5 秒內本地處理完畢、零 OOM。

### 核心管線架構

```
[原始 Bitmap]
    │
    ├─ (若有四角) 透視校正 warpPerspective
    │
    ├─ 分離 RGB / Alpha 通道
    │
    ├─ ⚡ 降採樣 1/4 → 形態學 CLOSE（背景估計）→ 升採樣回原尺寸
    │
    ├─ Core.divide（彩色漂白）→ convertTo（對比度微調）
    │
    ├─ 合併 Alpha 通道
    │
    └─ [乾淨彩色 Bitmap] → 寫回 JPEG/PNG 或匯出 PDF
```

### 不變項目

| 項目 | 現狀 | OpenCV 後 |
|---|---|---|
| `EnhancementStrength` enum | Light / Normal / Strong | 保留，映射到 OpenCV 參數 |
| `ScanSettings.enhanceBackground` | `EnhancementStrength?` | 不變 |
| ScanScreen UI | FilterChips 強度選擇器 | 不變 |
| 設定持久化 | `SettingsStore` | 不變 |

### 變更項目

| 項目 | 現狀（純 Kotlin） | OpenCV 後 |
|---|---|---|
| 背景估計 | Box Blur（近似 CLOSE） | `Imgproc.morphologyEx(MORPH_CLOSE)` |
| 除法正規化 | 逐像素 Float 除法 | `Core.divide(src, bg, dst, 255.0)` |
| 對比度微調 | `applyContrast(alpha, beta)` | `Mat.convertTo(dst, -1, alpha, beta)` |
| 透視校正 | 無（eSCL 不需要） | `warpPerspective`（手機拍照場景） |
| Alpha 通道 | 不處理 | 拆分保留再合併 |
| APK 大小 | ~61 MB（debug） | 預估 +15-25 MB（arm64 + armeabi-v7a） |
| A4 300dpi 處理時間 | 約 1-2 秒 | 預估 < 0.5 秒 |

## 2. 依賴與建置設定

### 2.1 Gradle 依賴

```kotlin
// app/build.gradle.kts → dependencies
implementation("com.quickbirdstudios:opencv:4.5.3.0")
```

### 2.2 ABI 瘦身

```kotlin
// app/build.gradle.kts → defaultConfig
ndk {
    // 現代 Android 手機 99% 為 ARM，排除 x86 可減少 ~70% native 庫體積
    abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
}
```

Emulator 測試改用 `arm64-v8a` system image。

### 2.3 ProGuard / R8 規則

```proguard
# app/proguard-rules.pro
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
```

### 2.4 OpenCV 初始化

```kotlin
// MainActivity.onCreate()
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (OpenCVLoader.initDebug()) {
        Log.d("MopriaScan", "OpenCV 載入成功")
    } else {
        Log.e("MopriaScan", "OpenCV 載入失敗 — 背景淨化將使用純 Kotlin fallback")
    }
    enableEdgeToEdge()
    setContent { ... }
}
```

## 3. 實作步驟

### 第一階段：建置整合（第 1-2 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 1.1 | 加入 `quickbirdstudios:opencv` 依賴 + ABI filters | `./gradlew :app:assembleDebug` 成功 |
| 1.2 | MainActivity 加入 `OpenCVLoader.initDebug()` | Log 確認載入成功 |
| 1.3 | 加入 ProGuard keep 規則 | `./gradlew :app:assembleRelease` 成功 |
| 1.4 | 測量 APK 大小變化 | 記錄 before/after |

### 第二階段：核心管線實作（第 3-5 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 2.1 | 建立 `DocumentScannerPipeline.kt`（`processScanPipeline` suspend fun） | 編譯通過 |
| 2.2 | 形態學 CLOSE + 金字塔下採樣 + `Core.divide` + `convertTo` | 輸出 bitmap 正確 |
| 2.3 | 透視校正 `applyPerspectiveTransform`（`warpPerspective`） | 四角拉正輸出正確 |
| 2.4 | 整合 `EnhancementStrength`：3 種強度映射 kernel / alpha / beta | 強度差異可見 |
| 2.5 | 嚴格 `Mat.release()` + `kernel.release()` | Android Profiler 無 native leak |
| 2.6 | 保留純 Kotlin `BackgroundEnhancer` 作為 fallback | 兩條路徑都可運作 |

### 第三階段：UI 整合與非同步（第 6 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 3.1 | `RealIntegrationProvider` 呼叫 OpenCV 管線（有 fallback） | 掃描後背景變白 |
| 3.2 | `Dispatchers.Default` 非同步處理 + UI Loading 進度 | 不凍結 UI |
| 3.3 | `ImageDecoder` 載入大圖（內存優化） | A4 300dpi 不 OOM |

### 第四階段：極限測試與驗證（第 7 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 4.1 | 壓力測試：連續處理 5-10 張 A4 300dpi | Profiler heap 回基準線 |
| 4.2 | 極端樣本：藍色簽名、紅色印章、全黑標題、手機投影陰影 | 彩色保留、無光暈 |
| 4.3 | Lint + assembleDebug + assembleRelease | 全部通過 |

## 4. 核心架構設計

### 4.1 DocumentScannerPipeline — 完整 OpenCV 管線

```kotlin
object DocumentScannerPipeline {

    /**
     * 核心管線：輸入原始 Bitmap，輸出漂白、（可選）擺正後的彩色 Bitmap。
     *
     * @param inputBitmap 原始掃描圖
     * @param strength 背景淨化強度
     * @param corners 使用者指定的四角座標（null = 不做透視校正）
     */
    suspend fun processScanPipeline(
        inputBitmap: Bitmap,
        strength: EnhancementStrength,
        corners: List<Point>? = null,
    ): Bitmap = withContext(Dispatchers.Default) {
        val config = strengthParams(strength)
        val srcMat = Mat().also { Utils.bitmapToMat(inputBitmap, it) }

        try {
            // 1. 透視校正（若有四角）
            val workingMat = if (corners?.size == 4) {
                applyPerspectiveTransform(srcMat, corners)
            } else {
                srcMat.clone()
            }

            // 2. 分離 RGB / Alpha
            val channels = ArrayList<Mat>().also { Core.split(workingMat, it) }
            val alphaMat = channels.removeAt(channels.size - 1)
            val colorMat = Mat().also { Core.merge(channels, it) }

            // 3. ⚡ 金字塔降採樣（防 OOM）
            val factor = config.downsampleFactor.toDouble()
            val smallMat = Mat().also {
                Imgproc.resize(colorMat, it,
                    Size(colorMat.cols() / factor, colorMat.rows() / factor),
                    0.0, 0.0, Imgproc.INTER_LINEAR)
            }

            // 4. 形態學 CLOSE（抹除文字，保留紙張底色與陰影）
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(config.kernelSize, config.kernelSize))
            val smallBg = Mat().also {
                Imgproc.morphologyEx(smallMat, it, Imgproc.MORPH_CLOSE, kernel)
            }

            // 5. 升採樣背景回原尺寸
            val fullBg = Mat().also {
                Imgproc.resize(smallBg, it,
                    Size(colorMat.cols().toDouble(), colorMat.rows().toDouble()),
                    0.0, 0.0, Imgproc.INTER_LINEAR)
            }

            // 6. 彩色矩陣除法漂白
            val normalized = Mat().also {
                Core.divide(colorMat, fullBg, it, 255.0)
            }

            // 7. 對比度微調
            normalized.convertTo(normalized, -1, config.alpha, config.beta)

            // 8. 合併回 Alpha 通道
            val resultChannels = ArrayList<Mat>().also { Core.split(normalized, it) }
            resultChannels.add(alphaMat)
            val dstMat = Mat().also { Core.merge(resultChannels, it) }

            // 9. 轉回 Bitmap
            val result = Bitmap.createBitmap(
                dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, result)

            // 💥 嚴格釋放所有 Native 記憶體
            workingMat.release(); colorMat.release(); smallMat.release()
            smallBg.release(); fullBg.release(); normalized.release()
            dstMat.release(); alphaMat.release(); kernel.release()
            channels.forEach { it.release() }
            resultChannels.forEach { it.release() }

            return@withContext result
        } finally {
            srcMat.release()
        }
    }

    /**
     * 透視變換：將使用者指定的四角拉正為正面矩形。
     */
    private fun applyPerspectiveTransform(src: Mat, points: List<Point>): Mat {
        val topWidth = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y)
        val bottomWidth = Math.hypot(points[2].x - points[3].x, points[2].y - points[3].y)
        val maxWidth = maxOf(topWidth, bottomWidth)
        val leftHeight = Math.hypot(points[3].x - points[0].x, points[3].y - points[0].y)
        val rightHeight = Math.hypot(points[2].x - points[1].x, points[2].y - points[1].y)
        val maxHeight = maxOf(leftHeight, rightHeight)

        val srcPts = Converters.vector_Point2f_to_Mat(points)
        val dstPts = Converters.vector_Point2f_to_Mat(listOf(
            Point(0.0, 0.0),
            Point(maxWidth - 1, 0.0),
            Point(maxWidth - 1, maxHeight - 1),
            Point(0.0, maxHeight - 1),
        ))

        val matrix = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, matrix, Size(maxWidth, maxHeight))

        srcPts.release(); dstPts.release(); matrix.release()
        return dst
    }
}
```

### 4.2 EnhancementStrength → OpenCV 參數映射

```kotlin
data class StrengthConfig(
    val kernelSize: Double,
    val alpha: Double,
    val beta: Double,
    val downsampleFactor: Int,
)

private fun strengthParams(strength: EnhancementStrength): StrengthConfig = when (strength) {
    EnhancementStrength.Light -> StrengthConfig(
        kernelSize = 11.0,     // 較小核心，保留更多細節
        alpha = 1.0,
        beta = 0.0,
        downsampleFactor = 4,
    )
    EnhancementStrength.Normal -> StrengthConfig(
        kernelSize = 15.0,     // 標準核心
        alpha = 1.05,
        beta = -10.0,
        downsampleFactor = 4,
    )
    EnhancementStrength.Strong -> StrengthConfig(
        kernelSize = 21.0,     // 較大核心，更積極抹除
        alpha = 1.15,
        beta = -20.0,
        downsampleFactor = 6,  // 更激進的下採樣
    )
}
```

### 4.3 雙軌策略（OpenCV + Fallback）

```
RealIntegrationProvider.scan()
    │
    ├── settings.enhanceBackground != null?
    │       │
    │       ▼
    │   BackgroundEnhancer.enhanceImageFile(file, strength)
    │       │
    │       ├── OpenCV 可用?（OpenCVLoader.initDebug() == true）
    │       │       │
    │       │       ├── YES → DocumentScannerPipeline.processScanPipeline(bitmap, strength)
    │       │       │         • morphologyEx(MORPH_CLOSE) on downsampled
    │       │       │         • Core.divide at full resolution
    │       │       │         • convertTo(alpha, beta) per strength
    │       │       │         • strict Mat.release()
    │       │       │
    │       │       └── NO  → 純 Kotlin BackgroundEnhancer.apply(bitmap, strength)
    │       │                 • boxBlur on downsampled (現有實作)
    │       │                 • per-pixel division + applyContrast
    │       │
    │       ▼
    │   寫回 JPEG/PNG 檔案
    │
    ▼
  建立 DocumentPage
```

### 4.4 非同步 UI 整合

```kotlin
// RealIntegrationProvider 或 ViewModel 中
viewModelScope.launch {
    try {
        _uiState.update { it.copy(isEnhancing = true) }
        val enhanced = DocumentScannerPipeline.processScanPipeline(bitmap, strength)
        // 寫回檔案、更新 UI
    } catch (e: Exception) {
        // fallback to pure Kotlin or report error
    } finally {
        _uiState.update { it.copy(isEnhancing = false) }
    }
}
```

## 5. 記憶體安全規範

### 5.1 Mat 釋放規則

- **每一個 `Mat()` 或 `getStructuringElement()` 回傳值都必須 `.release()`**
- 使用 `try-finally` 確保 `srcMat` 在例外時也釋放
- `Core.split()` 回傳的每個 channel 都要個別 release
- `Core.merge()` 回傳的 Mat 也要 release
- `getPerspectiveTransform` 回傳的 matrix 要 release

### 5.2 記憶體預估（A4 300dpi 彩色）

| 階段 | Mat 大小 | 備註 |
|---|---|---|
| srcMat (RGBA) | 2480 × 3508 × 4 ≈ 33 MB | 原圖 |
| colorMat (RGB) | 2480 × 3508 × 3 ≈ 25 MB | 拆分後 |
| smallMat (1/4) | 620 × 877 × 3 ≈ 1.6 MB | 下採樣後 |
| smallBg (1/4) | 620 × 877 × 3 ≈ 1.6 MB | 形態學結果 |
| fullBg (原尺寸) | 2480 × 3508 × 3 ≈ 25 MB | 上採樣後 |
| normalized | 2480 × 3508 × 3 ≈ 25 MB | 除法結果 |
| **峰值合計** | **~112 MB** | 即時釋放後回落至基準線 |

### 5.3 壓力測試驗收

- 連續處理 5-10 張 A4 300dpi 圖片
- Android Studio Profiler Memory：每次處理完 Java Heap + Native Heap 应回基準線
- 無 `OutOfMemoryError`

## 6. 風險與緩解

| 風險 | 機率 | 影響 | 緩解 |
|---|---|---|---|
| `quickbirdstudios:opencv` 停止維護 | 中 | 編譯失敗 | 保留純 Kotlin fallback；可改官方 SDK |
| OpenCV native 庫載入失敗 | 低 | 功能不可用 | `initDebug()` 檢查 + fallback |
| APK 增大 15-25 MB | 確定 | 下載量下降 | ABI splits（arm64 + armeabi-v7a） |
| R8 移除 OpenCV 反射類別 | 低 | Release crash | ProGuard keep 規則 |
| x86 emulator 無法測試 | 中 | 開發不便 | 使用 arm64 emulator image |
| 透視校正品質不佳 | 中 | 畫面變形 | 允許使用者手動微調四角 |

## 7. 未來擴展

| 功能 | 演算法 | 優先級 |
|---|---|---|
| 自動偵測四角 | `Canny` + `findContours` + `approxPolyDP` | 中 |
| PDF 匯出 | Android `PdfDocument` + 處理後 Bitmap 頁面 | 高 |
| 雙邊濾波降噪 | `Imgproc.bilateralFilter` | 低（eSCL 品質通常夠） |

## 8. 回滾計畫

如果 OpenCV 整合後出現無法解決的問題：

1. `BackgroundEnhancer.enhanceImageFile()` 改回直接呼叫純 Kotlin pipeline
2. 移除 OpenCV 依賴和 ABI filters
3. 純 Kotlin 版本持續作為生產版本

回滾成本：低（API 不變，只切換內部實作）。

## 9. 完成條件

- [ ] OpenCV 依賴加入且 `assembleDebug` + `assembleRelease` 通過
- [ ] `DocumentScannerPipeline.processScanPipeline()` 產生正確的漂白結果
- [ ] `EnhancementStrength` 3 種強度在 OpenCV 版本中均有可見差異
- [ ] 透視校正 `warpPerspective` 四角拉正輸出正確
- [ ] A4 300dpi 處理時間 < 1 秒
- [ ] 連續處理 5-10 張 A4 後 Profiler heap 回基準線
- [ ] 藍色簽名、紅色印章、黑色標題保留且無白色光暈
- [ ] OpenCV 初始化失敗時 fallback 到純 Kotlin 版本
- [ ] Lint clean，現有 126+ JVM tests 通過
- [ ] CHANGELOG、HANDOFF、README 更新
