# Development Plan

更新日期：2026-09-16

## 1. 產品目標

建立跨品牌 Android 掃描與列印 App：掃描由 App 直接實作 eSCL pull scan；列印預設交給 Android Print Framework，並提供 opt-in 的 Direct IPP 路徑。文件預設保留在本機，使用者可預覽、輸出、分享或列印掃描結果。

核心原則：

1. 協定正確性、安全邊界與錯誤清理優先。
2. Flatbed、ADF、文件庫與列印是彼此清楚的工作流程。
3. UI 以 icon、數值和短標籤為主，進階能力依設備 capability 顯示。
4. 不用 Mock 測試結果宣稱實體 Mopria／eSCL 相容性。
5. 不複製 ScanBridge／eSCLKt 的 GPL 程式碼；規格研究來源不提交 Git。

## 2. 已確認的架構決策

| 範圍 | 決策 |
|---|---|
| Android UI | Kotlin、Jetpack Compose、Material 3、single activity |
| App state | `MopriaViewModel` + `StateFlow`；SharedPreferences 保存設定，內部 JSON 保存文件／Flatbed session，gzip sidecar 保存 page-scoped OCR layout；active job 仍為記憶體狀態 |
| 掃描探索 | Android `NsdManager`，服務 `_uscan._tcp`／`_uscans._tcp` |
| 掃描傳輸 | 自建 bounded eSCL HTTP client；系統 trust store；不使用 trust-all |
| 列印 | Real 模式可切換：系統列印（Mopria，預設）或直接 IPP（`IppPrintClient`，opt-in，找不到印表機時顯示明確錯誤，不 silent fallback）；Brother／HP plain IPP 基本流程已實機驗證 |
| 掃描後處理 | OpenCV 4.14 file-first pipeline；deskew、auto-crop、blank-page drop、背景淨化；失敗保留 source |
| OCR | Google ML Kit Text Recognition v2 unbundled clients；模型由 Google Play services 管理；OCR 與 Searchable PDF 都是 opt-in |
| 文件輸出 | 一般 PDF 使用 Android `PdfDocument`；Searchable PDF 使用 PDFBox mixed/temp invisible text layer；MediaStore、FileProvider、Sharesheet |
| Mock／Real | 共用 domain model 與 UI，以 provider 切換實作 |
| 多國語言 | Android string resources；系統語系自動選擇、English fallback、設定頁手動覆寫 |

目前沒有 Hilt、Room、WorkManager 或多 module；若加入，必須以工作持久化或可測試性需求驅動，不做無目的架構搬移。

## 3. 目前完成範圍

### UI／UX

- Dark Mode surfaceContainer tonal palette 完整定義。
- 文件刪除按鈕（紅色 error 色彩）。
- 掃描設定改為 capability-driven（Real 模式依設備動態過濾）。
- PageThumbnail 無障礙語義（contentDescription + Role.Button）。

- icon-first widget dashboard 首頁。
- 首頁、文件、紀錄、設定四個底部 destination。
- 「掃描文件」與「文件」分離：前者只負責設定及執行，後者只顯示既有結果。
- 列印頁有明確返回鍵；Android DocumentsUI 取消後返回 App 列印頁。
- 文件頁提供實際縮圖、放大預覽、PDF／JPEG、列印與分享。
- Mock／Real 模式可切換並保存。
- UI、ViewModel 狀態訊息與文件顯示支援 10 種語言；系統語系不支援時 fallback English，使用者可手動覆寫。

### 掃描工作流

- Flatbed 單頁。
- Flatbed「逐頁合併 PDF」：每頁後顯示下一頁／完成，最多 50 頁。
- ADF 頁數上限由使用者設定 1–50 頁。
- ADF「合併為多頁 PDF」可切換；開啟時建立一份多頁文件，關閉時拆成單頁文件。
- 150／300／600 dpi、彩色／灰階／黑白設定保存。
- JPEG／PNG／PDF payload 形成同一份頁面模型；PDF 使用 `PdfRenderer` 取得實際頁數。

### eSCL v2.97 pull-scan

