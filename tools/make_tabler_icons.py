#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Converts Tabler icons (MIT, https://tabler.io/icons) to Android vector drawables for the Mechanical icon style.
Usage: python3 tools/make_tabler_icons.py <path to extracted @tabler/icons npm package>"""
import json
import os
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
STROKE_WIDTH = 1.8

# drawable name (tabler_<name>.xml) -> (tabler icon, filled)
ICONS = {
    "shift": ("arrow-big-up", False),
    "shifted": ("arrow-big-up", True),
    "shift_lock": ("arrow-big-up-lines", True),
    "delete": ("backspace", False),
    "enter": ("corner-down-left", False),
    "go": ("arrow-right", False),
    "search": ("search", False),
    "send": ("send", False),
    "done": ("check", False),
    "arrow_right": ("arrow-right", False),
    "arrow_left": ("arrow-left", False),
    "tab": ("arrow-bar-to-right", False),
    "space": ("space", False),
    "voice_off": ("microphone-off", False),
    "language": ("world", False),
    "stop_onehanded": ("arrows-maximize", False),
    "arrows_horizontal": ("arrows-horizontal", False),
    "toolbar": ("chevron-right", False),
    "bin": ("trash", False),
    "voice": ("microphone", False),
    "clipboard": ("clipboard", False),
    "numpad": ("numbers", False),
    "dpad": ("arrows-move", False),
    "undo": ("arrow-back-up", False),
    "redo": ("arrow-forward-up", False),
    "settings": ("settings", False),
    "select_all": ("select-all", False),
    "select_word": ("cursor-text", False),
    "copy": ("copy", False),
    "cut": ("cut", False),
    "paste": ("clipboard-text", False),
    "onehanded": ("hand-finger", False),
    "floating": ("picture-in-picture", False),
    "incognito": ("spy", False),
    "autocorrect": ("text-spellcheck", False),
    "clear_clipboard": ("clipboard-x", False),
    "close": ("x", False),
    "emoji": ("mood-smile", False),
    "chevron_left": ("chevron-left", False),
    "chevron_right": ("chevron-right", False),
    "chevron_up": ("chevron-up", False),
    "chevron_down": ("chevron-down", False),
    "word_left": ("chevrons-left", False),
    "word_right": ("chevrons-right", False),
    "page_up": ("chevrons-up", False),
    "page_down": ("chevrons-down", False),
    "to_start": ("arrow-bar-to-left", False),
    "to_end": ("arrow-bar-to-right", False),
    "page_start": ("arrow-bar-to-up", False),
    "page_end": ("arrow-bar-to-down", False),
    "split": ("layout-columns", False),
    "password": ("key", False),
    "translate": ("language", False),
    # quick settings panel
    "quick_settings": ("layout-grid", False),
    "palette": ("palette", False),
    "style": ("brush", False),
    "resize": ("arrows-diagonal", False),
    "hints": ("superscript", False),
    "sound": ("volume", False),
    "vibration": ("device-mobile-vibration", False),
    "transparency": ("droplet-half-2", False),
}


def vector(paths, filled):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Tabler Icons, Copyright (c) 2020-2026 Paweł Kuna, SPDX-License-Identifier: MIT -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp"', '    android:height="24dp"',
           '    android:viewportWidth="24"', '    android:viewportHeight="24">']
    for d in paths:
        if filled:
            out.append(f'    <path android:fillColor="#FFFFFF" android:pathData="{d}" />')
        else:
            out.append(f'    <path android:strokeColor="#FFFFFF" android:strokeWidth="{STROKE_WIDTH}" '
                       f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    package = sys.argv[1]
    outline = json.load(open(os.path.join(package, "tabler-nodes-outline.json")))
    filled = json.load(open(os.path.join(package, "tabler-nodes-filled.json")))
    for name, (icon, is_filled) in ICONS.items():
        nodes = (filled if is_filled else outline)[icon]
        if any(tag != "path" for tag, _ in nodes):
            sys.exit(f"{icon}: only path elements are supported")
        with open(os.path.join(OUT_DIR, f"tabler_{name}.xml"), "w") as f:
            f.write(vector([attrs["d"] for _, attrs in nodes], is_filled))
    print(f"wrote {len(ICONS)} icons")
