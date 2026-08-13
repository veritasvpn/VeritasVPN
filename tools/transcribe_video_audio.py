import json
import sys

from faster_whisper import WhisperModel


audio_path = sys.argv[1]
output_path = sys.argv[2]

model = WhisperModel("small.en", device="cpu", compute_type="int8")
segments, info = model.transcribe(
    audio_path,
    language="en",
    beam_size=5,
    vad_filter=True,
    word_timestamps=True,
)

payload = {
    "language": info.language,
    "duration": info.duration,
    "segments": [],
}

for segment in segments:
    payload["segments"].append(
        {
            "start": segment.start,
            "end": segment.end,
            "text": segment.text.strip(),
            "words": [
                {
                    "start": word.start,
                    "end": word.end,
                    "word": word.word,
                    "probability": word.probability,
                }
                for word in (segment.words or [])
            ],
        }
    )

with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)

print(json.dumps({"output": output_path, "segments": len(payload["segments"])}))