- PWG Version、namespace、Platen／Feeder、`DocumentFormatExt`／legacy format。
- source-specific `SettingProfile`／`ref`、色彩／格式組合、離散與 X/Y ranged resolutions。
- DNS-SD TXT `rs`／`uuid`／`vers`／`ty`／`pdl`、resource root 驗證、安全服務優先與 IPv6 scope。
- `ScannerStatus`／JobInfo、Pending／Processing／Completed／Canceled／Aborted。
- `201 + Location`、同 host URL policy、`NextDocument`、`404`、`410`、bounded `503 Retry-After`。
- `TE: chunked`、控制與文件大小上限、MIME/signature 檢查。
- 取消、錯誤與超過非 `SelectSinglePage` ADF 上限時執行 `DELETE` 清理。
- TLS 1.3 capability 檢查與 Android trust store；無 trust-all fallback。

### 影像處理、OCR 與 Searchable PDF

- eSCL response 直接 stream-to-file，不把完整 image response 放入 Kotlin `ByteArray`。
- OpenCV 4.14 執行 ADF deskew、平台 auto-crop、blank-page drop 與 Light／Normal／Strong 背景淨化；暫存輸出通過驗證後才 atomic replace。
- ML Kit OCR 只解碼受 12 MP／4096 px 長邊限制的取樣 Bitmap；OCR 掃描只協商 JPEG，PDF-only profile 在建立 ScanJob 前明確失敗。
- OCR 語言預設 English／繁中／簡中；日文、韓文及其他 catalog 由使用者按需選取。ML Kit script model 由 Google Play services 管理，JP／KR PDF 字型則從固定 Noto CJK commit 下載並驗證 SHA-256。
- `OcrResult.Applied` 保存 block／line／element／symbol、座標、角度、語言與 confidence；formatter 支援保守的雙欄／寬表格排序與數字正規化，但不是語意化 table extraction。
- Searchable PDF 由使用者另外選取；PDFBox 以 bounded page image、32 MiB mixed/temp storage、Noto 字型與明確 ToUnicode CMap 建立不可見文字層。缺 OCR layout／必要字型時明確失敗，不會降級成普通 PDF。
- OCR layout 以 versioned gzip sidecar 保存並在 process death 後恢復；crop 永遠存 source-space，旋轉 UI 與 PDF 文字層共用座標轉換。

### 列印

- 從手機資料夾選擇 PDF／JPEG／PNG。
- 從文件庫列印掃描文件。
- `SystemPrintAdapter`／`UriPrintAdapter` 遵守 Android page range 與 media size。
- 系統列印為預設路徑，由 Android Print Service 負責印表機探索、IPP/IPPS 與 spooler 設定。
- Direct IPP 已整合到 `main`，可在 Real 模式 opt-in；支援 `_ipp/_ipps` 探索、PDF／JPEG／PNG／PWG-Raster／PCLm、capability-constrained options、job polling、timeout cancel、fixed-length HTTP、bounded 300 dpi source rendering 與 single-document printer batching。
- Direct IPP 找不到印表機時會顯示本地化錯誤，不會默默 fallback 到系統列印；Brother MFC-L2715DW 與 HP LaserJet Pro MFP 3104fdw 已完成 plain IPP 基本工作，HP 另完成 7 頁 ADF → PDF → Direct IPP 端到端流程。

## 4. 目前不在完成宣稱內

- Push Scan、Stored Job Request、ScanBufferInfo、加密 PDF request。
- ADF duplex 跨機型實機相容性與透視校正；ADF duplex UI、掃描範圍、裁切／旋轉／排序已完成。
- ML Kit 在真實 ARM 裝置上的模型下載、accuracy、cold/warm latency、PSS 與無 Google Play services fallback。
- 語意化表格 cell extraction、欄位／數值驗證，以及日文／韓文／混合 script Searchable PDF 的完整字型 coverage。
- 使用者認證、PIN、OAuth、client certificate UI。
- QR／NFC 加入設備，以及與認證 UI 整合的進階手動 endpoint 流程；目前已有手動 IP／host 輸入。
- active job／網路工作持久化、前景服務與背景續傳；文件／Flatbed session／OCR layout 已可恢復。
- Direct IPP 的廣泛產品級相容性宣稱；Brother／HP 的 plain IPP 基本 job lifecycle 已實機驗證，但 IPPS 憑證、各格式／選項／錯誤接受度與高 DPI 多頁記憶體仍待實體／soak 驗證。
- Mopria Certified 或任何廠商品牌相容性宣稱。

## 5. 里程碑

