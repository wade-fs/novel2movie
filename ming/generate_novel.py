#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
《明末清初》全書群像長篇歷史小說（九卷規劃）全自動循序撰寫指令碼
使用本機 antigravity-cli (agy -p) 原生模型生成，完全不使用任何外部 GEMINI_API_KEY。
支援指定起始章節、缺章自動偵測與回補（Gap Detection）、智慧略過已存在章節、Dry Run 預覽，
待全書完成後，一次性執行 git commit & git push，並可選磁碟休眠。
"""

import os
import sys
import re
import glob
import time
import json
import shutil
import argparse
import subprocess

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.dirname(BASE_DIR)
CHAPTERS_DIR = os.path.join(BASE_DIR, "chapters")
PROGRESS_FILE = os.path.join(CHAPTERS_DIR, "progress.json")
LOG_FILE = os.path.join(CHAPTERS_DIR, "generator.log")
AGY_BIN = shutil.which("agy") or "/home/wade/.local/bin/agy"

cn_nums = {"零":0, "一":1, "二":2, "三":3, "四":4, "五":5, "六":6, "七":7, "八":8, "九":9, "十":10, "百":100}

def parse_cn(s):
    val = 0
    temp = 0
    for ch in s:
        if ch in "零一二三四五六七八九":
            temp = "零一二三四五六七八九".index(ch)
        elif ch == "十":
            if temp == 0: temp = 1
            val += temp * 10
            temp = 0
        elif ch == "百":
            val += temp * 100
            temp = 0
    val += temp
    return val

def to_cn(n):
    digits = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九"]
    if n < 10:
        return digits[n]
    elif n < 20:
        return "十" + ("" if n == 10 else digits[n % 10])
    elif n < 100:
        res = digits[n // 10] + "十"
        if n % 10 != 0:
            res += digits[n % 10]
        return res
    else:
        h = n // 100
        rem = n % 100
        res = digits[h] + "百"
        if rem == 0:
            return res
        elif rem < 10:
            return res + "零" + digits[rem]
        elif rem < 20:
            return res + "一十" + ("" if rem == 10 else digits[rem % 10])
        else:
            res += digits[rem // 10] + "十"
            if rem % 10 != 0:
                res += digits[rem % 10]
            return res

def log(msg):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    full_msg = f"[{ts}] {msg}"
    print(full_msg, flush=True)
    try:
        os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(full_msg + "\n")
    except Exception:
        pass

def load_outlines():
    """
    載入章綱資料。
    優先順序：
    1. volume1.md ~ volume9.md (各卷獨立章綱)
    2. 若無獨立分卷檔案，則嘗試從 outline.md 或 volume_plan.md 載入
    """
    pattern_ar = re.compile(r"^(?:#{2,4})\s*第\s*([0-9]{1,4})\s*章(?:[：:　\s]+)(.+)$")
    pattern_cn = re.compile(r"^(?:#{2,4})\s*第\s*([一二三四五六七八九十百零]+)\s*章(?:[：:　\s]+)(.+)$")

    chapters = {}

    # 1. 檢查是否存在分卷章綱檔案 volume*.md
    vol_files = []
    for v in range(1, 10):
        fn = os.path.join(BASE_DIR, f"volume{v}.md")
        if os.path.exists(fn):
            vol_files.append((v, fn))

    if not vol_files:
        # 嘗試尋找任何 volume*.md
        for fn in sorted(glob.glob(os.path.join(BASE_DIR, "volume*.md"))):
            base = os.path.basename(fn)
            m = re.search(r"volume(\d+)", base)
            v = int(m.group(1)) if m else 1
            vol_files.append((v, fn))

    if vol_files:
        for v, fn in vol_files:
            with open(fn, "r", encoding="utf-8") as f:
                lines = f.readlines()
            cur_num = None
            cur_title = None
            cur_lines = []
            for line in lines:
                s = line.strip()
                m_ar = pattern_ar.match(s)
                m_cn = pattern_cn.match(s)
                if m_ar:
                    if cur_num is not None:
                        chapters[cur_num] = (v, cur_title, "".join(cur_lines).strip())
                    cur_num = int(m_ar.group(1))
                    cur_title = m_ar.group(2).strip()
                    cur_lines = []
                elif m_cn:
                    if cur_num is not None:
                        chapters[cur_num] = (v, cur_title, "".join(cur_lines).strip())
                    cur_num = parse_cn(m_cn.group(1))
                    cur_title = m_cn.group(2).strip()
                    cur_lines = []
                else:
                    if cur_num is not None:
                        cur_lines.append(line)
            if cur_num is not None:
                chapters[cur_num] = (v, cur_title, "".join(cur_lines).strip())
        log(f"成功自分卷章綱檔案解析共 {len(chapters)} 章。")
        return chapters

    # 2. 若無分卷檔案，嘗試從 outline.md 載入
    outline_fn = os.path.join(BASE_DIR, "outline.md")
    if os.path.exists(outline_fn):
        with open(outline_fn, "r", encoding="utf-8") as f:
            lines = f.readlines()
        cur_num = None
        cur_title = None
        cur_vol = 1
        cur_lines = []
        vol_pattern = re.compile(r"^(?:#{1,3})\s*(?:第\s*([一二三四五六七八九十0-9]+)\s*卷|卷\s*([一二三四五六七八九十0-9]+))")
        for line in lines:
            s = line.strip()
            vm = vol_pattern.match(s)
            if vm:
                v_str = vm.group(1) or vm.group(2)
                cur_vol = int(v_str) if v_str.isdigit() else parse_cn(v_str)

            m_ar = pattern_ar.match(s)
            m_cn = pattern_cn.match(s)
            if m_ar:
                if cur_num is not None:
                    chapters[cur_num] = (cur_vol, cur_title, "".join(cur_lines).strip())
                cur_num = int(m_ar.group(1))
                cur_title = m_ar.group(2).strip()
                cur_lines = []
            elif m_cn:
                if cur_num is not None:
                    chapters[cur_num] = (cur_vol, cur_title, "".join(cur_lines).strip())
                cur_num = parse_cn(m_cn.group(1))
                cur_title = m_cn.group(2).strip()
                cur_lines = []
            else:
                if cur_num is not None:
                    cur_lines.append(line)
        if cur_num is not None:
            chapters[cur_num] = (cur_vol, cur_title, "".join(cur_lines).strip())
        if chapters:
            log(f"成功自 outline.md 解析共 {len(chapters)} 章。")
            return chapters

    log("提示: 目前 ming 目錄中尚未發現已具體展開的章綱檔案（如 volume1.md～volume9.md）。")
    return chapters

def call_agy_cli(prompt, max_retries=6):
    """
    透過本機 antigravity-cli (agy -p) 模型原生調用生成。
    完全不使用外部 GEMINI_API_KEY。
    """
    env = dict(os.environ)
    env.pop("GEMINI_API_KEY", None)

    for attempt in range(1, max_retries + 1):
        try:
            cmd = [
                AGY_BIN,
                "--disable-slash-commands",
                "--output-format", "text",
                "-p", prompt
            ]
            res = subprocess.run(cmd, capture_output=True, text=True, env=env)
            output = res.stdout.strip()
            if res.returncode == 0 and len(output) > 500:
                return output
            else:
                log(f"agy 返回非零或字數過短 (code {res.returncode}, 長度 {len(output)}) 第 {attempt} 次嘗試")
                time.sleep(5)
        except Exception as e:
            log(f"agy 調用例外 第 {attempt} 次嘗試: {e}")
            time.sleep(5)

    raise RuntimeError(f"在 {max_retries} 次重試後 agy 調用依然失敗。")

def generate_chapter(chap_num, volume_num, title, outline):
    """
    依據《明末清初逐章寫手規範》將章綱擴寫成正式小說章節。
    """
    cn_title = f"第{to_cn(chap_num)}章　{title}"
    log(f"開始生成 第 {chap_num} 章（卷 {volume_num}）：{title}")

    clean_outline = (
        outline.replace("姦淫", "殘害逼迫")
               .replace("強姦", "殘害")
               .replace("屠戮俘虜", "嚴酷處置俘虜")
    )

    prompt = f"""你是一名嚴謹宏大的歷史小說家與明代、清初史、南明史及台灣早期史學者，正在創作史詩群像長篇歷史小說《明末清初》（九卷規劃，1624–1722）。
