#!/usr/bin/env python3
"""The launch video's score, synthesized to the cut from public/audio/cues.json (scripts/cues.ts writes it).

A build in D major at the cut's tempo, layered up a part at a time and let go on the end card: a felt piano and a warm
pad from the first frame, a low pulse under the green card, strings coming in under "Say what you want.", the pulse and
a soft kick under "Watch it code.", the backbeat under "Queue it. Steer it.", high strings and the full kit under "Ship
it.", the lineup's devices each landing on a bell over the lifted IV, a sus chord gathering under the last pull back,
and everything but the piano, a bell and the strings' resolve dropping away on the end card, where a three-note fall
lands on the tonic and rings out. The harmony moves only on the cuts: I, IV, I, V, vi, IV, I/3, V, IV, Vsus, I.

Sound is cut to the picture too: a swell of air under each light sweep, a sub hit as the green closes into the phone, a
glass tick as the prompt is sent, faint taps where a finger comes down, and risers into the big cuts. Mastered to -14
LUFS, true peak under -1.2 dBTP.

    python3 scripts/music.py public/audio/score.wav      (npm run music writes the cues first, then runs this)
    python3 scripts/music.py public/audio/film-score.wav film-cues.json      (npm run music:film, for the film)

It needs numpy, scipy and pedalboard: pip install -r scripts/requirements.txt.
"""

import json
import sys
from pathlib import Path

import numpy as np
from pedalboard import Compressor, HighpassFilter, Pedalboard, Reverb
from scipy import ndimage, signal
from scipy.io import wavfile

SR = 48_000
RNG = np.random.default_rng(221)
TARGET_LUFS = -14.0
CEILING_DBTP = -1.2


def midi(note: float) -> float:
    return 440.0 * 2 ** ((note - 69) / 12)


# Each part's harmony: the pad's notes (MIDI), the strings' (low on the left, the high line on the right), the piano's
# arpeggio notes, and the bass's root.
D1, G1, A1, B1, Fs1 = 26, 31, 33, 35, 30
CHORDS = {
    "intro": dict(pad=[50, 57, 61, 64, 66], strings=([50, 57], [66, 69]), piano=[62, 66, 69, 73], root=D1),
    "android": dict(pad=[55, 59, 62, 66], strings=([43, 50], [66, 71]), piano=[59, 62, 66, 71], root=G1),
    "organize": dict(pad=[50, 54, 57, 64], strings=([50, 57], [64, 66]), piano=[57, 62, 64, 66], root=D1),
    "dictate": dict(pad=[49, 52, 57, 59], strings=([45, 52], [64, 69]), piano=[57, 61, 64, 69], root=A1),
    "code": dict(pad=[47, 50, 54, 57, 61], strings=([47, 54], [66, 69]), piano=[59, 62, 66, 69, 73], root=B1),
    "steer": dict(pad=[43, 47, 50, 54, 57], strings=([43, 50], [66, 71]), piano=[59, 62, 66, 69, 74], root=G1),
    "live": dict(pad=[42, 50, 57, 64], strings=([42, 50], [69, 73]), piano=[57, 62, 66, 69, 74], root=Fs1),
    "ship": dict(pad=[45, 49, 52, 59], strings=([45, 52], [69, 73, 76]), piano=[61, 64, 69, 71, 76], root=A1),
    "lineup": dict(pad=[55, 59, 62, 66, 69], strings=([43, 50], [71, 74, 78]), piano=[62, 66, 69, 74, 78], root=G1),
    "night": dict(pad=[42, 50, 57, 64], strings=([42, 50], [69, 73]), piano=[57, 62, 66, 69, 74], root=Fs1),
    "lift": dict(pad=[45, 50, 52, 57], strings=([45, 52], [69, 74, 76]), piano=[62, 64, 69, 74], root=A1),
    "end": dict(pad=[50, 57, 62, 66, 69], strings=([38, 50], [66, 69, 74]), piano=[50, 57, 62, 66], root=D1),
}
# The bells the lineup's devices land on, over the IV: its 5th, 7th and 9th, climbing.
BELLS = [74, 78, 81]
# The three notes the end card falls on, to the tonic.
FALL = [81, 78, 74]


def t_of(n: int) -> np.ndarray:
    return np.arange(n) / SR


def sos(kind: str, freq, order: int = 2):
    return signal.butter(order, freq, btype=kind, fs=SR, output="sos")


def filt(x: np.ndarray, kind: str, freq, order: int = 2) -> np.ndarray:
    return signal.sosfilt(sos(kind, freq, order), x)


def fade(x: np.ndarray, into: float = 0.002, out: float = 0.01) -> np.ndarray:
    n = x.shape[-1]
    a, b = min(n, int(into * SR)), min(n, int(out * SR))
    x = x.copy()
    if a:
        x[..., :a] *= np.linspace(0, 1, a)
    if b:
        x[..., n - b :] *= np.linspace(1, 0, b)
    return x


