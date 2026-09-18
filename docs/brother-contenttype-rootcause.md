# ADF/解析度問題 — 最終根因與修復（2026-09-16 更新）

## 總結

2026-08-13 的調查（見 `adf-resolution-investigation.md`）結論為「Brother 依 TCP 層級特徵降級解析度，僅 Windows curl.exe 取得高解析」。
**該結論是錯誤歸因。** 2026-09-16 交叉比對 iOS 版（airscan-studio-ios）後找到真正根因：

## 真正根因：POST ScanJobs 的 Content-Type

Brother MFC-L2715DW firmware（debut/1.30）對 `POST /eSCL/ScanJobs` 的 Content-Type 做嚴格匹配：

| Content-Type | 行為 |
|---|---|
| `application/xml` | 完整 eSCL 路徑：遵循 XResolution/YResolution/ScanRegion → 正確輸出 |
| `text/xml` 或 `text/xml; charset=utf-8`（其他） | 降級 fallback：200dpi、全感光範圍 → 1680×2193（ADF 紙張仍取，但解析度/區域被忽略） |

1680×2193 其實是感光元件原生範圍（2512×3290 @300dpi）的 200dpi 版本（÷1.5），與「解析度降級」無關，而是 firmware 完全忽略了請求參數。

## 對照實驗（2026-09-16，同機同日，6/6 一致）

| XML body | Content-Type | 結果 |
|---|---|---|
| Android 版 | `text/xml; charset=utf-8` | 1680×2193 ❌ |
| Android 版 | `application/xml` | 2448×3486 ✅ |
| iOS 版 | `application/xml` | 2448×3484/3486 ✅ ×3 |
| iOS 版 | `text/xml; charset=utf-8` | 1680×2193 ❌ |

XML body 內容（xmlns:escl 宣告、DocumentFormatExt class attr）**不影響**結果，只有 Content-Type 決定行為。

iOS 版 `ESCLClient.swift` 建立請求時使用 `application/xml`，因此從未遇到此問題。

## 為何 8/13 會誤判為 TCP 問題

8/13 的測試順序恰好讓 curl（成功案例）與 Python/Java/emulator（失敗案例）交錯，且當時 curl 的成功被歸因於 libcurl 的 Winsock 行為。實際上當日 curl 成功的請求可能因 scanner 端狀態或當時不明的條件而走了正確路徑（同樣的 `text/xml` header 在 9/16 重測 100% 失敗）。8/13 嘗試的 TCP_NODELAY、TAP bridge、libcurl JNI 修復方向全部無效是因為根本不是傳輸層問題。

## 修復

`EsclHttpClient.kt` `createScanJob()`：

```kotlin
contentType = "application/xml"   // was: "text/xml; charset=utf-8"
```

單行修改。iOS 版已採用相同值。

## 修復後驗證（2026-09-16 實測）

### App 端到端（Android emulator + Brother ADF）
- 修復前：1680×2193（62KB）
- 修復後：**2448×3486（1.9MB）** ✅

### curl 矩陣測試（Content-Type: application/xml，ADF 各 1 張）

| Case | 請求 | 實際輸出 | 判定 |
|---|---|---|---|
| 200dpi RGB24 A4 | 2480×3508@200 | 1632×2323 | ✅ 感光上限（光學寬 8.16"） |
| 300dpi RGB24 A4 | 2480×3508@300 | 2448×3486 | ✅ |
| 600dpi RGB24 A4 | 2480×3508@600 | 4912×6970 | ✅ |
| 300dpi Grayscale8 A4 | 2480×3508@300 | 2448×3481 | ✅ |
| 300dpi A5 region | 1748×2480 | 1712×2454 | ✅ 等比 |
| 300dpi 4×6 region | 1200×1800 | 1168×1774 | ✅ 等比 |
| 300dpi 5×7 region | 1500×2100 | 1472×2076 | ✅ 等比 |
| 300dpi BlackAndWhite1 + JPEG | — | POST 拒絕 | Brother 不支援此組合；App 對黑白模式送 application/pdf，不受影響 |

Brother 光學寬度為 2448px @300dpi（8.16"），略小於 A4 的 2480（8.27"），所有輸出 = min(請求, 光學上限)，行為正確。

### Unit tests
`EsclHttpClientTest` 更新斷言為 `application/xml` 後全數通過。

## 其餘觀察（2026-09-16 實測補充）

- Brother ADF：單頁 job 掃完後會把整疊紙退出（載 7 張掃 1 張剩餘全部退出）；HP ADF 同樣行為——測試時每次放 1 張
- Brother 無 Bonjour 廣告，需手動 IP（與 iOS 報告一致）；HP `_uscan._tcp` 在 emulator 內亦不可靠，手動 IP 可用
- HP eSCL canonical port 為 8080（mDNS TXT）；80 也可回應
- HP LaserJet Pro MFP 3104fdw：ADF 7 頁連續掃描全部 2480×3508 ✅、PDF 匯出 ✅、Direct IPP 列印 job Completed ✅（本輪 App 端到端實測）
- Brother 殘留 job（ScannerStatus 列出的 JobUri）會導致 POST 失敗，DELETE 清理（可能 404）後恢復

