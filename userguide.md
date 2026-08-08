# Mopria Android Scan & Print 使用者指南

本指南說明如何安裝 App、掃描文件、查看掃描結果，以及列印或分享文件。

## 1. 安裝 App

### 從 GitHub 下載

從 [GitHub Release v0.1.0](https://github.com/brianshih04/mopria-android-scan-print/releases/download/v0.1.0/avi-print-scan.apk) 下載 `avi-print-scan.apk`。此 APK 是開發／emulator 測試版本，以 debug keystore 簽署；正式上架前需要產品簽章。

### 使用 Android Debug Bridge

```powershell
adb install -r avi-print-scan.apk
adb shell am start -n com.brianshih.mopria.android.scanprint/.MainActivity
```

Android 若阻擋非 Play 商店安裝，請先在系統設定允許目前使用的檔案管理器或瀏覽器安裝未知來源 App。

## 2. 首頁與模式

首頁提供四個主要入口：

- 掃描文件：開啟掃描設定與掃描工作。
- 列印：從手機檔案選擇 PDF／JPEG／PNG，依設定使用 Android 系統列印或 Direct IPP。
- 文件：查看已完成的掃描結果。
- 紀錄：查看掃描、列印與匯出的工作狀態。

右上角設定可切換：

- 測試模式：使用內建 Mock scanner／printer，不需要硬體或網路，適合先熟悉流程。
- 實體裝置：透過 DNS-SD 探索同一網路中的 eSCL／AirScan scanner；列印預設使用 Android Print Framework，也可在設定中 opt-in Direct IPP。

第一次使用建議先留在「測試模式」驗證 UI 與文件流程，再切換「實體裝置」。

### 語言

到「設定」→「語言」可以選擇「依系統設定」或手動指定語言。依系統設定時，App 會使用 Android 的第一個支援語系；若系統語系不在支援清單，會使用 English。可選語言包括 English、日本語、한국어、Español、Português、Deutsch、Français、Русский、繁體中文與简体中文。手動選擇會立即套用，重新選擇「依系統設定」即可恢復跟隨手機語言。

## 3. 掃描文件

1. 在首頁點選「掃描文件」。
2. 選擇文件來源：
   - `Flatbed 單頁`：每次掃描玻璃平台上的一頁。
   - `ADF 多頁`：從自動送稿器連續掃描多頁。
3. 選擇解析度：`150 dpi`、`300 dpi` 或 `600 dpi`。
4. 選擇色彩：`彩色`、`灰階` 或 `黑白`。
5. 按下「開始掃描」。

### Flatbed 多頁 PDF

若要用 Flatbed 掃描多頁，先開啟「逐頁合併 PDF」。每頁完成後會出現操作對話框：

- `下一頁`：放上下一張紙並繼續掃描。
- `完成 PDF`：結束工作，將已掃描頁面合併成一份 multi-page PDF。

不開啟合併選項時，Flatbed 會以單頁文件保存。

### ADF 多頁 PDF

選擇 ADF 後：

1. 開啟或關閉「合併為多頁 PDF」。
2. 用「頁數上限」滑桿設定 1–50 頁。
3. 按下「開始掃描」。

開啟合併時，ADF 頁面會成為一份 multi-page PDF；關閉時，頁面會拆成個別文件。頁數上限是 App 端的保護值，不代表送稿器實際容量。

## 4. 查看與管理文件

在底部導覽點選「文件」，這裡只顯示已完成的掃描結果，不會重新啟動掃描。

每份文件可進行：

- 點選頁面縮圖：查看大圖預覽。
- `列印`：依目前設定送往 Android 系統列印預覽或 Direct IPP 列印流程。
- `分享`：開啟 Android Sharesheet，交給其他 App。
- `PDF`：輸出或重新保存為 PDF。
- `JPEG`：將文件頁面輸出為 JPEG 檔案。

Android 10 以上預設寫入：

```text
Download/Mopria Scan & Print/Scans
```

Android 9 會在需要寫入公開 Download 資料夾時要求儲存權限。

## 5. 從手機檔案列印

1. 切換到「實體裝置」後，到「設定」選擇列印方式；建議先保留預設的「系統列印」，Direct IPP 是尚待跨品牌實機驗證的 opt-in 功能。
2. 在首頁點選「列印」。
3. 在 Android DocumentsUI 選擇一個或多個 PDF／JPEG／PNG。
4. 確認選取後，App 會依列印方式繼續：
   - `系統列印`：開啟 Android Print Framework 預覽，由 Default Print Service／Mopria Print Service 探索印表機並提供紙張、色彩、雙面等選項。
   - `直接 IPP`：App 探索同一網路的 `_ipp._tcp`／`_ipps._tcp` 印表機，讀取 capability，顯示可用的份數、紙張、方向、色彩、雙面與品質選項，再直接送出工作。

Direct IPP 可送出 PDF、JPEG、PNG，並可依印表機 capability 轉為 PWG-Raster 或 PCLm。若印表機不支援多文件 job，圖片多頁會改成逐頁建立單文件 job。找不到 Direct IPP 印表機時會顯示錯誤，不會自動切回系統列印。

按 Android 返回鍵可從檔案選擇器回到 App；取消選檔也會回到 App 的列印流程。

## 6. 實體 scanner 測試

使用「實體裝置」模式前，請確認：

- 手機與 scanner 連到同一個區域網路。
- scanner 支援 eSCL／AirScan 掃描服務。
- scanner 已開機且允許網路掃描。
- App 能探索 `_uscan._tcp` 或 `_uscans._tcp` 服務。

回到首頁點選裝置卡片即可觸發 discovery。找到設備後，進入掃描頁並依設備 capability 使用 Flatbed 或 ADF。不同品牌對解析度、色彩、PDF 及 ADF 的支援可能不同，App 會依實際 capability 選擇有效設定。

實體列印有兩條路徑：系統列印是否看得到印表機，取決於 Android 裝置上已啟用的 Default Print Service／Mopria Print Service；Direct IPP 則由 App 探索 `_ipp._tcp`／`_ipps._tcp`。使用 Direct IPP 時，手機與印表機必須在允許 mDNS／DNS-SD 與 IPP 流量的同一區域網路；IPPS 憑證必須通過 Android 系統 trust store 與 hostname 驗證。

## 7. 常見問題

### 找不到 scanner

先切回測試模式確認 App 本身可運作，再檢查手機與設備是否在同一網段、設備是否支援 eSCL，以及網路是否阻擋 mDNS／DNS-SD。

### ADF 頁數和實際張數不同

「頁數上限」是 App 的最多處理頁數。若送稿器提前沒有紙，實際完成頁數會少於設定值；若設定值太小，App 會在達到上限時停止繼續取頁。

### 列印沒有出現印表機

若使用系統列印，確認 Android 設定中的 Print Service 已啟用，並檢查 Mopria Print Service 或廠商 Print Service 是否能看到同一網路中的印表機。若使用 Direct IPP，確認手機與印表機位於同一區域網路、設備有宣告 `_ipp._tcp` 或 `_ipps._tcp`，且網路未阻擋 mDNS／DNS-SD；Direct IPP 失敗時不會自動改走系統列印，可到設定手動切回。

### 文件沒有出現在 App

請先確認掃描工作已完成，再到「文件」頁查看。若要在其他 App 找檔案，請到 `Download/Mopria Scan & Print/Scans`。

## 8. 功能邊界

目前版本不宣稱支援 Push Scan、OCR／可搜尋 PDF、ADF duplex UI、使用者認證輸入、手動 IP／URL 或 Mopria 認證。Direct IPP 已提供 opt-in 實作，但尚未完成跨品牌實機驗證；高 DPI 多頁 PWG-Raster／PCLm 也仍需記憶體 soak，因此不應視為 Mopria Certified 或特定廠牌相容證據。真實設備仍需要 Canon、Brother、Fujifilm 或其他品牌的實機驗收。
