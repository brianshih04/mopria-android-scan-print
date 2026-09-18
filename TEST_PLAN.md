# Mopria Android Scan & Print 測試計畫

文件版本：1.2
建立日期：2026-08-12
更新日期：2026-09-17
測試對象：Android App `com.brianshih.mopria.android.scanprint`
目前產品版本：0.1.0
文件負責人：QA Lead

## 1. 目的

本計畫用於驗證 App 從設備探索、eSCL 掃描、文件處理／保存，到 Android 系統列印與 Direct IPP 列印的完整工作流。測試必須同時確認：

- 使用者操作成功，狀態與錯誤訊息正確。
- 掃描檔案與實體列印結果的尺寸、頁數、方向、色彩及內容正確。
- 失敗、取消、斷線及 App 重啟後，不產生損壞文件、卡住的 UI 或無法清理的設備工作。
- 不因 Mock、emulator 或有限機型結果宣稱 Mopria Certified、整個品牌或未測型號相容。

Brother MFC-L2715DW 與 HP LaserJet Pro MFP 3104fdw 已於 2026-09-17 完成 eSCL 掃描；Brother 另以 Android Default Print Service／Mopria（手動 IP）完成一次系統列印，HP 與 Brother 的 Direct IPP 工作均已完成（Brother 於 2026-09-18 修復 IPP HTTP/1.1 掛起後通過）。Brother eSCL 修復後覆蓋 ADF、300／600 dpi、A5／4×6／5×7 與 Grayscale8。這是兩款實機的基準結果，不取代本文件的完整 IPPS、格式／選項／錯誤與 soak matrix。

## 2. 測試範圍

### 2.1 本輪包含

- 安裝、啟動、導覽、Mock／Real 模式與設定保存。
- `_uscan._tcp`／`_uscans._tcp`、`_ipp._tcp`／`_ipps._tcp` 探索。
- mDNS 不可用時，以 IP 或 hostname 手動連接 eSCL 與 Direct IPP。
- 掃描來源、尺寸、解析度、色彩與設備 capability 動態限制。
- Flatbed 單頁／多頁、ADF 單頁／多頁與頁數上限。
- 文件尺寸：Auto、A4、Letter、A5、4×6、5×7、8×10。
- 背景淨化、deskew、auto-crop、blank-page drop。
- ML Kit OCR、語言模型準備與 Searchable PDF。
- 文件縮圖、預覽、旋轉、裁切、排序、刪除、PDF／JPEG 匯出與分享。
- Android Print Framework／Mopria Print Service。
- Direct IPP／IPPS、格式協商、列印選項、工作完成與錯誤處理。
- 多語系、TalkBack、大字體、Dark Mode、手機與平板版面。
- 網路中斷、設備忙碌、低儲存空間、App 背景化／重啟及長時間測試。

### 2.2 不在完成宣稱內

- Mopria 認證測試或 Mopria Certified 宣稱。
- Push Scan、Stored Job Requests。
- ADF duplex UI。
- 使用者名稱／密碼或企業驗證流程。
- 無 Google Play services 環境的 OCR 替代方案。
- 進行中網路工作的 process-death 自動續傳。
- 所有品牌、韌體、紙張與 IPP／IPPS 組合的全面相容性。

上述項目若在測試中出現，應記錄為限制或探索結果，不可自行改寫為已支援功能。

## 3. 人員與責任

| 角色 | 責任 |
|---|---|
| QA Lead | 鎖定 build、安排設備矩陣、確認進入／退出條件、簽署測試報告 |
| Tester | 依案例執行、保存證據、建立 defect、執行修正回歸 |
| Developer | 提供可追溯 APK、log 協助與 defect 修正，不代替 QA 判定實體輸出 |
| Product Owner | 決定非阻斷限制、OCR 品質門檻與 release 風險接受 |

## 4. 測試版本與環境

每輪開始前，QA 必須在報告中填入：

| 欄位 | 值 |
|---|---|
| APK 名稱／SHA-256 | 待填 |
| Git commit／branch | 待填 |
| Build type／version | 待填 |
| 測試開始／結束時間 | 待填 |
| Tester | 待填 |
| 已知問題清單版本 | 待填 |

### 4.1 Android 裝置矩陣

