# OpenCV 整合接手指南

更新日期：2026-08-09
關聯檔案：`docs/opencv-integration-plan.md`（完整計畫）
關聯 commit：`3855f1c`（計畫文件）、`8fe10e0`（純 Kotlin fallback）

## 1. 這份文件是什麼

這份文件讓接手開發者能在不依賴先前對話的情況下，獨立完成 OpenCV 影像處理管線的整合。完整演算法與架構細節在 `docs/opencv-integration-plan.md`，本文件補充接手所需的**專案現況、檔案位置、驗證指令與注意事項**。

## 2. 專案現況

| 項目 | 現狀 |
|---|---|
| 最新 commit | `3855f1c`（docs: update OpenCV plan） |
| JVM unit tests | 126 tests，全部通過 |
| BackgroundEnhancer | 純 Kotlin 實作（Box Blur + 金字塔下採樣 + 除法 + 對比度），作為 fallback |
| EnhancementStrength | 3 種強度（Light / Normal / Strong），已整合至 ScanScreen UI |
| OpenCV 依賴 | **尚未加入** |
| OpenCV 管線 | **尚未實作** |

## 3. 必讀檔案（接手前依序閱讀）

| 順序 | 檔案 | 內容 |
|---|---|---|
| 1 | `AGENTS.md` | 專案安全紅線、建置指令、i18n 規範、平台 gotchas |
| 2 | `docs/opencv-integration-plan.md` | OpenCV 整合完整計畫（402 行）：目標、架構、4 階段步驟、記憶體估算、風險 |
| 3 | `app/src/main/.../domain/BackgroundEnhancer.kt` | 現有純 Kotlin 實作（258 行）：API 不變，OpenCV 版本取代內部實作 |
| 4 | `app/src/main/.../domain/IntegrationModels.kt` | `EnhancementStrength` enum 與 `ScanSettings.enhanceBackground` 定義 |
| 5 | `app/src/main/.../domain/RealIntegrationProvider.kt` | 掃描流程中呼叫 `BackgroundEnhancer.enhanceImageFile()` 的位置 |
| 6 | `app/src/main/.../ui/ScanScreen.kt` | `EnhancementStrength` FilterChips UI |
| 7 | `app/src/test/.../domain/BackgroundEnhancerTest.kt` | 純邏輯測試（processPixels），OpenCV 遷移後仍需通過 |

## 4. 現有 API（不可破壞）

接手者必須保持以下 API 不變，只替換內部實作：

```kotlin
// EnhancementStrength — 定義在 IntegrationModels.kt
enum class EnhancementStrength(@StringRes val labelRes: Int) {
    Light(R.string.enhance_light),
    Normal(R.string.enhance_normal),
    Strong(R.string.enhance_strong),
}

// ScanSettings — 定義在 IntegrationModels.kt
data class ScanSettings(
    // ... 其他欄位 ...
    val enhanceBackground: EnhancementStrength? = null,  // null = 關閉
)

// BackgroundEnhancer — 定義在 BackgroundEnhancer.kt
// 這個 API 不變，內部從純 Kotlin 改為 OpenCV + fallback
object BackgroundEnhancer {
    fun apply(bitmap: Bitmap, strength: EnhancementStrength): Bitmap
    fun enhanceImageFile(file: File, strength: EnhancementStrength): Boolean
    internal fun processPixels(pixels: IntArray, width: Int, height: Int, strength: EnhancementStrength): IntArray
}
```

## 5. 驗證指令（照抄即可）

```powershell
# Windows PowerShell（本專案主要開發環境）
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
```

> WSL 環境請用 `cmd.exe /C "set \"JAVA_HOME=C:\Program Files\Android\Android Studio\jbr\" && gradlew.bat ..."`。
> JDK 25 來自 Android Studio bundled JBR（`C:\Program Files\Android\Android Studio\jbr`）。
> `local.properties` 需指向 Android SDK（`sdk.dir=C:/Users/Brian/AppData/Local/Android/Sdk`）。
> Lint PropertyEscape 在 WSL+Windows 會誤報 `local.properties`，CI 在 Linux 上不會有此問題。

## 6. 實作路線圖摘要

詳細步驟見 `docs/opencv-integration-plan.md` 第 3 節，以下為快速索引：

### 第一階段：建置整合（1-2 天）
1. `app/build.gradle.kts` 加入 `implementation("com.quickbirdstudios:opencv:4.5.3.0")`
2. 加入 `ndk { abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a")) }`
3. `app/proguard-rules.pro` 加入 `-keep class org.opencv.** { *; }`
4. `MainActivity.onCreate()` 加入 `OpenCVLoader.initDebug()`
5. 驗收：`assembleDebug` + `assembleRelease` 通過

