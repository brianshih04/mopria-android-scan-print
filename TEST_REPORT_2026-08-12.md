# Mopria Android Scan & Print 測試報告

狀態：現有環境執行完成；待外部設備／修正項目明列為 Blocked 或 Not Run
測試計畫：`TEST_PLAN.md` v1.1
測試日期：2026-08-12（Asia/Taipei）

## 1. Build 與環境

| 欄位 | 值 |
|---|---|
| Branch | `main` |
| HEAD commit | `19cc58e536ccc2c55d3b0a80ca9e16a490874192` |
| Worktree | Dirty；本 APK 包含尚未 commit 的掃描尺寸、ADF simplex／duplex、手動地址與 Brother interoperability 修改 |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK SHA-256 | `E281B9C6BFF205B66A71D2F715C58B8ED08DFCB65640FE8F8713298EB40F5A03` |
| ADF 實驗 APK SHA-256 | `B1DF560748D86FA2D4CDD1F8046FDBFEC6ACE04B68752CD0664A036983E037D9`；App 端回歸失敗，namespace 假設已撤回，不作為候選版本 |
| 目前 APK SHA-256 | `C23C15D6CA08B04BA90B756207C2975D8B3E8AD8ABFF90AC0FEFB1782F8DCF18`；包含本輪產品測試修正與 capability-aware ADF simplex／duplex 選項 |
| Android | `mopria_test` AVD；Android 16／API 36；`emulator-5554` |
| MFP | Brother MFC-L2715DW series；`10.1.121.175` |
| eSCL | HTTP 200；version 2.63；ScannerState=Idle；ADF 狀態可正確回報 Empty／Loaded |
| IPP | `http://10.1.121.175:631/ipp/print`；HTTP 200 |
| MFP capability 摘要 | Color：BlackAndWhite1／Grayscale8／RGB24；解析度：100／200／300／600 dpi |

本報告目前只涵蓋 API 36 emulator 與 MFP-01。實體 Android、第二品牌、IPPS、實體輸出品質與其他 Android 版本尚未執行。

## 2. Automated pre-flight

| Gate | 結果 | 證據 |
|---|---|---|
| `:app:testDebugUnitTest` | Pass | 修正前 171 tests；修正後 175 tests、0 failures、0 errors |
| `:app:lintDebug` | Pass | Gradle BUILD SUCCESSFUL |
| `:app:assembleDebug` | Pass | Gradle BUILD SUCCESSFUL |
| `:app:connectedDebugAndroidTest` | Pass | 修正前 31／31；修正後 34／34，新增 A4 MediaBox、Activity context 與 options inset regression |
| `git diff --check` | Pass | 無 whitespace error；僅有 Windows line-ending warning |

Instrumentation 在 package 未安裝的乾淨狀態執行；測試前已備份 11 個 App 內部檔案，測後安裝最新 APK 並完整還原。

### 2.1 整體結果與 release 判定

| 優先級 | Pass | Fail | Blocked | Not Run | Total |
|---|---:|---:|---:|---:|---:|
| P0 | 15 | 10 | 6 | 3 | 34 |
| P1 | 24 | 10 | 10 | 12 | 56 |
| P2 | 2 | 0 | 2 | 3 | 7 |
| **合計** | **41** | **20** | **18** | **18** | **97** |

- 97／97 個計畫案例皆已歸檔狀態，沒有遺漏案例。
- Open defects：S0 0、S1 1、S2 10、S3 0。
- Hardware protocols covered：eSCL HTTP partial、IPP physical partial；eSCL HTTPS／IPPS 未涵蓋。
- 原始測試結論：**No-Go**。下列表格保留修正前證據；修正後 System Print 已解除，但 S1 ADF interoperability（QA-006）及 Direct IPP 實體尺寸複測仍阻擋 release。

## 3. Manual／network 執行結果