def norm(x: np.ndarray) -> np.ndarray:
    return x / max(1e-9, np.max(np.abs(x)))


def stereo(x: np.ndarray, pan: float = 0.0) -> np.ndarray:
    if x.ndim == 2:
        return x
    return np.stack([x * np.sqrt(0.5 * (1 - pan)), x * np.sqrt(0.5 * (1 + pan))]) * np.sqrt(2)


def saw(freq: float, t: np.ndarray, top: float, phase: float = 0.0) -> np.ndarray:
    """A band-limited sawtooth: its harmonics summed up to [top] Hz, rolled off a little above half of it."""
    out = np.zeros_like(t)
    k = 1
    while k * freq < min(top, SR / 2 - 1000):
        out += np.sin(2 * np.pi * k * freq * t + phase * k) / k * np.exp(-k * freq / (0.55 * top))
        k += 1
    return out * 0.6


# ---- the kit ---------------------------------------------------------------------------------------------------------


def kick() -> np.ndarray:
    """A soft, round kick: more thump than click."""
    t = t_of(int(0.45 * SR))
    freq = 48 + 90 * np.exp(-t * 28) + 40 * np.exp(-t * 150)
    body = np.sin(2 * np.pi * np.cumsum(freq) / SR)
    amp = np.exp(-t * 8) * (1 - np.exp(-t * 1800))
    x = np.tanh(1.4 * body * amp) / np.tanh(1.4)
    click = filt(RNG.standard_normal(len(t)) * np.exp(-t * 900), "bandpass", (1200, 4000))
    return fade(x + 0.1 * click, 0.0005, 0.03)


def snap() -> np.ndarray:
    """A tight rim: a short bright knock on the backbeat, nothing like a clap."""
    t = t_of(int(0.18 * SR))
    tone = np.sin(2 * np.pi * 920 * t) * np.exp(-t * 90) + 0.5 * np.sin(2 * np.pi * 1840 * t) * np.exp(-t * 160)
    noise = filt(RNG.standard_normal(len(t)), "bandpass", (1800, 7000)) * np.exp(-t * 70)
    return fade(norm(0.6 * tone + noise), 0.0005, 0.04)


HAT_PARTIALS = (205.3, 304.4, 369.6, 522.7, 540.0, 800.0)


def hat(decay: float, length: float) -> np.ndarray:
    t = t_of(int(length * SR))
    metal = sum(signal.square(2 * np.pi * f * 1.9 * t + RNG.uniform(0, 6)) for f in HAT_PARTIALS) / len(HAT_PARTIALS)
    x = 0.45 * metal + 0.55 * RNG.standard_normal(len(t))
    x = filt(filt(x, "highpass", 6000, 4), "lowpass", 14000)
    x *= np.exp(-t * decay) * (1 - np.exp(-t * 4000))
    return fade(norm(x), 0.0003, 0.01)


def crash(length: float = 2.4) -> np.ndarray:
    t = t_of(int(length * SR))
    x = filt(RNG.standard_normal(len(t)), "highpass", 4200, 2)
    x = filt(x, "lowpass", 12500)
    x *= np.exp(-t * 2.2) * (1 - np.exp(-t * 600))
    return fade(norm(x), 0.0005, 0.3)


def impact(length: float = 3.2) -> np.ndarray:
    t = t_of(int(length * SR))
    freq = 36 + 50 * np.exp(-t * 7)
    boom = np.sin(2 * np.pi * np.cumsum(freq) / SR) * np.exp(-t * 2.4) * (1 - np.exp(-t * 900))
    air = filt(RNG.standard_normal(len(t)), "lowpass", 2000) * np.exp(-t * 7)
    x = np.tanh(1.4 * boom) + 0.3 * air
    return fade(norm(x), 0.0005, 0.4)


def tick() -> np.ndarray:
    """A glass tick, for the send."""
    t = t_of(int(0.35 * SR))
    x = np.sin(2 * np.pi * 2960 * t) * np.exp(-t * 28) + 0.4 * np.sin(2 * np.pi * 5920 * t) * np.exp(-t * 60)
    x += 0.5 * np.sin(2 * np.pi * 4430 * t) * np.exp(-t * 45)
    x += 0.15 * filt(RNG.standard_normal(len(t)), "highpass", 6000) * np.exp(-t * 900)
    return fade(norm(x), 0.0003, 0.05)


def tap() -> np.ndarray:
    t = t_of(int(0.06 * SR))
    x = np.sin(2 * np.pi * 1650 * t) * np.exp(-t * 230) + 0.5 * filt(RNG.standard_normal(len(t)), "highpass", 3500) * np.exp(-t * 700)
    return fade(norm(x), 0.0003, 0.01)