| ID | 裝置 | Android | 用途 | 要求 |
|---|---|---:|---|---|
| AND-01 | ARM 實體手機 | 9／API 28 | 最低版本、舊儲存權限 | 必測 |
| AND-02 | ARM 實體手機 | 13～15 | 主流版本、系統列印 | 必測 |
| AND-03 | ARM 實體手機 | 16／API 36 | target 行為、OCR／OpenCV | 必測 |
| AND-04 | API 36 emulator | 16 | 自動回歸、Direct IPP smoke | 必測；不可取代實體列印服務驗證 |
| AND-05 | ≥600 dp 平板或 foldable | 15～16 | NavigationRail、版面與大字體 | Release 前必測 |

實體 OCR 裝置需有 Google Play services，且需分別執行模型已下載與尚未下載的情境。

### 4.2 Scanner／Printer 矩陣

| ID | 設備 | 必測能力 | 備註 |
|---|---|---|---|
| MFP-01 | Brother MFC-L2715DW | Flatbed、ADF、eSCL HTTP、Android Default Print Service／Mopria、Direct IPP | eSCL、Mopria 系統列印與 Direct IPP 基準完成（Brother Direct IPP 2026-09-18 修復後通過）；IP 以當次測試報告為準 |
| MFP-02 | HP LaserJet Pro MFP 3104fdw | Flatbed、ADF、不同 capability／韌體、Direct IPP | eSCL 與 Direct IPP 基準完成 |
| PRN-03 | 後續 Mopria／IPP printer | 系統列印、IPPS、至少一種 raster format | 擴充 release matrix；也需在 MFP-01／02 補齊未測項目 |
| TLS-01 | 具有效受信任憑證的 `_uscans` 或 `_ipps` 設備 | TLS、hostname 驗證 | 若本輪無設備，列為明確未測風險 |
| TLS-02 | self-signed 或 hostname mismatch 測試端點 | 拒絕不可信連線 | 可使用隔離測試環境，不可加入 trust-all |

所有 Android 裝置與 MFP 應位於同一個區域網路；記錄 SSID、AP client isolation、VLAN、防火牆及 multicast 設定。測試文件不可含真實個資。

### 4.3 測試資料

| ID | 測試資料 |
|---|---|
| DOC-A4 | A4 對位頁：四角標記、10 mm 方格、方向箭頭、彩色色票、英／繁中小字 |
| DOC-LTR | Letter 對位頁，內容同 DOC-A4 |
| DOC-A5 | A5 對位頁，內容同 DOC-A4 |
| PHOTO-46 | 4×6 相片，含膚色、漸層、暗部與高光 |
| PHOTO-57 | 5×7 相片 |
| PHOTO-810 | 8×10 相片 |
| ADF-05 | 5 頁混合文件，頁碼 1～5；含一張輕微傾斜頁 |
| ADF-BLANK | 5 頁文件，中間含一張真正空白頁 |
| OCR-ZHTW | 300 dpi 繁中＋英文文件，附 ground truth 文字 |
| OCR-ZHCN | 300 dpi 簡中＋英文文件，附 ground truth 文字 |
| OCR-JA-KO | 日文與韓文各一頁，附 ground truth 文字 |
| PRINT-PDF | 單頁與 10 頁 PDF，各頁有頁碼、邊界與方向標記 |
| PRINT-IMG | JPEG、PNG 各一張，含透明 PNG |
| BAD-FILE | 損壞或副檔名與內容不符的檔案 |

## 5. 優先級、嚴重度與狀態

### 5.1 案例優先級

- P0：每個候選 build 必測；核心掃描、保存、列印與資料完整性。
- P1：Release 前必測；主要設定、錯誤處理、跨版本／跨品牌。
- P2：完整回歸、品質、相容性與探索測試。

### 5.2 Defect 嚴重度

- S0 Blocker：安全漏洞、資料外洩、裝置／App 無法恢復、廣泛資料損壞。
- S1 Critical：核心掃描或列印流程不可用、頁面遺失／錯序、持續 crash／ANR。
- S2 Major：功能錯誤但有合理 workaround、錯誤選項送往設備、錯誤狀態或明顯品質退化。
- S3 Minor：文案、對齊、截斷或不影響結果的視覺問題。

