import json
import math
import re
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "video" / "vpn-virus-protection"
SCENES = WORK / "scenes"
OUTPUT = WORK / "output"
SEGMENTS = WORK / "segments"
AUDIO = Path("/Users/juanpablogarcia/Downloads/can-a-vpn-protect-you-from-vir.wav")
FINAL_NAME = "Can-A-VPN-Protect-You-From-Viruses.mp4"

DURATION = 61.300
SCENE_SEQUENCE = [
    (1, 0.000, 7.018),
    (2, 7.018, 16.418),
    (3, 16.418, 24.660),
    (4, 24.660, 30.158),
    (5, 30.158, 40.063),
    (6, 40.063, 48.822),
    (7, 48.822, 55.883),
    (8, 55.883, DURATION),
]

SENTENCES = [
    (0.264, 2.779, "Can a VPN protect you from viruses?"),
    (3.622, 6.043, "The short answer is no—not by itself."),
    (7.018, 10.919, "A VPN encrypts your internet traffic and hides your IP address."),
    (11.275, 15.410, "But it doesn’t examine every file you download or every program you install."),
    (16.418, 24.276, "If you download a malicious attachment, install fake software, or give your password to a phishing website, the VPN won’t automatically stop you."),
    (24.660, 29.144, "The virus travels through the encrypted VPN tunnel just like any legitimate download."),
    (30.158, 39.062, "Some VPN services include additional malware or dangerous-website blocking, but those are separate security features—not something every VPN provides."),
    (40.063, 42.124, "Think of a VPN as a secure tunnel."),
    (42.463, 47.757, "It protects your information while it travels, but it doesn’t guarantee that whatever enters the tunnel is safe."),
    (48.822, 54.936, "You still need updated software, secure passwords, phishing awareness and your device’s built-in security tools."),
    (55.883, 60.608, "A VPN is one layer of protection—not a complete cybersecurity solution."),
]


def timed_words():
    words = []
    for start, end, sentence in SENTENCES:
        tokens = re.findall(r"\S+", sentence)
        weights = [max(1.0, len(re.sub(r"[^A-Za-z0-9]", "", token)) ** 0.72) for token in tokens]
        total = sum(weights)
        cursor = start
        for token, weight in zip(tokens, weights):
            duration = (end - start) * weight / total
            words.append({"word": token, "start": cursor, "end": cursor + duration})
            cursor += duration
    return words


def timestamp(seconds):
    seconds = max(0.0, seconds)
    h = int(seconds // 3600); m = int(seconds % 3600 // 60); s = seconds % 60
    ms = int(round((s - int(s)) * 1000))
    if ms == 1000: s = int(s) + 1; ms = 0
    return f"{h:02d}:{m:02d}:{int(s):02d},{ms:03d}"


def run(command):
    subprocess.run(command, check=True)


def caption_groups(words):
    groups, current = [], []
    for word in words:
        clean = word["word"].strip()
        candidate = " ".join([w["word"].strip() for w in current] + [clean])
        should_break = current and (len(current) >= 7 or len(candidate) > 42 or current[-1]["word"].endswith((".", "?", "!")))
        if should_break: groups.append(current); current = []
        current.append(word)
        if clean.endswith((".", "?", "!")): groups.append(current); current = []
    if current: groups.append(current)
    return groups


def render_caption_card(text, path):
    canvas = Image.new("RGBA", (1080, 1920), (0, 0, 0, 0)); draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 58)
    lines, current = [], ""
    for word in text.split():
        candidate = f"{current} {word}".strip()
        if current and draw.textbbox((0, 0), candidate, font=font)[2] > 840: lines.append(current); current = word
        else: current = candidate
    if current: lines.append(current)
    lines = lines[:2]; lh = 68; bh = 62 + lh * len(lines); left, right, bottom = 70, 1010, 1790; top = bottom - bh
    draw.rounded_rectangle((left, top, right, bottom), radius=28, fill=(2, 11, 24, 225), outline=(8, 213, 244, 235), width=4)
    y = top + (bh - lh * len(lines)) / 2 - 4
    for line in lines:
        box = draw.textbbox((0, 0), line, font=font); width = box[2] - box[0]
        draw.text(((1080-width)/2, y), line, font=font, fill="white", stroke_width=2, stroke_fill=(16,26,46,255)); y += lh
    canvas.save(path)


