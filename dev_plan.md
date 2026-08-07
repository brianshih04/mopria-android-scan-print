# Development Plan

更新日期：2026-08-05

## 1. 產品目標

建立跨品牌 Android 掃描與列印 App：掃描由 App 直接實作 eSCL pull scan，列印交給 Android Print Framework。文件預設保留在本機，使用者可預覽、輸出、分享或列印掃描結果。

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
| App state | `MopriaViewModel` + `StateFlow`；目前為記憶體狀態與 SharedPreferences 設定 |
| 掃描探索 | Android `NsdManager`，服務 `_uscan._tcp`／`_uscans._tcp` |
| 掃描傳輸 | 自建 bounded eSCL HTTP client；系統 trust store；不使用 trust-all |
| 列印 | Real 模式可切換：系統列印（Mopria，預設）或直接 IPP（`IppPrintClient`，opt-in，找不到印表機時 fallback 系統列印）；尚未實機驗證 |
| 文件輸出 | Android `PdfDocument`、MediaStore、FileProvider、Sharesheet |
| Mock／Real | 共用 domain model 與 UI，以 provider 切換實作 |
| 多國語言 | Android string resources；系統語系自動選擇、English fallback、設定頁手動覆寫 |

目前沒有 Hilt、Room、WorkManager 或多 module；若加入，必須以工作持久化或可測試性需求驅動，不做無目的架構搬移。

## 3. 目前完成範圍

### UI／UX

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

### 列印

- 從手機資料夾選擇 PDF／JPEG／PNG。
- 從文件庫列印掃描文件。
- `SystemPrintAdapter`／`UriPrintAdapter` 遵守 Android page range 與 media size。
- Android Print Service 負責印表機探索、IPP/IPPS 與列印設定。

## 4. 目前不在完成宣稱內

- Push Scan、Stored Job Request、ScanBufferInfo、OCR、可搜尋／加密 PDF request。
- ADF duplex UI、掃描範圍／裁切／旋轉／排序／透視校正。
- 使用者認證、PIN、OAuth、client certificate UI。
- 手動 IP／URL、QR／NFC 加入設備。
- 工作持久化、程序死亡恢復、前景服務、背景續傳。
- 自行實作直接 IPP／IPPS（進行中：`IppDiscovery` + `RealIntegrationProvider` + ViewModel + 列印方式切換（預設系統列印）已接線，待實機驗證與 capability 列印選項 UI）。
- Mopria Certified 或任何廠商品牌相容性宣稱。

## 5. 里程碑

| 里程碑 | 狀態 | 驗收重點 |
|---|---|---|
| M0 App shell／Mock／Print Framework | 完成 | 可建置、Mock scan、文件輸出、系統列印預覽 |
| M1 現代化 UI／UX | 完成 | 清楚導覽、scan/documents 分離、返回行為、縮圖與分享 |
| M2 eSCL v2.97 pull-scan client | 程式與 fixture 完成 | 34 unit tests、lint、build；尚缺實機 |
| M3 Flatbed／ADF 多頁 PDF | Mock／UI 完成 | Flatbed 2 頁、ADF 6 頁 emulator smoke |
| M3.1 多國語言 | 完成 | 10 種 resource locale、系統偵測、English fallback、手動選擇與 JVM tests |
| M4 實體跨品牌驗收 | 待辦 | 至少兩個 scanner 品牌與兩個 printer 品牌 |
| M5 文件編輯／持久工作 | 待辦 | 裁切、旋轉、排序、背景恢復、大型文件 |
| M6 Beta 品質 | 待辦 | TalkBack、平板、效能、隱私、Play 測試 |

## 6. 實體設備驗收計畫

### Scanner matrix

至少準備 Canon、Brother、Fujifilm 或其他兩個以上 eSCL 品牌。每台記錄：

- Android 手機型號／版本、Wi-Fi／Guest／Wi-Fi Direct 情境。
- `_uscan`、`_uscans`、TXT `rs`、UUID、eSCL version、TLS 憑證型態。
- Flatbed：JPEG、PDF、150／300／600 dpi、彩色／灰階／黑白。
- ADF：1 頁、使用者頁數上限、超過上限清理、空紙、卡紙、多頁 PDF。
- capability profile ref、非對稱 X/Y resolution、設備不支援設定時的 UI／錯誤。
- 503、使用者取消、網路中斷、scanner job 被移除、app 切背景。

### Printer matrix

至少兩個品牌，使用 Android Default Print Service 或 Mopria Print Service：

- 手機 PDF、JPEG、PNG 與掃描 multi-page PDF。
- 份數、紙張、方向、彩色／灰階、雙面、頁面範圍。
- 成功、取消、離線、缺紙與服務未啟用。
- 從 DocumentsUI 返回 App，以及 Print Spooler 返回 App。

實體驗收完成前，README／Play Store 不使用「已相容 Canon／Brother／Fujifilm」或「Mopria Certified」字樣。

## 7. 自動測試計畫

目前 JVM tests 涵蓋：

- eSCL namespace／Version／XXE、source profile、format、resolution range、ADF `NumberOfPages`。
- ScannerStatus／JobInfo、Location URL policy。
- DNS-SD TXT parsing、root sanitation、secure deduplication。
- HTTP 201、200、404、503、401、DELETE、TE、PDF／JPEG signature 與錯誤 status。
- Mock Flatbed／ADF 頁數及文件 merge／split 規則。
- 語系 tag、繁／簡中文 script／region 判斷與未知語系 fallback。

後續需補：

- Compose navigation／dialog／large text instrumentation tests。
- Real provider 的 fake NSD 與完整 job state integration tests。
- 10、50 頁 PDF soak、超大圖片、低記憶體與暫存清理。
- 程序死亡、工作持久化、重複啟動、取消 race。
- 真實 TLS、自簽憑證決策與認證挑戰 fixture。

標準驗證指令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
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

目前程式碼完成的是「實作與模擬器 DoD」；M4 的實體 scanner/printer DoD 尚未完成。

## 10. 建議下一步

1. 在同一 Wi-Fi 接上第一台 eSCL MFP，保存匿名化的 capability/status fixture 並執行 Flatbed、ADF 與取消測試。
2. 依真實 capability 將掃描設定 UI 改為動態選項，而非固定 150／300／600 dpi。
3. 驗證 `_uscans` 憑證與 401 challenge，設計使用者確認／認證流程。
4. 建立 Room／WorkManager 或等價持久化設計，解決程序死亡與暫存檔生命週期。
5. 執行第二品牌 scanner 與 printer matrix，再決定 Beta 發布條件。