狀態使用 `Pass`、`Fail`、`Blocked`、`Not Run`、`Not Applicable`。`Blocked` 必須附阻擋原因，不能當作 Pass。

## 6. 進入與退出條件

### 6.1 進入條件

- APK、commit、build type 與簽章來源已鎖定。
- 自動測試、lint 與 debug build 通過；測試數量以當次 Gradle 輸出為準。
- MFP firmware、網路與耗材狀態已記錄；ADF、Flatbed 與出紙匣可使用。
- Android Print Service／Mopria Print Service 已安裝並啟用，用於系統列印案例。
- 測試資料、紙張、相片紙、足夠儲存空間與可比對的 ground truth 已備妥。
- 上一輪 S0／S1 defect 已處理或由 Product Owner 書面接受風險。

### 6.2 Release 退出條件

- 所有 P0 案例 Pass。
- P1 通過率 100%；無開放的 S0／S1，S2 必須有風險決議與回歸範圍。
- MFP-01 與第二品牌完成核心 Flatbed、ADF、系統列印及 Direct IPP matrix。
- A4、Letter、A5、4×6、5×7、8×10 在至少一台支援對應區域的實機完成驗證。
- 繁中與 English 完整 smoke 通過；其餘 8 個語系完成文字、截斷與核心流程抽測。
- ARM 實機完成 OCR model、Searchable PDF 與 OpenCV smoke。
- 高解析／多頁測試無 OOM、ANR、頁面遺失、重複或錯序。
- 測試報告附 build、設備／firmware、輸出檔、實體頁面照片及未測風險。

## 7. 自動化 Pre-flight

在乾淨的 Windows PowerShell 環境執行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --max-workers=1 --no-daemon
.\gradlew.bat :app:lintDebug :app:assembleDebug --max-workers=1 --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --max-workers=1 --no-daemon
git diff --check
```

注意：instrumentation 預設值案例需使用乾淨的 app data；若 emulator 曾執行 Real／Direct IPP 測試，先卸載測試 App 或清除該 package data，再執行 suite。QA 報告需保存命令輸出或測試報告連結。

## 8. 測試案例

### 8.1 安裝、導覽與設定

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| APP-001 | P0 | Clean install 後啟動 App | 無 crash／ANR；首頁可操作；預設 Mock、System print、Document preset |
| APP-002 | P1 | 從上一個候選 APK以 `adb install -r` 升級 | App 可啟動；既有文件與設定保留；無 migration crash |
| APP-003 | P0 | 依序進入 Scan、Print、Documents、History、Settings，使用系統 Back 返回 | 導覽層級正確；不退出到錯誤畫面；無重複頁面 |
| APP-004 | P1 | 切換 Mock／Real，關閉再重開 App | 選擇被保存；忙碌中不可切換造成競態 |
| APP-005 | P1 | 修改掃描尺寸、來源、dpi、色彩、preset、列印方式及手動地址後重啟 | 所有設定按產品規則保存；OCR 關閉時 Searchable PDF 自動關閉 |
| APP-006 | P1 | Mock 模式完成一次掃描、文件預覽、PDF 匯出與列印動作 | 不需要實體設備即可完成 deterministic smoke；畫面不標示為真實相容證據 |

### 8.2 探索與手動連線

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| NET-001 | P0 | Real 模式、手動地址留空；同網路執行 Find devices | 可找到廣播中的 scanner／printer；顯示名稱、類型與協定；UI 不重複卡住 |
| NET-002 | P0 | 輸入 MFP-01 IPv4，再執行 Find devices | 即使 mDNS 不穩定，仍建立 eSCL scanner 與 IPP printer 候選；可繼續掃描／列印 |
| NET-003 | P1 | 輸入可解析 hostname | 結果與 IPv4 等效；地址重啟後保存 |
| NET-004 | P1 | 分別輸入 `http://host`、`host:631`、`host/path`、空白與非法字元 | scheme／port／path 顯示本地化格式錯誤且不送網路請求；空白代表只用探索 |
| NET-005 | P0 | 關閉 multicast 或放到無 mDNS 的測試 WLAN，保留手動 IP | 手動 eSCL／IPP 仍可使用；App 不要求修改系統安全設定 |
| NET-006 | P1 | AP client isolation 開啟或設備離線後搜尋，再恢復網路重試 | 首次顯示可理解錯誤／找不到設備；恢復後不重啟 App 也能重新搜尋成功 |
| NET-007 | P2 | 同一設備同時由 discovery 與手動地址找到 | 清單不應造成使用者無法辨識的重複 endpoint；scanner 與 printer 兩種角色可各顯示一次 |