### 第二階段：核心管線（2-3 天）
1. 建立 `DocumentScannerPipeline.kt`（`processScanPipeline` suspend fun）
2. 實作形態學 CLOSE + 金字塔下採樣 + `Core.divide` + `convertTo`
3. 實作透視校正 `applyPerspectiveTransform`（`warpPerspective`）
4. `EnhancementStrength` 映射到 kernel size / alpha / beta / downsample factor
5. 嚴格 `Mat.release()`（參見計畫第 5 節記憶體安全規範）
6. 保留純 Kotlin `BackgroundEnhancer` 作為 fallback

### 第三階段：UI 整合（1 天）
1. `RealIntegrationProvider` 呼叫 OpenCV 管線（有 fallback）
2. `Dispatchers.Default` 非同步處理
3. UI Loading 進度

### 第四階段：測試驗證（1 天）
1. 壓力測試：連續 5-10 張 A4 300dpi
2. 極端樣本：藍色簽名、紅色印章、黑色標題
3. Lint + assembleDebug + assembleRelease

## 7. 安全紅線（接手者必讀）

1. **TLS 一律使用 Android 系統 trust store，不可加入 trust-all。**
2. **eSCL job／redirect URL 必須同 host、同 resource root。**
3. **XML parser 必須停用 DOCTYPE 與 external entities（XXE 防護）。**
4. **不可用 Mock／fixture 結果宣稱「Mopria Certified」。**
5. **ScanBridge／eSCLKt 為 GPL：不可複製或連結其程式碼。**
6. **OpenCV 的每一個 `Mat()` 都必須 `.release()`，否則連續處理會 OOM。**

## 8. EnhancementStrength 參數映射

| 強度 | kernel size | alpha (對比度) | beta (亮度) | downsample | 效果 |
|---|---|---|---|---|---|
| Light | 11×11 | 1.0 | 0 | 4x | 溫和清理，保留淡色內容 |
| Normal | 15×15 | 1.05 | -10 | 4x | 均衡（Document preset 預設） |
| Strong | 21×21 | 1.15 | -20 | 6x | 激烈漂白，可能丟失極淡內容 |

## 9. 記憶體估算（A4 300dpi RGBA）

| 階段 | 大小 |
|---|---|
| srcMat (RGBA) | ~33 MB |
| colorMat (RGB) | ~25 MB |
| smallMat (1/4 downsample) | ~1.6 MB |
| fullBg (upscale 回原尺寸) | ~25 MB |
| normalized (divide 結果) | ~25 MB |
| **峰值合計** | **~112 MB** |

> 純 Kotlin 版本峰值約 150-200 MB（多個 IntArray 暫存）。OpenCV 版本因下採樣更激進且 native heap 管理，峰值更低。

## 10. 雙軌 Fallback 策略

```
BackgroundEnhancer.enhanceImageFile(file, strength)
    │
    ├── OpenCVLoader.initDebug() == true?
    │       │
    │       ├── YES → DocumentScannerPipeline.processScanPipeline(bitmap, strength)
    │       │
    │       └── NO  → 純 Kotlin processPixels（現有實作，不變）
    │
    └── 寫回 JPEG/PNG
```

## 11. 回滾

如果 OpenCV 整合失敗：
1. `BackgroundEnhancer` 內部改回直接呼叫純 Kotlin pipeline
2. 移除 `build.gradle.kts` 中的 OpenCV 依賴和 ABI filters
3. 純 Kotlin 版本即為生產版本

回滾成本：**低**（外部 API 不變，只切換內部實作）。

## 12. 完成條件 Checklist

- [ ] OpenCV 依賴加入且 `assembleDebug` + `assembleRelease` 通過
- [ ] `DocumentScannerPipeline.processScanPipeline()` 產生正確漂白結果
- [ ] `EnhancementStrength` 3 種強度有可見差異
- [ ] 透視校正 `warpPerspective` 四角拉正正確
- [ ] A4 300dpi 處理 < 1 秒
- [ ] 連續 5-10 張後 Profiler heap 回基準線
- [ ] 藍色簽名、紅色印章、黑色標題保留且無光暈
- [ ] OpenCV 初始化失敗時 fallback 到純 Kotlin
- [ ] Lint clean，126+ JVM tests 通過
- [ ] CHANGELOG、HANDOFF、README 更新
