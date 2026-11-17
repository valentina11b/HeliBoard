#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Synthesizes the Mechanical style sound packs into app/src/main/res/raw. Usage: python3 tools/generate_mechanical_sounds.py"""
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
             body_decay=0.010, bottom_out_ms=7.0, rattle_ms=None, click_lp=9000, second_click_ms=None,
             metal_hz=(), metal_decay=0.03, bell_hz=None, volume=0.7):
    rng = random.Random(seed)
    n = int(RATE * length)
    out = [0.0] * n

    def add(signal, start, gain):
        for i, v in enumerate(signal):
            if start + i < n:
                out[start + i] += v * gain

    # switch click at actuation, then the keycap bottoming out a few ms later
    add(burst(n, rng, 0.0012, 1500, click_lp), 0, click)
    if second_click_ms is not None:  # click jacket of a clicky switch snapping back
        add(burst(n, rng, 0.0008, 2500, 12000), int(RATE * second_click_ms / 1000), click * 0.8)
    add(burst(n, rng, 0.0020, 600, 5000), int(RATE * bottom_out_ms / 1000), click * 0.7)
    # low "thock" of the bottom-out and the plastic body resonance
    start = int(RATE * bottom_out_ms / 1000)
    add([math.sin(2 * math.pi * thump_hz * t / RATE) * math.exp(-(t / RATE) / 0.014) for t in range(n)], start, thump)
    for k, hz in enumerate(body_hz):
        add([math.sin(2 * math.pi * hz * t / RATE) * math.exp(-(t / RATE) / body_decay) for t in range(n)], start, body / (k + 1))
    if rattle_ms is not None:  # stabilizer wire on long keys
        add(burst(n, rng, 0.0030, 900, 6000), int(RATE * rattle_ms / 1000), click * 0.35)
    for k, hz in enumerate(metal_hz):  # typebar hitting the platen
        add([math.sin(2 * math.pi * hz * t / RATE) * math.exp(-(t / RATE) / metal_decay) for t in range(n)], 0, 0.3 / (k + 1))
    if bell_hz is not None:  # carriage return bell
        add([(math.sin(2 * math.pi * bell_hz * t / RATE) + 0.4 * math.sin(2 * math.pi * bell_hz * 2.76 * t / RATE))
             * math.exp(-(t / RATE) / 0.35) for t in range(n)], int(RATE * 0.03), 0.6)

    fade = int(RATE * 0.01)
    for i in range(fade):
        out[n - 1 - i] *= i / fade
    peak = max(abs(v) for v in out) or 1.0
    return [v / peak * volume for v in out]


def write(name, samples):
    path = os.path.join(OUT_DIR, name + ".wav")
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in samples))
    print("wrote", os.path.relpath(path))


# per pack: settings for letter keys, and overrides for delete, enter and space
PACKS = {
    "classic": dict(key=dict(thump_hz=220, body_hz=[1900, 3100]),
                    delete=dict(thump_hz=200, body_hz=[1650, 2700], click=1.1),
                    enter=dict(thump_hz=160, body_hz=[1400, 2500], length=0.12, thump=0.8, body_decay=0.014, rattle_ms=16),
                    space=dict(thump_hz=140, body_hz=[1200, 2300], length=0.13, thump=0.9, body_decay=0.016,
                               bottom_out_ms=9.0, rattle_ms=20)),
    "thock": dict(key=dict(thump_hz=120, body_hz=[900, 1500], click=0.4, click_lp=4000, thump=1.0, body=0.35, body_decay=0.016),
                  delete=dict(thump_hz=110, body_hz=[850, 1400], click=0.45, click_lp=4000, thump=1.0, body=0.35),
                  enter=dict(thump_hz=95, body_hz=[700, 1200], length=0.13, click=0.4, click_lp=3500, thump=1.0,
                             body_decay=0.02, rattle_ms=16),
                  space=dict(thump_hz=85, body_hz=[650, 1100], length=0.14, click=0.4, click_lp=3500, thump=1.0,
                             body_decay=0.022, bottom_out_ms=9.0, rattle_ms=20)),
    "clicky": dict(key=dict(thump_hz=260, body_hz=[2600, 4200], click=1.3, click_lp=14000, second_click_ms=3.0, thump=0.3, body=0.15),
                   delete=dict(thump_hz=240, body_hz=[2400, 4000], click=1.3, click_lp=14000, second_click_ms=3.0, thump=0.3),
                   enter=dict(thump_hz=180, body_hz=[1800, 3000], length=0.12, click=1.3, click_lp=14000,
                              second_click_ms=3.5, thump=0.5, rattle_ms=16),
                   space=dict(thump_hz=160, body_hz=[1500, 2700], length=0.13, click=1.2, click_lp=12000,
                              second_click_ms=4.0, thump=0.6, bottom_out_ms=9.0, rattle_ms=20)),
    "typewriter": dict(key=dict(thump_hz=180, body_hz=[1200], length=0.12, click=1.2, thump=0.5,
                                metal_hz=[3150, 4700], metal_decay=0.025),
                       delete=dict(thump_hz=170, body_hz=[1100], length=0.12, click=1.2, thump=0.5,
                                   metal_hz=[2900, 4400], metal_decay=0.025),
                       enter=dict(thump_hz=150, body_hz=[1000], length=0.6, click=1.0, thump=0.6, bell_hz=2350),
                       space=dict(thump_hz=130, body_hz=[900], length=0.14, click=0.9, thump=0.8, bottom_out_ms=9.0,
                                  metal_hz=[2600], metal_decay=0.02)),
    "soft": dict(key=dict(thump_hz=200, body_hz=[1500], click=0.35, click_lp=3000, thump=0.5, body=0.15, volume=0.35),
                 delete=dict(thump_hz=190, body_hz=[1400], click=0.35, click_lp=3000, thump=0.5, volume=0.35),
                 enter=dict(thump_hz=150, body_hz=[1100], length=0.11, click=0.35, click_lp=3000, thump=0.6, volume=0.4),
                 space=dict(thump_hz=130, body_hz=[1000], length=0.12, click=0.3, click_lp=2800, thump=0.7, volume=0.4)),
}


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    for pack, sounds in PACKS.items():
        for variant, offset in enumerate((0, 15, -10), start=1):  # three letter variants with slightly different pitch
            key = dict(sounds["key"])
            key["thump_hz"] += offset
            key["body_hz"] = [hz * (1 + offset / 400) for hz in key["body_hz"]]
            write(f"mech_{pack}_key_{variant}", keypress(variant, **key))
        for seed, name in enumerate(("delete", "enter", "space"), start=4):
            write(f"mech_{pack}_{name}", keypress(seed, **sounds[name]))