### 8.3 掃描設定與尺寸

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| SET-001 | P0 | 連接不同 capability 的 scanner | UI 只提供設備支援的來源、dpi、色彩；已保存的不支援值被安全調整 |
| SET-002 | P1 | 選 Document preset | 預設 A4、300 dpi、Color、PDF、Normal background、OCR off |
| SET-003 | P1 | 選 Photo preset | 預設 4×6、600 dpi、Color、JPEG、背景淨化 off、OCR off |
| SET-004 | P0 | 展開尺寸區域 | 文件組顯示 Auto／A4／Letter／A5；相片組顯示 4×6／5×7／8×10；僅一個選項被選取 |
| SET-005 | P1 | 依序選取七個尺寸並重啟 App | 選擇與摘要正確更新並保存，不被 preset 以外的操作意外重設 |
| SET-006 | P1 | 開啟 OCR 後嘗試 600 dpi | OCR 只允許 ≤300 dpi；若 scanner 無可用 ≤300 dpi profile，OCR 安全關閉並顯示合理狀態 |
| SET-007 | P1 | 在 600 dpi 開啟背景淨化，或由 preset 切換造成衝突 | >300 dpi 不執行背景淨化；UI／摘要與實際處理一致 |
| SET-008 | P1 | 選 ADF，調整 page limit 至最小、常用值與 50 | 範圍為 1～設備／App 上限；顯示值與送出的頁數限制一致 |
| SET-009 | P0 | 選 ADF，分別連接只廣告 `AdfSimplexInputCaps` 與同時廣告 `AdfDuplexInputCaps` 的 scanner | UI 顯示 Single-sided／Double-sided；只可選 capability 支援的模式，不支援的 Double-sided 明確停用；選擇可保存，摘要與送出的 `scan:Duplex` 一致 |

尺寸實機驗證時，記錄輸出像素。300 dpi 的參考請求值如下；設備合理 rounding 容許 ±2%，超出時須附 capabilities 與原始檔分析：

| 尺寸 | 300 dpi 參考像素 | 實體尺寸 |
|---|---:|---:|
| A4 | 2480 × 3508 | 210 × 297 mm |
| Letter | 2550 × 3300 | 8.5 × 11 in |
| A5 | 1748 × 2480 | 148 × 210 mm |
| 4×6 | 1200 × 1800 | 4 × 6 in |
| 5×7 | 1500 × 2100 | 5 × 7 in |
| 8×10 | 2400 × 3000 | 8 × 10 in |

### 8.4 Flatbed 與相片掃描

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| SCN-FB-001 | P0 | MFP-01；DOC-A4；Flatbed／A4／300 dpi／Color／PDF | 成功取得 1 頁；方向、內容、色彩、頁數正確；完成後 scanner 回 Idle，文件可重開 |
| SCN-FB-002 | P1 | 同上，尺寸改 Auto | Scanner 決定區域；App 不送固定 region；結果無 crash、截斷或空白 |
| SCN-FB-003 | P0 | DOC-LTR；Letter／300 dpi | 輸出接近 2550×3300；四角與方向標記正確；不誤裁成 A4 |
| SCN-FB-004 | P0 | DOC-A5；A5／300 dpi | 輸出接近 1748×2480；多餘平台區域不應取代指定範圍 |
| SCN-FB-005 | P1 | DOC-A4；150 dpi／Grayscale 與 Black-and-White 各一次 | 實際位深／外觀符合選擇；小字仍可辨識；metadata 正確 |
| SCN-FB-006 | P1 | DOC-A4；600 dpi／Color | 成功或明確 capability error；不可 OOM；不應執行 >300 dpi 的 OCR／背景淨化 |
| SCN-FB-007 | P0 | Flatbed PDF；連續掃描 3 頁，前兩頁按 Next page，最後按 Finish PDF | 產生 1 份 3 頁文件，順序正確；每頁為獨立 eSCL job；無重複或漏頁 |
| SCN-FB-008 | P1 | 上一案例第 2 頁後離開再回 App | pending Flatbed session 可恢復或明確讓使用者完成；既有頁不可損壞 |
| SCN-PH-001 | P0 | PHOTO-46；4×6／600 dpi／Color／JPEG | 比例 2:3、方向與色彩正確；輸出接近 2400×3600（600 dpi） |
| SCN-PH-002 | P1 | PHOTO-57；5×7／300 dpi／Color／JPEG | 輸出接近 1500×2100；無非預期拉伸 |
| SCN-PH-003 | P1 | PHOTO-810；8×10／300 dpi／Color／JPEG | 輸出接近 2400×3000；若超過 scanner 可掃區域，回本地化 capability error 且清理 job |

