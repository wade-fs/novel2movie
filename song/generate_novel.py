#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
《宋末》全書八卷（720章）全自動循序撰寫指令碼
使用本機 antigravity-cli (agy -p) 原生模型生成，完全不使用任何外部 GEMINI_API_KEY。
不逐章 commit，待全部 720 章完成後，一次性執行 git commit & git push，
最後執行 echo disk | sudo tee /sys/power/state 進入休眠。
"""

import os
import sys
import re
import time
import json
import base64
import shutil
import argparse
import subprocess

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.dirname(BASE_DIR)
PROGRESS_FILE = os.path.join(BASE_DIR, "chapters", "progress.json")
LOG_FILE = os.path.join(BASE_DIR, "chapters", "generator.log")
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
    pattern_ar = re.compile(r"^(?:#{3,4})\s*第\s*([0-9]{1,3})\s*章(?:[：:　\s]+)(.+)$")
    pattern_cn = re.compile(r"^(?:#{3,4})\s*第\s*([一二三四五六七八九十百零]+)\s*章(?:[：:　\s]+)(.+)$")

    chapters = {}
    for v in range(1, 9):
        fn = os.path.join(BASE_DIR, f"volume{v}.md")
        if not os.path.exists(fn):
            log(f"警告: 找不到章綱檔案 {fn}")
            continue
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
    log(f"成功解析全書章綱共 {len(chapters)} 章。")
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
            legacy_token = os.path.join(acc_dir, "antigravity-oauth-token")
            if os.path.exists(legacy_token):
                os.makedirs(os.path.dirname(token_path), exist_ok=True)
                shutil.copy2(legacy_token, token_path)
            else:
                raise FileNotFoundError(f"找不到帳號 '{account}' 的憑證檔案（預期：{token_path}）。\n請先執行：python3 song/generate_novel.py --add-account {account}")
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

            res = subprocess.run(cmd, capture_output=True, text=True, env=env)
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

def generate_chapter(chap_num, volume_num, title, outline, model=None, account=None):
    cn_title = f"第{to_cn(chap_num)}章　{title}"
    log(f"開始生成 第 {chap_num} 章（卷 {volume_num}）：{title}")

    clean_outline = (
        outline.replace("姦淫", "殘害逼迫")
               .replace("強姦", "殘害")
               .replace("屠戮俘虜", "嚴酷處置俘虜")
    )

    prompt = f"""你是一名嚴謹宏大的歷史小說家與北宋、南宋歷史學者，正在創作史詩群像長篇歷史小說《宋末》（全書共八卷，720章）。
請嚴格依據《逐章寫手規範》將章綱擴寫成具有沉浸敘事、厚重歷史感、鮮活人物行動的小說章節。

【寫作強制規範】：
1. 標題格式必須為第一行：`# {cn_title}`
2. 正文篇幅：必須達到 3,000 ～ 5,000 字（絕不可摘要、速寫或跳過細節，務求飽滿詳實）。
3. 敘事原則：
   - 嚴格鎖定指定視角人物，去神化、拒絕上帝視角與現代術語（嚴禁現代詞彙）。
   - 沉浸式感官細節：聲音、氣味、光線、風霜雨雪、服飾材質、泥濘道路、市井叫賣、兵刃甲冑質感。
   - 制度與名物嚴謹：官署官階、公文程式（公券、劄子、牙帖、度牒、交子、會子、引鈔）、兵制（禁軍、廂軍、鄉兵、義勇、簽軍、千戶、怯薛）、貨幣成色（銅錢、折二錢、當十錢、銀鋌、金鋌、貼水）。
   - 微觀衝突推動宏觀歷史：展現大時代風雲如何滲透並主宰市井小民、基層吏卒、商賈工匠、邊關斥候與前線士卒的命運抉擇。
4. 正文結束後空行，使用 `---` 分隔線。
5. 必須完整附帶 `## 章後工作紀錄`，包含以下六項（每項皆需詳細展開）：
   - **【史實骨架】：** 結合正史文獻標註本章事件、人物行蹤與制度背景。
   - **【待核事項】：** 列出本章涉及的細節考證與時間、官制疑點。
   - **【合理推演】：** 解釋本章如何依據歷史邏輯推導未載於史冊的人事互動與基層資訊傳播。
   - **【小說虛構】：** 標記本章虛構人物、微觀衝突與戲劇細節。
   - **【連續性變更】：** 說明本章人物狀態、物資、關係相較前文的變化。
   - **【後續銜接】：** 說明本章結尾如何與後續歷史事件及下一章自然銜接。

【本章所屬卷別】：第 {volume_num} 卷
【本章章綱資料】：
{clean_outline}

