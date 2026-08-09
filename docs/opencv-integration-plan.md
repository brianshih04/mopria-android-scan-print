# OpenCV 整合開發計畫：背景淨化升級

更新日期：2026-08-09
狀態：規劃中
關聯 commit：`8fe10e0`（純 Kotlin BackgroundEnhancer）、`81eb005`（MD 同步）

## 1. 目標

將目前的背景淨化功能從**純 Kotlin Box Blur 近似**升級為**OpenCV 形態學 CLOSE + 矩陣除法**，以獲得更精確的背景估計、更乾淨的漂白效果，同時維持現有的 `EnhancementStrength`（Light / Normal / Strong）API 與金字塔下採樣優化。

### 不變項目

| 項目 | 現狀 | OpenCV 後 |
|---|---|---|
| `EnhancementStrength` enum | Light / Normal / Strong | 保留，映射到 OpenCV 參數 |
| `ScanSettings.enhanceBackground` | `EnhancementStrength?` | 不變 |
| ScanScreen UI | FilterChips 強度選擇器 | 不變 |
| `RealIntegrationProvider` 呼叫 | `BackgroundEnhancer.enhanceImageFile(file, strength)` | 不變（內部改用 OpenCV） |
| 設定持久化 | `SettingsStore` | 不變 |

### 變更項目

| 項目 | 現狀（純 Kotlin） | OpenCV 後 |
|---|---|---|
| 背景估計演算法 | Box Blur（近似 morphological CLOSE） | `Imgproc.morphologyEx(MORPH_CLOSE)`（精確） |
| 除法正規化 | 逐像素 Float 除法 | `Core.divide(src, bg, dst, 255.0)` |
| 對比度微調 | `applyContrast(alpha, beta)` | `Mat.convertTo(dst, -1, alpha, beta)` |
| Alpha 通道處理 | 不處理 | 拆分保留再合併 |
| APK 大小 | ~61 MB（debug） | 預估 +15-25 MB（arm64 + armeabi-v7a） |
| A4 300dpi 處理時間 | 約 1-2 秒 | 預估 < 0.5 秒 |

## 2. 依賴與建置設定

### 2.1 Gradle 依賴

```kotlin
// app/build.gradle.kts → dependencies
implementation("com.quickbirdstudios:opencv:4.5.3.0")
```

> **替代方案**：若 `quickbirdstudios` 停止維護，可改用官方 `org.opencv:opencv:4.x.x`（需確認 Maven Central 發布）或手動匯入 OpenCV Android SDK module。

### 2.2 ABI 瘦身

```kotlin
// app/build.gradle.kts → defaultConfig
ndk {
    abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
}
```

排除 x86 / x86_64（emulator 用），APK 體積可減少約 40%。
Emulator 測試時改用 `arm64-v8a` system image。

### 2.3 ProGuard / R8 規則

```proguard
# app/proguard-rules.pro
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
```

Release build 啟用 R8 minify 時必須保留 OpenCV native bridge 類別。

### 2.4 OpenCV 初始化

```kotlin
// MainActivity.onCreate()
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // OpenCVLoader.initDebug() must succeed before any OpenCV call
    if (!OpenCVLoader.initDebug()) {
        Log.e("MopriaScan", "OpenCV initialization failed — background enhancement disabled")
    }
    enableEdgeToEdge()
    setContent { ... }
}
```

## 3. 實作步驟

### 第一階段：建置整合（1-2 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 1.1 | 加入 `quickbirdstudios:opencv` 依賴 + ABI filters | `./gradlew :app:assembleDebug` 成功 |
| 1.2 | MainActivity 加入 `OpenCVLoader.initDebug()` | Log 確認載入成功 |
| 1.3 | 加入 ProGuard keep 規則 | `./gradlew :app:assembleRelease` 成功 |
| 1.4 | 測量 APK 大小變化 | 記錄 before/after |

### 第二階段：演算法遷移（2-3 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 2.1 | 建立 `OpenCvBackgroundEnhancer.kt`，使用 `DocumentProcessor` pattern | 編譯通過 |
| 2.2 | 實作形態學 CLOSE + 金字塔下採樣 + `Core.divide` + `convertTo` | 輸出 bitmap 正確 |
| 2.3 | 整合 `EnhancementStrength`：3 種強度映射到不同 kernel size / alpha / beta | 強度差異可見 |
| 2.4 | 嚴格 `Mat.release()` + `kernel.release()` | Android Profiler 無 native leak |
| 2.5 | 保留純 Kotlin `BackgroundEnhancer` 作為 fallback（OpenCV 初始化失敗時使用） | 兩條路徑都可運作 |

