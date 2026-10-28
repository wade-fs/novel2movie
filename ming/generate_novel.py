#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
《明末清初》全書群像長篇歷史小說（九卷規劃）全自動循序撰寫指令碼
使用本機 antigravity-cli (agy -p) 原生模型生成，完全不使用任何外部 GEMINI_API_KEY。
每章 prompt 會附上 chapter_writer_rules.md 全文、outline.md 節錄、前一章結尾與下一章章綱，並在產出後依規範驗收（標題、六項工作紀錄、字數、時代錯置警告），未通過者退稿重寫。
支援指定起始章節、缺章自動偵測與回補（Gap Detection）、智慧略過已存在章節、Dry Run 預覽，
待全書完成後，一次性執行 git commit & git push，並可選磁碟休眠。
"""

import os
import sys
import re
import glob
import time
import json
import base64
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

ACCOUNTS_BASE_DIR = os.path.expanduser("~/.gemini/accounts")

def get_account_env(account=None):
    """
    依據指定帳號配置隔離執行環境，隔離系統 Keyring 與其他帳號干擾。
    若未指定 account，優先使用已綁定的 'wade' 帳號（若存在），徹底解決 Keyring 覆蓋問題。
    """
    env = dict(os.environ)
    env.pop("GEMINI_API_KEY", None)
    # 關閉 D-Bus 連線，防止 GNOME Keyring 攔截並覆蓋指定帳號憑證
    env["DBUS_SESSION_BUS_ADDRESS"] = "disabled"

    if account:
        acc_dir = os.path.join(ACCOUNTS_BASE_DIR, account)
        token_path = os.path.join(acc_dir, ".gemini", "antigravity-cli", "antigravity-oauth-token")
        if not os.path.exists(token_path):
            # 支援直接存於 acc_dir/antigravity-oauth-token 的情況
            legacy_token = os.path.join(acc_dir, "antigravity-oauth-token")
            if os.path.exists(legacy_token):
                os.makedirs(os.path.dirname(token_path), exist_ok=True)
                shutil.copy2(legacy_token, token_path)
            else:
                raise FileNotFoundError(f"找不到帳號 '{account}' 的憑證檔案（預期：{token_path}）。\n請先執行：python3 ming/generate_novel.py --add-account {account}")
        env["HOME"] = acc_dir
    else:
        # 預設行為：若 ~/.gemini/accounts/wade 存在則優先使用，以確保使用 wade.fs@gmail.com
        wade_token = os.path.join(ACCOUNTS_BASE_DIR, "wade", ".gemini", "antigravity-cli", "antigravity-oauth-token")
        if os.path.exists(wade_token):
            env["HOME"] = os.path.join(ACCOUNTS_BASE_DIR, "wade")

    return env

def extract_email(tdata):
    """自 oauth token 資料中提取登入者的 Google email"""
    if not isinstance(tdata, dict):
        return "未知"
    if tdata.get("id_token_email"):
        return tdata["id_token_email"]
    id_token = tdata.get("id_token")
    if id_token and isinstance(id_token, str):
        try:
            parts = id_token.split(".")
            if len(parts) >= 2:
                payload_b64 = parts[1]
                payload_b64 += "=" * (-len(payload_b64) % 4)
                payload = json.loads(base64.b64decode(payload_b64).decode("utf-8"))
                return payload.get("email") or "已授權"
        except Exception:
            pass
    return "已授權"

def list_accounts():
    """列出目前已設定的所有帳號及各自的即時配額狀態"""
    log("=== 查詢已配置之帳號與即時配額 ===")
    if not os.path.exists(ACCOUNTS_BASE_DIR):
        log(f"目前尚無配置的帳號目錄: {ACCOUNTS_BASE_DIR}")
        return

    accounts = [d for d in os.listdir(ACCOUNTS_BASE_DIR) if os.path.isdir(os.path.join(ACCOUNTS_BASE_DIR, d))]
    if not accounts:
        log("目前尚無任何自訂帳號。")
        return

    for acc in sorted(accounts):
        acc_dir = os.path.join(ACCOUNTS_BASE_DIR, acc)
        token_file = os.path.join(acc_dir, ".gemini", "antigravity-cli", "antigravity-oauth-token")
        email_str = "未知"
        if os.path.exists(token_file):
            try:
                with open(token_file, "r", encoding="utf-8") as f:
                    tdata = json.load(f)
                    email_str = extract_email(tdata)
            except Exception:
                pass
        is_default = " (預設執行帳號)" if acc == "wade" else ""
        log(f"\n[帳號別名: {acc}]{is_default} -> 綁定 Google 帳號: {email_str}")
        try:
            env = get_account_env(acc)
            res = subprocess.run([AGY_BIN, "-p", "/usage"], capture_output=True, text=True, env=env, timeout=15)
            if res.returncode == 0:
                log("  {:<24} {:<28} {:<10} {}".format("模型群組", "配額週期 / 類型", "剩餘比例", "重設時間"))
                log("  " + "-" * 75)
                for line in res.stdout.strip().splitlines():
                    parts = line.split("\t")
                    if len(parts) >= 3:
                        m_group = parts[0].strip()
                        q_type = parts[1].strip()
                        q_rem = parts[2].strip()
                        q_reset = parts[3].strip() if len(parts) > 3 else ""
                        log("  {:<24} {:<28} {:<10} {}".format(m_group, q_type, q_rem, q_reset))
                    else:
                        log(f"  {line}")
            else:
                log(f"  配額查詢失敗: {res.stderr.strip()[:150]}")
        except Exception as e:
            log(f"  查詢例外: {e}")

def add_account(account_name):
    """互動式為新帳號登入並保存憑證，之後即可使用 --account 指定"""
    acc_dir = os.path.join(ACCOUNTS_BASE_DIR, account_name)
    cli_dir = os.path.join(acc_dir, ".gemini", "antigravity-cli")
    os.makedirs(cli_dir, exist_ok=True)
    log(f"=== 開始綁定新帳號: '{account_name}' ===")
    log(f"建立專屬憑證目錄: {acc_dir}")
    log("請在終端提示中完成授權連結點擊與登入（輸入驗證碼後按 Enter）：\n")
    env = dict(os.environ)
    env.pop("GEMINI_API_KEY", None)
    env["HOME"] = acc_dir
    env["DBUS_SESSION_BUS_ADDRESS"] = "disabled"
    try:
        subprocess.run([AGY_BIN, "-p", "echo 登入測試"], env=env)
        token_file = os.path.join(cli_dir, "antigravity-oauth-token")
        if os.path.exists(token_file):
            with open(token_file, "r", encoding="utf-8") as f:
                tdata = json.load(f)
                email = tdata.get("id_token_email", "成功")
            log(f"\n恭喜！帳號 '{account_name}' ({email}) 綁定成功！")
            log(f"未來可直接加上參數：--account {account_name}")
        else:
            log("未能確認憑證檔案生成，請再試一次。")
    except Exception as e:
        log(f"綁定過程發生錯誤: {e}")

def check_quota_status():
    """嘗試讀取本機 quota 狀態，回傳剩餘資訊字串（若有）"""
    quota_file = os.path.expanduser("~/.gemini/antigravity-cli/quota_live.json")
    if os.path.exists(quota_file):
        try:
            with open(quota_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            quotas = data.get("quota", {})
            g5h = quotas.get("gemini-5h", {})
            rem = g5h.get("remaining_fraction", None)
            reset_sec = g5h.get("reset_in_seconds", None)
            if rem is not None:
                return f"gemini-5h 剩餘配額: {rem*100:.1f}%, 預計重設剩餘: {reset_sec} 秒"
        except Exception:
            pass
    return None

def call_agy_cli(prompt, max_retries=8, model=None, account=None):
    """
    透過本機 antigravity-cli (agy -p) 模型原生調用生成。
    完全不使用外部 GEMINI_API_KEY。
    具備智慧配額偵測、帳號隔離、stderr 捕獲與指數退避冷卻機制。
    """
    env = get_account_env(account)

    for attempt in range(1, max_retries + 1):
        try:
            cmd = [
                AGY_BIN,
                "--disable-slash-commands",
                "--dangerously-skip-permissions",
                "--output-format", "text",
            ]
            if model:
                cmd.extend(["--model", model])
            cmd.extend(["-p", prompt])

            res = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=REPO_DIR)
            output = res.stdout.strip()
            stderr_msg = res.stderr.strip()

            if res.returncode == 0 and len(output) > 500:
                return output
            else:
                # 判定是否為配額或速率限制 (429 / RESOURCE_EXHAUSTED / code 3)
                is_rate_limit = (
                    res.returncode == 3 or
                    "429" in stderr_msg or
                    "RESOURCE_EXHAUSTED" in stderr_msg or
                    "quota" in stderr_msg.lower() or
                    "rate limit" in stderr_msg.lower()
                )

                log(f"agy 返回非零或字數過短 (code {res.returncode}, 輸出長度 {len(output)}) 第 {attempt}/{max_retries} 次嘗試")
                if stderr_msg:
                    log(f"  [agy stderr 提示]: {stderr_msg[:300]}")

                quota_info = check_quota_status()
                if quota_info:
                    log(f"  [本機配額狀態]: {quota_info}")

                if is_rate_limit:
                    # 遭遇配額或速率限制時，採取漸進式冷卻等待，避免短時間內盲目重試
                    wait_time = min(30 * attempt, 180)
                    log(f"  偵測到配額/速率限制 (code 3 / 429)，啟動保護性冷卻：等待 {wait_time} 秒後重試...")
                    time.sleep(wait_time)
                else:
                    # 一般性非零退出或輸出過短
                    wait_time = min(5 * attempt, 30)
                    log(f"  等待 {wait_time} 秒後重試...")
                    time.sleep(wait_time)

        except Exception as e:
            log(f"agy 調用例外 第 {attempt}/{max_retries} 次嘗試: {e}")
            time.sleep(10)

    raise RuntimeError(f"在 {max_retries} 次重試後 agy 調用依然失敗。建議檢查配額或稍後重試。")

# ---------------------------------------------------------------------------
# 規範載入、上下文銜接與產出驗收
# ---------------------------------------------------------------------------
RULES_FILE = os.path.join(BASE_DIR, "chapter_writer_rules.md")
OUTLINE_FILE = os.path.join(BASE_DIR, "outline.md")
MAX_PROMPT_BYTES = 110000  # Linux 單一 argv 上限 131072 bytes，保留餘裕

# 章後工作紀錄必備六項（chapter_writer_rules.md 第 16.5 節）
RECORD_TAGS = ["【史實骨架】", "【待核事項】", "【合理推演】", "【小說虛構】", "【連續性變更】", "【後續銜接】"]

# 僅發出警告、不退稿的時代錯置/待核檢查（見 chapter_writer_rules.md 第 5.2、5.5 節）
WARN_PATTERNS = [
    (r"莊妃.{0,60}洪承疇|洪承疇.{0,60}莊妃", "莊妃勸降洪承疇屬【後世傳聞】，不得寫成事實"),
    (r"軍機處", "軍機處設於雍正朝（1729–1732），須確認是否時代錯置"),
    (r"鄭克[𡒉臧].{0,12}(非|不是).{0,6}親生", "血統指控屬【政治指控／待核】，須寫成政敵主張而非事實"),
    (r"(明朝|大明|明室).{0,8}(必亡|注定滅亡|氣數已盡)", "避免把明亡寫成當時人眼中的必然（規範 4.2、16.3）"),
]

_spec_cache = {}


def read_text(path):
    if path not in _spec_cache:
        with open(path, "r", encoding="utf-8") as f:
            _spec_cache[path] = f.read()
    return _spec_cache[path]


def load_rules():
    """逐章寫手規範全文（chapter_writer_rules.md）。缺檔即中止，避免無規範寫作。"""
    if not os.path.exists(RULES_FILE):
        raise FileNotFoundError(f"找不到寫作規範 {RULES_FILE}，無法在無規範狀態下寫作。")
    return read_text(RULES_FILE)


def load_outline_context(volume_num):
    """自 outline.md 取出：一、全書敘事定位；二、基本寫作原則；本卷段落；十二、九卷銜接。"""
    if not os.path.exists(OUTLINE_FILE):
        return ""
    text = read_text(OUTLINE_FILE)
    # 以「## 」二級標題切段
    parts = re.split(r"(?m)^(?=## )", text)
    picked = []
    cn_vol = to_cn(volume_num)
    for part in parts:
        head = part.splitlines()[0] if part.strip() else ""
        if head.startswith("## 一、") or head.startswith("## 二、") or head.startswith("## 十二、"):
            picked.append(part.strip())
        elif re.search(r"第" + cn_vol + r"卷", head):
            picked.append(part.strip())
    return "\n\n".join(picked)


def split_body_and_record(content):
    """切出正文與章後工作紀錄。"""
    m = re.search(r"(?m)^#{1,3}\s*章後工作紀錄", content)
    if not m:
        return content, ""
    return content[:m.start()], content[m.start():]


def read_chapter_file(chap_num, outlines):
    if chap_num not in outlines:
        return None
    vol = outlines[chap_num][0]
    path = os.path.join(CHAPTERS_DIR, f"volume{vol}", f"chap{chap_num:03d}.md")
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    return None


def build_continuity(chap_num, outlines):
    """前一章結尾與後續銜接、下一章章綱，供本章銜接使用。"""
    chunks = []
    prev = read_chapter_file(chap_num - 1, outlines)
    if prev:
        body, record = split_body_and_record(prev)
        tail = body.rstrip().rstrip("-").rstrip()[-900:]
        chunks.append(f"【前一章（第 {chap_num - 1} 章）正文結尾（最後約 900 字）】\n{tail}")
        m = re.search(r"【後續銜接】[：:\s*]*(.+?)(?:\n\n|\Z)", record, re.S)
        if m:
            chunks.append("【前一章工作紀錄中的【後續銜接】（本章必須承接）】\n" + m.group(1).strip()[:700])
        m = re.search(r"【連續性變更】[：:\s*]*(.+?)(?:\n\s*[-*]*\s*\*{0,2}【|\Z)", record, re.S)
        if m:
            chunks.append("【前一章【連續性變更】（人物狀態、物件歸屬、承諾、傷病）】\n" + m.group(1).strip()[:700])
    elif chap_num == 1 or (chap_num - 1) not in outlines:
        chunks.append("【前一章】本章為全書（或本卷銜接點）的首章，沒有前章可承接；請以人物名冊與章綱作為銜接依據。")
    else:
        chunks.append(f"【前一章】第 {chap_num - 1} 章尚未生成。請以人物名冊與章綱作為銜接依據，不得假設未確立的劇情已發生。")

    if (chap_num + 1) in outlines:
        _, ntitle, nout = outlines[chap_num + 1]
        chunks.append(f"【下一章（第 {chap_num + 1} 章）章綱摘要（本章結尾須提供銜接條件，但不得提前寫出其內容）】\n"
                      f"章名：{ntitle}\n{nout.strip()[:800]}")
    return "\n\n".join(chunks)


def validate_chapter(content, chap_num, cn_title):
    """
    驗收（chapter_writer_rules.md 第十三、十四章的可程式化部分）。
    回傳 (errors, warnings)。errors 非空則退稿重寫。
    """
    errors, warnings = [], []
    lines = [ln for ln in content.splitlines() if ln.strip()]
    first = lines[0].strip() if lines else ""
    expected_prefix = f"# 第{to_cn(chap_num)}章"
    if not first.startswith(expected_prefix):
        errors.append(f"第一行標題必須以「{expected_prefix}」開頭（目前為：{first[:30]}）")

    body, record = split_body_and_record(content)
    if not record:
        errors.append("缺少「## 章後工作紀錄」")
    else:
        pos = -1
        for tag in RECORD_TAGS:
            i = record.find(tag, pos + 1)
            if i == -1:
                errors.append(f"章後工作紀錄缺少或順序錯誤：{tag}")
            else:
                pos = i

    body_len = len(re.sub(r"\s+", "", body))
    if body_len < 2500:
        errors.append(f"正文過短（約 {body_len} 字，規範為 3,000–5,000 字）")
    elif body_len > 9000:
        warnings.append(f"正文偏長（約 {body_len} 字，規範為 3,000–5,000 字，重要轉折章可適度延長）")

    # 正文不得混入編輯註記或評分（規範第十四章）
    for bad in ("品質評分", "硬性條件檢查", "驗收結果", "工作卡"):
        if bad in body:
            errors.append(f"正文混入編輯內容：「{bad}」")
            break
    for tag in ("**保住：**", "**失去：**", "**入日常：**", "保住——", "入日常——"):
        if tag in body:
            errors.append("正文把「四問」逐項標題式寫出（規範 3.3 禁止）")
            break

    for pat, msg in WARN_PATTERNS:
        if re.search(pat, body):
            warnings.append(msg)
    return errors, warnings


def strip_code_fence(text):
    """模型偶爾把全文包在 ``` 中，去除外層圍欄。"""
    t = text.strip()
    m = re.match(r"^```[a-zA-Z]*\n(.*)\n```$", t, re.S)
    return m.group(1).strip() if m else t


def build_prompt(chap_num, volume_num, title, outline, outlines, feedback=None):
    cn_title = f"第{to_cn(chap_num)}章　{title}"
    clean_outline = (
        outline.replace("姦淫", "殘害逼迫")
               .replace("強姦", "殘害")
               .replace("屠戮俘虜", "嚴酷處置俘虜")
    )
    rules = load_rules()
    outline_ctx = load_outline_context(volume_num)
    continuity = build_continuity(chap_num, outlines)

    fb = ""
    if feedback:
        fb = "\n【上一次產出被退稿的原因，本次必須全部修正】\n" + "\n".join(f"- {e}" for e in feedback) + "\n"

    prompt = f"""你是歷史小說寫手，正在創作史詩群像長篇歷史小說《明末清初》（九卷、共 810 章，1624–1722）。
下列《逐章寫手規範》是本專案的強制規範，你必須逐條遵守；其中第 5.2 節列出的「已鎖定待核項目」尤其不得寫成確定史實。
專案目錄為 ming/，如需查閱人物、關係、地理、制度、年代校核與查證結果，可自行讀取該目錄下的
characters.md、relationships.md、social_geography.md、social_systems_and_daily_life.md、
chronology_and_fact_checks.md、historical_evidence_review.md（§十一為已查證結果與待核清單）。

==================== 《逐章寫手規範》全文 ====================
{rules}

==================== 全書總綱（節錄）====================
{outline_ctx}

==================== 本章資料 ====================
【本章所屬卷別】：第 {volume_num} 卷
【本章標題】：{cn_title}
【本章章綱資料】：
{clean_outline}

==================== 銜接資料 ====================
{continuity}
{fb}
==================== 輸出要求 ====================
1. 第一行必須是：`# {cn_title}`
2. 其後直接是小說正文，約 3,000–5,000 字；不得插入編輯註記、核查清單、評分或生成過程說明。
3. 正文結束後空一行，寫 `---`，再寫 `## 章後工作紀錄`，依序包含六項：【史實骨架】【待核事項】【合理推演】【小說虛構】【連續性變更】【後續銜接】。
4. 章綱的「層級」欄位標出的【史實骨架】【合理推演】【小說虛構】須原樣區分，不得把虛構標成史實。
5. 本章章綱若與規範第 5.2 節之待核項目有關，正文只能寫模糊時間或保留異說，並在【待核事項】列出。
6. 正文須承接上方「前一章」的結尾狀態，並為「下一章」提供銜接條件。
7. 請直接輸出完整 markdown 文本，不要任何開場白、結語，也不要用程式碼區塊包住全文。
"""
    return prompt


def generate_chapter(chap_num, volume_num, title, outline, model=None, account=None,
                     outlines=None, lenient=False, max_quality_retries=3):
    """
    依據 ming/chapter_writer_rules.md 與 outline.md，將章綱擴寫成正式小說章節。
    產出須通過 validate_chapter；未通過者附回饋重寫，最多 max_quality_retries 次。
    """
    outlines = outlines or {}
    cn_title = f"第{to_cn(chap_num)}章　{title}"
    log(f"開始生成 第 {chap_num} 章（卷 {volume_num}）：{title}")

    feedback = None
    content = ""
    for attempt in range(1, max_quality_retries + 1):
        prompt = build_prompt(chap_num, volume_num, title, outline, outlines, feedback)
        size = len(prompt.encode("utf-8"))
        if size > MAX_PROMPT_BYTES:
            log(f"  提示詞 {size} bytes 超過上限 {MAX_PROMPT_BYTES}，改用精簡版（略去總綱節錄）。")
            prompt = prompt.replace(load_outline_context(volume_num), "（因長度限制略，請自行讀取 ming/outline.md）")
            size = len(prompt.encode("utf-8"))
            if size > 125000:
                raise RuntimeError(f"提示詞仍達 {size} bytes，超過 argv 上限，請縮減規範或章綱。")
        log(f"  提示詞 {size} bytes，第 {attempt}/{max_quality_retries} 次品質嘗試")

        content = strip_code_fence(call_agy_cli(prompt, model=model, account=account))
        errors, warnings = validate_chapter(content, chap_num, cn_title)
        for w in warnings:
            log(f"  [警告] 第 {chap_num} 章：{w}")
        if not errors:
            break
        for e in errors:
            log(f"  [退稿原因] 第 {chap_num} 章：{e}")
        feedback = errors
    else:
        rej_dir = os.path.join(CHAPTERS_DIR, "_rejected")
        os.makedirs(rej_dir, exist_ok=True)
        rej_path = os.path.join(rej_dir, f"chap{chap_num:03d}.md")
        with open(rej_path, "w", encoding="utf-8") as f:
            f.write(content + "\n")
        if not lenient:
            raise RuntimeError(
                f"第 {chap_num} 章連續 {max_quality_retries} 次未通過驗收，已存至 {rej_path}。"
                f"請檢查後以 --lenient 接受草稿，或調整規範後重跑。"
            )
        log(f"  [lenient] 第 {chap_num} 章未通過驗收，仍依 --lenient 寫入。草稿副本：{rej_path}")

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
    parser.add_argument("--delay", "-d", type=int, default=10, help="每章生成成功後的冷卻休眠秒數（預設 10 秒，防止觸發短時速率限制）")
    parser.add_argument("--model", "-m", type=str, default="gemini-3.8-flash-high", help="指定 agy 呼叫的模型（預設 gemini-3.8-flash-high，可選 claude-sonnet-5-5-high, gemini-4-argon-medium 等）")
    parser.add_argument("--account", "-a", type=str, default=None, help="指定執行的帳號別名（如 wade, chen, pro；預設優先使用 wade 帳號）")
    parser.add_argument("--list-accounts", action="store_true", help="列出所有配置帳號及即時剩餘配額")
    parser.add_argument("--add-account", type=str, default=None, help="互動式登入並綁定新帳號（如 --add-account pro）")
    parser.add_argument("--force", "-f", action="store_true", help="強制重新生成已存在且完整（>1000位元組）的章節")
    parser.add_argument("--dry-run", action="store_true", help="僅檢查當前各卷章節與缺失狀況，不執行生成")
    parser.add_argument("--no-hibernate", action="store_true", help="全書完成後不執行休眠")
    parser.add_argument("--lenient", action="store_true", help="章節連續未通過驗收時仍寫入（預設為停止並存入 chapters/_rejected/）")
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

    if args.list_accounts:
        list_accounts()
        return

    if args.add_account:
        add_account(args.add_account)
        return

    log("=== 《明末清初》antigravity-cli 原生寫書守護進程啟動（完全免 API KEY） ===")
    if args.account:
        log(f"指定執行帳號: {args.account}")
    else:
        log("執行帳號: 預設隔離帳號（wade.fs@gmail.com 優先）")
    log(f"指定呼叫模型: {args.model}")
    rules_text = load_rules()
    log(f"已載入寫作規範：{os.path.basename(RULES_FILE)}（{len(rules_text)} 字元）；每章將附上規範全文、總綱節錄、前一章結尾與下一章章綱。")
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

        generate_chapter(chap_num, vol_num, title, outline, model=args.model, account=args.account,
                         outlines=outlines, lenient=args.lenient)
        existing_chapters.add(chap_num)
        update_progress(chap_num, status="running")

        # 連續生成時保持健康冷卻間隔，平滑消耗速率，避免短時間內打滿 Burst 配額
        time.sleep(args.delay)

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
