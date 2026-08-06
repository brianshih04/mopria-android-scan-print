# Direct IPP 後續修改清單

## 文件目的

本文件供接手開發者處理 `feat/direct-ipp` 的 code review findings。Direct IPP 目前已完成基本 discovery、IPP binary client 與 Real mode 接線，但仍應維持為 experimental opt-in，不要在完成本清單前改成預設列印方式。

## 審查基準

- Reviewed branch：[`feat/direct-ipp`](https://github.com/brianshih04/mopria-android-scan-print/tree/feat/direct-ipp)
- Reviewed commit：`c8f47fd`
- Compared with：`main` / `db35d13`
- 變更規模：18 commits、37 files changed
- Android Studio bundled JDK：25.0.2
- 已驗證：unit tests 42/42、lint、debug assemble、release/R8 assemble 均成功
- 尚未完成：實體 IPP/IPPS 印表機跨品牌驗證

## P1：合併或擴大測試前必須處理

### 1. 修正 document-format 與實際 bytes 不一致

相關檔案：

- [`IppPrintClient.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppPrintClient.kt)
- [`RealIntegrationProvider.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/RealIntegrationProvider.kt)
- [`IppPrintClientTest.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/test/java/com/brianshih/mopria/android/scanprint/domain/IppPrintClientTest.kt)

目前 `IppDocumentFormat.producible` 宣稱支援 PDF、JPEG、PNG，`printSupported()` 也會依印表機 capability 選擇格式；但是 `RealIntegrationProvider.print()` 永遠只產生一份 PDF，然後把同一個 PDF stream 傳給選出的格式。

因此，若印表機只宣告 `image/jpeg`，request 可能會是：

```text
document-format = image/jpeg
document bytes  = PDF
```

建議分兩階段處理：

1. 短期：在尚未有 JPEG／PNG renderer 前，`producible` 只保留 `application/pdf`；如果印表機不支援 PDF，明確回報不支援。
2. 長期：建立 `RenderedPrintDocument(format, file, contentType)`，依協商結果產生相同格式的 bytes，再傳給 `Send-Document`。

必要測試：

- printer 支援 PDF：送出 PDF，`document-format` 為 `application/pdf`。
- printer 只支援 JPEG：不能送 PDF 偽裝成 JPEG，應產生 JPEG 或清楚失敗。
- printer 同時支援 PDF/JPEG：確認 preference 與實際 bytes 相符。
- 在 HTTP test server 驗證 IPP header、payload magic bytes 與 payload content type 一致。

### 2. 完成 IPP job lifecycle polling

相關檔案：

- [`IppPrintClient.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppPrintClient.kt)
- [`RealIntegrationProvider.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/RealIntegrationProvider.kt)
- [`MopriaViewModel.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/ui/MopriaViewModel.kt)

`IppPrintClient` 已有 `getJobAttributes()` 與 `cancelJob()` helper，但 Real print flow 在 `Send-Document` 回應成功後就直接視為列印完成，沒有確認實際 job state。

建議：

- 建立可測試的 `awaitJobCompletion(uri, jobId)`。
- 依 IPP job-state 處理：
  - `3 Pending`、`4 Held`、`5 Processing`、`6 Stopped`：繼續 polling。
  - `7 Canceled`：回報使用者取消。
  - `8 Aborted`：回報印表機失敗原因。
  - `9 Completed`：才標記成功。
- 加入 polling interval、總 timeout 與 coroutine cancellation。
- 傳送期間或 timeout 時，以 `NonCancellable` best-effort 執行 `Cancel-Job`。
- 將 job state 映射到現有 `JobStatus`／progress，不要在 Send-Document 後立即顯示完成。

必要測試：

- Pending → Processing → Completed。
- Processing → Aborted，確認工作變成 Failed。
- Processing → Canceled，確認工作變成 Cancelled。
- polling timeout 後執行 Cancel-Job。
- HTTP／IPP error response 不得被誤判為成功。

### 3. 修正 CI 的 Android SDK 版本

相關檔案：

- [`app/build.gradle.kts`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/build.gradle.kts)
- [`ci.yml`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/.github/workflows/ci.yml)

目前 project 使用 `compileSdk 37`，但 CI 只明確安裝：

```yaml
platforms;android-36 build-tools;36.0.0
```

請選擇並保持一致：

- 維持 `compileSdk 37`：CI 安裝 Android 37 platform 及對應 build tools。
- 或將 project 降回 `compileSdk 36`：同步修改 README、CI 與開發環境說明。

驗收方式是在乾淨 runner 或只安裝文件指定 SDK 的環境執行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

### 4. 完成 IPPS 實體設備驗證，但不要使用 trust-all

相關檔案：

- [`IppDiscovery.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppDiscovery.kt)
- [`IppTransport.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppTransport.kt)
- [`RealIntegrationProvider.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/RealIntegrationProvider.kt)

目前 `_ipps._tcp` discovery 會優先於 `_ipp._tcp`，並使用 Android system trust store。實體印表機常見 self-signed certificate、hostname 與 resolved IP 不一致，可能造成 TLS 或 hostname verification 失敗。

請使用至少一台實體設備驗證：

- `_ipp._tcp` plain IPP。
- `_ipps._tcp` 有效憑證。
- `_ipps._tcp` self-signed／hostname mismatch 時，確認錯誤訊息與 fallback 行為。
- 同一台設備同時宣告 IPP/IPPS 時，確認 secure candidate 被選取。

安全限制：不可加入 trust-all、跳過 hostname verification 或把所有憑證視為可信。若一般家用印表機無法使用 IPPS，應在 UI／文件清楚說明，或讓使用者選擇 plain IPP。

## P2：功能穩定後處理

### 5. Direct IPP 找不到印表機時不要默默切換

目前選擇 Direct IPP 後，若找不到 IPP printer，ViewModel 會直接開啟 Android system print preview。這會讓使用者以為仍在使用 Direct IPP。

建議提供明確選項：

- 「找不到 Direct IPP 印表機」錯誤及重新探索。
- 「改用系統列印」明確按鈕。
- 或在設定中增加「Direct IPP 不可用時自動 fallback」開關，預設關閉。

### 6. 將 Direct IPP 錯誤訊息移至 string resources

新 IPP code 仍有硬編碼中文，例如 HTTP／Create-Job／格式不支援／列印失敗等訊息。English、簡中及其他語系可能看到繁中錯誤。

請將可見錯誤移到 `strings.xml`，至少同步：

- English fallback
- `values-zh-rTW`
- `values-zh-rCN`

ViewModel 應只接收可本地化的 error type 或 resource id，不要依賴 provider 產生的硬編碼顯示文字。

### 7. 避免 Direct IPP renderer 造成 OOM

`RealIntegrationProvider` 的 IPP PDF renderer 使用完整 `BitmapFactory.decodeFile()`，沒有尺寸取樣或記憶體上限。高 dpi 掃描的大圖可能讓 emulator 或手機 OOM。

請重用或抽取現有 `DocumentPageBitmapLoader` 的取樣邏輯，並確認：

- JPEG／PNG 使用 bounds decode 與 `inSampleSize`。
- PDF page render 有最大像素／尺寸限制。
- 多頁列印逐頁處理，不同時保留所有 bitmap。
- decode 失敗時回報錯誤，不要靜默產生空白頁。

### 8. 驗證 HTTP streaming interoperability

[`IppTransport.kt`](https://github.com/brianshih04/mopria-android-scan-print/blob/feat/direct-ipp/app/src/main/java/com/brianshih/mopria/android/scanprint/domain/IppTransport.kt) 使用 `setChunkedStreamingMode(0)`。HTTP chunked 對規格上可能可行，但部分印表機 firmware 只接受明確 `Content-Length`。

請在 HTTP test server 與至少兩個品牌實體印表機驗證：

- request 是否使用 chunked transfer。
- Create-Job、Send-Document 是否都能接受。
- 大型 PDF 是否能正確串流。
- 若設備不接受 chunked，針對已知 document length 改用 fixed-length streaming；不要為了方便把整個文件無限制讀入記憶體。

### 9. 補 capability-driven print options

目前 Direct IPP 主要使用印表機預設值，尚未將下列 capability 暴露給使用者：

- media／paper size
- copies
- color mode
- sides／duplex
- print quality

先從 `Get-Printer-Attributes` 解析 capability，再決定 UI 與 IPP job attributes。不要在未確認 capability 前硬送不支援的 attribute。

### 10. 補 Android／emulator integration coverage

branch 移除了 `androidTest` dependencies，目前 CI 只有 JVM unit test、lint、assemble。應至少補：

- print method settings persistence。
- Direct IPP 選擇與 no-printer error flow。
- system print fallback flow。
- Documents → print／share 返回流程。
- 語系切換後 Direct IPP 錯誤訊息。

## 文件與 release 同步

以下內容需在實作完成後一起更新：

- README、HANDOFF、CHANGELOG 的測試數量：目前實際為 42 tests，不是 34。
- README 的環境需求：JDK 25 daemon、compileSdk 37、Android SDK 版本要一致。
- CHANGELOG 不應宣稱已完成 Get-Job-Attributes／Cancel-Job lifecycle，除非 Real flow 已實際呼叫並測試。
- 明確記載 Direct IPP 是否仍為 opt-in，以及找不到設備時是否允許 fallback。
- 說明 APK 位於 GitHub Release，而不是 repository 的 `release/` 目錄。
- 保持 Mopria eSCL specification PDF 不進入 repository。

## 建議接手順序

1. 修正 document-format／payload mismatch，或暫時只支援 PDF。
2. 加入 IPP job polling、timeout、cancel 與狀態測試。
3. 修正 CI compileSdk／build-tools 版本並重新跑 GitHub Actions。
4. 使用真實 IPP／IPPS 印表機測試 discovery、TLS、列印與 job lifecycle。
5. 修正 fallback、錯誤本地化與 bitmap memory handling。
6. 補 print options、Android integration tests、README／HANDOFF／CHANGELOG。
7. 所有驗證通過後，才考慮將 Direct IPP 從 experimental opt-in 改成其他預設策略。

## 完成條件

- [ ] PDF／JPEG／PNG 的宣告格式與實際 bytes 永遠一致。
- [ ] 列印完成狀態來自 IPP job state，而不是只看 Send-Document response。
- [ ] IPP job failure、cancel、timeout 都能正確反映在工作紀錄。
- [ ] CI 在乾淨環境可取得正確 Android SDK 並通過 test、lint、debug assemble。
- [ ] 至少一台 plain IPP 與一台 IPPS 實體設備完成驗證。
- [ ] Direct IPP 錯誤支援 English、繁中、簡中。
- [ ] 高解析度多頁文件不會因 bitmap allocation 造成 OOM。
- [ ] 相關 MD files、CHANGELOG、HANDOFF 已同步實際行為與驗證結果。