### 第三階段：整合與測試（1-2 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 3.1 | `RealIntegrationProvider` 呼叫改為 OpenCV 版本（有 fallback） | 掃描後圖片背景變白 |
| 3.2 | 新增 instrumentation test：處理 A4 300dpi JPEG 並驗證不 OOM | 測試通過 |
| 3.3 | 壓力測試：連續處理 5 張 A4 300dpi | Profiler heap 回基準線 |
| 3.4 | 更新 JVM unit tests：`processPixels` 保持純 Kotlin fallback 的測試 | 126+ tests 通過 |

### 第四階段：品質驗證（1 天）

| 步驟 | 內容 | 驗收條件 |
|---|---|---|
| 4.1 | 極端樣本測試：藍色簽名、紅色印章、全黑標題 | 彩色內容保留、無白色光暈 |
| 4.2 | Dark Mode UI 驗證 | 強度選擇器在深色模式下正常 |
| 4.3 | Lint + assembleDebug + assembleRelease | 全部通過 |

## 4. 架構設計

### 4.1 雙軌策略（OpenCV + Fallback）

```
RealIntegrationProvider.scan()
    │
    ├── settings.enhanceBackground != null?
    │       │
    │       ▼
    │   BackgroundEnhancer.enhanceImageFile(file, strength)
    │       │
    │       ├── OpenCV 可用?
    │       │       │
    │       │       ├── YES → OpenCvBackgroundEnhancer.apply(bitmap, strength)
    │       │       │         • morphologyEx(MORPH_CLOSE) on 1/4 downsampled
    │       │       │         • Core.divide at full resolution
    │       │       │         • convertTo(alpha, beta) per strength
    │       │       │         • strict Mat.release()
    │       │       │
    │       │       └── NO  → PureKotlinBackgroundEnhancer.apply(bitmap, strength)
    │       │                 • boxBlur on 1/4 downsampled (current implementation)
    │       │                 • per-pixel division
    │       │                 • applyContrast per strength
    │       │
    │       ▼
    │   寫回 JPEG/PNG 檔案
    │
    ▼
  建立 DocumentPage
```

### 4.2 EnhancementStrength → OpenCV 參數映射

```kotlin
// EnhancementStrength 到 OpenCV 參數的映射
private fun strengthParams(strength: EnhancementStrength): StrengthConfig = when (strength) {
    EnhancementStrength.Light -> StrengthConfig(
        kernelSize = 11.0,    // 較小核心，保留更多細節
        alpha = 1.0,          // 不額外增強對比
        beta = 0.0,
        downsampleFactor = 4,
    )
    EnhancementStrength.Normal -> StrengthConfig(
        kernelSize = 15.0,    // 標準核心
        alpha = 1.05,
        beta = -10.0,
        downsampleFactor = 4,
    )
    EnhancementStrength.Strong -> StrengthConfig(
        kernelSize = 21.0,    // 較大核心，更積極抹除
        alpha = 1.15,
        beta = -20.0,
        downsampleFactor = 6, // 更激進的下採樣
    )
}

data class StrengthConfig(
    val kernelSize: Double,
    val alpha: Double,
    val beta: Double,
    val downsampleFactor: Int,
)
```

### 4.3 OpenCV DocumentProcessor 核心流程

```kotlin
object OpenCvBackgroundEnhancer {

    fun apply(bitmap: Bitmap, strength: EnhancementStrength): Bitmap {
        val config = strengthParams(strength)
        val srcMat = Mat().also { Utils.bitmapToMat(bitmap, it) }

        try {
            // 1. 分離 Alpha 通道
            val channels = ArrayList<Mat>().also { Core.split(srcMat, it) }
            val alphaMat = channels.removeAt(channels.size - 1)
            val colorMat = Mat().also { Core.merge(channels, it) }

            // 2. 金字塔下採樣
            val factor = config.downsampleFactor.toDouble()
            val smallMat = Mat().also {
                Imgproc.resize(colorMat, it,
                    Size(colorMat.cols() / factor, colorMat.rows() / factor),
                    0.0, 0.0, Imgproc.INTER_LINEAR)
            }

            // 3. 形態學 CLOSE 估計背景
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(config.kernelSize, config.kernelSize))
            val smallBg = Mat().also {
                Imgproc.morphologyEx(smallMat, it, Imgproc.MORPH_CLOSE, kernel)
            }

            // 4. 放大背景回原尺寸
            val fullBg = Mat().also {
                Imgproc.resize(smallBg, it,
                    Size(colorMat.cols().toDouble(), colorMat.rows().toDouble()),
                    0.0, 0.0, Imgproc.INTER_LINEAR)
            }

            // 5. 彩色矩陣除法漂白
            val normalized = Mat().also {
                Core.divide(colorMat, fullBg, it, 255.0)
            }

            // 6. 對比度微調
            normalized.convertTo(normalized, -1, config.alpha, config.beta)

            // 7. 合併回 Alpha 通道
            val resultChannels = ArrayList<Mat>().also { Core.split(normalized, it) }
            resultChannels.add(alphaMat)
            val dstMat = Mat().also { Core.merge(resultChannels, it) }

            // 8. 轉回 Bitmap
            val result = Bitmap.createBitmap(
                dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, result)

            // 釋放所有暫存 Mat
            colorMat.release(); smallMat.release(); smallBg.release()
            fullBg.release(); normalized.release(); dstMat.release()
            alphaMat.release(); kernel.release()
            channels.forEach { it.release() }
            resultChannels.forEach { it.release() }

            return result
        } finally {
            srcMat.release()
        }
    }
}
```