### HP 矩陣測試（2026-09-16，curl :8080）

| Case | 結果 | 判定 |
|---|---|---|
| `text/xml; charset=utf-8` 300dpi RGB A4 | 2480×3508 | ✅ **HP 不受 Content-Type 影響**（Content-Type 問題為 Brother 特有） |
| `application/xml` 300dpi RGB A4 | 2480×3508 | ✅ 精確 |
| 600dpi RGB（Feeder） | HTTP 409 `Input Settings Mismatch` | 非 bug：HP ADF caps 上限 300dpi（600 僅 Platen）；App `negotiate()` 會 coerce，不會送出 |
| 300dpi Grayscale8 A4 | 2480×3508 | ✅ |
| BlackAndWhite1 + JPEG | HTTP 409 `Input Settings Mismatch` | 非問題：App 黑白模式送 `application/pdf`，不會送此組合 |
| A5 region | 1748×2480 | ✅ 精確 |
| 4×6 region | 1200×1800 | ✅ 精確 |
| 5×7 region | 1500×2100 | ✅ 精確 |

HP 的 409 回應帶 HP ErrorInfo XML（`conflictWithExisting — Input Settings Mismatch Error!`），是設定驗證拒絕，非 job 衝突、非間隔問題。

## 結論

1. 「Brother 對非 Windows TCP client 降級」的舊結論作廢；`docs/adf-resolution-investigation.md` 僅供歷史參考
2. Content-Type 修復同時解決：解析度被忽略（1680×2193）、ScanRegion 被忽略（非 A4 比例 0.766）兩個症狀
3. Brother「ADF 不取紙」（8/14 報告）在本輪未再出現；ADF 取紙正常

---

## Direct IPP：HTTP/1.1 掛起問題（2026-09-17 發現並修復）

### 症狀

Direct IPP 列印在 HP LaserJet Pro MFP 3104fdw 成功，在 Brother MFC-L2715DW 失敗（App 等 60 秒 readTimeout 後回報錯誤）。

### 根因

Brother `debut/1.30` 的 IPP 服務（`:631`）**無法完成 HTTP/1.1 回應**。對照實測（連續、序列化、可 100% 重現）：

| 請求框架 | Brother `:631/ipp/print` | HP `:8080`/`:631` |
|---|---|---|
| HTTP/1.1（keep-alive） | ❌ 掛住 → client timeout | ✅ |
| HTTP/1.1（`Connection: close`） | ❌ 掛住 → client timeout | ✅ |
| **HTTP/1.0** | ✅ HTTP 200 + IPP successful-ok（8445B 完整屬性） | ✅ |

`java.net.HttpURLConnection` 沒有公開 API 可強制 HTTP/1.0，因此 App（原本走 `HttpURLConnection`）在 Brother 上必失敗。

### 修復

`BoundedIppTransport`（`domain/IppTransport.kt`）改為 raw socket 直寫 HTTP frame：

- `POST {path} HTTP/1.0` + `Host` + `Content-Type: application/ipp` + `Content-Length` + `Connection: close`
- `ipps://` 目標經 `SSLSocketFactory.getDefault()`（Android 系統 trust store，無 trust-all）
- 保留既有傳輸紀律：固定 `Content-Length`（不做 chunked；DIRECT_IPP_FOLLOWUPS.md P2.8）、IPP 回應 2MB 上限、HTTP header 16KB 上限
- HTTP/1.0 對 IPP 合法：每個請求都帶精確 `Content-Length`，回應讀至 printer 關閉連線（或 Content-Length 滿足）

### 驗證（2026-09-17/18 實測）

- curl probe 矩陣：Brother HTTP/1.0 ×3 全成功（ipp-status 0x0000、含 `printer-make-and-model`/`document-format-supported`）；HP HTTP/1.0 亦成功
- App 端到端（emulator + Brother + ADF 1 張）：掃描（2448×3484）→ PDF 匯出 → **Direct IPP 列印 job Completed**；Job history 三項全綠
- Unit tests（含 `IppPrintClientTest` 對 JDK HttpServer 的 12 項）全數通過

### 實作注意事項

- 自製 HTTP header 解析需偵測 **CR LF CR LF**（4 bytes）結尾 — 最後兩 bytes 是 CR、LF，不是 LF、LF（開發時踩過的 bug）
- Brother 的 eSCL（`debut/1.30`）與 IPP 是同一 firmware 的兩個獨立 quirk：eSCL 要求 `Content-Type: application/xml`（見上文章節），IPP 要求 HTTP/1.0
