# 小說有聲閱讀器 (Android Novel Reader & TTS)

專為 Markdown 歷史小說章節格式（依據 [chapter_writer_rules.md](file:///home/wade/src/github/novel/yuan/chapter_writer_rules.md) 規範）打造的高效能有聲閱讀軟體。
支援動態載入、GitHub 專案目錄與章節網址連動、本機 ZIP/TAR 檔上傳匯入、直達指定章節與快速換章。

---

## 🌟 主要功能與特色

### 1. 快速切換下一章與直達指定章節
- **直達指定章節彈窗 ([JumpChapterBottomSheetDialogFragment.kt](file:///home/wade/src/github/novel/android/app/src/main/java/com/novel/reader/ui/JumpChapterBottomSheetDialogFragment.kt))**：
  - **點擊頂部章節標題**隨時叫出直達彈窗。
  - **精確章號輸入**：輸入例如 `128` 或 `350`，按「前往 ➔」立即秒跳該章。
  - **進度滑桿 (1 ~ 540 章)**：滑動滑桿即時預覽章節名稱與卷數。
  - **六大卷快速鍵**：點擊 `[第1卷 (1章)]`、`[第2卷 (91章)]`、`[第3卷 (181章)]`、`[第4卷 (271章)]`、`[第5卷 (361章)]`、`[第6卷 (451章)]` 瞬間跳轉。
- **多元快速換章途徑**：
  - **底部控制列**：`[⏭ 下一章]` 與 `[⏮ 上一章]` 按鈕。
  - **內文最底部導航卡片**：讀完當章後，文末直接提供醒目的 `[下一章 ▶]` 與 `[◀ 上一章]` 快捷按鈕。
  - **章節目錄即時直達**：目錄內搜尋框輸入數字按 Enter 或點擊「直達 ➔」按鈕即可跳轉。
  - **通知欄換章**：螢幕鎖定或背景播放時，通知欄控制按鈕直接切換上一章/下一章。

### 2. 動態載入：指定 GitHub 專案目錄與單章網址 ([GitHubHelper.kt](file:///home/wade/src/github/novel/android/app/src/main/java/com/novel/reader/util/GitHubHelper.kt))
- **整本書 / 目錄連動**：
  - 輸入 GitHub Tree 網址，例如：  
    `https://github.com/wade-fs/novel2movie/tree/main/yuan`
  - App 會透過 GitHub 串流直接抓取對應分支壓縮包，解壓並自動分類 6 卷共 540 章，建立全書索引直接開啟朗讀。
  - 同樣支援其他作品目錄，如：  
    `https://github.com/wade-fs/novel2movie/tree/main/guixu`  
    `https://github.com/wade-fs/novel2movie/tree/main/Origins-of-Formosa`
- **單一章節網址連動**：
  - 輸入 GitHub Blob 網址，例如：  
    `https://github.com/wade-fs/novel2movie/blob/main/yuan/chapters/volume1/chap001.md`
  - App 自動解析 Raw 檔案位址，即刻下載該章、解析結構並開始語音朗讀。

### 3. 本機上傳與壓縮檔支援 (ZIP / TAR / TAR.GZ)
- **上傳本機壓縮檔**：點擊匯入對話框中的「上傳 ZIP / TAR」，可直接從手機選取 `.zip`、`.tar`、`.tar.gz` (.tgz) 格式的小說包，App 原生串流解壓並索引。
- **選取本機目錄 (SAF)**：亦可選取手機內部儲存空間中的任意資料夾。

### 4. 朗讀進度自動儲存與接續朗讀 (續讀)
- **自動記憶中斷點**：
  - 每當切換章節、完成段落或暫停時，自動儲存小說 ID、章號、段落索引與畫面位置。
- **一鍵接續朗讀**：
  - 重新打開 App 時，頂部顯示**「上次朗讀至：第 N 章 [章名] · 段落 M」**提示橫條，點擊「繼續朗讀」立即定位滾動並自動播放。

### 5. 完整章節結構解析 (NovelChapterParser)
- 依照專案逐章規範自動解析章節：
  - **章名**：例如 `# 第一章　上都煙塵`
  - **小說正文**：完整主體段落，保留標準中文縮排與排版
  - **章後工作紀錄**：細分各類目（史實依據 / 史實骨架、待核事項、合理推演、小說虛構、連續性變更、後續銜接、其他紀錄）
- 文字清洗與自然口語化：自動剃除 Markdown 標籤符號（如 `**`、`*`、`#`、`---`），優化括號停頓，避免語音朗讀出現機械符號讀音。

### 6. 靈活朗讀範圍與工作記錄設定
- **預設全文朗讀**：預設每個字都朗讀（章名、正文、工作紀錄所有細項全讀）。
- **快速情境切換**：
  - **全文朗讀 (預設)**
  - **只讀正文**（自動略過章名及章後工作紀錄，純享故事）
  - **讀章名 + 正文**
- **自訂細項過濾開關**：
  - 可單獨設定是否讀章名、是否讀正文、是否讀章後工作記錄（各子項目可獨立勾選）。

### 7. 自動換下一章與播放控制
- **自動換下一章**：當前章節所有指定內容朗讀完畢後，**全自動無縫加載下一章並繼續朗讀**。
- **全方位播放控制**：朗讀 / 暫停 / 停止 / 上一段 / 下一段 / 上一章 / 下一章，點選段落點擊即讀，即時黃金高光與平滑滾動。

### 8. 語速與語調（男生/女生）設定
- **語速設定**：滑桿微調（0.5x ~ 2.5x），並提供 `0.75x`、`1.0x`、`1.25x`、`1.5x`、`2.0x` 快速按鈕。
- **語調與男生/女生設定**：
  - **語音性別切換**：提供「系統預設」、「男聲」（沉穩低調 / 自動適配男聲 Voice）、「女聲」（清亮明快 / 自動適配女聲 Voice）。
  - **語調 (Pitch) 微調**：0.6x ~ 1.6x 連續滑桿。
  - **系統語音 Voice 選單**：可直接選擇系統 TTS 引擎所安裝的特定中文語音。

---

## 🛠 專案架構

```
android/
├── app/
│   ├── src/main/
│   │   ├── java/com/novel/reader/
│   │   │   ├── model/
│   │   │   ├── parser/
│   │   │   ├── repository/
│   │   │   ├── service/
│   │   │   ├── util/
│   │   │   │   ├── ArchiveHelper.kt          (支援 ZIP、TAR、TAR.GZ 解壓)
│   │   │   │   ├── GitHubHelper.kt           (GitHub 網址解析與串流下載)
│   │   │   │   └── ReadingProgressManager.kt (儲存與續讀進度管理)
│   │   │   └── ui/
│   │   │       ├── MainActivity.kt
│   │   │       ├── NovelContentAdapter.kt
│   │   │       ├── ChapterListAdapter.kt
│   │   │       ├── ChapterListBottomSheetDialogFragment.kt
│   │   │       ├── JumpChapterBottomSheetDialogFragment.kt  (直達指定章節)
│   │   │       ├── ImportBottomSheetDialogFragment.kt
│   │   │       └── SettingsBottomSheetDialogFragment.kt
│   │   └── res/
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

---

## 📦 建置指令

```bash
cd android
./gradlew assembleDebug
```
產出檔案位於：`android/app/build/outputs/apk/debug/app-debug.apk`