def air(length: float, seed: int) -> np.ndarray:
    """The room the score is played in: a breath of high noise, each side its own, drifting slowly in level and colour,
    eased in and out."""
    rng = np.random.default_rng(seed)
    n = int(length * SR)
    t = t_of(n)
    out = np.zeros((2, n))
    for ch in range(2):
        x = filt(rng.standard_normal(n), "bandpass", (2600, 11000), 2)
        drift = 0.65 + 0.35 * np.sin(2 * np.pi * rng.uniform(0.11, 0.19) * t + rng.uniform(0, 6))
        out[ch] = x * drift
    return fade(norm(out), 0.25, 0.25)


def click() -> np.ndarray:
    """A lamp's switch: a dry snap of plastic, a smaller one as it seats, and the knock of it through the body."""
    t = t_of(int(0.12 * SR))
    x = filt(RNG.standard_normal(len(t)), "bandpass", (1800, 7000)) * np.exp(-t * 900)
    late = int(0.011 * SR)
    x[late:] += 0.45 * filt(RNG.standard_normal(len(t) - late), "bandpass", (2400, 8000)) * np.exp(-t[: len(t) - late] * 1400)
    x += 0.6 * np.sin(2 * np.pi * 240 * t) * np.exp(-t * 120)
    return fade(norm(x), 0.0002, 0.02)


def glow(length: float = 0.9) -> np.ndarray:
    """The screen waking: a faint high shimmer coming up with its light and settling, no louder than the room."""
    t = t_of(int(length * SR))
    env = (1 - np.exp(-t * 9)) * np.exp(-t * 3.2)
    x = sum(np.sin(2 * np.pi * f * t + k) / (k + 1) for k, f in enumerate([1480, 2217, 2960]))
    x += 0.3 * filt(RNG.standard_normal(len(t)), "bandpass", (3000, 9000))
    return fade(norm(x * env), 0.004, 0.1)


def riser(length: float) -> np.ndarray:
    """Noise through a band-pass that sweeps up as it swells, its filter stepped a block at a time."""
    n = int(length * SR)
    noise = RNG.standard_normal(n)
    out = np.zeros(n)
    block = 256
    zi = None
    for i in range(0, n, block):
        p = i / n
        centre = 220 * (8000 / 220) ** p
        s = sos("bandpass", (centre / 1.6, min(centre * 1.6, SR / 2 - 100)))
        if zi is None:
            zi = signal.sosfilt_zi(s) * 0
        chunk, zi = signal.sosfilt(s, noise[i : i + block], zi=zi)
        out[i : i + block] = chunk
    t = t_of(n)
    out *= (t / length) ** 2.6
    return norm(out)


def whoosh(length: float = 0.7, peak: float = 0.6) -> np.ndarray:
    """A swell of air, gathering to [peak] of its length and let go: under a light sweeping the stage."""
    n = int(length * SR)
    t = t_of(n)
    x = filt(RNG.standard_normal(n), "bandpass", (500, 3200))
    env = np.where(t < peak * length, (t / (peak * length)) ** 2.2, np.exp(-(t - peak * length) * 14))
    x = x * env
    # It moves across the stereo field with the light, left to right.
    pan = -0.6 + 1.2 * t / length
    out = np.stack([x * np.sqrt(0.5 * (1 - pan)), x * np.sqrt(0.5 * (1 + pan))]) * np.sqrt(2)
    return fade(out / max(1e-9, np.max(np.abs(out))), 0.01, 0.05)


def swell(length: float) -> np.ndarray:
    """A crash played backwards: it gathers up to its end."""
    x = crash(length + 0.4)[::-1][-int(length * SR) :]
    return fade(norm(x), 0.05, 0.004)


# ---- the tonal parts -------------------------------------------------------------------------------------------------


def piano(note: int, length: float, velocity: float = 0.6) -> np.ndarray:
    """A felt piano note: a few inharmonic partials, the higher ones dying faster, under a soft hammer, low-passed so
    the felt is heard more than the string."""
    n = int(length * SR)
    t = t_of(n)
    f = midi(note)
    x = np.zeros(n)
    inharm = 0.00035
    for k in range(1, 7):
        fk = f * k * np.sqrt(1 + inharm * k * k)
        if fk > SR / 2 - 2000:
            break
        decay = 0.9 + 0.75 * k + f / 500
        x += np.sin(2 * np.pi * fk * t + RNG.uniform(0, 6)) * np.exp(-t * decay) / k ** (1.4 - 0.5 * velocity)
    x *= 1 - np.exp(-t * 350)
    hammer = filt(RNG.standard_normal(n), "lowpass", 900) * np.exp(-t * 110) * 0.35
    x = filt(x + hammer, "lowpass", 1800 + 2200 * velocity)
    x *= np.clip((length - t) / 0.12, 0, 1)
    pan = np.clip((note - 64) / 28, -0.5, 0.5)
    return stereo(fade(norm(x) * velocity, 0.0005, 0.05), pan)


