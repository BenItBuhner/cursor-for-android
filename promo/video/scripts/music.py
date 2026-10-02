#!/usr/bin/env python3
"""The launch video's score, synthesised here from oscillators and noise alone, so that it is the video's own and owes
no one a licence: a 100 BPM track in B minor laid on the cut's sections, with its swells, the taps, the key clicks and
the Fold's hinge landing on the frames scripts/cues.ts found them on.

    python3 scripts/music.py public/audio/score.wav      (reads cues.json beside the output)

It needs numpy and scipy: pip install -r scripts/requirements.txt.
"""

import json
import sys
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 48000
rng = np.random.default_rng(1009)

out = Path(sys.argv[1] if len(sys.argv) > 1 else "public/audio/score.wav")
cues = json.loads((out.parent / "cues.json").read_text())
FPS = cues["fps"]
BEAT = 60 / cues["bpm"]
BAR = 4 * BEAT
N = int(round(cues["frames"] / FPS * SR))


def beat_of(frame: float) -> float:
    return frame / FPS / BEAT


SECTION = {s["name"]: beat_of(s["from"]) for s in cues["sections"]}
END_BEAT = beat_of(cues["frames"])


def section_at(beat: float) -> str:
    name = "intro"
    for s, start in SECTION.items():
        if beat >= start - 1e-9:
            name = s
    return name


# ---- Pitch and harmony ----------------------------------------------------------------------------------------------

NAMES = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}


def hz(note: str) -> float:
    midi = 12 * (int(note[-1]) + 1) + NAMES[note[:-1]]
    return 440.0 * 2 ** ((midi - 69) / 12)


# One chord a bar, Bm - G - D - A: the bass's root, the pad's voicing and the arpeggio's tones.
CHORDS = [
    ("B1", ["B3", "D4", "F#4", "B4"], ["B3", "D4", "F#4", "B4", "D5", "F#5"]),
    ("G1", ["G3", "B3", "D4", "G4"], ["G3", "B3", "D4", "G4", "B4", "D5"]),
    ("D2", ["A3", "D4", "F#4", "A4"], ["D4", "F#4", "A4", "D5", "F#5", "A5"]),
    ("A1", ["A3", "C#4", "E4", "A4"], ["A3", "C#4", "E4", "A4", "C#5", "E5"]),
]
BARS = int(round(END_BEAT / 4))


def chord(bar: int):
    return CHORDS[bar % 4]


# ---- Buffers --------------------------------------------------------------------------------------------------------

def bus() -> np.ndarray:
    return np.zeros((2, N))


TAPER = 240


def place(buf: np.ndarray, start: float, sig: np.ndarray, gain: float = 1.0, pan: float = 0.0) -> None:
    """Adds [sig] (mono, or stereo as 2 x n) into [buf] from [start] seconds, panned -1 left to 1 right; its last 5 ms
    fade out, so that a sound cut off while it still rings doesn't click."""
    i = int(round(start * SR))
    if i >= N:
        return
    if sig.ndim == 1:
        angle = (pan + 1) * np.pi / 4
        sig = np.stack([sig * np.cos(angle), sig * np.sin(angle)]) * np.sqrt(2)
    else:
        sig = sig.copy()
    k = min(TAPER, sig.shape[1])
    sig[:, -k:] *= np.linspace(1, 0, k)
    j0 = max(0, -i)
    n = min(sig.shape[1] - j0, N - max(i, 0))
    if n > 0:
        buf[:, max(i, 0) : max(i, 0) + n] += gain * sig[:, j0 : j0 + n]


def seconds(n: float) -> int:
    return int(round(n * SR))


def ramp(n: int) -> np.ndarray:
    return np.arange(n) / SR


# ---- Oscillators, envelopes and filters -----------------------------------------------------------------------------

def phase_of(freq, n: int, phase0: float = 0.0) -> np.ndarray:
    dt = np.broadcast_to(np.asarray(freq, dtype=float) / SR, (n,))
    return (phase0 + np.cumsum(dt) - dt) % 1.0, dt


