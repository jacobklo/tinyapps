"""
Regenerates app/src/main/assets/click.wav, the metronome tick.

The sound is synthesized rather than downloaded so there is no licence to track and no
binary in the tree whose provenance is a mystery. Standard library only - no ffmpeg, no
sox, no install step:

    python3 tools/make_click.py

Output is byte-for-byte reproducible, so `git diff` after a run is the check that this
script and the committed asset still agree. Two details that matter if you touch it:
int() TRUNCATES toward zero where round() would not, and wave writes the canonical
44-byte header. Change either and the bytes move.
"""

import math
import struct
import wave
from pathlib import Path

# 20 ms is long enough to hear and short enough that it cannot overlap the next tick.
RATE, MS, FREQ = 44100, 20, 2000.0
# Decays to well under 1% by the end of the burst, which is what makes it read as a dry
# click rather than a 2 kHz beep. A gentler decay just sounds like a tone.
DECAY = 260.0
AMPLITUDE = 0.8

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "click.wav"


def main() -> None:
	n = RATE * MS // 1000
	frames = bytearray()
	for i in range(n):
		t = i / RATE
		amp = math.exp(-t * DECAY)
		frames += struct.pack("<h", int(32767 * AMPLITUDE * amp * math.sin(2 * math.pi * FREQ * t)))
	OUT.parent.mkdir(parents=True, exist_ok=True)
	with wave.open(str(OUT), "wb") as w:
		w.setnchannels(1)
		w.setsampwidth(2)
		w.setframerate(RATE)
		w.writeframes(bytes(frames))


if __name__ == "__main__":
	main()