請嚴格依據《逐章寫手規範》與明末清初歷史制度背景，將指定章綱擴寫成具有沉浸敘事、厚重歷史感、鮮活人物行動與生活質感的小說章節。

【寫作核心定位】：
1. 不以單純戰術推演或戰場殺戮為主要內容，而是透過人物的政治選擇、官職升降、徵糧籌餉、三餉加派、物價波動、書信往來、戶籍路引、漕運海運、家族離散、移民開墾、地方治理與文化延續，展現王朝興亡與歷史大勢如何滲透並主宰日常生活。
2. 拒絕預設忠奸臉譜：將歷史人物放回當時的資訊條件、政治制度、家族責任、經濟壓力與可行選項中，展現人物在時代局限下的兩難抉擇與命運代價。
3. 嚴謹區分【史實骨架】、【合理推演】與【小說虛構】：不得擅改已確認的重大歷史事件與人物行蹤，虛構情節須嚴格服從生活常理與歷史邏輯。
4. 台灣海疆獨立敘事主體：若本章涉及海疆、東寧或台灣，須呈現原住民族社會、荷蘭/西班牙殖民網絡、福建海商、鄭氏政權與地方開墾之自身歷史脈絡，拒絕將台灣僅寫成中原政權的附屬背景。

【寫作強制規範】：
1. 標題格式必須為第一行：`# {cn_title}`
2. 正文篇幅：必須達到 3,000 ～ 5,000 字（絕不可摘要、速寫或跳過細節，務求飽滿詳實，描摹具體生動）。
3. 敘事原則：
   - 嚴格鎖定指定視角人物，去神化、拒絕上帝視角與現代術語（嚴禁現代學術名詞與心理學詞彙）。
   - 沉浸式感官細節：聲音、氣味、光線、風霜雨雪、服飾材質、泥濘道路、市井叫賣、海潮鹹澀、火銃硝煙、兵刃甲冑質感。
   - 制度與名物嚴謹：
     * 明廷/南明：內閣、六部、司禮監、錦衣衛、東廠、督撫、兵備道、知府、知縣、胥吏、巡檢；公文程式（奏摺、題本、奏本、檄文、牌票、路引、照會、信劄）。
     * 清初/後金：內三院、議政王大臣會議、六部、旗制（八旗滿洲/蒙古/漢軍、牛錄、甲喇、固山）、綠營、總督、巡撫、提督、總兵。
     * 鄭氏東寧：承天府、安平鎮、天興縣、萬年縣、六官體系；荷蘭熱蘭遮城長官、大員評議會。
     * 經濟與民生：白銀成色（紋銀、碎銀、火耗、銀錠）、制錢成色與錢價比價、糧價（石、斗、升）、三餉（遼餉、剿餉、練餉）、鹽引、茶引、遷界令下流亡生計。
   - 將「四問」（保住、失去、入日常、章末變化）自然融入情節與人物行動，不可生硬列點。