## 5. 記憶體安全規範

### 5.1 Mat 釋放規則

- **每一個 `Mat()` 或 `Imgproc.getStructuringElement()` 的回傳值都必須 `.release()`**
- 使用 `try-finally` 確保即使例外也會釋放 `srcMat`
- `Core.split()` 回傳的每個 channel Mat 都要個別 release
- `Core.merge()` 回傳的 Mat 也要 release

### 5.2 Bitmap 生命週期

- 輸入 `bitmap` 由呼叫端管理（`enhanceImageFile` 會 recycle）
- 輸出 `result` 由呼叫端管理
- `OpenCvBackgroundEnhancer.apply()` 不 recycle 輸入或輸出

### 5.3 記憶體預估（A4 300dpi 彩色）

| 階段 | Mat 大小 | 備註 |
|---|---|---|
| srcMat (RGBA) | 2480 × 3508 × 4 = ~33 MB | 原圖 |
| colorMat (RGB) | 2480 × 3508 × 3 = ~25 MB | 拆分後 |
| smallMat (1/4) | 620 × 877 × 3 = ~1.6 MB | 下採樣後 |
| smallBg (1/4) | 620 × 877 × 3 = ~1.6 MB | 形態學結果 |
| fullBg (原尺寸) | 2480 × 3508 × 3 = ~25 MB | 上採樣後 |
| normalized | 2480 × 3508 × 3 = ~25 MB | 除法結果 |
| **峰值合計** | **~112 MB** | 可接受（即時釋放後回落） |

> 純 Kotlin 版本的峰值約 150-200 MB（多個 IntArray 暫存），OpenCV 版本因為下採樣更激進且 native heap 管理，峰值更低。

## 6. 風險與緩解

| 風險 | 機率 | 影響 | 緩解 |
|---|---|---|---|
| `quickbirdstudios:opencv` 停止維護 | 中 | 編譯失敗 | 保留純 Kotlin fallback；可改用官方 SDK |
| OpenCV native 庫在某些裝置載入失敗 | 低 | 功能不可用 | `OpenCVLoader.initDebug()` 檢查 + fallback |
| APK 增大 15-25 MB | 確定 | 下載量下降 | ABI splits（只 arm64 + armeabi-v7a） |
| R8 移除 OpenCV 反射類別 | 低 | Release crash | ProGuard keep 規則 |
| x86 emulator 無法測試 | 中 | 開發不便 | 使用 arm64 emulator image |

## 7. 回滾計畫

如果 OpenCV 整合後出現無法解決的問題：

1. `BackgroundEnhancer.enhanceImageFile()` 改回直接呼叫純 Kotlin pipeline
2. 移除 OpenCV 依賴和 ABI filters
3. 純 Kotlin 版本持續作為生產版本

回滾成本：低（API 不變，只切換內部實作）。

## 8. 完成條件

- [ ] OpenCV 依賴加入且 `assembleDebug` + `assembleRelease` 通過
- [ ] `OpenCvBackgroundEnhancer.apply()` 產生正確的漂白結果
- [ ] `EnhancementStrength` 3 種強度在 OpenCV 版本中均有可見差異
- [ ] A4 300dpi 處理時間 < 1 秒
- [ ] 連續處理 5 張 A4 圖片後 Profiler heap 回基準線
- [ ] 藍色簽名、紅色印章、黑色標題保留且無白色光暈
- [ ] OpenCV 初始化失敗時 fallback 到純 Kotlin 版本
- [ ] Lint clean，現有 126+ JVM tests 通過
- [ ] CHANGELOG、HANDOFF、README 更新
