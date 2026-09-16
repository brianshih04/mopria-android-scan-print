# ADF Scan Resolution Investigation — 2026-08-13

> **⚠️ 本文件結論已被推翻（2026-09-16）。**
> 「Brother 依 TCP 層級特徵降級解析度」為錯誤歸因（當時測試順序的巧合）。
> 真正根因是 **POST ScanJobs 的 Content-Type**：Brother firmware 只對
> `application/xml` 走完整 eSCL 路徑，`text/xml` 落入 200dpi 降級 fallback。
> 修復與完整驗證見 `docs/brother-contenttype-rootcause.md`。以下內容僅供歷史參考。

## 背景

Brother MFC-L2715DW（eSCL v2.63, firmware `debut/1.30`）ADF 掃描回傳低解析度圖像。
App 要求 300 dpi，scanner 回傳 1680×2193（~200 dpi, 62 KB），應為 2448×3470（300 dpi, 524 KB）。

## 測試環境

- 掃描器: Brother MFC-L2715DW @ `10.1.121.175`（eSCL v2.63, Mopria Scan Certified 1.3）
- 開發機: Windows 11 Pro, i9-9900K, Boot Camp iMac
- Emulator: AVD `mopria_test`（API 36, x86_64）
- App: mopria-android-scan-print v0.1.0（Real mode, 手動 IP 輸入）

## 確認正確的部分

### App 送出的 eSCL XML 正確

tcpdump 封包驗證 App 送出的 XML 與 curl 完全相同：

```xml
<pwg:Version>2.63</pwg:Version>
<scan:Intent>Document</scan:Intent>
<pwg:InputSource>Feeder</pwg:InputSource>
<scan:XResolution>300</scan:XResolution>
<scan:YResolution>300</scan:YResolution>
<scan:ColorMode>RGB24</scan:ColorMode>
<scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
```

### ADF 掃描生命週期完整運作

- ADF 無紙 → `validateScannerReady()` 正確擋住（`ScanError.AdfNotReady`）
- ADF 有紙 → POST 201 Created → NextDocument 200 OK JPEG → 404 結束 → Job Completed
- ADF 實際取紙（`ScannerAdfLoaded` → `ScannerAdfEmpty`），掃描器從 Idle → Processing → Idle

### App 的 eSCL 協定處理無 bug

- `negotiate()` 正確選出 300 dpi Feeder RGB24
- `buildScanSettings()` 產生正確的 XML
- `awaitJobReady()` 正確處理 Brother 的 Pending→Completed job 生命週期
- Brother 的 job state 從未進入 Processing（只有 scanner state 進入 Processing）

## 根因分析

### 排除的假設

| 假設 | 測試結果 | 結論 |
|---|---|---|
| QEMU emulator NAT (slirp) | TAP bridge + 直接連線 | ❌ 排除 |
| `awaitJobReady` 太早返回 | pcap 確認第一次 NextDocument 即回 200 | ❌ 排除 |
| HTTP header 差異 | curl 帶 Python 的 header 仍得高解析 | ❌ 排除 |
| `Accept-Encoding` header | gzip / identity / 無 → 結果不變 | ❌ 排除 |
| `TCP_NODELAY` | Python monkey-patch 設定 → 結果不變 | ❌ 排除 |
| `Connection: close` vs keep-alive | 兩者結果相同 | ❌ 排除 |
| HTTP/1.0 vs HTTP/1.1 | HTTP/1.0 → HTTP 500 | ❌ 排除 |
| `--no-tcp-nodelay` | curl 無此 flag → HTTP 500 | 確認 TCP 分段敏感 |

### 確認的根因

問題出在 **TCP stack 層級的差異**：

| 測試 | 平台 | TCP Stack | 結果 |
|---|---|---|---|
| `curl.exe` (libcurl/8.21.0) | **Windows** | Winsock (WSASend) | **2448×3470** ✅ |
| Python `http.client` | Windows | Winsock (send) | 1680×2193 ❌ |
| Python raw socket + TCP_NODELAY | Windows | Winsock (send) | 1680×2193 ❌ |
| .NET `HttpClient` | Windows | Winsock (HttpWebRequest) | 1680×2193 ❌ |
| Android `HttpURLConnection` | Android (Linux) | Linux (send/recv) | 1680×2193 ❌ |
| **Android libcurl 7.82.0 (JNI)** | **Android (Linux)** | Linux (send/recv) | **1680×2193** ❌ |

Brother `debut/1.30` 根據 TCP 連線的某種特徵（可能是 TCP window scale option、SACK、timestamp、或 ACK pattern）決定回傳高解析或低解析圖。

只有 **Windows 的 curl.exe / libcurl**（使用 Winsock 的 `WSASend`）觸發高解析回應。
所有其他 HTTP client（不論 Windows 或 Linux）都得到低解析。

### 額外確認

- `curl --no-tcp-nodelay` → HTTP 500（TCP 分段導致 XML 被截斷）
- `curl --http1.0` → HTTP 500（Brother 不支援 HTTP/1.0）
- libcurl JNI bridge 在 Android emulator 上成功載入並執行，但仍得到低解析

## 嘗試過的修復

### libcurl JNI 整合（已回退）

- 使用 `com.fpliu.ndk.pkg.prefab.android.21:curl:7.82.0` prebuilt AAR
- 手動用 NDK clang 27.0.12077973 編譯 JNI bridge（`curl_jni.c`）
- 覆寫 `EsclHttpClient` 所有 HTTP 操作改用 `CurlEngine` JNI
- **結果：libcurl 在 Android (Linux) 上仍然得到 1680×2193**
- 原因：Android 的 Linux TCP stack 與 Windows Winsock 行為不同
- 已確認無效，完整回退

## 結論

這是 **Brother MFC-L2715DW firmware（debut/1.30）的不可修復 bug**：
- 僅影響非 Windows Winsock 的 TCP client
- Android（Linux kernel）的真機和 emulator 都會受到影響
- App 程式碼完全正確，無需修改
- libcurl JNI 整合無法解決問題（已實測確認）

## 建議

1. 檢查 Brother 官網是否有 MFC-L2715DW firmware 更新
2. 在不同品牌 scanner 上測試確認是 Brother 特有問題
3. 若 firmware 更新後修復，重新測試 App 端到端流程
