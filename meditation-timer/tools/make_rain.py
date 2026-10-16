"""Builds app/src/main/res/raw/rain.ogg from a downloaded rain recording.

    python3 tools/make_rain.py rain-and-thunder.mp3

Source: "Rain and Thunder" by loswin23 on Pixabay (pixabay.com, sound 585637), under the Pixabay
Content License: free to use in an app, no credit required, not to be redistributed on its own.

What it does to the 4-minute recording, and why:
- Cuts the 2 s fade-in at the start, which would be a dip every time the loop came round.
- Tames the thunder: above 4 dB over the rain it's turned down 3:1, so a roll still comes in
  (at most about +8 dB over the rain instead of +14) but no longer jolts you mid-sit.
- Crossfades the last 4 s into the opening, so the loop has no seam to hear.
- Levels it to -18.4 LUFS, the loudness of the generated rain it replaces, so the volume you set
  sounds the same; the sharpest drops (0.2% of samples) are rounded off below 0.7 so that,
  after encoding, nothing reaches full scale and clips.
- Encodes Ogg Vorbis: plays on every Android version and, unlike MP3, loops without a gap.

Needs numpy and ffmpeg on the PATH.
"""
import subprocess
import sys

import numpy as np

RATE = 48_000


def main(src: str, out: str = "app/src/main/res/raw/rain.ogg") -> None:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", src, "-f", "f32le", "-ac", "2", "-ar", str(RATE), "-"],
                         check=True, capture_output=True).stdout
    x = np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).astype(np.float64)
    x = x[int(2.0 * RATE):]

    # Loudness envelope: 400 ms RMS every 10 ms.
    hop = RATE // 100
    power = (x ** 2).mean(1)
    c = np.concatenate([[0], np.cumsum(power)])
    w = int(0.4 * RATE)
    idx = np.arange(0, len(power), hop)
    lo = np.clip(idx - w // 2, 0, len(power))
    hi = np.clip(idx + w // 2, 0, len(power))
    env = 10 * np.log10((c[hi] - c[lo]) / (hi - lo) + 1e-12)

    # Thunder: 3:1 above the rain bed + 4 dB, in fast (80 ms), out slowly (1.2 s) so it never pumps.
    over = np.maximum(env - (np.median(env) + 4), 0)
    want = -over * (1 - 1 / 3)
    gain_db = np.zeros_like(want)
    cur = 0.0
    attack = 1 - np.exp(-1 / (0.08 * 100))
    release = 1 - np.exp(-1 / (1.2 * 100))
    for i, t in enumerate(want):
        cur += (attack if t < cur else release) * (t - cur)
        gain_db[i] = cur
    y = x * (10 ** (np.interp(np.arange(len(power)), idx, gain_db) / 20))[:, None]

    # Seamless loop: the last 4 s fade into the first 4 s (equal power: rain is uncorrelated noise),
    # and the file starts just after them, so its end runs straight on into its start.
    fade = int(4 * RATE)
    t = np.linspace(0, np.pi / 2, fade)[:, None]
    tail = y[-fade:] * np.cos(t) + y[:fade] * np.sin(t)
    loop = np.concatenate([y[fade:-fade], tail])

    # Level: rain bed (median 1 s RMS) at -20.5 dBFS, about -18.4 LUFS overall.
    mono = loop.mean(1)
    secs = len(mono) // RATE
    rms = np.array([np.sqrt((mono[i * RATE:(i + 1) * RATE] ** 2).mean()) for i in range(secs)])
    loop *= 10 ** (-20.5 / 20) / np.median(rms)
    a = np.abs(loop)
    k = 0.5
    loop = np.where(a > k, np.sign(loop) * (k + 0.2 * np.tanh((a - k) / 0.2)), loop)
    print(f"{len(loop) / RATE:.1f} s, {100 * (a > k).mean():.3f}% of samples rounded off")

    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(RATE), "-ac", "2", "-i", "-",
                    "-c:a", "libvorbis", "-q:a", "3", "-map_metadata", "-1", out],
                   input=loop.astype(np.float32).tobytes(), check=True)


if __name__ == "__main__":
    main(*sys.argv[1:])