| 里程碑 | 狀態 | 驗收重點 |
|---|---|---|
| M0 App shell／Mock／Print Framework | 完成 | 可建置、Mock scan、文件輸出、系統列印預覽 |
| M1 現代化 UI／UX | 完成 | 清楚導覽、scan/documents 分離、返回行為、縮圖與分享 |
| M2 eSCL v2.97 pull-scan client | 程式、fixture 與兩款 MFP 基本實機完成 | Brother／HP eSCL、ADF、多解析度／尺寸／灰階；尚缺 `_uscans`、更多錯誤與設備矩陣 |
| M3 Flatbed／ADF 多頁 PDF | Mock／UI 完成 | Flatbed 2 頁、ADF 6 頁 emulator smoke |
| M3.1 多國語言 | 完成 | 10 種 resource locale、系統偵測、English fallback、手動選擇與 JVM tests |
| M3.2 Direct IPP | 程式、自動測試與兩款 MFP plain IPP 基本實機完成 | opt-in 路徑與 job completion 已驗證；尚缺 IPPS、完整格式／錯誤矩陣與高 DPI soak |
| M3.3 OpenCV／OCR／Searchable PDF | 程式與 emulator gate 完成 | 12 MP OCR bound、256 MB PSS gate、50 頁 PDF；尚缺 ARM 模型／accuracy／16 KB／多語實機 |
| M4 實體跨品牌驗收 | 部分完成 | Brother／HP 基本掃描與 plain Direct IPP 已完成；完整系統列印、IPPS、格式、錯誤、TLS 與 soak matrix 待補 |
| M5 文件編輯／持久工作 | 部分完成（文件／Flatbed／OCR sidecar、暫存清理、旋轉／排序／裁切完成；active job 尚未持久化） | process death、raw retention、背景工作、大型文件 |
| M6 Beta 品質 | 部分完成（Dark Mode + 平板適配 + 無障礙已完成；TalkBack/Play 測試待辦） | TalkBack、平板、效能、隱私、Play 測試 |

## 6. 實體設備驗收計畫

### Scanner matrix

Brother MFC-L2715DW 與 HP LaserJet Pro MFP 3104fdw 已完成基本 eSCL 實機驗證；後續以這兩款為基準擴充其他型號與下列完整矩陣。每台記錄：

- Android 手機型號／版本、Wi-Fi／Guest／Wi-Fi Direct 情境。
- `_uscan`、`_uscans`、TXT `rs`、UUID、eSCL version、TLS 憑證型態。
- Flatbed：JPEG、PDF、150／300／600 dpi、彩色／灰階／黑白。
- ADF：1 頁、使用者頁數上限、超過上限清理、空紙、卡紙、多頁 PDF。
- capability profile ref、非對稱 X/Y resolution、設備不支援設定時的 UI／錯誤。
- 503、使用者取消、網路中斷、scanner job 被移除、app 切背景。

### Printer matrix

Brother／HP 已完成 plain Direct IPP 基本工作；仍須在這兩款與後續設備完整測試 Android Default Print Service／Mopria Print Service、IPP／IPPS 與下列矩陣：

- 手機 PDF、JPEG、PNG 與掃描 multi-page PDF。
- 份數、紙張、方向、彩色／灰階、雙面、頁面範圍。
- 成功、取消、離線、缺紙與服務未啟用。
- 從 DocumentsUI 返回 App，以及 Print Spooler 返回 App。
- Direct IPP `_ipp/_ipps` discovery、TLS／hostname 驗證、PDF／JPEG／PNG／PWG-Raster／PCLm、`multiple-document-jobs-supported`、capability options、job polling 與 timeout cleanup。
- 高 DPI 多頁 PWG-Raster／PCLm soak，記錄峰值記憶體並確認不發生 OOM。

文件可精確記錄 Brother MFC-L2715DW／HP LaserJet Pro MFP 3104fdw 已執行的測試情境，但完整矩陣完成前不得延伸成整個品牌、其他型號或「Mopria Certified」宣稱。

## 7. 自動測試計畫

目前 JVM tests 涵蓋：