### 8.5 ADF 掃描

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| SCN-ADF-001 | P0 | ADF 放入 1 頁；A4／300 dpi／Color／PDF | 取得 1 頁並完成；ADF／job 狀態恢復可用 |
| SCN-ADF-002 | P0 | ADF-05；page limit 5 | 取得 5 頁、順序與頁碼正確；單一文件 5 頁；無重複／漏頁 |
| SCN-ADF-003 | P1 | ADF 放 10 頁，page limit 3 | App 只保存前 3 頁並停止／清理工作；設備不持續保留 active job |
| SCN-ADF-004 | P0 | ADF 空紙時開始掃描 | 顯示本地化 ADF not ready；不建立空文件；可放紙後重試 |
| SCN-ADF-005 | P1 | ADF-05；啟用 deskew | 傾斜頁被校正，其他頁不被明顯過度旋轉；原始內容不缺角 |
| SCN-ADF-006 | P1 | ADF-BLANK；啟用 drop blank pages | 真空白頁被移除、內容頁保留且頁碼重排；未啟用時 5 頁全部保存 |
| SCN-ADF-007 | P1 | 掃描中製造可恢復卡紙／開蓋／缺紙狀態 | App 顯示失敗，不保存損壞的半頁；設備 job 可取消／清理，排除故障後可重試 |
| SCN-ADF-008 | P2 | 50 頁、300 dpi、PDF；設備能力允許時執行 | 不 OOM／ANR；頁數、順序完整；耗時、APK PSS、檔案大小與失敗點均記錄 |
| SCN-ADF-009 | P1 | 使用廣告 `AdfDuplexInputCaps` 的 MFP；同一組雙面測試稿分別選 Single-sided／Double-sided | Single-sided 每張只取正面；Double-sided 每張正反面均保存且頁序正確；duplex job 使用 duplex capability profile 並送出 `<scan:Duplex>true</scan:Duplex>` |

### 8.6 影像處理、OCR 與 Searchable PDF

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| IMG-001 | P1 | 同一頁分別以背景淨化 Off／Light／Normal／Strong 掃描 | 背景逐級變白；淡色內容不應在 Normal 明顯消失；每份結果可開啟 |
| IMG-002 | P1 | 平台上放置小於掃描區域的文件，開／關 auto-crop 比較 | 開啟時裁到文件邊界且不切字；關閉時保留完整掃描區域 |
| IMG-003 | P2 | 以低對比、黑底、彩色照片執行 auto-crop／background cleanup | 失敗時保留可用 source，不產生空白或損壞檔；記錄不適用樣本 |
| OCR-001 | P0 | ARM＋Google Play services；準備 Latin／Chinese model，掃描 OCR-ZHTW | 模型狀態可理解；OCR 完成且不阻塞 UI；結果文件可重新開啟 |
| OCR-002 | P1 | 首次模型尚未下載，分別在線與離線開始準備 | 在線可完成或顯示進度；離線顯示可重試錯誤，不使一般非 OCR 掃描失效 |
| OCR-003 | P1 | OCR-ZHTW／OCR-ZHCN；300 dpi；各跑 3 次 | 計算 CER；建議 clean sample gate：Latin CER ≤5%、繁／簡中 CER ≤10%；若產品未簽署門檻則列為測量值，不宣稱 accuracy |
| OCR-004 | P2 | 準備日文／韓文 model 與必要字型，掃描 OCR-JA-KO | model／font 僅按需取得；文字可抽取；缺字、亂碼與 fallback 字型須記錄 |
| PDF-S-001 | P0 | OCR 開啟後啟用 Searchable PDF，輸出 OCR-ZHTW | PDF viewer 外觀保留原掃描影像；搜尋／選取可找到 ground truth 關鍵字；頁數與方向正確 |
| PDF-S-002 | P1 | OCR 關閉時檢查 Searchable PDF | 選項不可啟用或自動關閉；不默默輸出假 Searchable PDF |
| PDF-S-003 | P1 | 移除必要 OCR layout／字型條件後輸出 | 顯示 missing OCR／font 的本地化錯誤；不降級成一般 PDF 假裝成功 |
| PDF-S-004 | P2 | 50 頁 Searchable PDF 壓力測試 | 可完成且文字可抽取；無 OOM／ANR；peak PSS 目標 <256 MiB，記錄耗時與輸出大小 |