def chord(notes: list[int], length: float, top: float, attack: float, release: float, seed: int) -> np.ndarray:
    """The pad: each note two detuned band-limited saws, one a side, fading in over [attack] and out over [release]."""
    rng = np.random.default_rng(seed)
    t = t_of(int(length * SR))
    left = np.zeros_like(t)
    right = np.zeros_like(t)
    for n in notes:
        f = midi(n)
        left += saw(f * 2 ** (-7 / 1200), t, top, rng.uniform(0, 6)) + 0.5 * saw(f * 2 ** (4 / 1200), t, top, rng.uniform(0, 6))
        right += saw(f * 2 ** (7 / 1200), t, top, rng.uniform(0, 6)) + 0.5 * saw(f * 2 ** (-4 / 1200), t, top, rng.uniform(0, 6))
    env = np.minimum(1, t / max(attack, 1e-3)) * np.clip((length - t) / release, 0, 1)
    # Its low notes are the bass's room, so the pad keeps above 180 Hz.
    return filt(np.stack([left, right]), "highpass", 180) * env / len(notes)


def strings(notes: list[int], length: float, attack: float, release: float, seed: int, top: float = 5200) -> np.ndarray:
    """A string section: each note several saws a few cents apart, each with its own slow vibrato and a little late,
    bowed in over [attack] and let go over [release], through a body of two formants."""
    rng = np.random.default_rng(seed)
    n = int(length * SR)
    t = t_of(n)
    out = np.zeros((2, n))
    for note in notes:
        f = midi(note)
        for v in range(4):
            cents = rng.uniform(-9, 9)
            rate = rng.uniform(4.2, 5.6)
            depth = rng.uniform(3, 6) * (1 - np.exp(-t * 1.5))
            vib = 2 ** ((cents + depth * np.sin(2 * np.pi * rate * t + rng.uniform(0, 6))) / 1200)
            phase = 2 * np.pi * np.cumsum(f * vib) / SR
            voice = np.zeros(n)
            k = 1
            while k * f < min(top, SR / 2 - 1000):
                voice += np.sin(k * phase + rng.uniform(0, 6)) / k * np.exp(-k * f / (0.6 * top))
                k += 1
            late = int(rng.uniform(0, 0.035) * SR)
            voice = np.concatenate([np.zeros(late), voice[: n - late]])
            pan = rng.uniform(-0.7, 0.7)
            out[0] += voice * np.sqrt(0.5 * (1 - pan))
            out[1] += voice * np.sqrt(0.5 * (1 + pan))
    env = (1 - np.exp(-t / max(attack, 1e-3) * 3)) ** 1.5 * np.clip((length - t) / release, 0, 1)
    body = signal.sosfilt(sos("bandpass", (180, 4200)), out)
    formant = signal.sosfilt(sos("bandpass", (700, 1400)), out) * 0.35 + signal.sosfilt(sos("bandpass", (2400, 3400)), out) * 0.2
    return (body + formant) * env / (len(notes) * 4)


def pluck(note: int, length: float, bright: float = 0.5) -> np.ndarray:
    """The pulse: a saw through a low-pass that snaps shut, the note's root plucked on the eighths."""
    n = int(length * SR)
    t = t_of(n)
    f = midi(note)
    x = saw(f, t, 6000) + 0.35 * np.sin(2 * np.pi * f * t)
    env = (1 - np.exp(-t * 2500)) * np.exp(-t * 11)
    # The filter closes with the note: brighter attack, darker tail, stepped a block at a time.
    out = np.zeros(n)
    block = 128
    zi = None
    for i in range(0, n, block):
        p = i / n
        cutoff = 300 + (1800 + 2400 * bright) * np.exp(-p * length * 16)
        s = sos("lowpass", cutoff, 2)
        if zi is None:
            zi = signal.sosfilt_zi(s) * 0
        chunk, zi = signal.sosfilt(s, x[i : i + block], zi=zi)
        out[i : i + block] = chunk
    return fade(norm(out * env), 0.0005, 0.02)


def bass(root: int, length: float) -> np.ndarray:
    t = t_of(int(length * SR))
    f = midi(root)
    sub = np.sin(2 * np.pi * f * t)
    grit = saw(2 * f, t, 1400)
    env = (1 - np.exp(-t * 60)) * np.clip((length - t) / 0.08, 0, 1)
    x = 0.8 * sub * env + 0.4 * grit * env
    return fade(np.tanh(1.2 * x), 0.002, 0.03)


