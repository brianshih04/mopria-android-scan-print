# HP 與 Brother MFP 實機測試報告

> **📝 後續更新（2026-09-16）：** 本報告的兩個 Brother 開放問題均已解決/翻案。
> (1) 「ADF 未取紙」與「1680×2193 低解析」的真正根因是 POST ScanJobs 的
> Content-Type 必須為 `application/xml`（Brother firmware 對 `text/xml` 走降級
> fallback）——修復後 ADF 取紙正常、300/600dpi/A5/4×6/5×7 全部精確命中，
> 詳見 `docs/brother-contenttype-rootcause.md`。
> (2) HP 經对照測試不受 Content-Type 影響，各尺寸解析度皆精確。

測試日期：2026-08-14（Asia/Taipei）
測試模式：Real mode、Android API 36 emulator、同一區域網路
測試範圍：eSCL 掃描、ADF／Flatbed、A4／300 dpi／RGB24、JPEG／PDF 匯出，以及 IPP 列印結果

## 1. 執行摘要

本輪測試顯示，HP 與 Brother 的差異主要發生在 eSCL 掃描輸出的影像尺寸，不是 App 產生 A4 PDF 或 IPP 列印的共通問題。

- HP LaserJet Pro MFP 3104fdw（`10.1.121.182`）的 ADF 三頁原始影像均為 `2480 x 3508 px`、JPEG 標示 `300 x 300 dpi`，符合 A4 300 dpi。
- Brother MFC-L2715DW（`10.1.121.175`）的 Flatbed 原始影像為 `1680 x 2193 px`，比例為 `0.7661`，與 A4 比例 `0.7070` 不符；App 仍只能依請求值保存為 A4，因設備沒有回報實際掃描設定。
- HP 的 ADF 掃描可完成三頁；Brother 的 ADF 未能取紙，回傳結果曾呈現 Flatbed 類似影像，ADF 互通性仍為開放問題。
- 兩台設備的 IPP 工作都曾成功完成，但「IPP job completed」不等於掃描影像尺寸正確，也不等於已完成實體 1:1 尺寸驗收。

## 2. 測試環境與請求設定

| 項目 | HP MFP | Brother MFP |
|---|---|---|
| 型號 | HP LaserJet Pro MFP 3104fdw | Brother MFC-L2715DW |
| IP | `10.1.121.182` | `10.1.121.175` |
| 掃描協定 | eSCL v2.63 | eSCL v2.63 |
| 掃描來源 | Feeder；另有 Flatbed 操作流程 | Flatbed 成功；Feeder 未取紙 |
| 主要請求 | A4、300 dpi、RGB24、JPEG／PDF | A4、300 dpi、RGB24、JPEG／PDF |
| 列印路徑 | plain IPP 可完成；Direct IPP 路徑曾不可用 | IPP 工作可完成；A4 media option 可見 |

測試以目前實際請求與產物為準。除非另有 JPEG DPI metadata，影像像素尺寸不能單獨證明設備使用了哪一個實體 DPI。

## 3. HP 測試結果

### 3.1 eSCL 與 ADF

HP capabilities 回報的型號為 HP LaserJet Pro MFP 3104fdw，eSCL 版本為 2.63。ADF 三頁測試所送出的設定包含：

```xml
<pwg:InputSource>Feeder</pwg:InputSource>
<scan:XResolution>300</scan:XResolution>
<scan:YResolution>300</scan:YResolution>
<scan:ColorMode>RGB24</scan:ColorMode>
<pwg:Width>2480</pwg:Width>
<pwg:Height>3508</pwg:Height>
```

三張原始 JPEG 的結果一致：

| 頁面 | 原始影像尺寸 | JPEG DPI metadata | 判定 |
|---|---:|---:|---|
| ADF page 1 | `2480 x 3508 px` | `300 x 300 dpi` | Pass |
| ADF page 2 | `2480 x 3508 px` | `300 x 300 dpi` | Pass |
| ADF page 3 | `2480 x 3508 px` | `300 x 300 dpi` | Pass |

影像比例為 `2480 / 3508 = 0.7070`，與 A4 比例一致。這表示 HP 在本次 ADF 流程中有依照 A4／300 dpi 請求回傳影像，沒有觀察到 Brother 那種固定回傳 `1680 x 2193` 的尺寸問題。

### 3.2 PDF 匯出與列印

HP App PDF 的頁面 MediaBox 為 `595 x 842 pt`，即 A4。PDF 內嵌影像為 `1448 x 2048 px`，比例仍為 A4，代表 App 的 bounded export 只做等比例取樣，沒有把影像拉成非原始比例。

列印測試中，plain IPP 工作可完成。初次列印曾觀察到縮小，重新 Scan／Print 後，實際列印結果與 Scan 原稿尺寸一致；這項尺寸結果是人工觀察，尚未以尺規或列印後再掃描方式量測。

## 4. Brother 測試結果

### 4.1 Flatbed eSCL

Brother Flatbed A4／300 dpi／RGB24 掃描可以建立工作並取得 JPEG，但原始影像尺寸為：

