#!/usr/bin/env python3
"""Generate AA-014's synthetic, public-domain-equivalent PCM WAV fixtures."""

import math
import pathlib
import struct
import wave


SAMPLE_RATE = 44_100
AMPLITUDE = 1_200
ASSET_DIR = pathlib.Path(__file__).parents[1] / "app" / "src" / "androidTest" / "assets"
FIXTURES = (
    ("aa014_01b_tone_a.wav", 3, 440),
    ("aa014_01a_tone_b.wav", 4, 660),
)


def generate(filename: str, duration_seconds: int, frequency_hz: int) -> None:
    frame_count = SAMPLE_RATE * duration_seconds
    path = ASSET_DIR / filename
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for frame in range(frame_count):
            sample = round(AMPLITUDE * math.sin(2.0 * math.pi * frequency_hz * frame / SAMPLE_RATE))
            frames.extend(struct.pack("<h", sample))
        output.writeframes(frames)


def main() -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    for fixture in FIXTURES:
        generate(*fixture)


if __name__ == "__main__":
    main()
