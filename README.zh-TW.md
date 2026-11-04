# CarPlay · 極氪 OS 7.2.5 客製版

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**以 Zeekr OS 7.2.5 車機為適配目標的 CarPlay 應用程式**，支援有線與無線連線，著重改善極氪車機上的介面體驗、媒體中心整合、熱點連線與原生車輛資料接入。

本專案 fork 自 [DiPlay](https://github.com/shihabal3amri/DiPlay)。

## 相較原專案的主要改動

| 方向 | 客製內容 |
| --- | --- |
| 介面 | 參考極氪 OS 7 視覺語言，採用淺色／深色主題、節制的藍色強調、線性圖示與分組清單；適配橫向畫面、分割畫面及大字體。 |
| 設定 | 重整設定、搜尋及首次設定精靈，隱藏 BYD／DiLink 專屬設定，突出極氪車機功能。 |
| 媒體中心 | 同步 CarPlay 歌曲、封面與播放進度，轉送支援的車機播放指令，並提供自動切換 CarPlay 音源的選項。 |
| 車機熱點 | 偵測到原廠熱點服務時優先使用該服務，沿用車機儲存的設定；這條服務路徑不需要 ADB。 |
| 車輛資料 | 透過相符的 CarSensor 介面讀取實測電量、動態續航、車速與檔位，依需求向 iPhone 回報。 |
| 設定體驗 | 媒體中心、自動選源及播放控制可分別設定；需要新連線的變更會顯示重新連線提示，由使用者決定何時重連。 |

## Zeekr OS 7.2.5 適配說明

**本分支以 Zeekr OS 7.2.5 為適配目標。** 介面依實際視窗大小與系統保留區域排列，不複製原廠狀態列、空調 Dock 或車輛控制元件，也不重新繪製 CarPlay 投影內容。

相同 OS 版本不代表所有車型、韌體介面都一致。媒體中心、熱點與車輛資料功能以車機實際服務及權限為準；車輛資料解析仍限定於已相符的車型／韌體組合，不會虛構缺少或未經驗證的讀數。詳細範圍請見[車輛資料適配說明](docs/ZEEKR-VEHICLE-DATA.zh-CN.md)。

## 使用方式

1. 將 **Zeekr 版本安裝至車機**，不是 iPhone。套件名稱為 `com.shihab.diplay.hudtest`。
2. 選擇 USB、車機熱點、Wi-Fi Direct 或現有 Wi-Fi 網路，完成配對並授予所需權限。
3. 在 **設定 → 車輛 → 車機連動** 中設定媒體中心、自動選源及播放控制。
4. 確認原生車輛資料可讀後，再依需求開啟進階設定中的電量／續航與車速回報；車速回報還需要開啟位置共享。

覆蓋安裝舊版本時需使用相容簽章，才能保留設定與配對資料。獨立車輛資料探針是另一個應用程式，不是 CarPlay 主程式。

## 建置極氪版本

```sh
./gradlew -Pdiplay.edition=zeekr :mobile:assembleDebug
```

輸出檔案：`mobile/build/outputs/apk/debug/mobile-debug.apk`。

一般原始碼／CI 建置不包含 CarPlay 認證資產。完整本機建置需依[極氪建置說明](docs/ZEEKR-BUILD.md)提供外部執行資產；認證檔案及簽署金鑰不應提交至儲存庫。

## 文件與問題回報

- [極氪版建置與封裝](docs/ZEEKR-BUILD.md)
- [媒體中心整合](docs/ZEEKR-MEDIA-SOURCE.md)
- [免 ADB 原廠熱點服務](docs/ZEEKR-HOTSPOT.md)
- [車輛資料與實車驗收](docs/ZEEKR-VEHICLE-DATA.zh-CN.md)
- [介面與元件規範](docs/design/ZEEKR-OS72-HMI.md)

遇到連線或車機整合問題時，可在 **設定 → 診斷** 中匯出報告，並附上車型、完整 OS／韌體版本、iPhone／iOS 版本、連線方式、重現步驟及故障時間。分享前請檢查報告中的個人資訊。

## 來源與授權

本 fork 保留 DiPlay 的現有開源授權及歸屬聲明，詳見 [LICENSE](LICENSE) 與[第三方聲明](docs/THIRD_PARTY_NOTICES.md)。本專案為獨立 fork，與極氪或 Apple 無官方隸屬關係。