| 項目 | 結果 |
|---|---:|
| 原始 JPEG | `1680 x 2193 px` |
| 影像比例 | `0.7661` |
| A4 300 dpi 參考值 | `2480 x 3508 px` |
| 與 A4 比例差異 | 約 `8.36%` |
| JPEG DPI／EXIF | 未回報 |
| App metadata | `documentSize=A4`、`actualScanSettingsReported=false` |

如果只把 Brother 像素數當作 300 dpi，換算約為 `142.2 x 185.7 mm`，但設備沒有回報實際 DPI，因此這個實體尺寸只能作為數學換算，不能當成設備的正式尺寸宣告。影像像素數則可確認不是 A4 300 dpi 輸出。

Brother 匯出的 PDF 頁面仍是 A4 `595 x 842 pt`，但內嵌影像為 `1569 x 2048 px`。這正好保留了原始 JPEG 的非 A4 比例，所以 PDF 的白邊是「非 A4 掃描內容等比例放入 A4 頁面」的結果，不是 PDF 把影像變形。

現有解析度調查也記錄到：在 Android／Linux HTTP client 下，Brother 多次回傳 `1680 x 2193`；只有 Windows `curl.exe` 的特定 Winsock 行為曾取得接近 300 dpi 的 `2448 x 3470`。因此目前強烈懷疑是 Brother MFC-L2715DW firmware／TCP client 行為相關的 eSCL 互通問題，而非 App 單純縮放。

### 4.2 ADF

Brother ADF 測試未能穩定取紙。即使工作回報建立／完成，取得的結果仍曾是 Flatbed 類似影像，不能視為成功的 ADF 掃描。已知隔離結果如下：

- App 可送出 Feeder 相關 eSCL request，設備也可回應 HTTP 201／200。
- ADF 紙張沒有被實際帶入掃描路徑，設備狀態會在 `Loaded`／`Empty` 間變化。
- Brother 面板 Copy 與 Windows WIA LAN ADF 可運作，因此問題目前集中在 App eSCL request 與 Brother eSCL 互通。

判定：ADF interoperability Fail／Open；尚不能用 Brother ADF 與 HP ADF 做等價的三頁掃描比較。

### 4.3 IPP 列印

Brother IPP 列印工作可完成，列印選項顯示 `iso_a4_210x297mm`，目前記錄有三個 completed jobs。這證明列印工作生命週期與 A4 media option 可以運作，但沒有證明 Brother eSCL 回傳的掃描影像是 A4，也沒有完成以尺規量測的 1:1 實體列印驗收。

## 5. 主要差異

| 比較項目 | HP | Brother | 差異判讀 |
|---|---|---|---|
| ADF 三頁 | 三頁皆取得且尺寸正確 | 未能取紙 | HP 通過；Brother ADF 互通失敗 |
| 原始 Scan 尺寸 | `2480 x 3508` | `1680 x 2193` | Brother 約只有 A4 300 dpi 像素量的 42.3% |
| Scan 比例 | `0.7070` | `0.7661` | Brother 非 A4 比例 |
| JPEG DPI metadata | `300 x 300` | 未回報 | HP 可驗證；Brother 不能由檔案確認實際 DPI |
| App PDF MediaBox | A4 | A4 | 兩者 PDF 外框都正確 |
| PDF 內嵌影像 | A4 比例 | 保留 Brother 非 A4 比例 | 差異在 Scan payload，不在 PDF 外框 |
| IPP job | plain IPP 成功 | IPP job 成功 | 列印傳輸大致可用，但不代表 Scan 尺寸正確 |

## 6. 結論

1. HP MFP 在本次測試中沒有發現 Brother 那種 Scan 尺寸問題；HP ADF 三頁均為 A4 300 dpi 尺寸。
2. Brother 的問題可分成兩層：Flatbed eSCL 回傳非 A4／低像素影像，以及 ADF 未取紙。兩者都發生在掃描端，不是 plain IPP 列印端。
3. App 目前把 Brother 文件標成 A4，是因為使用者請求為 A4，而 Brother 沒有回報 actual scan settings；這個 metadata 行為已經明確標示為 requested settings，不能當成設備實際回傳尺寸。
4. A4 PDF 外框可以保持正確，但不能自動把 Brother 原始影像變成真正的 A4。若直接拉伸會破壞比例；後續應在「保留原始比例」與「裁切／補白成 A4」之間做產品決策。

## 7. 後續建議與限制

- 先確認 Brother MFC-L2715DW 是否有更新 firmware，並用官方 Mopria Scan 或 macOS AirPrint 做 eSCL 對照。
- 若產品要求固定輸出 A4，應明確定義補白或裁切策略，並在 metadata 中保留原始像素尺寸與實際設定回報狀態。
- 重新驗證 Brother Flatbed：同一張已知 A4 對位頁，記錄原始 JPEG 尺寸、JPEG DPI、PDF MediaBox、內嵌影像尺寸與列印後實測尺寸。
- Brother ADF 仍需另行解決；在 ADF 互通修正前，不應宣稱跨品牌 ADF 相容。
- 本報告只整理目前實機觀察，不代表 Mopria Certified 或完整跨品牌相容性認證。原始影像、PDF、capabilities XML 與測試暫存檔未納入 repository。