| Case | 狀態 | 結果與證據 |
|---|---|---|
| APP-001 | Pass | Debug APK streamed install 成功；cold launch 1.221 s；首頁、底部導覽與預設 Mock／System／Document 狀態正常 |
| APP-002 | Pass | 多次以 `adb install -r` 在原 APK、ADF 實驗 APK與恢復版 APK之間覆蓋安裝；App 可啟動，既有文件與 SharedPreferences 保留，無 migration crash |
| APP-003 | Pass | 已依序進入 Print、Scan、Documents、History、Settings；每個畫面使用系統 Back 均正確返回 Home，無錯頁、重複頁或意外退出 |
| APP-004 | Pass | Mock 切換 Real 成功；force-stop／cold launch 後仍為 `integration_mode=Real` |
| APP-005 | Pass | 由 UI 設為 Real／Photo／Flatbed／600 dpi／Grayscale／4×6／Direct IPP／hostname，force-stop 後 SharedPreferences 與 Home 摘要全部保存；OCR Disabled 且 Searchable PDF false |
| APP-006 | Fail | Mock 模式可完成單頁掃描、文件預覽與 PDF 匯出，文件明確標示 Mock；但改回 System print 後按 Print 顯示 `Could not open system printing: Can print only from an activity`，未開啟 Android print preview。見 QA-008 |
| NET-001 | Not Run | 尚未以可傳遞 multicast 的實體 Android 驗證 `_uscan/_uscans/_ipp/_ipps` |
| NET-002 | Pass | 輸入 `10.1.121.175` 後找到 2 個角色候選，UI 顯示 `eSCL connected`；重啟後地址仍保存 |
| NET-003 | Fail | 使用 `BRWCC5EF8E21A14` 搜尋會建立 2 個候選且 force-stop 後地址仍保存；Windows host 可解析至 `10.1.121.175`，但 emulator 由 App 實際掃描時無法連線，只顯示 generic scan failed。改回 IPv4 後立即成功。候選未驗證問題併入 QA-007 |
| NET-004 | Pass | UI 依序輸入 `http://host`、`host:631`、`host/path`、`bad..host` 均立即顯示本地格式錯誤；清空後錯誤消失並代表只用探索。domain tests 同時覆蓋 query、空 label 與前置連字號 |
| NET-005 | Pass | emulator 環境未取得 multicast 設備；加入手動 IP 後 eSCL／IPP endpoint 可用 |
| NET-006 | Fail | 手動地址改為不可達的保留測試 IP `192.0.2.1`；搜尋期間先顯示 Scanner not found，但完成後錯誤顯示 `Scanner ready／Devices Ready`，沒有驗證 endpoint。改回真實 hostname 後無需重啟即可再次找到 2 個角色。見 QA-007 |
| NET-007 | Blocked | emulator 無法接收 LAN multicast，無法建立「discovery 與手動地址同時找到」前提；需實體 Android 執行 |
| SET-001 | Pass | Discover 完成後 Scan UI 顯示 Brother 公布的 100／200／300／600 dpi，而非 Mock 預設 150／300／600；色彩 capability 取得成功 |
| SET-002 | Pass | Document preset 寫入 A4／300 dpi／Color／Normal background／OCR Disabled／Searchable PDF false；UI 與 SharedPreferences 一致 |
| SET-003 | Pass | Photo preset 寫入 4×6／600 dpi／Color／background Disabled／OCR Disabled／Searchable PDF false；UI 與 SharedPreferences 一致 |
| SET-004 | Pass | UI 同時顯示 Auto、A4、Letter、A5、4×6、5×7、8×10，分組與單選狀態正常 |
| SET-005 | Pass | 七個尺寸均可由 UI 選取，SharedPreferences 依序正確保存 Auto／A4／Letter／A5／Photo4x6／Photo5x7／Photo8x10；force-stop 後代表值仍保留，摘要不被非 preset 操作重設。實際掃描尺寸不生效另計 QA-001 |
| SET-006 | Pass | Photo 600 dpi 狀態開啟 OCR 後自動調整為 300 dpi；OCR 啟用期間再點 600 dpi 不會突破 300 dpi 限制，Searchable PDF 仍依使用者選擇保持 false |
| SET-007 | Pass | Document／300 dpi 時 background=Normal 且控制可見；切至 600 dpi 後立即改為 Disabled，背景淨化控制隱藏，避免 >300 dpi 執行 enhancer |
| SET-008 | Pass | ADF page-limit slider 以拖曳驗證最小 1 與最大 50；中間值也可設定，label 與 `scan_max_pages` SharedPreferences 同步 |
| SET-009 | Pass | Mock capability 下 Single-sided／Double-sided 均可選，選擇與摘要同步；切換至 Brother L2715DW 後，實機 capability 只含 `AdfSimplexInputCaps`，App 自動 reconcile 回 Single-sided，Double-sided 保留顯示但停用並附不支援說明。protocol tests 驗證 duplex capability 使用自己的 profile 並只在雙面 job 送出 `<scan:Duplex>true</scan:Duplex>`；`scan_adf_mode` persistence instrumentation 通過 |
| SCN-FB-001 | Fail | eSCL transport 與 1 頁保存成功，scanner 回 Idle；但 JPEG 為 1680×2193，和 A4／300 dpi 參考 2480×3508 差異超過 ±2%。影像可解碼、24-bit RGB、609,457 bytes。見 QA-001 |
| SCN-FB-002 | Pass | Auto／300 dpi 以目前 Flatbed 原稿執行，App 不送固定 region；成功取得可解碼影像、無 crash／空白／截斷，scanner 自行回傳 1680×2193／200 dpi。requested 與 actual dpi metadata 不一致仍見 QA-001 |
| SCN-FB-003 | Fail | Letter／300 dpi region smoke 回傳 1680×2193／200 dpi，而非接近 2550×3300；本輪使用同一 4×6 原稿，未執行 DOC-LTR 四角內容檢查，但僅尺寸條件已足以判 Fail。見 QA-001 |
| SCN-FB-004 | Fail | A5／300 dpi region smoke 回傳 1680×2193／200 dpi，而非接近 1748×2480；本輪使用同一 4×6 原稿，僅尺寸條件已足以判 Fail。見 QA-001 |
| SCN-FB-005 | Fail | 依 Brother capability 將計畫中的 150 dpi 調整為最近的 200 dpi，分別請求 Grayscale8 與 BlackAndWhite1；兩次皆成功保存 JPEG，但實際檔案仍為 1680×2193、200 dpi、24-bit RGB 彩色影像。9,240 點取樣中僅約 83.5% 為 R=G=B，最大色版差 203～205，目視也保留紅、黃、藍色；App metadata 卻分別標示 Grayscale8／BlackAndWhite1。見 QA-001 |
| SCN-FB-006 | Fail | 4×6／600 dpi／Color 掃描完成、可預覽且沒有 OOM／ANR，但設備實際只回傳 1680×2193／200 dpi，因此解析度條件失敗。見 QA-001 |
| SCN-FB-007 | Pass | 兩個獨立 Platen job 完成，App 依序顯示 Page 1／2 complete；Finish PDF 後產生 1 份 2 頁文件。兩個 JPEG hash 不同、皆可解碼且順序正確；PDF 可由 Poppler 解析為 2 pages。尺寸與 PDF 版面仍重現 QA-001～QA-003 |
| SCN-PH-001 | Fail | Real／Flatbed／Photo 4×6／600 dpi／Color，App 成功建立 1 頁文件且影像有原稿內容；但原始 JPEG 僅 1680×2193、200 dpi、24-bit RGB、100,864 bytes，比例 0.7661，並包含整個 Letter-like 平台與大量白邊。未接近 2400×3600／2:3，證實 Brother 未採用 4×6 ScanRegion 與 600 dpi。見 QA-001 |
| SCN-PH-002 | Fail | 5×7／300 dpi region smoke 仍回傳 1680×2193／200 dpi，而非接近 1500×2100；使用同一 Flatbed 原稿驗證 region，未另換 PHOTO-57，但尺寸條件已失敗。見 QA-001 |
| SCN-PH-003 | Fail | 8×10／300 dpi region smoke 仍回傳 1680×2193／200 dpi，而非接近 2400×3000；設備未回 capability error，直接忽略 region。使用同一 Flatbed 原稿，尺寸條件已失敗。見 QA-001 |
| SCN-ADF-001 | Fail | 開始前 Brother 回報 `ScannerAdfLoaded`，App 設定與 metadata 都標示 `Feeder`，eSCL job 最終也為 Completed；但 Brother 沒有從 ADF 取紙。隔離測試移除 Flatbed 原稿、關閉上蓋且保持 ADF loaded，App 仍產生 63,968-byte、1680×2193 的近全白 Flatbed 影像，ADF 紙張未移動。見 QA-006 |
| SCN-ADF-001-R1 | Fail | 修正版 App 仍未從 ADF 取紙，產生 63,959-byte、1680×2193 的空白 Flatbed JPEG，但 metadata 錯誤標成 Feeder。設備在工作期間暫時回報 `ScannerAdfEmpty`，約 8 秒後回到 `ScannerAdfLoaded`，因此不可用瞬間 Empty 狀態判定已進紙 |
| SCN-ADF-CTRL-01 | Pass | 使用同一份測試稿直接從 Brother 面板執行單張 Copy，可正常由 ADF 取紙並輸出；排除 ADF 感測器、滾輪、紙張方向與基本機構故障 |
| SCN-ADF-CTRL-02 | Pass | Windows `Brother MFC-L2715DW LAN` WIA driver 設定 Feeder／1 page／A4／300 dpi 後，正常由 ADF 取紙並取得 2480×3508、24-bit RGB、約 26 MB JPEG；人工檢視為完整原稿，ADF 最終保持 Empty。證明同一 LAN 的原廠網路掃描可用，但此控制組不是 eSCL client |
| SCN-ADF-002 | Blocked | 待 SCN-ADF-001-R1 通過後執行多頁 ADF 測試 |
| SCN-ADF-003 | Blocked | 需先修復 SCN-ADF-001 的實機 ADF 取紙問題，才能驗證 10 張原稿、page limit 3 與 job cleanup |
| SCN-ADF-004 | Pass | Brother Status 回報 `ScannerAdfEmpty`；App 顯示本地化 feeder empty/not ready 訊息，未建立空文件；UI 可繼續操作 |
| SCN-ADF-009 | Blocked | capability-aware UI／XML 契約已由 Mock、Brother simplex-only capability 與自動測試驗證；目前沒有廣告 `AdfDuplexInputCaps` 的實體 MFP，且 Brother ADF 互通仍受 QA-006 阻擋，故未宣稱實體雙面掃描相容 |
| DOC-001 | Pass | 文件庫顯示 1 頁、eSCL／Platen／300 dpi／RGB24；縮圖與原始 JPEG 可開啟，內容和彩色色票完整 |
| DOC-004 | Pass | 完成單頁與兩頁掃描、PDF／JPEG 匯出後 force-stop 並 cold launch；文件、頁數與已保存項目可重新載入，無損壞或 crash |
| EXP-001 | Fail | PDF 可由 Poppler 解析與 render，1 page、未加密、3,737,583 bytes；但 MediaBox 為 Letter 612×792 pt，且底部 title／metadata 重疊。見 QA-002、QA-003 |
| EXP-002 | Pass | JPEG export 成功；609,457 bytes、1680×2193、24-bit RGB；SHA-256 與 app 內掃描 JPEG 相同，可正常解碼 |
| EXP-003 | Pass | Mock PDF 開啟 Android Sharesheet 成功；Chooser 顯示正確檔名，系統授予 `content://com.brianshih.mopria.android.scanprint.fileprovider/shared_scans/...pdf` read URI permission，未暴露 `file://` 或任意內部路徑 |
| IPP-001 | Pass | Get-Printer-Attributes 成功並開啟 Direct IPP options；設備未公布的值顯示為不可選，未在 UI 假造選項 |
| IPP-002 | Fail | 使用鍵盤焦點提交後，History 顯示 `Completed`／`Print job completed`，且人工確認 Brother 正常出紙；但實體列印內容比 A4 原稿明顯縮小。見 QA-005 |
| IPP-006 | Fail | options sheet 的 Cancel／Print bounds 為 y=2257～2383，落入 2400 px emulator 的 gesture navigation 區；座標點擊連續兩次觸發 Android Overview／Screenshot，鍵盤 Tab＋Enter 才能提交。見 QA-004 |
| SCN-FB-008 | Pass | Mock Flatbed 完成第 1 頁後保存 pending document ID；force-stop／cold launch 後重新進入 Scan，仍顯示「第 1 頁完成」並可繼續或完成 PDF；完成後 pending 狀態清除。|
| SCN-ADF-005 | Blocked | deskew instrumentation 通過；但 Brother eSCL ADF 仍不取紙（QA-006），無法做 5 張實體 ADF 完整性驗證。|
| SCN-ADF-006 | Blocked | blank-page instrumentation 通過；但 QA-006 阻擋實體 ADF blank-page 流程。|
| SCN-ADF-007 | Not Run | 現有 MFP 未建立可控制的 ADF jam／中途缺紙條件。|
| SCN-ADF-008 | Blocked | OpenCV 10-page A4／300 dpi stress 通過；50 張實體 ADF soak 受 QA-006 阻擋。|
| IMG-001 | Pass | Background enhancer 的 Off／Light／Normal／Strong instrumentation 輸出皆可解碼且結果不同；JPEG fixture 驗證背景變白並保留藍／紅內容。|
| IMG-002 | Pass | Auto-crop instrumentation 成功偵測頁框並縮小輸出範圍。|
| IMG-003 | Pass | OpenCV 10-page A4／300 dpi stress 與 Mat release tests 通過，無逐頁累積。|
| OCR-001 | Blocked | 現有 API 36 AVD 為 x86_64，不符合 ARM＋Google Play services 條件；僅完成 OCR layout／invalid-source 元件測試。|
| OCR-002 | Not Run | 缺少低品質／傾斜／低對比 ground-truth 原稿與 ARM OCR runtime。|
| OCR-003 | Blocked | 需要 ARM 裝置、實際模型與 ground-truth 才能量測 CER。|
| OCR-004 | Not Run | 尚未在可下載 JP／KR font/model 的支援裝置執行離線與下載失敗流程。|
| PDF-S-001 | Blocked | 合成 English OCR layout 的 instrumentation 可輸出可擷取 invisible text layer；但缺少 ARM 上實際 OCR-ZHTW end-to-end 輸出。|
| PDF-S-002 | Pass | OCR 關閉時 Searchable PDF 被停用；缺 OCR layout 的 opt-in 會回 `MissingOcrLayout`，不靜默輸出普通 PDF。|
| PDF-S-003 | Pass | OCR sidecar 可跨 process death 恢復；sidecar 缺失時不捏造 OCR result。|
| PDF-S-004 | Pass | 50-page Searchable PDF instrumentation 完成 50 頁輸出，peak PSS 未超過 256 MiB gate，無 OOM／ANR。|
| DOC-002 | Fail | 三頁 Mock 文件的裁切、旋轉 90°、排序立即保存，重啟後 metadata 正確；匯出 PDF 頁序正確，但旋轉頁仍為 portrait。見 QA-011。|
| DOC-003 | Pass | 刪除中間頁後頁碼重排；再刪除剩餘頁面，指定文件移除，其他既有文件未受影響。|
| EXP-004 | Blocked | 目前只有 API 36 emulator；無 Android 9 裝置驗證 legacy storage branch。|
| EXP-005 | Not Run | 已觀察同名檔自動產生 `(1)` 而未覆寫；尚未執行使用者取消與儲存失敗分支。|
| SYS-PRT-001 | Fail | System print 入口仍回 `Can print only from an activity`，無法進入 Android print preview。見 QA-008。|
| SYS-PRT-002 | Blocked | PDF／JPEG／PNG 選檔列印受 QA-008 阻擋。|
| SYS-PRT-003 | Blocked | 無法先進入 system print preview，取消／Back 狀態無法測。|
| SYS-PRT-004 | Blocked | 無法先進入 system print preview，copies／media／color 無法測。|
| SYS-PRT-005 | Blocked | QA-008 在 PrintManager 呼叫前即失敗，尚未到 Print Service unavailable 分支。|
| IPP-003 | Not Run | 未進行 30-page 實體耗材測試。|
| IPP-004 | Not Run | PWG-Raster／PCLm instrumentation smoke 通過；未在 Brother 實體逐一列印 JPEG／PNG／PDF。|
| IPP-005 | Not Run | capability UI 可顯示選項；未完成所有 options 的實體輸出矩陣。|
| IPP-007 | Not Run | NET-006 已驗證不可達位址探索誤報，但尚未提交不可達 Direct IPP job。|
| IPP-008 | Not Run | unit test 覆蓋 timeout 後 best-effort Cancel-Job；未在實體列印中切斷 Wi-Fi。|
| IPP-009 | Pass | tests 覆蓋 completed／aborted／canceled／timeout；aborted/canceled 不誤報完成，timeout 嘗試 Cancel-Job。|
| IPP-010 | Blocked | 沒有可用 `_ipps`／可信 TLS hostname endpoint。|
| IPP-011 | Blocked | config 僅信任 Android system trust store且 discovery 不由 TLS 降級；缺少自簽／hostname mismatch endpoint 做握手驗證。|
| IPP-012 | Not Run | 未執行 30-page PWG-Raster／PCLm 實體 soak。|
| RES-001 | Not Run | 未在真實 eSCL image download 期間關閉 Wi-Fi。|
| RES-002 | Not Run | 未建立 scanner busy 的可重現實體條件。|
| RES-003 | Pass | Mock 3-page scan 開始後立即切背景，回前景只新增一份文件，無重複 job。|
| RES-004 | Pass | Mock scan 開始 150 ms 後 force-stop；cold launch 正常，既有文件與 9 個 real scan 檔完整，未新增半成品。|
| RES-005 | Pass | Start scan 連點 3 次只新增一份文件；忙碌 gate 阻止重複 scan job。|
| RES-006 | Not Run | 未執行 20 次 real scan＋20 次 Direct IPP 實體 soak。|
| UI-001 | Fail | English 與繁中完成 Mock scan→document→PDF export→print job；文件名稱、頁名與 source label 仍混入英文。見 QA-009。|
| UI-002 | Fail | ja／ko／es／pt／de／fr／ru／zh-CN 核心頁面均可顯示、無缺字；既有 document/history 仍混用建立時語言。見 QA-009、QA-010。|
| UI-003 | Not Run | unit tests 驗證 unsupported locale mapping；尚未把 emulator 系統語言設為不支援語言做完整 UI fallback。|
| UI-004 | Fail | 200% font＋560 dpi、portrait／landscape 下 Scan 可捲至 Start scan 並成功執行；Direct IPP options 遮擋仍使整體 Fail。見 QA-004。|
| UI-005 | Not Run | 未啟用 TalkBack 逐項走訪。|
| UI-006 | Pass | Light／Dark mode 人工檢視，文字、選取、disabled 與卡片對比可辨識。|
| UI-007 | Not Run | 手機 portrait／landscape 均可操作，landscape 使用 NavigationRail；尚無 ≥600 dp tablet。|
| SEC-001 | Pass | 惡意 DOCTYPE／external entity fixture 被 parser 拒絕。|
| SEC-002 | Pass | 跨 host、HTTPS→HTTP 降級、越出 eSCL root、query 與 redirect 攻擊均被拒絕。|
| SEC-003 | Blocked | 程式使用 Android system trust store 且無 trust-all；缺少不可信 `_uscans`／IPPS endpoint 做實際握手。|
| SEC-004 | Pass | `scans/` 排除 cloud backup 與 device transfer；FileProvider 非 exported，只分享 cache `shared-scans/` 並授予 read URI。|
| SEC-005 | Not Run | source 無 `Log.*`／`printStackTrace`，但尚未依正式去識別規則審核完整 real-mode logcat bundle。|