def saw(freq, n: int, phase0: float = 0.0) -> np.ndarray:
    """A band-limited sawtooth: PolyBLEP smooths the reset so it doesn't alias."""
    p, dt = phase_of(freq, n, phase0)
    y = 2 * p - 1
    lo = p < dt
    x = p[lo] / dt[lo]
    y[lo] -= x + x - x * x - 1
    hi = p > 1 - dt
    x = (p[hi] - 1) / dt[hi]
    y[hi] -= x * x + x + x + 1
    return y


def square(freq, n: int, phase0: float = 0.0) -> np.ndarray:
    return 0.5 * (saw(freq, n, phase0) - saw(freq, n, (phase0 + 0.5) % 1.0))


def sine(freq, n: int, phase0: float = 0.0) -> np.ndarray:
    p, _ = phase_of(freq, n, phase0)
    return np.sin(2 * np.pi * p)


def adsr(n: int, attack: float, decay: float, sustain: float, release: float, hold: float) -> np.ndarray:
    """Up over [attack], down to [sustain] over [decay], held until [hold] seconds and released over [release]."""
    t = ramp(n)
    env = np.where(t < attack, t / max(attack, 1e-6), sustain + (1 - sustain) * np.exp(-(t - attack) / max(decay, 1e-6)))
    rel = t >= hold
    level = np.interp(hold, t, env) if n else 0
    env[rel] = level * np.exp(-(t[rel] - hold) / max(release, 1e-6))
    return env


def filt(x: np.ndarray, kind: str, fc, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, fc, kind, fs=SR, output="sos")
    return signal.sosfilt(sos, x, axis=-1)


