# AGENTS.md

給 OpenCode session 的進入指南。只列容易踩雷、檔名看不出來、或與 framework 預設不同的事實。完整產品／架構說明在 `README.md`、`dev_plan.md`、`HANDOFF.md`。

## Build / Verify（務必照抄旗標）

PowerShell on Windows；Gradle wrapper 是 `.\gradlew.bat`。

```powershell
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
git diff --check
```

- `--max-workers=1 --no-daemon` 是本專案驗證時的固定用法，不要換成 `--parallel` 或 daemon。
- 跑單一測試：`.\gradlew.bat :app:testDebugUnitTest --tests "*.EsclProtocolTest" --max-workers=1 --no-daemon`。
- CI：`main` 沒有 workflow；`feat/direct-ipp*` 有 `.github/workflows/ci.yml`（push／PR 觸發）。先確認 branch 再決定是否仰賴 CI。
- 各文件記錄的 unit test 數量（34／42／30）對應不同時間點與不同 branch，**不要把數字寫進 commit message 或新文件**；以實際 `testDebugUnitTest` 輸出為準。

## 架構事實（檔名看不出來的）

- 單一 `app` module；package root `com.brianshih.mopria.android.scanprint`。沒有 Hilt／Room／WorkManager／多 module，也不要無目的地搬移。
- **掃描與列印是兩條不同路徑**：掃描由 App 自建 eSCL v2.97 client 實作；列印在 `main` 交給 Android `PrintManager`／`PrintDocumentAdapter`（不自行發 IPP），在 `feat/direct-ipp*` 另有 opt-in 的 direct IPP client（`IppPrintClient`／`IppTransport`／`IppDiscovery`）。
- Mock／Real 透過 `domain/IntegrationProviders.kt` 的三個介面切換；domain model 與 UI 在兩種模式下共用。改協定行為時兩個 provider 都要顧。
- App state 在 `MopriaViewModel` + `StateFlow` + SharedPreferences（名稱 `mopria_settings`）；文件與工作主要存在記憶體，process death 不可恢復。
- eSCL 規格 PDF 是受限制的本機研究來源，**嚴禁複製進 repository／issue／CI artifact**；只能連結 [Mopria eSCL Specification](https://mopria.org/mopria-escl-specification)。

## 安全紅線（任何修改都不可違反）

- **TLS 一律使用 Android 系統 trust store，不可加入 trust-all、跳過 hostname verification 或視所有憑證為可信。**
- **eSCL job／redirect URL 必須同 host、同 resource root；拒絕跨主機、降級、query、fragment 與 path traversal。**
- **XML parser 必須停用 DOCTYPE 與 external general／parameter entities（XXE 防護）。**
- 不可用 Mock／fixture 結果宣稱「Mopria Certified」或特定廠牌相容。
- ScanBridge／eSCLKt 為 GPL-3.0-or-later：只參考可觀察行為，**不可複製或連結其程式碼**。

## i18n（10 語系）

- 支援：en（預設 fallback）、ja、ko、es、pt、de、fr、ru、`zh-rTW`、`zh-rCN`。
- **任何使用者可見文字必須走 `res/values*/strings.xml`**，不可在 Compose、ViewModel、domain provider 硬編碼。新增字串時先加 English 到 `res/values/strings.xml`，再同步所有語系目錄。
- 中文 script／region 判斷邏輯在 `ui/LanguageManager.kt`；locale 套用在 `MainActivity.attachBaseContext`。
- 錯誤訊息也要本地化；provider 應回可本地化的 error type 或 resource id，不要回硬編碼顯示文字。

## Branch 差異（最容易誤判的事）

- `DIRECT_IPP_FOLLOWUPS.md` 描述的是 **`feat/direct-ipp` branch**（compileSdk 37、JDK 25、42 tests、有 direct IPP client、有 CI），**不是 `main`**。該文件的 P1/P2 清單在合併該 branch 前才適用，不要直接拿來改 main。
- `main`：compileSdk/targetSdk 36、JDK 17、無 direct IPP、無 CI、列印走 Android Print Framework。
- 改動前先確認你在哪條 branch，並以 `app/build.gradle.kts` 為準，不要相信文件裡的 SDK 數字。

## 平台／環境 gotchas

- `local.properties`（Android SDK 路徑）被 gitignore；新機器需自行建立，或由 Android Studio 產生。
- JDK／SDK 隨 branch 不同：`main` = JDK 17／compileSdk 36；`feat/direct-ipp*` = JDK 25（見 `gradle/gradle-daemon-jvm.properties`）／compileSdk 37。以 `app/build.gradle.kts` 為準。
- `release/avi-print-scan.apk` 用**本機 debug keystore 簽署**，僅供 emulator／開發測試；正式發布前必須換產品簽章。
- `targetSdk 36` 的 local network 存取仍由 `INTERNET` 涵蓋；**不要提前加入 `ACCESS_LOCAL_NETWORK`**，那是 target SDK 37+ 的遷移項目。
- `app/build/` 是 gitignored 的建置產物；`.workflow/` 是過往 review／compliance 的歷史紀錄，不是活躍設定。
- `network_security_config.xml` 刻意允許 cleartext，因為 `_uscan` 常以本地 HTTP 運作；不要為了「安全」拔掉而破壞 real-mode 探索。

## 開發慣例

- 動 eSCL 協定、discovery、HTTP client 行為時，先看 `app/src/test/java/.../domain/` 對應測試，那裡定義了契約（URL policy、status 處理、signature 驗證、協商規則）。
- 改 UI 動作前先確認可見文字已進 string resources；`DocumentsScreen`、`ScanScreen`、`SupportScreens`、`HomeScreen`、`PrintScreen` 都不應出現新的硬編碼字串。
- 文件輸出（`ScanExportService`）與列印 adapter（`SystemPrintAdapter`／`UriPrintAdapter`）共用 `DocumentPageBitmapLoader` 的取樣邏輯；新增頁面 render 時重用它，避免 OOM。
- 變更涉及實機相容性聲稱時，同步更新 README、HANDOFF、CHANGELOG，並保持「未實機驗證就不宣稱」的措辭。