def bell(note: int, length: float = 3.0) -> np.ndarray:
    """A bell: a few stretched partials, the higher dying faster, with a soft strike."""
    t = t_of(int(length * SR))
    f = midi(note)
    x = np.zeros_like(t)
    for ratio, amp, decay in ((1.0, 1.0, 1.6), (2.0, 0.5, 2.6), (2.76, 0.35, 3.6), (5.4, 0.12, 6.0), (8.93, 0.05, 9.0)):
        x += amp * np.sin(2 * np.pi * f * ratio * t + RNG.uniform(0, 6)) * np.exp(-t * decay)
    x *= 1 - np.exp(-t * 900)
    x += 0.08 * filt(RNG.standard_normal(len(t)), "highpass", 5000) * np.exp(-t * 400)
    pan = np.clip((note - 76) / 10, -0.4, 0.4)
    return stereo(fade(norm(x), 0.0005, 0.3), pan)


# ---- the score -------------------------------------------------------------------------------------------------------


class Mix:
    def __init__(self, seconds: float):
        self.n = int(round(seconds * SR))
        self.dry = np.zeros((2, self.n))
        self.pumped = np.zeros((2, self.n))
        self.send = np.zeros((2, self.n))

    def add(self, at: float, x: np.ndarray, gain: float, pan: float = 0.0, verb: float = 0.0, pump: bool = False):
        x = stereo(x, pan)
        i = int(round(at * SR))
        if i >= self.n or i < 0:
            return
        x = x[:, : self.n - i] * gain
        (self.pumped if pump else self.dry)[:, i : i + x.shape[1]] += x
        if verb:
            self.send[:, i : i + x.shape[1]] += x * verb


def pump(mix: Mix, kicks: list[float], depth: float = 0.3, release: float = 0.18) -> np.ndarray:
    """The sidechain: what's pumped ducks a little under each kick and swells back before the next."""
    gain = np.ones(mix.n)
    t = t_of(int(release * 3 * SR))
    dip = 1 - depth * np.exp(-t / release * 2.2) * (1 - np.exp(-t * 900))
    for k in kicks:
        i = int(round(k * SR))
        j = min(mix.n, i + len(dip))
        gain[i:j] = np.minimum(gain[i:j], dip[: j - i])
    return gain


# Each voice's level in the mix, before mastering.
LEVEL = {
    "impact": 0.75,
    "tick": 0.28,
    "riser": 0.3,
    "swell": 0.34,
    "whoosh": 0.26,
    "kick": 0.42,
    "snap": 0.3,
    "hat": 0.2,
    "bass": 0.33,
    "pad": 0.62,
    "strings": 0.55,
    "piano": 0.5,
    "pluck": 0.26,
    "crash": 0.3,
    "bell": 0.5,
    "tap": 0.14,
    "air": 0.05,
}
# How loud each part stands (the pad's, the bass's and the piano's gain), how far its pad opens (the top of its
# harmonics, in Hz), how loud its strings stand (low, high), how the piano moves (notes a beat), how loud the pulse is,
# and what of the kit plays: the build, a part at a time.
ARC = {
    "intro": dict(gain=0.55, top=1600, strings=(0.0, 0.0), piano=0, pluck=0.0, kit=()),
    "android": dict(gain=0.62, top=1900, strings=(0.0, 0.0), piano=0, pluck=0.0, kit=()),
    "organize": dict(gain=0.66, top=2200, strings=(0.35, 0.0), piano=1, pluck=0.0, kit=()),
    "dictate": dict(gain=0.74, top=2500, strings=(0.7, 0.0), piano=1, pluck=0.35, kit=()),
    "code": dict(gain=0.84, top=2800, strings=(0.85, 0.25), piano=2, pluck=0.7, kit=("kick",)),
    "steer": dict(gain=0.9, top=3000, strings=(0.9, 0.45), piano=2, pluck=0.85, kit=("kick", "snap", "hat")),
    "live": dict(gain=0.94, top=3200, strings=(1.0, 0.7), piano=2, pluck=0.9, kit=("kick", "snap", "hat")),
    "night": dict(gain=0.38, top=1400, strings=(0.32, 0.26), piano=1, pluck=0.0, kit=()),
    "ship": dict(gain=1.0, top=3600, strings=(1.0, 1.0), piano=2, pluck=1.0, kit=("kick", "snap", "hat")),
    "lineup": dict(gain=1.06, top=4200, strings=(1.0, 1.0), piano=2, pluck=1.0, kit=("kick", "snap", "hat")),
    "lift": dict(gain=1.06, top=4600, strings=(1.0, 1.1), piano=0, pluck=0.8, kit=("kick", "hat")),
    "end": dict(gain=0.8, top=3400, strings=(0.8, 0.6), piano=0, pluck=0.0, kit=()),
}