### 8.7 文件、編輯、匯出與分享

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| DOC-001 | P0 | 完成單頁與多頁掃描，進入 Documents | 縮圖、名稱、頁數、來源、dpi、色彩正確；點縮圖可看完整預覽 |
| DOC-002 | P1 | 對 3 頁文件執行旋轉、裁切、排序 | 預覽立即反映；匯出與列印使用相同旋轉／裁切／順序；重啟後仍保存 |
| DOC-003 | P1 | 刪除中間頁，再刪除最後一頁／整份文件 | 頁碼正確重排；不留下不可開啟項目；只刪除指定文件的 app-controlled 檔案 |
| DOC-004 | P0 | Force stop／重啟裝置後重開 App | 文件 metadata、Flatbed session 與 OCR sidecar 可恢復；工作紀錄不要求跨 process 保存 |
| EXP-001 | P0 | 將單頁與多頁文件輸出 PDF | 檔案出現在預期位置；可由至少兩個 PDF viewer 開啟；頁數、方向、裁切正確 |
| EXP-002 | P0 | 輸出 JPEG | 每頁產生一個可解碼 JPEG；檔名不衝突；頁數與順序正確 |
| EXP-003 | P1 | 使用 Sharesheet 分享 PDF | 接收 App 可讀取 URI；App 不暴露任意內部檔案路徑 |
| EXP-004 | P1 | Android 9 與 Android 10+ 分別輸出 | Android 9 僅在需要時要求權限；Android 10+ 不要求不必要的舊儲存權限 |
| EXP-005 | P1 | 製造儲存空間不足再匯出 | 顯示失敗，不留下標示成功的零位元／損壞檔；釋放空間後可重試 |

### 8.8 Android 系統列印

系統列印必須在有可用 Print Service 的實體 Android 裝置執行；emulator 只做導覽 smoke，不作為系統列印相容證據。

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| SYS-PRT-001 | P0 | Print method=System；從 Documents 列印掃描 PDF | 開啟 Android print preview；選擇 MFP 後成功出紙；頁數、方向、裁切正確 |
| SYS-PRT-002 | P0 | 從手機選擇 PRINT-PDF、JPEG、PNG 列印 | DocumentsUI 返回正確；三種檔案可進 preview 並列印；不支援檔案有明確錯誤 |
| SYS-PRT-003 | P1 | 在 print preview 取消／按 Back | 返回 App 合理頁面；不新增 Completed 工作；App 可繼續操作 |
| SYS-PRT-004 | P1 | 在系統 preview 選紙張、色彩、雙面、份數 | 實體輸出符合 Print Service 選項；App 不覆寫系統選擇 |
| SYS-PRT-005 | P1 | Print Service 未啟用或裝置沒有 print Activity | 顯示可理解錯誤；App 不 crash；可返回 Settings 改用 Direct IPP |

### 8.9 Direct IPP／IPPS

