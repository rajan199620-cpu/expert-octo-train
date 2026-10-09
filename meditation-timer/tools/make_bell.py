#!/usr/bin/env python3
"""Synthesises the singing-bowl bell used by the app (app/src/main/res/raw/bell.wav).

Pure standard library, so it runs anywhere:  python3 tools/make_bell.py

A struck bowl is a handful of inharmonic partials, each ringing as two slightly
detuned modes (the slow "wah-wah" shimmer), with higher partials dying faster.
The fundamental sits at D4 rather than lower because phone speakers barely
reproduce anything under ~300 Hz.
"""
import math
import os
import struct
import wave

RATE = 22050
SECONDS = 9.0
FUNDAMENTAL = 293.66  # D4

# (frequency ratio, amplitude, decay time constant in s, beat frequency in Hz)
PARTIALS = [
    (1.00, 1.00, 3.2, 0.9),
    (2.76, 0.55, 2.2, 1.7),
    (5.40, 0.22, 1.3, 2.4),
    (8.93, 0.07, 0.8, 3.1),
]
ATTACK = 0.025  # soft mallet: no click
FADE_OUT = 1.2  # guarantees the file ends in silence

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw", "bell.wav")
n = int(RATE * SECONDS)
samples = []
for i in range(n):
    t = i / RATE
    v = 0.0
    for ratio, amp, tau, beat in PARTIALS:
        f = FUNDAMENTAL * ratio
        # Two modes split by `beat` Hz -> slow amplitude shimmer.
        mode = math.sin(2 * math.pi * (f - beat / 2) * t) + 0.8 * math.sin(2 * math.pi * (f + beat / 2) * t + 0.6)
        v += amp * math.exp(-t / tau) * mode
    env = min(1.0, t / ATTACK)
    if t > SECONDS - FADE_OUT:
        env *= 0.5 * (1 + math.cos(math.pi * (t - (SECONDS - FADE_OUT)) / FADE_OUT))
    samples.append(v * env)

peak = max(abs(s) for s in samples)
scale = 0.7 * 32767 / peak  # ~-3 dBFS headroom
with wave.open(out, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(b"".join(struct.pack("<h", int(s * scale)) for s in samples))
print(f"wrote {os.path.normpath(out)} ({n / RATE:.1f}s, {os.path.getsize(out) // 1024} KB)")