def score(cues: dict) -> np.ndarray:
    fps, bpm = cues["fps"], cues["bpm"]
    at = cues["at"]
    beat = 60 / bpm
    sec = lambda frame: frame / fps  # noqa: E731
    length = cues["frames"] / fps
    mix = Mix(length)
    lv = LEVEL

    parts = [
        ("intro", at["title"], at["android"]),
        ("android", at["android"], at["organize"]),
        ("organize", at["organize"], at["dictate"]),
        ("dictate", at["dictate"], at["code"]),
        ("code", at["code"], at["steer"]),
        ("steer", at["steer"], at["live"]),
        *(
            [("live", at["live"], at["night"]), ("night", at["night"], at["ship"])]
            if "night" in at
            else [("live", at["live"], at["ship"])]
        ),
        ("ship", at["ship"], at["lineup"]),
        ("lineup", at["lineup"], at["lineup"] + 4 * fps * beat),
        ("lift", at["lineup"] + 4 * fps * beat, at["end"]),
    ]
    kicks: list[float] = []
    k_sound, s_sound = kick(), snap()
    closed, open_ = hat(110, 0.1), hat(18, 0.4)

    # The open: the icon lands on a soft hit, two high piano notes name it, and the air gathers into the green card.
    mix.add(sec(at["title"]), impact(2.5), lv["impact"] * 0.55, verb=0.4)
    mix.add(sec(at["title"]), piano(81, 3.0, 0.5), lv["piano"] * 0.9, verb=0.6)
    mix.add(sec(at["wordmark"]), piano(74, 3.0, 0.55), lv["piano"] * 0.9, verb=0.6)
    mix.add(sec(at["android"]) - beat * 1.25, swell(beat * 1.25), lv["swell"] * 0.8)
    mix.add(sec(at["android"]), impact(2.0), lv["impact"] * 0.5, verb=0.3)
    # The green closing into the hero's phone: a sub hit under a riser, and the first crash.
    mix.add(sec(at["android"]) + beat * 0.5, riser(sec(at["organize"]) - sec(at["android"]) - beat * 0.5), lv["riser"] * 0.9, verb=0.3)
    mix.add(sec(at["organize"]), impact(3.5), lv["impact"], verb=0.45)

    for seed, (name, start, until) in enumerate(parts, start=10):
        t0, t1 = sec(start), sec(until)
        c = CHORDS[name]
        arc = ARC[name]
        span = t1 - t0
        g = arc["gain"]
        # The pad, the strings and the bass hold the part's chord from cut to cut.
        mix.add(t0, chord(c["pad"], span + 0.1, arc["top"], 0.04, 0.1, seed), lv["pad"] * g, verb=0.35, pump=True)
        low, high = arc["strings"]
        if low:
            mix.add(t0, strings(c["strings"][0], span + 0.25, 0.5, 0.3, seed + 100), lv["strings"] * low, verb=0.5, pump=True)
        if high:
            mix.add(t0, strings(c["strings"][1], span + 0.25, 0.7, 0.3, seed + 200, top=6500), lv["strings"] * 0.7 * high, verb=0.55, pump=True)
        mix.add(t0, air(span + 0.3, seed + 400), lv["air"] * g, verb=0.2)
        if name not in ("intro",):
            mix.add(t0, bass(c["root"], span + 0.05), lv["bass"] * g, pump=True)
        # The piano states the chord on the cut and then walks it, a note a beat or two, up and back.
        if name != "intro":
            for k, note in enumerate(c["piano"]):
                mix.add(t0 + 0.004 * k, piano(note, 4.0, 0.55 if name in ("android", "organize") else 0.65), lv["piano"] * 0.75 * g, verb=0.45)
        if arc["piano"]:
            walk = c["piano"] + c["piano"][-2:0:-1]
            step = beat / arc["piano"]
            n_steps = int((span - 0.001) / step)
            for i in range(1, n_steps):
                note = walk[i % len(walk)]
                vel = 0.38 + 0.12 * (i % 2 == 0) + 0.1 * (name in ("ship", "lineup"))
                mix.add(t0 + i * step, piano(note, 2.2, vel), lv["piano"] * 0.6 * g, verb=0.5)
        # The crashes, risers and swells on the big cuts.
        if name in ("code", "ship", "lineup"):
            mix.add(t0, crash(), lv["crash"], verb=0.25)
        if name == "live":
            mix.add(t0, crash(1.6), lv["crash"] * 0.5, verb=0.25)
        if name == "night":
            mix.add(t0, impact(3.0), lv["impact"] * 0.25, verb=0.5)
        if name in ("ship", "lift", "night"):
            mix.add(t1 - beat * 1.5, riser(beat * 1.5), lv["riser"], verb=0.3)
            mix.add(t1 - beat, swell(beat), lv["swell"])
        if name == "dictate":
            mix.add(t1 - beat * 2, riser(beat * 2), lv["riser"] * 0.8, verb=0.3)
        # The cut starts every part on a beat but not always on a bar, so beats count from the top of the video and
        # the backbeat keeps to two and four across the cuts.
        first, until_beat = int(round(t0 / beat)), int(round(t1 / beat))
        for g in range(first, until_beat):
            t = g * beat
            # A breath before the lineup and the end: the kick drops out under the swell for the part's last beat.
            breath = g == until_beat - 1 and name in ("ship", "lift")
            if "kick" in arc["kit"] and not breath:
                mix.add(t, k_sound, lv["kick"])
                kicks.append(t)
            if "snap" in arc["kit"] and g % 2 == 1 and not breath:
                mix.add(t, s_sound, lv["snap"], pan=-0.08, verb=0.3)
            if "hat" in arc["kit"]:
                mix.add(t + beat / 2, closed, lv["hat"], pan=0.2)
                if name in ("ship", "lineup", "lift"):
                    mix.add(t + beat / 2, open_, lv["hat"] * 0.6, pan=0.1)
            if arc["pluck"]:
                for q in (0.0, 0.5):
                    note = c["root"] + 24 + (7 if q and name in ("steer", "live", "ship", "lineup", "lift") and g % 2 else 0)
                    mix.add(t + beat * q, pluck(note, beat * 0.5, 0.3 + 0.5 * arc["pluck"]), lv["pluck"] * arc["pluck"] * (1 if q == 0 else 0.7), pan=0.12, pump=True)

    # The lineup's devices, each landing on a bell.
    for i, frame in enumerate(cues["lands"]):
        mix.add(sec(frame), bell(BELLS[i]), lv["bell"] * (0.8 + 0.1 * i), verb=0.5)

    # The end card: the last hit, the fall to the tonic, the chord ringing out and the strings resolving under it.
    end = sec(at["end"])
    mix.add(end, impact(4.0), lv["impact"] * 0.9, verb=0.4)
    mix.add(end, crash(3.0), lv["crash"] * 0.8, verb=0.3)
    mix.add(end, bell(74, 5.0), lv["bell"] * 0.8, verb=0.6)
    for k, note in enumerate(CHORDS["end"]["piano"]):
        mix.add(end + 0.006 * k, piano(note, length - end, 0.7), lv["piano"] * 0.9, verb=0.55)
    for i, note in enumerate(FALL):
        mix.add(end + beat * (1 + i), piano(note, length - end, 0.6 - 0.05 * i), lv["piano"] * 0.85, verb=0.6)
    mix.add(end + beat * 3, piano(62, length - end, 0.5), lv["piano"] * 0.8, verb=0.6)
    mix.add(end, chord(CHORDS["end"]["pad"], length - end, ARC["end"]["top"], 0.02, 2.4, 7), lv["pad"] * 0.9, verb=0.55)
    mix.add(end, strings(CHORDS["end"]["strings"][0], length - end, 0.8, 2.2, 300), lv["strings"] * 0.8, verb=0.6)
    mix.add(end, strings(CHORDS["end"]["strings"][1], length - end, 1.2, 2.2, 301, top=6500), lv["strings"] * 0.5, verb=0.65)
    mix.add(end, bass(CHORDS["end"]["root"], length - end - 0.5), lv["bass"] * 0.9)

    # The light sweeping the stage, each with a swell of air gathering into the cut and let go across it.
    for frame in cues["sweeps"]:
        mix.add(sec(frame) - 0.42, whoosh(0.75, 0.56), lv["whoosh"] * (0.8 if frame == at["organize"] else 1), verb=0.3)
    # The lights carrying the cuts: a swell of air the length of each crossing, breaking as its beam crosses the middle
    # of the frame on the cut, the one into the lineup the fullest.
    for carry in cues["carries"]:
        lineup = carry["at"] == at["lineup"]
        mix.add(sec(carry["from"]), whoosh(carry["frames"] / fps, 0.5), lv["whoosh"] * (1.25 if lineup else 1.05), verb=0.35)
    # The send: a glass tick as the prompt flies.
    mix.add(sec(cues["send"]), tick(), lv["tick"], pan=0.15, verb=0.6)
    # The taps on screen, faintly.
    for frame in cues["taps"]:
        mix.add(sec(frame), tap(), lv["tap"], pan=0.1)

    # The day's foley: the screen waking, the lamp's switch at night, the notification's chime on the lock screen.
    foley = cues.get("foley")
    if foley:
        for frame in foley["screen"]:
            mix.add(sec(frame), glow(), lv["tick"] * 0.5, pan=0.1, verb=0.5)
        mix.add(sec(foley["lamp"]), click(), lv["tick"] * 0.7, pan=-0.35, verb=0.25)
        mix.add(sec(foley["chime"]), bell(86, 2.4), lv["bell"] * 0.42, pan=0.12, verb=0.6)
        mix.add(sec(foley["chime"]) + 0.16, bell(81, 2.4), lv["bell"] * 0.32, pan=0.12, verb=0.6)

    gain = pump(mix, kicks)
    wet = Pedalboard([Reverb(room_size=0.74, damping=0.4, wet_level=1.0, dry_level=0.0, width=1.0)])(mix.send.astype(np.float32), SR)
    wet = filt(wet, "highpass", 220)
    out = mix.dry + mix.pumped * gain + 0.55 * wet
    # The dip into the end card: the score falls away with the picture, 19 dB and all the way down six frames before the
    # cut, so the last hit comes back up out of a held hush rather than out of the bottom of a fade.
    a, c, b = int((end - cues["dip"] / fps) * SR), int((end - 6 / fps) * SR), int(end * SR)
    u = np.linspace(0, 1, c - a)
    duck = np.ones(out.shape[1])
    floor = 10 ** (-19 / 20)
    duck[a:c] = 1 - (1 - floor) * u * u * u * (10 + u * (6 * u - 15))
    duck[c:b] = floor
    release = int(0.002 * SR)
    duck[b - release : b] = np.linspace(duck[b - release], 1, release)
    return out * duck