def swept(x: np.ndarray, kind: str, curve: np.ndarray, width: float = 1.0, block: int = 256) -> np.ndarray:
    """A two-pole filter whose cutoff (or band centre, [width] octaves wide) follows [curve], one value a block."""
    y = np.empty_like(x)
    zi = None
    for i in range(0, x.shape[-1], block):
        fc = float(curve[min(i + block // 2, len(curve) - 1)])
        fc = min(max(fc, 20.0), SR * 0.45)
        if kind == "band":
            lo, hi = fc / 2 ** (width / 2), min(fc * 2 ** (width / 2), SR * 0.45)
            b, a = signal.butter(1, [lo, hi], "band", fs=SR)
        else:
            b, a = signal.butter(2, fc, kind, fs=SR)
        if zi is None:
            zi = np.zeros(x.shape[:-1] + (max(len(a), len(b)) - 1,))
        y[..., i : i + block], zi = signal.lfilter(b, a, x[..., i : i + block], axis=-1, zi=zi)
    return y


def noise(n: int) -> np.ndarray:
    return rng.standard_normal(n)


# ---- Drums ----------------------------------------------------------------------------------------------------------

def kick(level: float = 1.0) -> np.ndarray:
    n = seconds(0.9)
    t = ramp(n)
    f = 52 + 125 * np.exp(-t / 0.03)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.15) * 0.8
    # The knock and the click are what small speakers, which can't move the body's air, hear of it.
    knock = np.sin(2 * np.pi * 128 * t) * np.exp(-t / 0.03) * 0.45
    click = filt(noise(n), "high", 1800) * np.exp(-t / 0.003) * 0.5
    return np.tanh(1.6 * (body + knock + click)) * level


def clap(level: float = 1.0) -> np.ndarray:
    n = seconds(0.6)
    t = ramp(n)
    env = sum(np.where(t >= d, np.exp(-(t - d) / 0.006), 0) for d in (0.0, 0.009, 0.019)) * 0.6
    env += np.where(t >= 0.028, np.exp(-(t - 0.028) / 0.11), 0)
    return filt(noise(n), "band", [900, 5200]) * env * level


def hat(open_: bool = False, level: float = 1.0) -> np.ndarray:
    n = seconds(0.8 if open_ else 0.1)
    t = ramp(n)
    metal = sum(square(f, n, rng.random()) for f in (317, 422, 553, 697, 802, 1041)) / 6
    body = filt(0.55 * noise(n) + 0.8 * metal, "high", 7200, 3)
    return body * np.exp(-t / (0.16 if open_ else 0.022)) * level


def snare(level: float = 1.0) -> np.ndarray:
    n = seconds(0.25)
    t = ramp(n)
    tone = np.sin(2 * np.pi * 186 * t) * np.exp(-t / 0.035) * 0.5
    rattle = filt(noise(n), "band", [1500, 7500]) * np.exp(-t / 0.07)
    return (tone + rattle) * level


# ---- Pitched voices -------------------------------------------------------------------------------------------------

def pad_note(freq: float, length: float, bright: float) -> np.ndarray:
    n = seconds(length + 1.6)
    voices = []
    for cents, pan in ((-11, -0.7), (0, 0.0), (11, 0.7)):
        f = freq * 2 ** (cents / 1200)
        voices.append((saw(f, n, rng.random()) + 0.35 * saw(2 * f, n, rng.random()), pan))
    env = adsr(n, 0.35, 0.8, 0.8, 0.4, length)
    stereo = np.zeros((2, n))
    for v, pan in voices:
        angle = (pan + 1) * np.pi / 4
        stereo += np.stack([v * np.cos(angle), v * np.sin(angle)])
    return filt(filt(stereo * env / 3, "low", bright), "high", 150)


def pluck(freq: float, level: float, decay: float = 0.16) -> np.ndarray:
    n = seconds(decay * 5)
    t = ramp(n)
    raw = saw(freq, n, rng.random()) * 0.7 + square(freq * 1.002, n, rng.random()) * 0.45
    bright = filt(raw, "low", 5200)
    dark = filt(raw, "low", 900)
    mix = np.exp(-t / (decay * 0.45))
    return (bright * mix + dark * (1 - mix)) * np.exp(-t / decay) * np.minimum(1, t / 0.002) * level


def bell(freq: float, length: float, level: float) -> np.ndarray:
    """An FM bell: a sine carrier, its modulator a fourteenth above, the brightness falling away as it rings."""
    n = seconds(length + 1.6)
    t = ramp(n)
    index = 2.2 * np.exp(-t / 0.35) + 0.25
    mod = np.sin(2 * np.pi * freq * 3.5 * t)
    car = np.sin(2 * np.pi * freq * t + index * mod)
    body = np.sin(2 * np.pi * freq * t) * 0.5
    env = np.minimum(1, t / 0.004) * np.exp(-t / 1.1) * np.where(t < length, 1, np.exp(-(t - length) / 0.35))
    return (car * 0.6 + body) * env * level


def bass_note(freq: float, length: float, level: float, cutoff: float = 700) -> np.ndarray:
    n = seconds(length + 0.25)
    sub = sine(freq, n, 0.25)
    grit = filt(saw(freq, n, rng.random()) + 0.5 * square(2 * freq, n, rng.random()), "low", cutoff)
    env = adsr(n, 0.004, 0.18, 0.75, 0.04, length)
    return np.tanh(1.5 * (sub * 0.35 + grit * 0.7)) * env * level


# ---- FX -------------------------------------------------------------------------------------------------------------

def whoosh(length: float, peak: float, lo: float, hi: float, level: float, sweep_to: float = -1.0) -> np.ndarray:
    """Air rushing past: noise in a band that rises to [hi] at [peak] of its [length] and falls back, panning across."""
    n = seconds(length)
    t = ramp(n) / length
    rise = np.clip(t / peak, 0, 1)
    fall = np.clip((t - peak) / (1 - peak), 0, 1)
    env = np.where(t < peak, rise ** 2.2, (1 - fall) ** 1.6)
    centre = np.where(t < peak, lo * (hi / lo) ** rise, hi * (lo / hi) ** fall)
    body = swept(noise(n), "band", centre * 1.0, width=1.4)
    shimmer = swept(noise(n), "band", centre * 2.4, width=0.8) * 0.35
    mono = (body + shimmer) * env * level
    pan = np.clip(-sweep_to + 2 * sweep_to * t, -1, 1)
    angle = (pan + 1) * np.pi / 4
    return np.stack([mono * np.cos(angle), mono * np.sin(angle)]) * np.sqrt(2)


def riser(length: float, level: float) -> np.ndarray:
    n = seconds(length)
    t = ramp(n) / length
    centre = 300 * (7000 / 300) ** t
    air = swept(noise(n), "band", centre, width=1.1)
    tone = saw(110 * 2 ** (t * 2), n) * 0.12
    tone = swept(tone, "low", centre)
    return (air + tone) * (t ** 2.4) * level


def impact(level: float, tail: float = 1.4) -> np.ndarray:
    n = seconds(tail + 1.2)
    t = ramp(n)
    f = 36 + 40 * np.exp(-t / 0.09)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.55)
    crash = filt(noise(n), "high", 3200) * np.exp(-t / (tail * 0.45)) * 0.28
    return np.tanh(1.4 * boom) * level + crash * level


def tap_click(level: float) -> np.ndarray:
    n = seconds(0.05)
    t = ramp(n)
    tick = np.sin(2 * np.pi * 2300 * t) * np.exp(-t / 0.0035)
    thud = np.sin(2 * np.pi * 420 * t) * np.exp(-t / 0.008) * 0.5
    return (tick + thud) * level


def key_click(down: bool, level: float, pitch: float) -> np.ndarray:
    n = seconds(0.08)
    t = ramp(n)
    if down:
        snap = filt(noise(n), "band", [1800 * pitch, 5200 * pitch]) * np.exp(-t / 0.006)
        thock = np.sin(2 * np.pi * 170 * pitch * t) * np.exp(-t / 0.022) * 0.9
        return (snap * 0.7 + thock) * level
    snap = filt(noise(n), "band", [2600 * pitch, 7000 * pitch]) * np.exp(-t / 0.004)
    return snap * level * 0.35


def hinge_thunk(level: float) -> np.ndarray:
    n = seconds(0.25)
    t = ramp(n)
    body = np.sin(2 * np.pi * 95 * t) * np.exp(-t / 0.05)
    latch = filt(noise(n), "band", [900, 3500]) * np.exp(-t / 0.008) * 0.6
    return (body + latch) * level


def ping_pong(x: np.ndarray, delay: float, feedback: float, taps: int = 6) -> np.ndarray:
    """A stereo delay bouncing between the sides, each echo darker than the last."""
    y = np.zeros_like(x)
    d = seconds(delay)
    echo = x.mean(axis=0)
    for k in range(1, taps + 1):
        echo = filt(echo, "low", 5200 / k ** 0.3)
        side = 0 if k % 2 else 1
        shifted = np.zeros(x.shape[1])
        if k * d < x.shape[1]:
            shifted[k * d :] = echo[: x.shape[1] - k * d]
        y[side] += shifted * feedback ** k
    return y


def reverb(x: np.ndarray, length: float = 2.6, decay: float = 0.5) -> np.ndarray:
    """A hall: decorrelated noise falling away in each ear, darker as it goes, after a short pre-delay."""
    n = seconds(length)
    t = ramp(n)
    pre = seconds(0.022)
    irs = []
    for _ in range(2):
        ir = noise(n) * np.exp(-t / decay)
        ir = swept(ir, "low", 9000 * np.exp(-t / 0.9) + 1200)
        ir[:pre] = 0
        irs.append(ir / np.sqrt(np.sum(ir**2)))
    return np.stack([signal.fftconvolve(x[0], irs[0])[:N], signal.fftconvolve(x[1], irs[1])[:N]])


# ---- The arrangement ------------------------------------------------------------------------------------------------

drums = bus()
bass = bus()
pad = bus()
arp = bus()
lead = bus()
fx = bus()
ui = bus()
kicks: list[float] = []


def at(beat: float) -> float:
    return beat * BEAT


def hit(buf, beat, sig, gain=1.0, pan=0.0, humanise=0.004):
    place(buf, at(beat) + rng.uniform(0, humanise), sig, gain, pan)


# Energy by section: how loud, how bright and how busy each layer plays.
PLAN = {
    "intro": dict(pad=0.55, bright=900, kick=None, hats=None, clap=False, bass=None, arp=None),
    "groove": dict(pad=0.42, bright=1700, kick="half", hats="eighths", clap=False, bass="pulse", arp="eighths"),
    "lift": dict(pad=0.46, bright=2300, kick="four", hats="lift", clap=True, bass="pulse", arp="sixteenths"),
    "build": dict(pad=0.5, bright=2300, kick="four", hats="eighths", clap=True, bass="hold", arp="sixteenths"),
    "drop": dict(pad=0.55, bright=4200, kick="four", hats="sixteenths", clap=True, bass="drive", arp="sixteenths"),
    "tablet": dict(pad=0.5, bright=3600, kick="four", hats="sixteenths", clap=True, bass="drive", arp="sixteenths"),
    "desk": dict(pad=0.42, bright=2200, kick="broken", hats="eighths", clap=True, bass="syncopated", arp="staccato"),
    "outro": dict(pad=0.6, bright=2600, kick=None, hats=None, clap=False, bass="hold", arp=None),
}

for bar in range(BARS):
    start = bar * 4
    name = section_at(start + 0.01)
    plan = PLAN[name]
    root, voicing, tones = chord(bar)
    last_bar = bar == BARS - 1

    # Pad: the chord held across the bar, the last one rung out.
    length = BAR * (1.8 if last_bar else 1.0)
    bright = plan["bright"]
    if name == "build":
        bright = 2300 * (5200 / 2300) ** 0.5
    for note in voicing:
        place(pad, at(start), pad_note(hz(note), length, bright), plan["pad"] * 0.22)
    if name == "intro":
        swell = bass_note(hz("B1"), BAR * 0.95, 0.22, 260)
        swell *= np.minimum(1, ramp(swell.shape[0]) / 1.2) ** 2
        place(pad, at(start) + 0.2, swell, 1.0)

    # Kick.
    pattern = {
        "half": [0, 2, 2.5],
        "four": [0, 1, 2, 3],
        "broken": [0, 1.5, 2, 3.25],
    }.get(plan["kick"] or "", [])
    if name == "build":
        # The last beat before the drop is the roll's alone.
        pattern = [0, 1, 2]
    for k in pattern:
        hit(drums, start + k, kick(), 0.9, humanise=0)
        kicks.append(at(start + k))

    # Clap on the back beats; in the build it opens up into a roll.
    if plan["clap"] and name != "build":
        for k in (1, 3):
            hit(drums, start + k, clap(), 0.5, 0.05)
    if name == "build":
        steps = [(b / 2) for b in range(4)] + [2 + b / 4 for b in range(4)] + [3 + b / 8 for b in range(8)]
        for i, s in enumerate(steps):
            hit(drums, start + s, snare(0.2 + 0.5 * i / len(steps)), 0.5, rng.uniform(-0.2, 0.2), 0)

    # Hats.
    hats = plan["hats"]
    if hats == "eighths":
        for k in range(8):
            if k % 2:
                hit(drums, start + k / 2, hat(), 0.28, 0.25)
    elif hats == "lift":
        for k in range(8):
            hit(drums, start + k / 2, hat(open_=k % 2 == 1), 0.2 if k % 2 else 0.12, 0.25)
    elif hats == "sixteenths":
        for k in range(16):
            accent = 0.24 if k % 4 == 2 else 0.1 + 0.03 * (k % 2)
            hit(drums, start + k / 4, hat(open_=k % 8 == 6), accent, 0.3 if k % 2 else -0.15)

    # Bass.
    freq = hz(root)
    style = plan["bass"]
    if style == "pulse":
        for k in range(8):
            octave = 2 if k in (3, 7) else 1
            place(bass, at(start + k / 2), bass_note(freq * octave, BEAT * 0.42, 0.55), 1.0)
    elif style == "drive":
        for k in range(8):
            octave = 2 if k % 2 else 1
            place(bass, at(start + k / 2), bass_note(freq * octave, BEAT * 0.4, 0.6, 520), 1.0)
    elif style == "syncopated":
        for k, length_ in ((0, 0.6), (0.75, 0.4), (1.5, 0.35), (2, 0.6), (2.75, 0.4), (3.5, 0.4)):
            place(bass, at(start + k), bass_note(freq, BEAT * length_, 0.6, 480), 1.0)
    elif style == "hold":
        tail = 1.6 if last_bar else 0.97
        place(bass, at(start), bass_note(freq, BAR * tail, 0.5, 300), 1.0)

    # Arpeggio.
    style = plan["arp"]
    if style == "eighths":
        for k, i in enumerate([0, 2, 4, 2, 1, 3, 5, 3]):
            hit(arp, start + k / 2, pluck(hz(tones[i]), 0.2), 1.0, -0.35 if k % 2 else 0.35)
    elif style == "sixteenths":
        order = [0, 2, 4, 5, 4, 2, 3, 1, 0, 2, 4, 5, 3, 4, 2, 1]
        for k, i in enumerate(order):
            level = 0.17 if k % 4 == 0 else 0.12
            hit(arp, start + k / 4, pluck(hz(tones[i]), level, 0.13), 1.0, -0.4 if k % 2 else 0.4)
    elif style == "staccato":
        for k, i in enumerate([0, 4, 2, 5, 1, 4, 3, 5]):
            hit(arp, start + k / 2 + (0.25 if k % 4 == 3 else 0), pluck(hz(tones[i]), 0.18, 0.08), 1.0, -0.5 if k % 2 else 0.5)

# The title's bell: two notes as the name comes up, and the motif's opening over the drop and the tablet.
MOTIF = [(0, "F#5", 1.5), (1.5, "A5", 0.5), (2, "F#5", 1), (3, "E5", 1), (4, "E5", 1.5), (5.5, "C#5", 0.5), (6, "E5", 0.5), (6.5, "A4", 1.5)]
place(lead, 0.23, bell(hz("F#5"), 1.2, 0.2), 1.0, 0.1)
place(lead, 0.23 + BEAT * 1.5, bell(hz("B5"), 1.6, 0.16), 1.0, -0.1)
for base in (SECTION["drop"], SECTION["tablet"] + 12):
    for offset, note, length in MOTIF:
        hit(lead, base + offset, bell(hz(note), length * BEAT, 0.2), 1.0, 0.15)
# The end card's cadence, G to D, closing on the tonic's third.
OUTRO = [(0, "D5", 1.5), (1.5, "B4", 0.5), (2, "D5", 1), (3, "E5", 1), (4, "F#5", 3.5)]
for offset, note, length in OUTRO:
    hit(lead, SECTION["outro"] + offset, bell(hz(note), length * BEAT, 0.22), 1.0, 0.0)

# Swells and hits.
for i, frame in enumerate(cues["whooshes"]):
    start = frame / FPS
    if i == 0:
        place(fx, start - 0.2, whoosh(0.9, 0.4, 250, 2600, 0.5, 0.0))
    elif i == 1:
        place(fx, start - 0.1, whoosh(1.2, 0.45, 180, 2200, 0.45, 0.5))
    elif i == 2:
        place(fx, start, whoosh(1.1, 0.5, 200, 1800, 0.35, 0.3))
    else:
        # The whip: its swiftest frame on the downbeat, the air rushing right to left with the pan.
        place(fx, start, whoosh(0.95, 0.35, 260, 3400, 0.6, -0.8))
build = at(SECTION["build"])
place(fx, build, riser(at(SECTION["drop"]) - build, 0.3))
place(fx, at(SECTION["drop"]), impact(0.55))
place(fx, at(SECTION["desk"]), impact(0.3, 0.9))
place(fx, at(SECTION["outro"]), impact(0.35, 2.2))

# The app's own sounds.
for frame in cues["taps"]:
    place(ui, frame / FPS, tap_click(0.16), 1.0, 0.1)
for k in cues["keys"]:
    pitch = {"Ctrl": 0.92, "Shift": 0.95, "Enter": 0.85, "Tab": 1.0}.get(k["key"], 1.08)
    place(ui, k["frame"] / FPS, key_click(k["down"], 0.3, pitch), 1.0, 0.0)
place(ui, cues["hinge"] / FPS, hinge_thunk(0.4))

# ---- The mix --------------------------------------------------------------------------------------------------------

# The kick pulls the pad and the bass down as it hits, and lets them back up over its tail.
duck = np.ones(N)
t = np.arange(N) / SR
for k in kicks:
    i = int(k * SR)
    j = min(N, i + seconds(0.6))
    since = t[i:j] - k
    duck[i:j] = np.minimum(duck[i:j], 1 - 0.55 * np.minimum(1, since / 0.004) * np.exp(-since / 0.11))
pad *= duck
bass *= 0.5 + 0.5 * duck

arp += ping_pong(arp, BEAT * 0.75, 0.32)
send = pad * 0.35 + arp * 0.5 + lead * 0.6 + fx * 0.25 + drums * 0.06
wet = reverb(send)

mix = drums * 0.75 + bass * 0.5 + pad * 1.25 + arp * 1.5 + lead * 1.3 + fx * 0.9 + ui * 1.1 + wet * 0.6
mix = filt(mix, "high", 32)
# A shelf under 80 Hz: the kick's and the bass's lows, which fill a big room, not a phone.
mix -= 0.3 * filt(mix, "low", 80)

# The end rings out and the last half second falls silent with the picture.
fade = np.clip((cues["frames"] / FPS - t) / 1.4, 0, 1) ** 1.5
mix *= fade * np.clip(t / 0.01, 0, 1)


def loudness(x: np.ndarray) -> float:
    """Integrated loudness in LUFS, as ITU-R BS.1770 measures it: K-weighted, in gated 400 ms blocks."""
    k = signal.lfilter([1.53512485958697, -2.69169618940638, 1.19839281085285], [1, -1.69065929318241, 0.73248077421585], x)
    k = signal.lfilter([1.0, -2.0, 1.0], [1, -1.99004745483398, 0.99007225036621], k)
    size, hop = seconds(0.4), seconds(0.1)
    blocks = np.array([np.sum(np.mean(k[:, i : i + size] ** 2, axis=1)) for i in range(0, k.shape[1] - size, hop)])
    lufs = -0.691 + 10 * np.log10(np.maximum(blocks, 1e-12))
    gated = blocks[lufs > -70]
    relative = -0.691 + 10 * np.log10(np.mean(gated)) - 10
    return float(-0.691 + 10 * np.log10(np.mean(blocks[(lufs > -70) & (lufs > relative)])))


def limit(x: np.ndarray, ceiling: float, look: float = 0.004) -> np.ndarray:
    """A look-ahead limiter: the gain each peak needs, held across the look-ahead and eased into, never above 1."""
    from scipy.ndimage import minimum_filter1d, uniform_filter1d

    need = np.minimum(1.0, ceiling / np.maximum(np.max(np.abs(x), axis=0), 1e-9))
    w = seconds(look)
    gain = uniform_filter1d(minimum_filter1d(need, 2 * w + 1), w + 1)
    return x * gain


# Level: -14 LUFS, as streaming players play it back, its peaks held under -1.5 dBFS.
mix *= 10 ** ((-14 - loudness(mix)) / 20)
mix = limit(mix, 10 ** (-1.5 / 20))

dither = (rng.random(mix.shape) - rng.random(mix.shape)) / 32768
pcm = np.clip(np.round((mix + dither) * 32767), -32768, 32767).astype(np.int16)
out.parent.mkdir(parents=True, exist_ok=True)
wavfile.write(out, SR, pcm.T.copy())
print(f"score: {N / SR:.2f} s, {BARS} bars, {loudness(mix):.1f} LUFS, peak {20 * np.log10(np.max(np.abs(mix))):.1f} dBFS")