請直接輸出完整 markdown 文本，不可省略，不要任何無關開場白或結束語。
"""
    content = call_agy_cli(prompt, model=model, account=account)

    vol_dir = os.path.join(BASE_DIR, "chapters", f"volume{volume_num}")
    os.makedirs(vol_dir, exist_ok=True)
    out_path = os.path.join(vol_dir, f"chap{chap_num:03d}.md")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(content + "\n")

    log(f"完成第 {chap_num} 章寫入：{out_path}（長度：{len(content)} 字元）")
    return out_path

def update_progress(chap_num, status="running"):
    try:
        os.makedirs(os.path.dirname(PROGRESS_FILE), exist_ok=True)
        with open(PROGRESS_FILE, "w", encoding="utf-8") as f:
            json.dump({
                "last_completed_chapter": chap_num,
                "status": status,
                "updated_at": time.strftime("%Y-%m-%d %H:%M:%S")
            }, f, ensure_ascii=False, indent=2)
    except Exception as e:
        log(f"寫入進度失敗: {e}")

def parse_args():
    parser = argparse.ArgumentParser(description="《宋末》全書循序撰寫守護進程")
    parser.add_argument("start_chapter", nargs="?", type=int, default=None, help="起始章節編號（例如 221）")
    parser.add_argument("--start", "-s", type=int, default=None, help="指定起始章節編號")
    parser.add_argument("--end", "-e", type=int, default=720, help="指定結束章節編號（預設 720）")
    parser.add_argument("--delay", "-d", type=int, default=10, help="每章生成成功後的冷卻休眠秒數（預設 10 秒，防止觸發短時速率限制）")
    parser.add_argument("--model", "-m", type=str, default="gemini-3.8-flash-high", help="指定 agy 呼叫的模型（預設 gemini-3.8-flash-high，可選 claude-sonnet-5-5-high, gemini-4-argon-medium 等）")
    parser.add_argument("--account", "-a", type=str, default=None, help="指定執行的帳號別名（如 wade, chen, pro；預設優先使用 wade 帳號）")
    parser.add_argument("--list-accounts", action="store_true", help="列出所有配置帳號及即時剩餘配額")
    parser.add_argument("--add-account", type=str, default=None, help="互動式登入並綁定新帳號（如 --add-account pro）")
    parser.add_argument("--force", "-f", action="store_true", help="強制重新生成已存在且完整（>1000位元組）的章節")
    parser.add_argument("--dry-run", action="store_true", help="僅檢查當前各卷章節與缺失狀況，不執行生成")
    parser.add_argument("--no-hibernate", action="store_true", help="全書完成後不執行休眠")
    return parser.parse_args()

def scan_chapters():
    existing = set()
    missing = []
    for n in range(1, 721):
        vol = (n - 1) // 90 + 1
        expected_path = os.path.join(BASE_DIR, "chapters", f"volume{vol}", f"chap{n:03d}.md")
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

    log("=== 《宋末》antigravity-cli 原生寫書守護進程啟動（完全免 API KEY） ===")
    if args.account:
        log(f"指定執行帳號: {args.account}")
    else:
        log("執行帳號: 預設隔離帳號（wade.fs@gmail.com 優先）")
    log(f"指定呼叫模型: {args.model}")
    outlines = load_outlines()
    if len(outlines) != 720:
        log(f"錯誤: 讀取的章綱數量為 {len(outlines)}，非 720 章，請檢查。")

    existing_chapters, missing_chapters = scan_chapters()
    log(f"目前已存在完整章節數：{len(existing_chapters)} / 720，缺失章節數：{len(missing_chapters)}")

    # 決定起始章節
    specified_start = args.start or args.start_chapter
    if specified_start is not None:
        start_chap = specified_start
        log(f"依使用者指令指定，從第 {start_chap} 章開始執行。")
    elif missing_chapters:
        start_chap = missing_chapters[0]
        log(f"自動偵測首個缺失章節：從第 {start_chap} 章開始執行補寫。")
    else:
        start_chap = 721
        log("全書 720 章皆已存在且完整！")

    end_chap = min(args.end, 720)

    if args.dry_run:
        log("--- [Dry Run 模式] ---")
        log(f"執行範圍: 第 {start_chap} 章 ～ 第 {end_chap} 章 (force={args.force})")
        if missing_chapters:
            preview = missing_chapters[:15]
            log(f"缺失章節前 15 項: {preview}" + ("..." if len(missing_chapters) > 15 else ""))
        log("Dry run 結束。")
        return

    for chap_num in range(start_chap, end_chap + 1):
        if chap_num not in outlines:
            log(f"錯誤: 找不到第 {chap_num} 章章綱，中斷。")
            break

        vol_num, title, outline = outlines[chap_num]
        expected_path = os.path.join(BASE_DIR, "chapters", f"volume{vol_num}", f"chap{chap_num:03d}.md")

        if not args.force and chap_num in existing_chapters:
            log(f"第 {chap_num} 章已存在且完整（{os.path.getsize(expected_path)} bytes），略過。")
            continue

        generate_chapter(chap_num, vol_num, title, outline, model=args.model, account=args.account)
        existing_chapters.add(chap_num)
        update_progress(chap_num, status="running")

        # 不逐章 commit，連續生成，章節間保持健康冷卻間隔
        time.sleep(args.delay)

    # 檢查是否全部完成至 720 章
    final_chap_path = os.path.join(BASE_DIR, "chapters", "volume8", "chap720.md")
    all_done = (len(existing_chapters) == 720) and os.path.exists(final_chap_path)

    if all_done:
        log("=== 狂賀！全書 720 章已全部撰寫完成！進行一次性 Git 提交與推播 ===")
        try:
            subprocess.run(["git", "add", "song/chapters/"], cwd=REPO_DIR, check=True)
            subprocess.run(["git", "commit", "-m", "feat(song): complete all 720 chapters of Song novel"], cwd=REPO_DIR, check=True)
            subprocess.run(["git", "push", "origin", "main"], cwd=REPO_DIR, check=True)
            log("全書 720 章 Git 提交與推播成功！")
        except Exception as e:
            log(f"Git 最終提交或推送失敗: {e}")

        update_progress(720, status="completed")
        if not args.no_hibernate:
            log("最後執行磁碟休眠命令: echo disk | sudo tee /sys/power/state")
            time.sleep(5)
            os.system("sync && echo disk | sudo tee /sys/power/state")
        else:
            log("已指定 --no-hibernate，略過磁碟休眠。")
    else:
        log(f"目前執行至第 {end_chap} 章（全書完成 {len(existing_chapters)}/720），進程結束。")

if __name__ == "__main__":
    main()
