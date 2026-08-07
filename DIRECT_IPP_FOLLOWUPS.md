# Direct IPP 後續修改清單

## 文件目的

本文件供接手開發者處理 Direct IPP 的 code review findings。此次整合已將最新 `main` 的 PWG-Raster／PCLm 功能帶入 `feat/direct-ipp-fixes`，並保留格式 payload 一致、job polling、fixed-length streaming、OOM sampling、錯誤本地化、no-printer error 與 instrumentation tests；Direct IPP 仍應維持 experimental opt-in，直到實體設備驗證完成。

## 審查基準

- Reviewed branch：[`feat/direct-ipp-fixes`](https://github.com/brianshih04/mopria-android-scan-print/tree/feat/direct-ipp-fixes)
- Integration base：`origin/main` / `2b4bb05`
- Fixes base：`d299bf3`
- 變更內容：Direct IPP base、PWG-Raster／PCLm、capability options 與 review fixes
- Android Studio bundled JDK：25.0.2
- 已驗證：unit tests 57/57、lint、debug/release assemble、4 個 emulator instrumentation tests 均成功
- 尚未完成：實體 IPP/IPPS 印表機跨品牌驗證

## 目前接手重點

- [x] 修正 declared `document-format` 與實際 payload 不一致。
- [x] 加入 IPP job polling、timeout 與 Cancel-Job cleanup。
- [x] 使用 fixed-length HTTP streaming。
- [x] 使用 sampled bitmap，避免一般圖片列印路徑直接載入完整高解析影像。
- [x] Direct IPP 找不到印表機時顯示明確錯誤，不再 silent fallback。
- [x] Direct IPP 錯誤訊息支援 string resources。
- [x] 恢復 instrumentation test dependencies 與基本 UI smoke tests。
- [ ] 實體設備驗證 IPP、IPPS、PWG-Raster、PCLm、job lifecycle 與憑證行為。
- [ ] 進一步改善 `IppRasterizer` 多頁高 DPI 的 bitmap streaming，避免同時保留所有頁面。

## P1：合併或擴大測試前必須處理

### 1. 修正 document-format 與實際 bytes 不一致

相關檔案：

- [`IppPrintClient.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppPrintClient.kt)
- [`RealIntegrationProvider.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/RealIntegrationProvider.kt)
- [`IppPrintClientTest.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/test/java/com/brianshih/mopria/android/scanprint/domain/IppPrintClientTest.kt)

目前已完成格式與 payload 對齊：`IppDocumentFormat.producible` 依偏好順序提供 PDF、PWG-Raster、PCLm、JPEG、PNG；`RealIntegrationProvider` 會分別產生對應 bytes，且 image 多頁以多個 `Send-Document` 傳送。PWG-Raster／PCLm 由 `jipp-pdl` 從中介 PDF rasterize。

必要的 JVM tests 已涵蓋 PDF、JPEG 多頁、格式協商、fixed-length body、PWG/PCLm writer magic，以及中途 Send-Document 失敗時的 Cancel-Job cleanup。仍需以實體印表機驗證各品牌對 PCLm／PWG-Raster 的實際接受度。

### 2. 完成 IPP job lifecycle polling

相關檔案：

- [`IppPrintClient.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppPrintClient.kt)
- [`RealIntegrationProvider.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/RealIntegrationProvider.kt)
- [`MopriaViewModel.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/ui/MopriaViewModel.kt)

已完成 `awaitJobCompletion(uri, jobId)`：Pending／Held／Processing／Stopped 會 polling，Completed 才成功，Canceled／Aborted 會轉成 domain error，timeout 會在 `NonCancellable` 中 best-effort Cancel-Job；中途頁面傳輸失敗也會清理已建立的 job。相關狀態、timeout 與 HTTP／IPP error tests 已通過。

### 3. 修正 CI 的 Android SDK 版本

相關檔案：

- [`app/build.gradle.kts`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/build.gradle.kts)
- [`ci.yml`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/.github/workflows/ci.yml)

此問題已修正。project 使用 `compileSdk 37`，CI 會明確安裝 Android 37 platform 與對應 build tools：

```yaml
platforms;android-36 build-tools;36.0.0
```

驗收方式是在乾淨 runner 或只安裝文件指定 SDK 的環境執行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

### 4. 完成 IPPS 實體設備驗證，但不要使用 trust-all

相關檔案：

- [`IppDiscovery.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppDiscovery.kt)
- [`IppTransport.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppTransport.kt)
- [`RealIntegrationProvider.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih04/mopria/android/scanprint/domain/RealIntegrationProvider.kt)

目前 `_ipps._tcp` discovery 會優先於 `_ipp._tcp`，並使用 Android system trust store。實體印表機常見 self-signed certificate、hostname 與 resolved IP 不一致，可能造成 TLS 或 hostname verification 失敗。

請使用至少一台實體設備驗證：

- `_ipp._tcp` plain IPP。
- `_ipps._tcp` 有效憑證。
- `_ipps._tcp` self-signed／hostname mismatch 時，確認錯誤訊息與 fallback 行為。
- 同一台設備同時宣告 IPP/IPPS 時，確認 secure candidate 被選取。

安全限制：不可加入 trust-all、跳過 hostname verification 或把所有憑證視為可信。若一般家用印表機無法使用 IPPS，應在 UI／文件清楚說明，或讓使用者選擇 plain IPP。

## P2：功能穩定後處理

### 5. Direct IPP 找不到印表機時不要默默切換

此問題已修正：選擇 Direct IPP 後找不到印表機會回傳明確的本地化錯誤，不會自動開啟 Android system print preview。若產品未來要允許 fallback，必須增加明確的使用者操作或設定，不能在 provider 內隱式切換。

### 6. 將 Direct IPP 錯誤訊息移至 string resources

此問題已修正：`PrintError` 只攜帶結構化錯誤，ViewModel 映射到 string resources；English、繁中、簡中及其餘語系均有資源，provider 不產生可見的硬編碼訊息。

### 7. 避免 Direct IPP renderer 造成 OOM

目前已完成大部分記憶體與錯誤處理：JPEG／PNG 使用 sampled decode，PDF page render 限制在列印畫布尺寸，decode 失敗會回報 `PageRenderFailed`，且多頁 image renderer 會逐頁產生並清理暫存檔。剩餘風險是 `IppRasterizer` 為 PCLm／PWG-Raster 暫時保留所有高 DPI 頁面 bitmap，後續應改成 swath／逐頁 streaming。

### 8. 驗證 HTTP streaming interoperability

[`IppTransport.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp-fixes/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppTransport.kt) 已改用已知長度的 fixed-length HTTP body，不再使用 chunked streaming；JVM test server 已驗證 `Content-Length`。仍需在至少兩個品牌實體印表機確認 Create-Job／Send-Document 的 interoperability。

### 9. 補 capability-driven print options

capability-driven print options 已完成：從 `Get-Printer-Attributes` 解析 copies、media、sides、color mode、quality、orientation，UI 只送經 `coerceTo` 後符合 printer capability 的 job-template attributes。實體印表機仍需確認不同品牌對各 option 的語意。

### 10. 補 Android／emulator integration coverage

目前已恢復 `androidTest` dependencies，emulator 已通過 4 個 instrumentation tests，包含 MainActivity smoke、print method persistence 與 PDF→PWG-Raster/PCLm Android rasterizer path；Direct IPP 真實網路與 no-printer UI 仍以實體／mock discovery test 擴充為後續工作。

## 文件與 release 同步

以下內容需在實作完成後一起更新：

- README、HANDOFF、CHANGELOG 的測試數量：目前實際為 57 JVM unit tests，另有 4 個 emulator instrumentation tests。
- README 的環境需求：JDK 25 daemon、compileSdk 37、Android SDK 版本要一致。
- CHANGELOG 可記載 Get-Job-Attributes／Cancel-Job lifecycle 已接線並有 JVM tests；實體 printer job lifecycle 仍待驗證。
- 明確記載 Direct IPP 是否仍為 opt-in，以及找不到設備時是否允許 fallback。
- 說明 APK 位於 GitHub Release，而不是 repository 的 `release/` 目錄。
- 保持 Mopria eSCL specification PDF 不進入 repository。

## 建議接手順序

1. 使用真實 IPP／IPPS 印表機測試 discovery、TLS、格式、選項與 job lifecycle。
2. 針對 PCLm／PWG-Raster 高 DPI 多頁工作，將目前全頁 bitmap 改為 swath／逐頁 streaming。
3. 補充 Direct IPP no-printer、TLS error、選項與文件列印流程的 emulator／mock coverage。
4. 所有實體驗證通過後，再評估是否改變 Direct IPP 的 experimental opt-in 策略。

## 完成條件

- [x] PDF／JPEG／PNG／PWG-Raster／PCLm 的宣告格式與實際 bytes 一致。
- [x] 列印完成狀態來自 IPP job state，而不是只看 Send-Document response。
- [x] IPP job failure、cancel、timeout 都能轉成 domain/UI error。
- [x] CI 在乾淨環境可取得正確 Android SDK 並通過 test、lint、debug assemble。
- [ ] 至少一台 plain IPP 與一台 IPPS 實體設備完成驗證。
- [x] Direct IPP 錯誤支援 English、繁中、簡中。
- [ ] 高解析度多頁 PCLm／PWG-Raster 文件不會因 bitmap allocation 造成 OOM。
- [x] 相關 MD files、CHANGELOG、HANDOFF 已同步實際行為與驗證結果。
