#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Synthesizes the Mechanical style key sounds into app/src/main/res/raw. Usage: python3 tools/generate_mechanical_sounds.py"""
import math
import os
import random
import struct
import wave

RATE = 44100
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")


def high_pass(samples, cutoff):
    rc = 1.0 / (2 * math.pi * cutoff)
    a = rc / (rc + 1.0 / RATE)
    out, prev_x, prev_y = [], 0.0, 0.0
    for x in samples:
        prev_y = a * (prev_y + x - prev_x)
        prev_x = x
        out.append(prev_y)
    return out


def low_pass(samples, cutoff):
    dt = 1.0 / RATE
    a = dt / (1.0 / (2 * math.pi * cutoff) + dt)
    out, y = [], 0.0
    for x in samples:
        y += a * (x - y)
        out.append(y)
    return out


def burst(n, rng, decay, hp, lp):
    """Filtered noise with exponential decay: the 'tick' of plastic hitting plastic."""
    noise = [rng.uniform(-1, 1) * math.exp(-(i / RATE) / decay) for i in range(n)]
    return low_pass(high_pass(noise, hp), lp)


def keypress(seed, thump_hz, body_hz, length=0.09, click=1.0, thump=0.6, body=0.25,
             body_decay=0.010, bottom_out_ms=7.0, rattle_ms=None):
    rng = random.Random(seed)
    n = int(RATE * length)
    out = [0.0] * n

    def add(signal, start, gain):
        for i, v in enumerate(signal):
            if start + i < n:
                out[start + i] += v * gain

    # switch click at actuation, then the keycap bottoming out a few ms later
    add(burst(n, rng, 0.0012, 1500, 9000), 0, click)
    add(burst(n, rng, 0.0020, 600, 5000), int(RATE * bottom_out_ms / 1000), click * 0.7)
    # low "thock" of the bottom-out and the plastic body resonance
    start = int(RATE * bottom_out_ms / 1000)
    add([math.sin(2 * math.pi * thump_hz * t / RATE) * math.exp(-(t / RATE) / 0.014) for t in range(n)], start, thump)
    for k, hz in enumerate(body_hz):
        add([math.sin(2 * math.pi * hz * t / RATE) * math.exp(-(t / RATE) / body_decay) for t in range(n)], start, body / (k + 1))
    if rattle_ms is not None:  # stabilizer wire on long keys
        add(burst(n, rng, 0.0030, 900, 6000), int(RATE * rattle_ms / 1000), click * 0.35)

    fade = int(RATE * 0.01)
    for i in range(fade):
        out[n - 1 - i] *= i / fade
    peak = max(abs(v) for v in out) or 1.0
    return [v / peak * 0.7 for v in out]


def write(name, samples):
    path = os.path.join(OUT_DIR, name + ".wav")
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in samples))
    print("wrote", os.path.relpath(path))


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    write("mechanical_key_1", keypress(1, 220, [1900, 3100]))
    write("mechanical_key_2", keypress(2, 235, [2050, 3300]))
    write("mechanical_key_3", keypress(3, 210, [1800, 2950]))
    write("mechanical_delete", keypress(4, 200, [1650, 2700], click=1.1))
    write("mechanical_enter", keypress(5, 160, [1400, 2500], length=0.12, thump=0.8, body_decay=0.014, rattle_ms=16))
    write("mechanical_space", keypress(6, 140, [1200, 2300], length=0.13, thump=0.9, body_decay=0.016,
                                       bottom_out_ms=9.0, rattle_ms=20))