- eSCL namespace／Version／XXE、source profile、format、resolution range、ADF `NumberOfPages`。
- ScannerStatus／JobInfo、Location URL policy。
- DNS-SD TXT parsing、root sanitation、secure deduplication。
- HTTP 201、200、404、503、401、DELETE、TE、PDF／JPEG signature 與錯誤 status。
- Mock Flatbed／ADF 頁數及文件 merge／split 規則。
- 語系 tag、繁／簡中文 script／region 判斷與未知語系 fallback。
- Direct IPP format negotiation、fixed-length transport、job lifecycle、multi-document capability／batching 與 print-resolution sizing。
- OpenCV 參數、source integrity、OCR sizing／語言 catalog／版面排序／數字正規化、crop 座標與 Searchable PDF layout transform。

2026-08-11 本機快照另有 28 個 API 36 instrumentation tests，覆蓋 OpenCV runtime／A4 十頁 memory soak、deskew／auto-crop／blank-page、ML Kit boundary、文件與 OCR sidecar 恢復、Searchable PDF 抽取與 50 頁 256 MB PSS gate。JVM tests 為 163；後續仍以當次 Gradle 輸出為準。

後續需補：

- Compose navigation／dialog／large text instrumentation tests。
- Real provider 的 fake NSD 與完整 job state integration tests。
- 真實高解析多頁 Direct IPP、低磁碟、raw scan retention 與低記憶體實機。
- active job process death、重複啟動與取消 race；文件／OCR sidecar 的 process-death restore 已有 instrumentation coverage。
- 真實 ARM ML Kit model download／accuracy／PSS、16 KB page-size 與 JP／KR 字型下載／抽取。
- 真實 TLS、自簽憑證決策與認證挑戰 fixture。

標準驗證指令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --max-workers=1 --no-daemon
git diff --check
```

## 8. 安全、隱私與平台遷移

- eSCL job URL 僅允許同 host／resource root，拒絕跨主機、降級、query、fragment 與 path traversal。
- XML parser 禁止 DOCTYPE／external entities；HTTP 與文件皆有大小上限。
- TLS 使用 Android 系統憑證；沒有永久 trust-all。
- 暫存 scan files 位於 app-controlled storage，backup/data extraction rules 排除掃描原稿。
- 公開文件透過 MediaStore；分享使用短期 FileProvider read grant。
- 目前 `targetSdk 36` 的 local network 仍由 `INTERNET` 權限涵蓋。升到 target SDK 37 時，依 Android 官方文件加入 local-network permission／picker，不提前宣告尚未生效的權限。
- 不在 log 記錄文件內容、認證資訊或不必要的完整 URI。

## 9. Definition of Done

功能只有在以下條件都成立時才算產品完成：

- 正常、取消、逾時、離線與設備錯誤路徑都有可恢復的結果。
- 自動測試、lint、debug build 與對應 emulator 測試通過。
- 涉及硬體相容性的功能有實體設備型號、韌體、Android 版本及結果紀錄。
- 文件、CHANGELOG 與 HANDOFF 同步更新。
- 沒有未處理的 blocker／critical finding，且敏感文件／規格未提交 Git。

目前程式碼已完成實作、本機／模擬器 DoD，以及 Brother／HP 基本 eSCL 與 plain Direct IPP 實機驗證；M4 的完整 IPPS／格式／錯誤／TLS／soak matrix 與 ML Kit ARM／16 KB DoD 尚未完成。`./gradlew` exit 127 已由 wrapper／line-ending 修復，`main@68c2112` 的 GitHub Actions 已於 2026-09-16 通過。

## 10. 建議下一步

1. 將 Brother／HP 已驗證結果整理為可提交且去識別的 capability／status fixture，並擴充 Flatbed、ADF、取消、卡紙與斷線回歸。
2. 保持 Linux wrapper／line-ending gate；`main@68c2112` 已恢復 CI 綠燈，後續 commit 仍須確認對應 run。
3. 在 ARM 實機驗證 ML Kit Latin／Chinese／Japanese／Korean model request、accuracy、latency、PSS 與 JP／KR 字型下載／Searchable PDF 抽取。
4. 驗證 `_uscans` 憑證與 401 challenge，設計使用者確認／認證流程。
5. 設計 active job persistence、raw scan retention 與背景工作；文件／Flatbed／OCR layout 已有基礎持久化，不需要為了形式先導入 Room／WorkManager。
6. 補齊 Brother／HP 的系統列印、IPPS、格式／選項／錯誤與高 DPI soak，並擴充更多 scanner／printer 型號後再決定 Beta 發布條件。
