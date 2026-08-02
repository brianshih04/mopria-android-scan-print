# Development Handoff

更新日期：2026-08-02

Repository：`brianshih04/mopria-android-scan-print`

目標分支：`main`

## 1. 接手摘要

目前版本已完成可執行的 Android Compose App、Mock／Real 模式、eSCL v2.97 pull-scan client、Flatbed／ADF 多頁文件工作流、PDF／JPEG 文件庫，以及 Android Print Framework 列印入口。

自動測試與 API 36 emulator 已通過；唯一重要的產品級缺口是尚未連接真實 eSCL scanner 與 Mopria printer 做跨品牌驗收。請勿把 Mock／fixture 結果描述成 Mopria Certified 或廠牌相容證據。

## 2. 快速啟動

```powershell
cd E:\Projects\mopria-android-scan-print
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.brianshih.mopria.android.scanprint/.MainActivity
```

已使用的 AVD：`Brian_Pixel_8_API_36`。本機若沒有 SDK 設定，建立未追蹤的 `local.properties` 指向 Android SDK。

## 3. 重要程式位置

| 檔案 | 責任 |
|---|---|
| `domain/IntegrationModels.kt` | 掃描設定、設備、文件頁與 UI state |
| `domain/EsclDiscovery.kt` | TXT metadata、resource root 驗證、安全服務優先 |
| `domain/EsclProtocol.kt` | XML parse、capability negotiation、ScanSettings、Job URL policy |
| `domain/EsclHttpClient.kt` | HTTP status、Retry-After、redirect、TLS、bounded streaming |
| `domain/RealIntegrationProvider.kt` | Android NSD、eSCL job lifecycle、JPEG／PDF 文件映射 |
| `domain/MockIntegrationProvider.kt` | 不依賴硬體的 deterministic scan／print fixture |
| `domain/ScanDocumentOrganizer.kt` | Flatbed append 與 ADF split 純邏輯 |
| `ui/MopriaViewModel.kt` | discovery／scan／export／print 協調及 Flatbed session state |
| `ui/ScanScreen.kt` | ADF max pages、Flatbed／ADF PDF 合併設定及下一頁 dialog |
| `ui/DocumentsScreen.kt` | 掃描文件頁面、列印／分享／輸出操作 |
| `ui/DocumentPageBitmapLoader.kt` | JPEG／PNG／PDF-backed 頁面取樣／render |
| `ui/ScanExportService.kt` | MediaStore PDF／JPEG 與分享 PDF |
| `ui/SystemPrintAdapter.kt` | 掃描文件交給 Android Print Framework |
| `ui/UriPrintAdapter.kt` | 手機 PDF／JPEG／PNG 交給 Android Print Framework |

Package root：`app/src/main/java/com/brianshih/mopria/android/scanprint/`。

## 4. eSCL 行為摘要

1. `NsdManager` 同時探索 `_uscan._tcp.` 與 `_uscans._tcp.`。
2. `EsclDiscovery` 解析 TXT，拒絕不安全 `rs`，以 UUID + root 去重並偏好 TLS。
3. Scan 前 GET `ScannerStatus`，Scanner 必須 Idle；ADF 若有狀態則需 Loaded／Processing。
4. GET `ScannerCapabilities`，依來源、SettingProfile、格式、色彩及 X/Y resolution 協商。
5. POST `{root}/ScanJobs`，要求 `201` 和安全 `Location`。
6. 依 JobInfo 狀態確認工作可傳輸，重複 GET `NextDocument`。
7. `503` 遵守 bounded `Retry-After`；`404` 表示頁面結束；timeout／`410` 會回查狀態。
8. 取消、失敗或無法讓設備只送指定頁數時，以 DELETE 清理工作。
9. JPEG／PNG 形成 image page；PDF 以 `PdfRenderer` 建立每一頁的 reference。

ADF max pages 有兩層意義：設備支援 `SelectSinglePage` 時送出 `NumberOfPages`；不支援時 client 取到上限即停止並 DELETE job。UI 與協定層都限制 1–50。