## 4. 目前缺陷

| Defect | Severity | 狀態 | 說明 |
|---|---|---|---|
| QA-001 | S2 Major | Partial | Brother 仍未採用 App 請求的 ScanRegion、解析度及色彩模式；協定互通仍 Open。App 已在設備未回報 actual settings 時明確標示目前 metadata 是 requested settings，誤導風險已修正 |
| QA-002 | S2 Major | Fixed | 一般 PDF、Searchable PDF 與 Direct IPP render 已依文件保存的 A4／Letter／A5／相片尺寸建立頁面；API 36 A4 MediaBox 595×842 instrumentation 通過 |
| QA-003 | S2 Major | Fixed | title、source、page number 改用相對頁高的獨立區域；修正後 system print preview 已目視確認無重疊 |
| QA-004 | S2 Major | Fixed | 動作列移出可捲動內容並套用 navigation bar inset；API 36 bounds instrumentation 覆蓋完整 capability sheet |
| QA-005 | S2 Major | Ready for retest | Direct IPP 已依文件尺寸建立 render canvas，且移除 bitmap `maxScale=1` 造成的額外縮小；仍需 Brother 實體 1:1 尺寸複測才可關閉 |
| QA-006 | S1 Critical | Open | App eSCL 未從 ADF 取紙並產生空白 Flatbed 影像。直接隔離已測 `pwg:InputSource=Feeder`、`pwg:InputSource=ADF`、`scan:InputSource=Feeder`、`scan:InputSource=Adf`，以及 legacy／extended DocumentFormat、有無 A4 ScanRegion；設備全部回 201／200，但均未取紙。工作期間 ADF 狀態會暫時由 Loaded 變 Empty，稍後回 Loaded，故不可依瞬間狀態判定成功。Brother 官方文件確認 MFC-L2715DW 支援 AirPrint ADF；設備韌體為 ZA／1.13；面板 Copy 與 Brother WIA LAN 的 ADF 網路掃描均成功。問題已限縮為目前 App eSCL request／Brother eSCL 互通，下一步需以官方 Mopria Scan 或 macOS AirPrint 做已知良好的 eSCL 對照 |
| QA-007 | S2 Major | Fixed | Real discovery 現在以 scanner capabilities／IPP attributes 驗證候選，只保留有回應的設備；不可達候選有 unit regression，UI 仍列入人工複測 |
| QA-008 | S2 Major | Fixed | Activity 不再以 configuration context 取代 base context；API 36 實際回歸已開啟 `com.android.printspooler/.ui.PrintActivity` 並成功顯示 1 頁預覽 |
| QA-009 | S2 Major | Partial | Mock／Real／Flatbed generated document name 與 page title 已保存語意 key 並以目前語系顯示／匯出；Mock device 名與 protocol/source technical values 仍維持固定名稱 |
| QA-010 | S2 Major | Partial | 已完成工作在 Home／History 改以目前語系的 job kind 與 status 顯示；進行中工作 detail 仍是建立時解析字串，完整 message-key model 尚未實作 |
| QA-011 | S2 Major | Fixed | Mock fallback 先建立 fixture bitmap，再共用 crop／rotation pipeline；編輯過的 JPEG 不再直接複製原檔。Real image page loader 行為維持原有共用路徑 |

