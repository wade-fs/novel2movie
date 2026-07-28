#!/usr/bin/env python3
import os
import sys
import re
import json
import hashlib
import asyncio
import argparse
import shutil
import subprocess
from mutagen.mp3 import MP3
from PIL import Image, ImageDraw, ImageFont

# ==========================================
# 1. MARKDOWN PARSER
# ==========================================
def parse_novel_markdown(file_path):
    """
    Parses novel chapter markdown files.
    Extracts metadata from headers and cleans paragraphs for TTS.
    """
    if not os.path.exists(file_path):
        print(f"[-] Error: File not found: {file_path}")
        sys.exit(1)

    with open(file_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    novel_name = ""
    volume_title = ""
    chapter_title = ""
    paragraphs = []

    for line in lines:
        line_str = line.strip()
        if not line_str:
            continue
        # Skip navigation links and horizontal rules
        if "[回目錄]" in line_str or "下一章" in line_str or "上一章" in line_str:
            continue
        if re.match(r'^[-*_]{3,}$', line_str):
            continue

        # Heading matching
        m = re.match(r'^(#{1,6})\s+(.*)$', line_str)
        if m:
            level = len(m.group(1))
            text = m.group(2).strip()
            if level == 1:
                novel_name = text
            elif level == 2:
                volume_title = text
            elif level in (3, 4):
                chapter_title = text
        else:
            paragraphs.append(line_str)

    # Fallbacks
    if not novel_name:
        # Check folder structure
        parent_dir = os.path.basename(os.path.dirname(os.path.dirname(os.path.abspath(file_path))))
        novel_name = parent_dir if parent_dir and parent_dir != "chapter" else "精品有聲書"
    if not chapter_title:
        chapter_title = os.path.basename(file_path).replace(".md", "")

    return {
        "novel_name": novel_name,
        "volume_title": volume_title,
        "chapter_title": chapter_title,
        "paragraphs": paragraphs
    }

# ==========================================
# 2. SRT SHIFTING UTILITIES
# ==========================================
def parse_time(time_str):
    h, m, s_ms = time_str.split(':')
    s, ms = s_ms.split(',')
    return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000.0

def format_time(seconds):
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    ms = int(round((seconds - int(seconds)) * 1000))
    if ms >= 1000:
        s += 1
        ms -= 1000
    if s >= 60:
        m += 1
        s -= 60
    if m >= 60:
        h += 1
        m -= 60
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"

def shift_srt_content(srt_content, offset_seconds, start_index=1):
    lines = srt_content.strip().split('\n')
    shifted_lines = []
    current_idx = start_index

    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        if re.match(r'^\d+$', line):
            shifted_lines.append(str(current_idx))
            current_idx += 1
            i += 1
            continue
        match = re.match(r'^(\d{2}:\d{2}:\d{2},\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2},\d{3})', line)
        if match:
            start_t = parse_time(match.group(1)) + offset_seconds
            end_t = parse_time(match.group(2)) + offset_seconds
            shifted_lines.append(f"{format_time(start_t)} --> {format_time(end_t)}")
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(r'^\d+$', lines[i].strip()):
                shifted_lines.append(lines[i])
                i += 1
            shifted_lines.append("")
        else:
            shifted_lines.append(line)
            i += 1

    return "\n".join(shifted_lines), current_idx

# ==========================================
# 3. DYNAMIC COVER GENERATOR (Pillow)
# ==========================================
def find_font():
    possible_paths = [
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
    ]
    for path in possible_paths:
        if os.path.exists(path):
            return path
    for root, _, files in os.walk("/usr/share/fonts"):
        for file in files:
            if file.endswith((".ttf", ".ttc")):
                if any(x in file.lower() for x in ["wqy", "noto", "cjk", "zenhei", "microhei"]):
                    return os.path.join(root, file)
    return None

def draw_centered_text(draw, text, x, y, font, color):
    try:
        draw.text((x, y), text, fill=color, font=font, anchor="mm")
    except Exception:
        try:
            bbox = draw.textbbox((0, 0), text, font=font)
            w = bbox[2] - bbox[0]
            h = bbox[3] - bbox[1]
        except Exception:
            w = len(text) * font.size * 0.7
            h = font.size
        draw.text((x - w/2, y - h/2), text, fill=color, font=font)

def create_dynamic_cover(novel_name, volume_title, chapter_title, output_path):
    width, height = 1920, 1080

    # 1. Create a beautiful diagonal gradient background (obsidian to deep purple)
    color1 = (10, 10, 25)
    color2 = (45, 20, 60)
    small_img = Image.new('RGB', (8, 8))
    for y in range(8):
        for x in range(8):
            factor = (x + y) / 14.0
            r = int(color1[0] + (color2[0] - color1[0]) * factor)
            g = int(color1[1] + (color2[1] - color1[1]) * factor)
            b = int(color1[2] + (color2[2] - color1[2]) * factor)
            small_img.putpixel((x, y), (r, g, b))
    
    img = small_img.resize((width, height), Image.Resampling.BILINEAR)
    draw = ImageDraw.Draw(img)

    # 2. Draw subtle background patterns (concentric circles for quantum/space feel)
    for r in range(200, 900, 150):
        draw.ellipse([width//2 - r, height//2 - r, width//2 + r, height//2 + r], outline=(65, 40, 100), width=1)

    # 3. Draw a elegant frame
    draw.rectangle([60, 60, width - 60, height - 60], outline=(90, 50, 130), width=2)

    # 4. Load fonts
    font_path = find_font()
    if font_path:
        font_large = ImageFont.truetype(font_path, 64)
        font_medium = ImageFont.truetype(font_path, 36)
        font_small = ImageFont.truetype(font_path, 40)
        font_tiny = ImageFont.truetype(font_path, 20)
    else:
        # Fallback to default PIL font
        font_large = font_medium = font_small = font_tiny = ImageFont.load_default()

    # 5. Draw text elements
    # Novel Name
    draw_centered_text(draw, novel_name, width//2, 200, font_small, (0, 240, 255))
    # Accent Line
    draw.line([width//2 - 120, 240, width//2 + 120, 240], fill=(0, 240, 255), width=2)
    # Chapter Title (Main Header)
    draw_centered_text(draw, chapter_title, width//2, height//2 - 20, font_large, (255, 255, 255))
    # Volume Info (Subtitle)
    if volume_title:
        draw_centered_text(draw, volume_title, width//2, height//2 + 80, font_medium, (200, 200, 220))


    img.save(output_path, "PNG")
    print(f"[+] Cover image generated at: {output_path}")

async def generate_paragraph_image(paragraph, style, idx, cache_dir, semaphore):
    """
    Generates a unique scene image for a paragraph using the agy CLI.
    Caches the image using an MD5 hash of the paragraph text and style.
    """
    # Create MD5 hash for cache verification
    hash_input = f"{paragraph}_{style}"
    para_hash = hashlib.md5(hash_input.encode("utf-8")).hexdigest()
    cached_img = os.path.abspath(os.path.join(cache_dir, f"img_{idx:03d}_{para_hash}.png"))

    if os.path.exists(cached_img):
        try:
            if os.path.getsize(cached_img) > 1024:
                return cached_img, True
        except Exception:
            pass

    async with semaphore:
        for attempt in range(3):
            try:
                # Truncate paragraph to 400 chars to avoid command/prompt length limits
                clean_para = paragraph[:400].replace('"', '\\"').replace("'", "\\'")
                prompt = f"Style: {style}. Scene: {clean_para}"

                cmd = [
                    "agy",
                    "--dangerously-skip-permissions",
                    "--print",
                    f"Generate an image for prompt: {prompt}. Save the generated image file to {cached_img}."
                ]

                proc = await asyncio.create_subprocess_exec(
                    *cmd,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )

                stdout, stderr = await proc.communicate()
                if proc.returncode == 0 and os.path.exists(cached_img) and os.path.getsize(cached_img) > 1024:
                    return cached_img, False
                else:
                    err_msg = (stderr or b"").decode("utf-8").strip() or (stdout or b"").decode("utf-8").strip()
                    print(f"\n[-] Attempt {attempt+1} failed to generate image for paragraph {idx}: {err_msg}")
            except Exception as e:
                print(f"\n[-] Attempt {attempt+1} error generating image for paragraph {idx}: {e}")
            
            await asyncio.sleep(2 ** attempt)

    return None, False

# ==========================================
# 4. ASYNC TTS PIPELINE
# ==========================================
async def process_paragraph(paragraph, voice, idx, cache_dir, semaphore):
    """
    Downloads audio & subtitle for a single paragraph with caching.
    """
    import edge_tts

    # Generate a unique MD5 hash for cache file naming
    hasher = hashlib.md5()
    hasher.update(paragraph.encode('utf-8'))
    hasher.update(voice.encode('utf-8'))
    para_hash = hasher.hexdigest()

    cached_mp3 = os.path.join(cache_dir, f"{idx:03d}_{para_hash}.mp3")
    cached_srt = os.path.join(cache_dir, f"{idx:03d}_{para_hash}.srt")

    if os.path.exists(cached_mp3) and os.path.exists(cached_srt):
        # Double check validity by loading MP3
        try:
            MP3(cached_mp3)
            return cached_mp3, cached_srt, True
        except Exception:
            pass # Re-download if corrupted

    async with semaphore:
        for attempt in range(3):
            try:
                communicate = edge_tts.Communicate(paragraph, voice)
                submaker = edge_tts.SubMaker()

                with open(cached_mp3, "wb") as f:
                    async for chunk in communicate.stream():
                        if chunk["type"] == "audio":
                            f.write(chunk["data"])
                        elif chunk["type"] in ("SentenceBoundary", "WordBoundary"):
                            submaker.feed(chunk)

                srt_content = submaker.get_srt()
                with open(cached_srt, "w", encoding="utf-8") as f:
                    f.write(srt_content)

                return cached_mp3, cached_srt, False
            except Exception as e:
                if attempt == 2:
                    print(f"\n[-] Error generating speech for paragraph {idx}: {e}")
                    raise e
                await asyncio.sleep(2 ** attempt)

# ==========================================
# 5. MAIN ORCHESTRATOR
# ==========================================
async def main_async(args):
    # Check FFmpeg
    if not shutil.which("ffmpeg"):
        print("[-] Error: FFmpeg is not installed on your system. Please install it using: sudo apt install ffmpeg")
        sys.exit(1)

    # 1. Parse markdown chapter
    meta = parse_novel_markdown(args.chapter_file)
    print(f"[+] Novel Name   : {meta['novel_name']}")
    print(f"[+] Volume Title : {meta['volume_title']}")
    print(f"[+] Chapter Title: {meta['chapter_title']}")
    print(f"[+] Total Paras  : {len(meta['paragraphs'])}")

    if not meta['paragraphs']:
        print("[-] Error: No paragraphs found to speak.")
        sys.exit(1)

    # Setup Cache Dir
    cache_dir = args.cache_dir
    if not cache_dir:
        # Create a unique cache dir name under .cache based on novel and chapter
        safe_novel = re.sub(r'[^\w\-_]', '_', meta['novel_name'])
        safe_chapter = re.sub(r'[^\w\-_]', '_', meta['chapter_title'])
        cache_dir = os.path.join(".cache", f"tts_{safe_novel}_{safe_chapter}")
    os.makedirs(cache_dir, exist_ok=True)

    # 2. Concurrently download paragraphs (limit to 5 connections to prevent blocking)
    tts_semaphore = asyncio.Semaphore(5)
    completed_tts = 0
    async def wrap_tts(para, voice, idx, c_dir, sem):
        nonlocal completed_tts
        mp3, srt, cached = await process_paragraph(para, voice, idx, c_dir, sem)
        completed_tts += 1
        status = "[Cached]" if cached else "[Generated]"
        print(f"\r  - TTS Progress: {completed_tts}/{len(meta['paragraphs'])} segments completed {status}", end="", flush=True)
        return mp3, srt

    tts_tasks = [wrap_tts(para, args.voice, idx, cache_dir, tts_semaphore) for idx, para in enumerate(meta['paragraphs'])]
    print("[+] Synthesizing speech segments...")
    results = await asyncio.gather(*tts_tasks)
    print()

    # 3. Concatenate audio and merge subtitles
    print("[+] Merging audio and subtitles...")
    audio_list_file = os.path.join(cache_dir, "audio_list.txt")
    with open(audio_list_file, "w", encoding="utf-8") as f:
        for mp3_path, _ in results:
            f.write(f"file '{os.path.abspath(mp3_path)}'\n")

    combined_audio = os.path.join(cache_dir, "combined_audio.mp3")
    cmd_audio = f"ffmpeg -f concat -safe 0 -i \"{audio_list_file}\" -c copy \"{combined_audio}\" -y"
    subprocess.run(cmd_audio, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Combine & Shift Subtitles
    combined_srt_content = []
    accumulated_duration = 0.0
    srt_index = 1

    for mp3_path, srt_path in results:
        # Load subtitle
        with open(srt_path, "r", encoding="utf-8") as sf:
            srt_content = sf.read()
        
        # Shift and append
        shifted_content, next_idx = shift_srt_content(srt_content, accumulated_duration, srt_index)
        if shifted_content.strip():
            combined_srt_content.append(shifted_content)
            srt_index = next_idx
            
        # Get duration of this segment
        try:
            audio = MP3(mp3_path)
            accumulated_duration += audio.info.length
        except Exception as e:
            print(f"[-] Warning: Failed to read duration for {mp3_path}: {e}")

    combined_srt_path = os.path.join(cache_dir, "combined.srt")
    with open(combined_srt_path, "w", encoding="utf-8") as f:
        f.write("\n\n".join(combined_srt_content))

    # 4. Handle Visual Background (Cover, Slide folder, or AI Generated Images)
    image_files = []
    
    if args.ai_images:
        # First, prepare fallback cover if any AI generation fails
        fallback_cover = args.image
        if not fallback_cover:
            chapter_dir = os.path.dirname(os.path.abspath(args.chapter_file))
            local_covers = [os.path.join(chapter_dir, f) for f in os.listdir(chapter_dir) if "封面" in f or "cover" in f.lower()]
            parent_covers = [os.path.join(os.path.dirname(chapter_dir), f) for f in os.listdir(os.path.dirname(chapter_dir)) if "封面" in f or "cover" in f.lower()]
            all_covers = [c for c in (local_covers + parent_covers) if os.path.isfile(c) and c.lower().endswith((".png", ".jpeg", ".jpg"))]
            if all_covers:
                fallback_cover = all_covers[0]
            else:
                fallback_cover = os.path.join(cache_dir, "generated_cover.png")
                if not os.path.exists(fallback_cover):
                    create_dynamic_cover(meta['novel_name'], meta['volume_title'], meta['chapter_title'], fallback_cover)

        # Concurrently generate images
        print("[+] Generating AI images for each paragraph...")
        img_semaphore = asyncio.Semaphore(2)  # Limit concurrent image jobs
        completed_img = 0
        async def wrap_img(para, style, idx, c_dir, sem):
            nonlocal completed_img
            path, cached = await generate_paragraph_image(para, style, idx, c_dir, sem)
            completed_img += 1
            status = "[Cached]" if cached else "[Generated]"
            print(f"\r  - Image Progress: {completed_img}/{len(meta['paragraphs'])} segments completed {status}", end="", flush=True)
            return path

        img_tasks = [wrap_img(para, args.ai_style, idx, cache_dir, img_semaphore) for idx, para in enumerate(meta['paragraphs'])]
        raw_images = await asyncio.gather(*img_tasks)
        print()

        # Fill any failures with the fallback cover
        image_files = [path if path else fallback_cover for path in raw_images]
    else:
        # Check if a custom image/dir was passed
        if args.image:
            if os.path.isdir(args.image):
                # It's a directory, look for images inside
                image_files = sorted([os.path.join(args.image, f) for f in os.listdir(args.image) if f.lower().endswith(('.png', '.jpg', '.jpeg'))])
            elif os.path.isfile(args.image):
                image_files = [args.image]
        else:
            # Scan automatically
            chapter_dir = os.path.dirname(os.path.abspath(args.chapter_file))
            base_name = os.path.splitext(os.path.basename(args.chapter_file))[0]
            
            # Look for a directory matching chapter name, e.g., 001_images or 001 or images
            possible_dirs = [
                os.path.join(chapter_dir, f"{base_name}_images"),
                os.path.join(chapter_dir, base_name),
                os.path.join(chapter_dir, "images"),
            ]
            for d in possible_dirs:
                if os.path.isdir(d):
                    image_files = sorted([os.path.join(d, f) for f in os.listdir(d) if f.lower().endswith(('.png', '.jpg', '.jpeg'))])
                    if image_files:
                        print(f"[+] Found {len(image_files)} chapter images in: {d}")
                        break
            
            # If no image folder found, look for local cover image files
            if not image_files:
                local_covers = [os.path.join(chapter_dir, f) for f in os.listdir(chapter_dir) if "封面" in f or "cover" in f.lower()]
                parent_covers = [os.path.join(os.path.dirname(chapter_dir), f) for f in os.listdir(os.path.dirname(chapter_dir)) if "封面" in f or "cover" in f.lower()]
                all_covers = [c for c in (local_covers + parent_covers) if os.path.isfile(c) and c.lower().endswith((".png", ".jpeg", ".jpg"))]
                if all_covers:
                    image_files = [all_covers[0]]
                    print(f"[+] Found cover image locally: {all_covers[0]}")
                else:
                    # Generate dynamically
                    generated_cover = os.path.join(cache_dir, "generated_cover.png")
                    create_dynamic_cover(meta['novel_name'], meta['volume_title'], meta['chapter_title'], generated_cover)
                    image_files = [generated_cover]

    is_multi_image = len(image_files) > 1

    # 5. Assemble Video (FFmpeg)
    output_video = args.output
    if not output_video:
        abs_input = os.path.abspath(args.chapter_file)
        if "/chapter/" in abs_input:
            # Replace /chapter/ with /video/ and change extension to .mp4
            abs_output = abs_input.replace("/chapter/", "/video/").rsplit(".", 1)[0] + ".mp4"
        else:
            base_name = os.path.splitext(os.path.basename(args.chapter_file))[0]
            abs_output = os.path.abspath(f"{base_name}.mp4")
        output_video = abs_output

    # Make sure output directory exists
    output_dir = os.path.dirname(output_video)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    print(f"[+] Assembling final video to: {output_video}...")
    
    # Copy SRT file locally to avoid escaping issues in FFmpeg path parser
    temp_srt_local = "temp_subs.srt"
    shutil.copyfile(combined_srt_path, temp_srt_local)

    # Set up video input configuration
    if is_multi_image:
        N = len(results)
        M = len(image_files)
        if M > N:
            print(f"[!] Warning: More images ({M}) than paragraphs ({N}). Using first {N} images.")
            image_files = image_files[:N]
            M = N

        # Get duration for each paragraph
        paragraph_durations = []
        for mp3_path, _ in results:
            try:
                audio = MP3(mp3_path)
                paragraph_durations.append(audio.info.length)
            except Exception:
                paragraph_durations.append(0.0)

        # Distribute paragraphs to M image slots as evenly as possible
        chunk_size = N / M
        chunks = []
        start_idx = 0
        for i in range(M):
            end_idx = int(round((i + 1) * chunk_size))
            if end_idx == start_idx:
                end_idx = start_idx + 1
            if i == M - 1:
                end_idx = N
            chunks.append((start_idx, end_idx))
            start_idx = end_idx

        # Calculate exact duration for each image
        image_durations = []
        for start_idx, end_idx in chunks:
            chunk_duration = sum(paragraph_durations[start_idx:end_idx])
            image_durations.append(chunk_duration)

        # Write the image playlist for FFmpeg's concat demuxer
        image_list_file = os.path.join(cache_dir, "image_list.txt")
        with open(image_list_file, "w", encoding="utf-8") as f:
            for img_path, dur in zip(image_files, image_durations):
                f.write(f"file '{os.path.abspath(img_path)}'\n")
                f.write(f"duration {dur:.3f}\n")
            # Duplicate the last file to work around FFmpeg concat cut-off
            f.write(f"file '{os.path.abspath(image_files[-1])}'\n")

        cmd_video = (
            f'ffmpeg -f concat -safe 0 -i "{image_list_file}" -i "{combined_audio}" '
            f'-vf "subtitles={temp_srt_local}:force_style=\'FontSize=20,Alignment=2,OutlineColour=&H80000000,BorderStyle=3\'" '
            f'-c:v libx264 -c:a aac -b:a 192k -pix_fmt yuv420p -shortest "{output_video}" -y'
        )
    else:
        cmd_video = (
            f'ffmpeg -loop 1 -i "{image_files[0]}" -i "{combined_audio}" '
            f'-vf "subtitles={temp_srt_local}:force_style=\'FontSize=20,Alignment=2,OutlineColour=&H80000000,BorderStyle=3\'" '
            f'-c:v libx264 -tune stillimage -c:a aac -b:a 192k -pix_fmt yuv420p -shortest "{output_video}" -y'
        )
    
    try:
        res = subprocess.run(cmd_video, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode != 0:
            print(f"[-] FFmpeg error: {res.stderr}")
            sys.exit(1)
        print(f"[+] Success! Video generated at: {os.path.abspath(output_video)}")
    finally:
        # Clean up local temporary srt
        if os.path.exists(temp_srt_local):
            os.remove(temp_srt_local)

    # Cleanup cache if clean flag is set
    if args.clean:
        print("[+] Cleaning cache folder...")
        shutil.rmtree(cache_dir)

# ==========================================
# 6. ENTRY POINT
# ==========================================
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Convert novel markdown chapters into MP4 videos with subtitles.")
    parser.add_argument("chapter_file", help="Path to the novel chapter Markdown file.")
    parser.add_argument("-o", "--output", help="Output MP4 file path (default: <chapter_filename>.mp4)")
    parser.add_argument("-v", "--voice", default="zh-TW-HsiaoChenNeural", help="Microsoft Edge TTS voice name (default: zh-TW-HsiaoChenNeural)")
    parser.add_argument("-i", "--image", help="Cover image path (optional). If not provided, it scans or generates a title card.")
    parser.add_argument("--cache-dir", help="TTS Cache directory (optional)")
    parser.add_argument("--clean", action="store_true", help="Delete cache after successful video generation")
    parser.add_argument("--ai-images", action="store_true", help="Enable automatic paragraph-level AI image generation using the antigravity (agy) CLI. Each paragraph will show its own unique AI-generated scene.")
    parser.add_argument("--ai-style", default="cyberpunk anime style, vibrant colors, detailed sci-fi illustration", help="The style description to prepend to each AI image generation prompt. Examples: 'traditional chinese ink wash painting, ethereal landscape' (Ink), 'cinematic, photorealistic sci-fi scene, 8k' (Realism), 'watercolor painting, soft colors' (Watercolor).")

    args = parser.parse_args()
    asyncio.run(main_async(args))