def render_caption_track(entries, paths, output_path):
    fps, width, height = 15, 940, 250; blank = bytes(width*height*4)
    cards = [Image.open(p).convert("RGBA").crop((70,1540,1010,1790)).tobytes() for p in paths]
    command=["ffmpeg","-y","-f","rawvideo","-pix_fmt","rgba","-s",f"{width}x{height}","-r",str(fps),"-i","-","-an","-c:v","qtrle",str(output_path)]
    process=subprocess.Popen(command,stdin=subprocess.PIPE); idx=0
    for frame in range(math.ceil(DURATION*fps)):
        time=frame/fps
        while idx<len(entries) and time>entries[idx]["end"]: idx+=1
        process.stdin.write(cards[idx] if idx<len(entries) and entries[idx]["start"]<=time<=entries[idx]["end"] else blank)
    process.stdin.close()
    if process.wait()!=0: raise RuntimeError("Caption track render failed")


def main():
    OUTPUT.mkdir(parents=True,exist_ok=True); SEGMENTS.mkdir(parents=True,exist_ok=True)
    groups=caption_groups(timed_words()); entries=[]; srt=[]
    for index,group in enumerate(groups,start=1):
        start=group[0]["start"]; next_start=groups[index][0]["start"] if index<len(groups) else DURATION
        end=min(DURATION,group[-1]["end"]+.08,next_start-.02); text=" ".join(w["word"] for w in group)
        entries.append({"start":start,"end":end,"text":text}); srt += [str(index),f"{timestamp(start)} --> {timestamp(end)}",text,""]
    (WORK/"captions.srt").write_text("\n".join(srt),encoding="utf-8")
    (WORK/"scene-timing.json").write_text(json.dumps([{"scene":n,"start":a,"end":b,"duration":b-a} for n,a,b in SCENE_SEQUENCE],indent=2)+"\n")
    segment_paths=[]
    for index,(scene,start,end) in enumerate(SCENE_SEQUENCE):
        frames=math.ceil((end-start)*30); image=SCENES/f"scene-{scene:02d}.png"; segment=SEGMENTS/f"scene-{index+1:02d}.mp4"; segment_paths.append(segment)
        zoom="min(zoom+0.000035,1.018)" if index%2==0 else "if(eq(on,1),1.018,max(zoom-0.000035,1.0))"
        vf=f"zoompan=z='{zoom}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d={frames}:s=1080x1920:fps=30,format=yuv420p"
        run(["ffmpeg","-y","-loop","1","-i",str(image),"-vf",vf,"-frames:v",str(frames),"-an","-c:v","libx264","-preset","medium","-crf","18","-pix_fmt","yuv420p",str(segment)])
    concat=WORK/"segments.txt"; concat.write_text("".join(f"file '{p.resolve()}'\n" for p in segment_paths))
    silent=OUTPUT/"vpn-virus-protection-silent.mp4"; run(["ffmpeg","-y","-f","concat","-safe","0","-i",str(concat),"-c","copy",str(silent)])
    caption_dir=WORK/"caption-cards"; caption_dir.mkdir(exist_ok=True); paths=[]
    for i,e in enumerate(entries,1):
        p=caption_dir/f"caption-{i:03d}.png"; render_caption_card(e["text"],p); paths.append(p)
    track=WORK/"caption-track.mov"; render_caption_track(entries,paths,track)
    final=OUTPUT/FINAL_NAME
    run(["ffmpeg","-y","-i",str(silent),"-i",str(AUDIO),"-i",str(track),"-filter_complex","[0:v][2:v]overlay=70:1540:shortest=1[v]","-map","[v]","-map","1:a:0","-c:v","libx264","-preset","medium","-crf","18","-c:a","aac","-b:a","192k","-ar","48000","-movflags","+faststart","-shortest",str(final)])
    print(json.dumps({"video":str(final),"captions":len(groups)}))


if __name__ == "__main__": main()