補充：`SET-001` 的 capability UI 必須先完成 device discovery；App 重啟後 devices／capabilities 不保存，這符合目前 active network state 只存在記憶體的設計。

## 5. 修正後回歸

本節是同日針對 QA-001～011 中可由 App 修正項目的增量回歸；第 3 節保留原始完整產品測試證據，不回寫成尚未重跑的 Pass。

| 項目 | 結果 | 證據 |
|---|---|---|
| JVM | Pass | 175 tests；0 failures；0 errors |
| Lint／Debug APK | Pass | `:app:lintDebug :app:assembleDebug` BUILD SUCCESSFUL |
| API 36 instrumentation | Pass | 34／34；包含 A4 MediaBox、Activity unwrap／locale context、Direct IPP actions inset 與 document-size persistence regression |
| System Print | Pass | 實際開啟 `com.android.printspooler/.ui.PrintActivity`，顯示 1／1 頁預覽；不再出現 Activity context 例外 |
| A4 PDF | Pass | instrumentation 驗證 page 595×842 points；舊文件／Auto 保留 Letter fallback |
| PDF metadata | Pass | 修正後 Print Spooler preview 目視確認 page title、source、page number 不重疊 |
| Direct IPP physical size | Not Run | render sizing 與 scale cap 已修正，需重新送 Brother 實體列印確認 1:1 |
| Brother ADF | Fail／Open | 本輪未修改未知的廠商相容性 workaround；QA-006 維持 S1 Open |

修正後 release recommendation 仍為 **No-Go**：QA-006 是 S1，且 QA-005 尚未完成實體尺寸複測。

## 6. 下一步

1. 優先修正 S1 QA-006；修正前實體 ADF 多頁／雙面／blank／jam／soak 保持 Blocked。
2. 以目前 APK 重跑 A4 PDF、編輯匯出及 Direct IPP 實體尺寸；System Print API 36 預覽已通過，仍需完成其餘列印矩陣。
3. 完成 QA-009～010 的剩餘 message-key／technical-label 本地化，重跑 10 語系完整 flow 與跨語言 History；同時補 TalkBack、tablet、Android 9。
4. 準備 ARM＋Google Play services 裝置與 OCR ground-truth，完成 OCR-001～004、PDF-S-001 的實際模型／CER 測試。
5. 準備可信 IPPS、不可信 TLS endpoint、其他品牌 simplex／duplex MFP，完成 IPPS、安全握手與跨廠牌矩陣。
