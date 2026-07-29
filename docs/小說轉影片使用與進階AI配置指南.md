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

## 🔄 階梯式漸進工作流 (Step-by-Step Workflow) 🌟

當章節篇幅較長、AI 圖片生成需要微調或因配額限制需要單張微調時，您可以使用**分步執行**功能：

### 第一步：一次性生成所有 TTS 語音與時間軸
```bash
./novel_to_video.sh guixu/chapter/01/001.md --step tts
```
* **作用**：解析 Markdown、為所有段落產生 TTS 語音檔及單獨字幕，計算發音時間與總時長，並生成音訊合併檔 `combined_audio.mp3`、合併字幕 `combined.srt` 以及管線繼承資訊檔 `metadata.json`。

### 第二步：逐張/分步建立 AI 圖片 (支援單張重建)
* **一次性生成/快取所有圖片**：
  ```bash
  ./novel_to_video.sh guixu/chapter/01/001.md --step image --ai-images
  ```
* **指定重建某個段落的畫面** (例如：重新生成第 4 個畫面)：
  ```bash
  ./novel_to_video.sh guixu/chapter/01/001.md --image-index 4 --ai-images
  ```
  *(註：此時腳本會自動刪除快取中該索引舊圖並重新調用 AI 繪圖產生新圖。)*

### 第三步：一鍵組裝成最終影片
```bash
./novel_to_video.sh guixu/chapter/01/001.md --step video --ai-images
```
* **作用**：直接讀取 `metadata.json` 的時序資料與快取中的 AI 圖片（未產生圖片的段落則自動以預設封面墊底），快速使用 FFmpeg 組裝壓製出最終帶滾動字幕與音軌的 `.mp4` 影片。

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
| `--step` | `all` | 指定執行的階段：`tts` (Step 1)、`image` (Step 2)、`video` (Step 3) 或 `all` (預設，完整全自動管線)。 |
| `--image-index` | `None` | 指定單一畫面的索引 (0-based) 進行生成/重新生成。指定此參數時 `--step` 會預設為 `image`。 |
| `--agy-model` | `None` (agy 預設) | 指定 `agy` CLI 使用的文字推理模型（用於生成圖片時解讀 prompt）。詳見下方說明。 |

---

## 🧠 AI 圖片生成模型選擇 (`--agy-model`)

`novel_to_video.py` 在生成 AI 圖片時，是以**獨立子程序**的方式呼叫 `agy` CLI，而非直接使用您在 Antigravity IDE 所選的模型。因此，IDE 的 Model Selection 設定不會影響此工具的行為。

### 如何指定 model

```bash
./novel_to_video.sh guixu/chapter/01/001.md --step image --ai-images \
  --agy-model gemini-3.1-pro-high
```

### 可用的 Model ID

執行 `agy models` 可查看即時清單，目前可用的 model 包含：

| Model ID | 說明 | 建議場景 |
| :--- | :--- | :--- |
| `gemini-3.6-flash-high` | Gemini 3.6 Flash (High) | 速度快，適合大量圖片批次生成 |
| `gemini-3.5-flash-high` | Gemini 3.5 Flash (High) | 穩定備援選項 |
| `gemini-3.1-pro-high` | Gemini 3.1 Pro (High) | 品質最高，適合重要封面場景 |
| `claude-sonnet-4-6` | Claude Sonnet 4.6 (Thinking) | 創意指令理解能力強 |
| `claude-opus-4-6-thinking` | Claude Opus 4.6 (Thinking) | 最強推理，適合複雜情境描述 |
| `gpt-oss-120b-medium` | GPT-OSS 120B (Medium) | 開源模型備援 |

> [!NOTE]
> `--agy-model` 只影響 `agy` 在**解讀 prompt、決定如何存檔等文字推理步驟**使用的 LLM。AI **圖片本身的生成**由 `agy` 底層的 image API 統一處理，與選用的文字模型無關。

> [!TIP]
> 若沒有指定 `--agy-model`，`agy` 會使用其本身的設定檔預設模型。若想永久修改 `agy` 的預設 model，可編輯 `agy` 的設定檔或在 Antigravity IDE 的 Model Selection 中切換（`agy` 在非子程序模式下會繼承此設定）。