每一個成功案例都必須同時保存 App History／log 與實體出紙照片。只有 `job-state=completed`、但未確認出紙，不算完整 Pass。

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| IPP-001 | P0 | Print method=Direct IPP；用 discovery 或手動 IP 找到 MFP-01 | Get-Printer-Attributes 成功；顯示 printer 實際公布的選項；未公布選項不送出 |
| IPP-002 | P0 | 列印 SCN-FB-001 的單頁掃描文件 | App 工作達 Completed 且實際出紙；內容、方向、A4 大小、邊界正確 |
| IPP-003 | P0 | 列印 3～10 頁 PDF | 所有頁依序出紙；無漏頁／重複；單一／多文件 job 行為符合 printer capability |
| IPP-004 | P1 | 分別列印 JPEG、PNG、PDF | 選擇設備支援且 App 可產生的格式；必要時使用 PWG-Raster／PCLm；輸出外觀正確 |
| IPP-005 | P1 | 設備有公布時，測 copies、media、sides、color、quality、orientation | 只顯示／送出支援值；實體結果符合選擇；取消 options sheet 不建立 job |
| IPP-006 | P0 | 驗證 options sheet 最底部 Cancel／Print | 手機、手勢導航、三鍵導航及大字體下皆可點擊，不被 system navigation bar 遮住 |
| IPP-007 | P1 | Printer 離線、錯誤 IP、631 blocked | 顯示 no printer／network error；不 silent fallback 到 System print；可重試 |
| IPP-008 | P1 | 列印中斷 Wi-Fi 或讓設備不完成 job | timeout 後執行 best-effort Cancel-Job；History 不標示 Completed；恢復後可新建 job |
| IPP-009 | P1 | Printer 回報 canceled／aborted／unsupported format | 對應本地化失敗；不把 Send-Document HTTP 成功誤判為列印完成 |
| IPP-010 | P1 | `_ipps` 使用有效系統信任憑證與正確 hostname | TLS 列印成功；仍完成實體輸出驗證 |
| IPP-011 | P0 | self-signed、過期憑證或 hostname mismatch | 連線被拒絕並顯示錯誤；不可 trust-all、跳過 hostname 或自動降級 HTTP |
| IPP-012 | P2 | A4 300 dpi、10 頁 PWG-Raster／PCLm soak | 無 OOM／ANR；頁數完整；記錄格式、峰值 PSS、耗時及設備 job history |

### 8.10 中斷、恢復與資源限制

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| RES-001 | P0 | eSCL 下載中關閉 Wi-Fi | 顯示掃描失敗；不保存半張損壞頁；暫存檔與設備 job 最終被清理 |
| RES-002 | P1 | Scanner 正在處理其他工作時開始掃描 | 顯示 scanner not ready／busy；不無限 loading；稍後可重試 |
| RES-003 | P1 | 掃描／處理中將 App 切背景再回前景 | 可繼續或明確失敗；UI 狀態不重複送 job；完成文件只有一份 |
| RES-004 | P1 | 進行中強制結束 App，再重新啟動 | 目前不要求續傳；但 App 可啟動、既有完成文件不損壞、設備沒有永久卡住 job |
| RES-005 | P1 | 快速連按 Scan／Print 或旋轉螢幕 | 忙碌狀態阻止重複 job；不產生雙份文件／列印 |
| RES-006 | P2 | 連續執行 20 次單頁掃描＋20 次單頁 Direct IPP | 無逐次惡化、OOM、ANR 或設備 job 堆積；失敗率、PSS 與耗時趨勢被記錄 |

### 8.11 多語系、外觀與無障礙

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| UI-001 | P0 | English 與繁中各走一次完整 scan→document→export→print | 所有使用者可見文字正確；無 raw exception、未翻譯 key 或混用語言 |
| UI-002 | P1 | ja、ko、es、pt、de、fr、ru、zh-CN 各走 APP-003＋SCN-FB-001 smoke | 無缺字、截斷、重疊；錯誤訊息與按鈕可理解 |
| UI-003 | P1 | 系統語言不在支援清單，App language=System | 使用 English fallback；切換 App 語言後立即或重啟正確套用 |
| UI-004 | P1 | 字體 200%、Display size 最大、三鍵／手勢導航 | Scan 與 Print options 可捲動；主要按鈕可見且可點；無被 system bars 遮住 |
| UI-005 | P1 | TalkBack 操作核心流程 | 導覽、radio、chip、slider、縮圖與圖示有角色／標籤；焦點順序合理 |
| UI-006 | P1 | Light／Dark mode | 文字、選取、錯誤、disabled 狀態可辨識；掃描預覽不被 theme 改色 |
| UI-007 | P1 | 手機直／橫向與 ≥600 dp 平板 | 手機用底部導覽、平板用 NavigationRail；內容寬度與 dialog／sheet 不溢出 |

