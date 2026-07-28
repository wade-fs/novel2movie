# 🎬 小說自動轉影片工具：使用與進階 AI 配置指南

本指南詳細介紹如何使用 `novel_to_video.py` 工具將 Markdown 格式的小說章節，自動轉換為包含**自然語音**、**動態畫面轉場**與**字幕燒錄**的 MP4 影片，並著重介紹進階的 **AI 插圖生成模式**。

---

## 🛠️ 環境需求與依賴關係

本專案在 Linux (Ubuntu) 系統下運行，並採用 Python 虛擬環境隔離依賴：

1. **Python 虛擬環境 (`.venv`)**：
   * 已安裝依賴：`edge-tts`（語音合成）、`mutagen`（音訊時長解析）、`Pillow`（圖像處理/動態封面生成）。
2. **FFmpeg**：
   * 用於音訊拼接與影片最終合成與字幕燒錄。需確保系統已安裝（`sudo apt install ffmpeg`）。
3. **Antigravity CLI (`agy`)**：
   * 用於進行進階 AI 圖像生成（無須手動配置 API Key，直接使用系統內建的配額與授權）。

---

## 🚀 快速開始

我們在專案根目錄下提供了一個包裝好的 Shell 腳本：[novel_to_video.sh](file:///home/wade/src/github/novel/novel_to_video.sh)，它會自動調用 `.venv` 中的 Python 環境。

### 1. 最簡基本執行 (自動漸層封面 + 台灣微軟女聲)
```bash
./novel_to_video.sh guixu/chapter/01/001.md
```
* **輸出路徑**：自動識別輸入路徑。若輸入包含 `/chapter/`，將自動替換輸出至 `guixu/video/01/001.mp4`（若目錄不存在會自動建立）。
* **暫存快取**：快取資料夾會自動建立在 `.cache/tts_歸墟_Online_第一章_天穹裂隙/` 下，保持根目錄乾淨。

### 2. 使用自訂靜態封面與微軟男聲
```bash
./novel_to_video.sh guixu/chapter/01/001.md \
  --image guixu/封面2.png \
  --voice zh-TW-YunJheNeural
```

---

## 📸 畫面視覺模式 (Visual Modes)

工具支援三種視覺展示模式，會根據您的目錄結構與參數自動選擇：

### 模式 A：單一封面/標題卡 (預設)
* **規則**：若未指定任何圖片，腳本會自動在章節目錄或其父目錄中搜尋含有「`封面`」或「`cover`」字眼的圖檔。
* **動態產生**：若無任何本地圖片，腳本會利用 Pillow **動態生成一張 1920x1080 的極簡極光漸層封面**（無任何 AI 水印），印上小說名、卷名與章節名。

### 模式 B：手動多圖幻燈片
* **規則**：在章節 Markdown 檔案同目錄下，建立一個**與章節同名**（如 `001_images`、`001` 或 `images`）的資料夾，放入多張圖片（如 `01.png`, `02.jpg` ...）。
* **段落對齊切換**：腳本會依據圖片數量將段落平均分配，圖片的切換點會**精確對齊段落發音的空白處**，切換流暢自然。

### 模式 C：進階 AI 逐段插圖 (推薦) 🌟
* **規則**：使用 `--ai-images` 參數。腳本將讀取每個段落的文本，調用 `agy` CLI 為**每一個段落自動生成專屬的 AI 畫面**。
* **1-to-1 對齊**：第 $i$ 個段落發音時，畫面會精確顯示該段落生成的 AI 插圖。

---

## 🎨 進階 AI 繪圖配置 (`--ai-images` & `--ai-style`)

啟用 AI 繪圖模式時，建議配合 `--ai-style` 設定與小說世界觀契合的畫風。

```bash
./novel_to_video.sh guixu/chapter/01/001.md --ai-images --ai-style "<畫風描述>"
```

### 💡 推薦畫風模版與範例

| 風格類型 | `--ai-style` 參數推薦 | 適用題材 |
| :--- | :--- | :--- |
| **預設科幻動漫** | `"cyberpunk anime style, vibrant colors, detailed sci-fi illustration"` | 科幻、虛擬網遊、量子覺醒 |
| **國風水墨** | `"traditional chinese ink wash painting, ethereal landscape, soft brush strokes, artistic hand-drawn"` | 修仙、仙俠、歷史演義 |
| **寫實電影感** | `"cinematic, photorealistic sci-fi scene, 8k resolution, dramatic lighting, octane render"` | 科幻大作、硬核寫實、科幻末日 |
| **溫馨水彩** | `"watercolor painting style, soft pastel colors, cozy and warm atmosphere, hand-drawn texture"` | 都市生活、溫馨治癒、回憶錄 |
| **黑暗奇幻** | `"dark fantasy concept art, oil painting texture, moody light, gothic style, highly detailed"` | 奇幻冒險、克蘇魯、魔幻史詩 |

### 🔄 快取與容錯機制說明
* **雙重 MD5 快取**：生成的 AI 圖片與 TTS 語音均被快取。若您修改了某個段落，**只有該段落的圖片與語音會重新生成**，其餘段落直接讀取快取，節約時間與 Quota。
* **安全機制**：若某段文字觸發敏感詞審查或生成失敗，腳本會自動使用預設封面作為該段落的背景圖，保證影片正常壓製。

---

## 📋 命令行參數完整參考 (CLI Arguments)

執行 `./novel_to_video.sh --help` 可查看英文幫助資訊，以下為詳細中文對照：

| 參數 | 預設值 | 說明 |
| :--- | :--- | :--- |
| `chapter_file` | (必填) | 輸入的小說章節 Markdown 檔案路徑。 |
| `-o`, `--output` | `None` | 輸出 MP4 路徑。預設會自動映射（如 `chapter/01/001.md` -> `video/01/001.mp4`）。 |
| `-v`, `--voice` | `zh-TW-HsiaoChenNeural` | 微軟 Edge TTS 發音人。男聲推薦使用 `zh-TW-YunJheNeural`。 |
| `-i`, `--image` | `None` | 自訂封面圖路徑（可以是單一圖片檔或手動多圖資料夾）。 |
| `--cache-dir` | `None` | TTS 與圖片快取目錄。預設為隱藏資料夾 `.cache/tts_<書名>_<章節名>`。 |
| `--clean` | `False` | 影片生成成功後，是否自動清理快取目錄。一般建議保留以實現快速二次修改。 |
| `--ai-images` | `False` | 是否開啟進階的 AI 逐段插圖生成模式。 |
| `--ai-style` | (預設科幻動漫) | AI 生成圖片的畫風提示詞字串。 |