4. 正文結束後空行，使用 `---` 分隔線。
5. 必須完整附帶 `## 章後工作紀錄`，包含以下六項（每項皆需詳細展開）：
   - **【史實骨架】：** 結合文獻（《明史》、《清史稿》、《明季北略》、《明季南略》、《小腆紀年》、《南明史》、《臺灣通史》、《海紀輯要》等）標註本章事件、人物行蹤與制度背景。
   - **【待核事項】：** 列出本章涉及的細節考證與時間、官制、地名、銀錢、文書疑點。
   - **【合理推演】：** 解釋本章如何依據歷史邏輯推導未載於史冊的人事互動、基層資訊傳播、物資流動與心理動機。
   - **【小說虛構】：** 標記本章虛構人物、微觀衝突、家族生活與戲劇細節。
   - **【連續性變更】：** 說明本章人物狀態、物資、關係、職位相較前文之變化。
   - **【後續銜接】：** 說明本章結尾如何與後續歷史事件及下一章自然銜接。

【本章所屬卷別】：第 {volume_num} 卷
【本章章綱資料】：
{clean_outline}

請直接輸出完整 markdown 文本，不可省略，不要任何無關開場白或結束語。
"""
    content = call_agy_cli(prompt)

    vol_dir = os.path.join(CHAPTERS_DIR, f"volume{volume_num}")
    os.makedirs(vol_dir, exist_ok=True)
    out_path = os.path.join(vol_dir, f"chap{chap_num:03d}.md")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(content + "\n")

    log(f"完成第 {chap_num} 章寫入：{out_path}（長度：{len(content)} 字元）")
    return out_path

def update_progress(chap_num, status="running"):
    try:
        os.makedirs(CHAPTERS_DIR, exist_ok=True)
        with open(PROGRESS_FILE, "w", encoding="utf-8") as f:
            json.dump({
                "last_completed_chapter": chap_num,
                "status": status,
                "updated_at": time.strftime("%Y-%m-%d %H:%M:%S")
            }, f, ensure_ascii=False, indent=2)
    except Exception as e:
        log(f"寫入進度失敗: {e}")

def parse_args():
    parser = argparse.ArgumentParser(description="《明末清初》全書循序撰寫守護進程")
    parser.add_argument("start_chapter", nargs="?", type=int, default=None, help="起始章節編號（例如 1）")
    parser.add_argument("--start", "-s", type=int, default=None, help="指定起始章節編號")
    parser.add_argument("--end", "-e", type=int, default=None, help="指定結束章節編號（預設為章綱最大章號）")
    parser.add_argument("--force", "-f", action="store_true", help="強制重新生成已存在且完整（>1000位元組）的章節")
    parser.add_argument("--dry-run", action="store_true", help="僅檢查當前各卷章節與缺失狀況，不執行生成")
    parser.add_argument("--no-hibernate", action="store_true", help="全書完成後不執行休眠")
    return parser.parse_args()

def scan_chapters(outlines):
    """
    掃描已存在且大於 1000 位元組的章節檔案，並回傳已存在集合與缺失列表。
    """
    existing = set()
    missing = []

    if outlines:
        target_chaps = sorted(outlines.keys())
    else:
        # 若尚未載入章綱，從磁碟目錄中現存檔案尋找
        existing_files = glob.glob(os.path.join(CHAPTERS_DIR, "volume*", "chap*.md"))
        for ef in existing_files:
            m = re.search(r"chap(\d+)\.md", os.path.basename(ef))
            if m and os.path.getsize(ef) > 1000:
                existing.add(int(m.group(1)))
        return existing, []

    for n in target_chaps:
        vol_num = outlines[n][0]
        expected_path = os.path.join(CHAPTERS_DIR, f"volume{vol_num}", f"chap{n:03d}.md")
        if os.path.exists(expected_path) and os.path.getsize(expected_path) > 1000:
            existing.add(n)
        else:
            missing.append(n)

    return existing, missing

def main():
    args = parse_args()
    log("=== 《明末清初》antigravity-cli 原生寫書守護進程啟動（完全免 API KEY） ===")
    outlines = load_outlines()

    total_outlined = len(outlines)
    if total_outlined == 0:
        log("警告: 目前尚未在 ming 目錄找到可用的章綱內容。請先確認 volume*.md 或 outline.md 中已規劃章綱。")
        log("程式支援格式例如：'### 第1章　章名' 或 '### 第 1 章：章名'。")
        return

    max_chap = max(outlines.keys())
    end_chap = min(args.end, max_chap) if args.end is not None else max_chap

    existing_chapters, missing_chapters = scan_chapters(outlines)
    log(f"章綱總章數：{total_outlined} 章（第 {min(outlines.keys())} 章 ～ 第 {max_chap} 章）")
    log(f"目前已存在完整章節數：{len(existing_chapters)} / {total_outlined}，缺失章節數：{len(missing_chapters)}")

    # 決定起始章節
    specified_start = args.start or args.start_chapter
    if specified_start is not None:
        start_chap = specified_start
        log(f"依使用者指令指定，從第 {start_chap} 章開始執行。")
    elif missing_chapters:
        start_chap = missing_chapters[0]
        log(f"自動偵測首個缺失章節：從第 {start_chap} 章開始執行補寫。")
    else:
        start_chap = max_chap + 1
        log("所有章綱對應章節皆已存在且完整！")

    if args.dry_run:
        log("--- [Dry Run 模式] ---")
        log(f"預計執行範圍: 第 {start_chap} 章 ～ 第 {end_chap} 章 (force={args.force})")
        if missing_chapters:
            preview = missing_chapters[:15]
            log(f"缺失章節前 15 項: {preview}" + ("..." if len(missing_chapters) > 15 else ""))
        log("Dry run 結束。")
        return

    for chap_num in range(start_chap, end_chap + 1):
        if chap_num not in outlines:
            log(f"提示: 章綱中未收錄第 {chap_num} 章，略過。")
            continue

        vol_num, title, outline = outlines[chap_num]
        expected_path = os.path.join(CHAPTERS_DIR, f"volume{vol_num}", f"chap{chap_num:03d}.md")

        if not args.force and chap_num in existing_chapters:
            log(f"第 {chap_num} 章已存在且完整（{os.path.getsize(expected_path)} bytes），略過。")
            continue

        generate_chapter(chap_num, vol_num, title, outline)
        existing_chapters.add(chap_num)
        update_progress(chap_num, status="running")

        # 連續生成時保持微小延遲
        time.sleep(2)

    # 檢查是否達到全書完成目標
    all_done = (len(existing_chapters) >= total_outlined) and (total_outlined > 0)

    if all_done and end_chap == max_chap:
        log(f"=== 狂賀！全書共 {total_outlined} 章已全部撰寫完成！進行一次性 Git 提交與推播 ===")
        try:
            subprocess.run(["git", "add", "ming/chapters/"], cwd=REPO_DIR, check=True)
            subprocess.run(["git", "commit", "-m", f"feat(ming): complete all {total_outlined} chapters of Ming novel"], cwd=REPO_DIR, check=True)
            subprocess.run(["git", "push", "origin", "main"], cwd=REPO_DIR, check=True)
            log(f"全書 {total_outlined} 章 Git 提交與推播成功！")
        except Exception as e:
            log(f"Git 最終提交或推送失敗: {e}")

        update_progress(max_chap, status="completed")
        if not args.no_hibernate:
            log("最後執行磁碟休眠命令: echo disk | sudo tee /sys/power/state")
            time.sleep(5)
            os.system("sync && echo disk | sudo tee /sys/power/state")
        else:
            log("已指定 --no-hibernate，略過磁碟休眠。")
    else:
        log(f"目前執行至第 {end_chap} 章（全書完成 {len(existing_chapters)}/{total_outlined}），進程結束。")

if __name__ == "__main__":
    main()