### 8.12 安全與隱私回歸

| ID | Pri | 條件／步驟 | 預期結果 |
|---|---:|---|---|
| SEC-001 | P0 | 惡意 eSCL fixture 回傳 DOCTYPE／external entity | XML 被拒絕；不讀取本機檔案、不對外發 request |
| SEC-002 | P0 | Scan job／redirect URL 改為跨 host、降級、query、fragment、`..` traversal | App 拒絕 URL；不向攻擊端點傳輸；顯示一般安全失敗訊息 |
| SEC-003 | P0 | 執行 IPP-011 與不可信 `_uscans` | 使用 Android system trust store；不信任自簽或 hostname mismatch |
| SEC-004 | P1 | 檢查 backup／data extraction 與分享 URI | 掃描原稿不進 backup；只有使用者選擇的文件能透過受控 URI 分享 |
| SEC-005 | P1 | 收集 logcat、測試報告與 capabilities fixture | 不含掃描內容、認證資訊或不必要個資；設備 ID／IP 依團隊規則去識別 |

## 9. 執行順序

建議每個候選 build 依下列順序執行；前一階段有 P0 Fail 時停止後續耗材型測試：

1. 自動化 Pre-flight。
2. APP／NET／SET 冒煙測試。
3. MFP-01 Flatbed A4 與 Direct IPP 單頁 P0。
4. ADF、多頁、尺寸與文件／匯出回歸。
5. 系統列印與完整 Direct IPP matrix。
6. ARM OCR／Searchable PDF。
7. 擴充其他機型、IPPS、安全與中斷測試。
8. 多語系、無障礙、平板及 soak。
9. Defect fix verification 與受影響範圍回歸。

## 10. 證據與 Defect 格式

每個實機 Scan／Print 案例至少附：

- Case ID、Pass／Fail、時間、tester。
- APK SHA-256、commit、Android 裝置／版本。
- MFP 品牌、型號、firmware、連線協定、IP／hostname、discovery 或 manual。
- 完整掃描／列印設定。
- App 操作前後截圖與 History 狀態。
- 掃描原始輸出、頁數、像素尺寸、檔案大小與 SHA-256。
- 實體列印頁照片；需要尺寸驗證時加入尺規或測試格線。
- 失敗案例的重現率、logcat 時段、設備面板／job log 與是否可恢復。

Defect 標題格式：

```text
[Area][Case ID][MFP/Android] 簡短問題描述
```

Defect 內容必須包含 Expected、Actual、Steps、Frequency、Build、Environment、Evidence、Workaround 與 Severity。不得在 issue 附上受限制的 Mopria eSCL 規格 PDF或含個資的掃描原稿。

## 11. 測試報告摘要範本

```text
Build / commit:
APK SHA-256:
Test window:
Android devices:
Scanner / printer / firmware:

P0: Pass __ / Fail __ / Blocked __ / Not Run __
P1: Pass __ / Fail __ / Blocked __ / Not Run __
P2: Pass __ / Fail __ / Blocked __ / Not Run __

Open defects: S0 __ / S1 __ / S2 __ / S3 __
Hardware protocols covered: eSCL HTTP __ / eSCL HTTPS __ / IPP __ / IPPS __
Brands covered:
Not-tested risks:
Release recommendation: Go / Conditional Go / No-Go
QA Lead:
Product Owner decision:
```

## 12. 最小每日 Smoke

時間不足時仍必須完成以下 P0 路徑：

1. Clean launch 與 Settings 保存。
2. Real mode 找到 MFP-01；若 mDNS 不可用，以手動 IP 連接。
3. Flatbed A4／300 dpi／Color 掃描一頁，完成 PDF 並重開預覽。
4. Flatbed 連續兩頁，Finish PDF 後確認頁數與順序。
5. ADF 一頁；空紙錯誤。
6. 4×6 相片尺寸掃描。
7. 匯出 PDF 與 JPEG。
8. 實體 Android 系統列印一頁。
9. Direct IPP 列印同一份掃描文件，確認 job completed 與實際出紙。
10. 斷線後恢復並重新搜尋設備。