Flatbed multi-page 是多個獨立 eSCL Platen job 的 App-level session，不是單一 eSCL job。每頁完成後由使用者換紙並選「下一頁」，最後以 Android `PdfDocument` 合併。

## 5. 規格與授權界線

- Mopria Alliance eSCL Technical Specification v2.97 PDF 只存在開發者本機，不在 Git，也不應被複製到 repository、issue 或 CI artifact。
- 公開入口可連結 [Mopria eSCL Specification](https://mopria.org/mopria-escl-specification)。
- ScanBridge／eSCLKt 為 GPL-3.0-or-later：本專案只參考可觀察行為，不複製或連結其程式碼。
- HP JIPP 為 MIT，但目前沒有納入依賴；列印仍使用 Android Print Framework。

## 6. 最新驗證證據

| 驗證 | 結果 |
|---|---|
| `:app:testDebugUnitTest` | 30 passed，0 failed |
| `:app:lintDebug` | 0 errors，9 warnings |
| `:app:assembleDebug` | passed；APK 位於 `app/build/outputs/apk/debug/app-debug.apk` |
| `git diff --check` | passed |
| ADF emulator | max pages 改為 6；合併選項產生一份 6 頁文件及 PDF |
| Flatbed emulator | 2 個獨立 scan job；dialog 顯示第 1／2 頁；完成後一份 2 頁 PDF |
| DocumentsUI／Print | 系統選檔返回 App 列印頁，再回首頁 |
| Runtime | smoke flow 無 app fatal exception／OOM |

Lint warnings 為依賴版本與 Kotlin extension 建議，沒有 correctness／security error。Emulator screenshots 位於 ignored 的 `app/build/reports/emulator-smoke/`，不提交 Git。

## 7. 已知限制與風險

- 尚未以實體 scanner 驗證廠商 capability 差異、TLS 憑證、ADF 空紙／卡紙與 job retention。
- 尚未實作 401 credential UI；目前會顯示 challenge 資訊並失敗。
- `426` 可切換同 host HTTPS，但沒有 RFC 2817 同一 TCP connection raw Upgrade。
- 掃描設定 UI 仍固定 150／300／600 dpi；provider 會協商最接近能力，下一步應改為 capability-driven UI。
- Flatbed session、文件與工作主要存在記憶體；process death 不可恢復，raw scan 檔案也尚無定期清理策略。
- 沒有手動 IP／URL fallback、duplex scan、編輯、OCR 或 searchable PDF。
- 文件輸出使用 Android `PdfDocument` 重繪頁面，不保留原始 PDF 的文字／向量語意。
- 50 頁是 App 安全上限；大型高 dpi 掃描仍需實機 soak 與儲存空間檢查。

## 8. Android 平台注意事項

目前 `targetSdk 36`。依 Android 官方文件，target SDK 36 仍透過既有 `INTERNET` 權限存取 local network；`ACCESS_LOCAL_NETWORK` 是 target SDK 37+ 的遷移要求，請勿在 target 36 提前加入無效權限。升級時評估 system picker 路徑及完整 local-network permission 流程：

- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)

## 9. 建議接手順序

1. 先在 `main` 重新跑三個驗證指令並確認工作樹乾淨。
2. 接第一台真實 eSCL MFP；優先保存去識別的 TXT、Capabilities、Status 與 HTTP status sequence fixture。
3. 驗證 Flatbed JPEG、ADF PDF、`SelectSinglePage` true／false、取消與 503。
4. 將真實 capability 注入 ViewModel，讓 UI 動態限制來源／解析度／色彩。
5. 設計持久化 job/document model 與 raw scan retention，再處理 background／process death。
6. 接第二品牌與 Android Print Service 實體印表機，完成硬體 matrix 後才準備 Beta 宣稱。

詳細產品範圍見 [`README.md`](README.md)，里程碑見 [`dev_plan.md`](dev_plan.md)，本輪變更見 [`CHANGELOG.md`](CHANGELOG.md)。