# ---- mastering -------------------------------------------------------------------------------------------------------


def lufs(x: np.ndarray) -> float:
    """Integrated loudness as ITU-R BS.1770-4 has it, for 48 kHz: K-weighted, in 400 ms blocks, gated."""
    shelf = (np.array([1.53512485958697, -2.69169618940638, 1.19839281085285]), np.array([1.0, -1.69065929318241, 0.73248077421585]))
    rlb = (np.array([1.0, -2.0, 1.0]), np.array([1.0, -1.99004745483398, 0.99007225036621]))
    k = signal.lfilter(*rlb, signal.lfilter(*shelf, x, axis=1), axis=1)
    size, hop = int(0.4 * SR), int(0.1 * SR)
    z = np.array([np.mean(k[:, i : i + size] ** 2, axis=1).sum() for i in range(0, k.shape[1] - size + 1, hop)])
    loud = -0.691 + 10 * np.log10(np.maximum(z, 1e-12))
    z = z[loud > -70]
    rel = -0.691 + 10 * np.log10(np.mean(z)) - 10
    z = z[-0.691 + 10 * np.log10(z) > rel]
    return float(-0.691 + 10 * np.log10(np.mean(z)))


def peaks(x: np.ndarray) -> np.ndarray:
    """Each sample's true peak: the loudest of the channels' 4x-oversampled values from it to the next."""
    up = np.abs(signal.resample_poly(x, 4, 1, axis=1)).max(axis=0)
    return up[: 4 * x.shape[1]].reshape(-1, 4).max(axis=1)


