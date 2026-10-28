"""
Un album di prova per il server: due toni di 30 secondi in WAV (48 kHz, stereo, 16 bit), in
<cartella>/Check Artist/Check Album. Senza ffmpeg: li scrive Python.

    python3 tones.py <cartella>
"""

from __future__ import annotations

import math
import struct
import sys
import wave
from pathlib import Path

RATE = 48000
SECONDS = 30


def main() -> None:
    album = Path(sys.argv[1]) / "Check Artist" / "Check Album"
    album.mkdir(parents=True, exist_ok=True)
    for number, frequency in ((1, 380), (2, 460)):
        path = album / f"0{number} Tone {number}.wav"
        with wave.open(str(path), "wb") as out:
            out.setnchannels(2)
            out.setsampwidth(2)
            out.setframerate(RATE)
            frames = bytearray()
            for index in range(RATE * SECONDS):
                sample = int(8000 * math.sin(2 * math.pi * frequency * index / RATE))
                frames += struct.pack("<hh", sample, sample)
            out.writeframes(bytes(frames))
        print(path)


if __name__ == "__main__":
    main()
