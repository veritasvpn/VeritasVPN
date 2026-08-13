import json
import math
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "video" / "what-happens-website"
SCENES = WORK / "scenes"
OUTPUT = WORK / "output"
SEGMENTS = WORK / "segments"
TRANSCRIPT_JSON = Path("/private/tmp/what-happens-website-transcript.json")
AUDIO = Path("/Users/juanpablogarcia/Downloads/what-happens-when-you-visit-a-.wav")

SCENE_CUTS = [0.0, 8.16, 14.92, 25.40, 39.36, 43.24, 52.24, 68.02, 89.175]


def timestamp(seconds: float, ass: bool = False) -> str:
    seconds = max(0.0, seconds)
    hours = int(seconds // 3600)
    minutes = int(seconds % 3600 // 60)
    secs = seconds % 60
    if ass:
        return f"{hours}:{minutes:02d}:{secs:05.2f}"
    millis = int(round((secs - int(secs)) * 1000))
    if millis == 1000:
        secs = int(secs) + 1
        millis = 0
    return f"{hours:02d}:{minutes:02d}:{int(secs):02d},{millis:03d}"


def run(command: list[str]) -> None:
    print(" ".join(command))
    subprocess.run(command, check=True)


def caption_groups(words: list[dict]) -> list[list[dict]]:
    groups: list[list[dict]] = []
    current: list[dict] = []
    for word in words:
        clean = word["word"].strip()
        if not clean:
            continue
        candidate = " ".join([w["word"].strip() for w in current] + [clean])
        pause = 0 if not current else word["start"] - current[-1]["end"]
        should_break = current and (
            len(current) >= 7
            or len(candidate) > 42
            or pause > 0.42
            or current[-1]["word"].strip().endswith((".", "?", "!"))
        )
        if should_break:
            groups.append(current)
            current = []
        current.append(word)
        if clean.endswith((".", "?", "!")):
            groups.append(current)
            current = []
    if current:
        groups.append(current)
    return groups


def render_caption_card(text: str, path: Path) -> None:
    canvas = Image.new("RGBA", (1080, 1920), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    font_path = Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf")
    font = ImageFont.truetype(str(font_path), 58)
    max_width = 840
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if current and draw.textbbox((0, 0), candidate, font=font)[2] > max_width:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    lines = lines[:2]
    line_height = 68
    box_height = 62 + line_height * len(lines)
    left, right = 70, 1010
    bottom = 1790
    top = bottom - box_height
    draw.rounded_rectangle(
        (left, top, right, bottom),
        radius=28,
        fill=(2, 11, 24, 225),
        outline=(8, 213, 244, 235),
        width=4,
    )
    total_height = line_height * len(lines)
    y = top + (box_height - total_height) / 2 - 4
    for line in lines:
        bounds = draw.textbbox((0, 0), line, font=font)
        width = bounds[2] - bounds[0]
        draw.text(
            ((1080 - width) / 2, y),
            line,
            font=font,
            fill=(255, 255, 255, 255),
            stroke_width=2,
            stroke_fill=(16, 26, 46, 255),
        )
        y += line_height
    canvas.save(path)


def render_caption_track(
    entries: list[dict], caption_paths: list[Path], output_path: Path
) -> None:
    fps = 15
    width, height = 940, 250
    blank = bytes(width * height * 4)
    cards = [
        Image.open(path).convert("RGBA").crop((70, 1540, 1010, 1790)).tobytes()
        for path in caption_paths
    ]
    command = [
        "ffmpeg",
        "-y",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgba",
        "-s",
        f"{width}x{height}",
        "-r",
        str(fps),
        "-i",
        "-",
        "-an",
        "-c:v",
        "qtrle",
        str(output_path),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    entry_index = 0
    for frame in range(math.ceil(89.175 * fps)):
        time = frame / fps
        while entry_index < len(entries) and time > entries[entry_index]["end"]:
            entry_index += 1
        if (
            entry_index < len(entries)
            and entries[entry_index]["start"] <= time <= entries[entry_index]["end"]
        ):
            process.stdin.write(cards[entry_index])
        else:
            process.stdin.write(blank)
    process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("Caption track render failed")


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    SEGMENTS.mkdir(parents=True, exist_ok=True)

    transcript = json.loads(TRANSCRIPT_JSON.read_text(encoding="utf-8"))
    words = [
        word
        for segment in transcript["segments"]
        for word in segment.get("words", [])
        if word.get("start") is not None and word.get("end") is not None
    ]
    groups = caption_groups(words)

    transcript_text = "\n".join(
        f"{segment['start']:06.2f}–{segment['end']:06.2f}  {segment['text']}"
        for segment in transcript["segments"]
    )
    (WORK / "transcript.txt").write_text(transcript_text + "\n", encoding="utf-8")

    srt_lines: list[str] = []
    ass_lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        "PlayResX: 1080",
        "PlayResY: 1920",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "",
        "[V4+ Styles]",
        "Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding",
        "Style: Captions,Arial,58,&H00FFFFFF,&H00FFFFFF,&H00101A2E,&HC0180B02,-1,0,0,0,100,100,0,0,3,4,0,2,72,72,118,1",
        "",
        "[Events]",
        "Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text",
    ]
    caption_entries: list[dict] = []
    for index, group in enumerate(groups, start=1):
        start = group[0]["start"]
        next_start = groups[index][0]["start"] if index < len(groups) else 89.175
        end = min(89.175, group[-1]["end"] + 0.08, next_start - 0.02)
        text = " ".join(word["word"].strip() for word in group)
        text = text.replace("ISB", "ISP").replace("Veritas VPN", "VeritasVPN")
        caption_entries.append({"start": start, "end": end, "text": text})
        srt_lines.extend(
            [str(index), f"{timestamp(start)} --> {timestamp(end)}", text, ""]
        )
        escaped = text.replace("\\", r"\\").replace("{", r"\{").replace("}", r"\}")
        ass_lines.append(
            f"Dialogue: 0,{timestamp(start, True)},{timestamp(end, True)},Captions,,0,0,0,,{escaped}"
        )

    (WORK / "captions.srt").write_text("\n".join(srt_lines), encoding="utf-8")
    (WORK / "captions.ass").write_text("\n".join(ass_lines) + "\n", encoding="utf-8")
    (WORK / "scene-timing.json").write_text(
        json.dumps(
            [
                {
                    "scene": index + 1,
                    "start": SCENE_CUTS[index],
                    "end": SCENE_CUTS[index + 1],
                    "duration": SCENE_CUTS[index + 1] - SCENE_CUTS[index],
                }
                for index in range(8)
            ],
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    segment_paths: list[Path] = []
    for index in range(8):
        duration = SCENE_CUTS[index + 1] - SCENE_CUTS[index]
        frames = math.ceil(duration * 30)
        image = SCENES / f"scene-{index + 1:02d}.png"
        segment = SEGMENTS / f"scene-{index + 1:02d}.mp4"
        segment_paths.append(segment)
        zoom_direction = 1 if index % 2 == 0 else -1
        if zoom_direction > 0:
            zoom = "min(zoom+0.000035,1.018)"
        else:
            zoom = "if(eq(on,1),1.018,max(zoom-0.000035,1.0))"
        vf = (
            f"zoompan=z='{zoom}':"
            "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':"
            f"d={frames}:s=1080x1920:fps=30,"
            "format=yuv420p"
        )
        if not segment.exists():
            run(
                [
                    "ffmpeg",
                    "-y",
                    "-loop",
                    "1",
                    "-i",
                    str(image),
                    "-vf",
                    vf,
                    "-frames:v",
                    str(frames),
                    "-an",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "medium",
                    "-crf",
                    "18",
                    "-pix_fmt",
                    "yuv420p",
                    str(segment),
                ]
            )

    concat_file = WORK / "segments.txt"
    concat_file.write_text(
        "".join(f"file '{path.resolve()}'\n" for path in segment_paths),
        encoding="utf-8",
    )
    silent_video = OUTPUT / "what-happens-website-silent.mp4"
    if not silent_video.exists():
        run(
            [
                "ffmpeg",
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(concat_file),
                "-c",
                "copy",
                str(silent_video),
            ]
        )

    final_video = OUTPUT / "What-Happens-When-You-Visit-a-Website.mp4"
    caption_dir = WORK / "caption-cards"
    caption_dir.mkdir(parents=True, exist_ok=True)
    caption_paths: list[Path] = []
    for index, entry in enumerate(caption_entries, start=1):
        path = caption_dir / f"caption-{index:03d}.png"
        render_caption_card(entry["text"], path)
        caption_paths.append(path)

    caption_track = WORK / "caption-track.mov"
    render_caption_track(caption_entries, caption_paths, caption_track)
    command = [
        "ffmpeg",
        "-y",
        "-i",
        str(silent_video),
        "-i",
        str(AUDIO),
        "-i",
        str(caption_track),
        "-filter_complex",
        "[0:v][2:v]overlay=70:1540:shortest=1[captioned]",
        "-map",
        "[captioned]",
        "-map",
        "1:a:0",
        "-c:v",
        "libx264",
        "-preset",
        "medium",
        "-crf",
        "18",
        "-c:a",
        "aac",
        "-b:a",
        "192k",
        "-ar",
        "48000",
        "-movflags",
        "+faststart",
        "-shortest",
        str(final_video),
    ]
    run(command)
    print(json.dumps({"video": str(final_video), "captions": len(groups)}))


if __name__ == "__main__":
    main()