def true_peak(x: np.ndarray) -> float:
    return float(20 * np.log10(np.max(peaks(x))))


def limit(x: np.ndarray, ceiling_db: float, lookahead: float = 0.002, release: float = 0.08) -> np.ndarray:
    """A look-ahead limiter on the true peak. The gain each peak needs is held from [lookahead] before it to as long
    after, released from there, then averaged over the look-ahead so the gain eases down; every sample the average
    takes in is held to the peak's gain, so the eased gain still meets it."""
    need = np.minimum(1.0, 10 ** (ceiling_db / 20) / np.maximum(peaks(x), 1e-9))
    n = int(lookahead * SR)
    held = ndimage.minimum_filter1d(need, size=2 * n + 1, mode="nearest")
    a = np.exp(-1 / (release * SR))
    gain = np.empty_like(held)
    g = 1.0
    for i, h in enumerate(held):
        g = h if h < g else a * g + (1 - a) * h
        gain[i] = g
    return x * ndimage.uniform_filter1d(gain, size=n + 1, mode="nearest")


def master(x: np.ndarray) -> np.ndarray:
    x = x * 10 ** ((-20 - lufs(x)) / 20)
    x = Pedalboard([HighpassFilter(32), HighpassFilter(32), Compressor(threshold_db=-18, ratio=1.8, attack_ms=20, release_ms=180)])(x.astype(np.float32), SR).astype(np.float64)
    gain = TARGET_LUFS - lufs(x)
    for _ in range(8):
        y = limit(x * 10 ** (gain / 20), CEILING_DBTP)
        miss = TARGET_LUFS - lufs(y)
        if abs(miss) < 0.05:
            break
        gain += miss
    peak = true_peak(y)
    if peak > CEILING_DBTP:
        y *= 10 ** ((CEILING_DBTP - peak) / 20)
    tail = int(0.02 * SR)
    y[:, -tail:] *= np.linspace(1, 0, tail)
    return y


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "public/audio/score.wav")
    name = sys.argv[2] if len(sys.argv) > 2 else "cues.json"
    cues = json.loads((Path(__file__).resolve().parent.parent / "public" / "audio" / name).read_text())
    x = master(score(cues))
    print(f"score: {x.shape[1] / SR:.2f}s, {lufs(x):.2f} LUFS, true peak {true_peak(x):.2f} dBTP")
    pcm = np.clip(x.T + RNG.triangular(-1, 0, 1, x.T.shape) / 32768, -1, 1)
    out.parent.mkdir(parents=True, exist_ok=True)
    wavfile.write(out, SR, (pcm * 32767).astype(np.int16))


if __name__ == "__main__":
    main()
