#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Builds assets/dicts/emoji_sr_ZZ.dict (Serbian Latin) by transliterating the Cyrillic emoji_sr.dict."""
import os
import struct
import sys

CYR = "абвгдђежзијклљмнњопрстћуфхцчџш"
LAT = ["a", "b", "v", "g", "d", "đ", "e", "ž", "z", "i", "j", "k", "l", "lj", "m", "n", "nj",
       "o", "p", "r", "s", "t", "ć", "u", "f", "h", "c", "č", "dž", "š"]
TRANSLIT = {c: l for c, l in zip(CYR, LAT)}
TRANSLIT.update({c.upper(): l.capitalize() for c, l in zip(CYR, LAT)})

# PtNode flags of the binary dictionary format (version 202)
CHILDREN_3_BYTES, MULTIPLE_CHARS, TERMINAL, SHORTCUTS, BIGRAMS, NOT_A_WORD = 0xC0, 0x20, 0x10, 0x08, 0x04, 0x02


def read_dict(data):
    """Returns header bytes and {word: (frequency, not_a_word, [(shortcut_flags, target)])}."""
    if data[:4] != b"\x9b\xc1\x3a\xfe" or struct.unpack(">H", data[4:6])[0] != 202:
        sys.exit("unexpected dictionary format")
    header_size = struct.unpack(">I", data[8:12])[0]
    words = {}

    def read_char(p):
        b = data[p]
        if b == 0x1F:
            return None, p + 1
        if b < 0x20:
            return chr((b << 16) | (data[p + 1] << 8) | data[p + 2]), p + 3
        return chr(b), p + 1

    def read_string(p):
        s = []
        while True:
            c, p = read_char(p)
            if c is None:
                return "".join(s), p
            s.append(c)

    def walk(p, prefix):
        count = data[p]; p += 1
        if count & 0x80:
            count = ((count & 0x7F) << 8) | data[p]; p += 1
        for _ in range(count):
            flags = data[p]; p += 1
            if flags & MULTIPLE_CHARS:
                chars, p = read_string(p)
            else:
                chars, p = read_char(p)
            word = prefix + chars
            freq = None
            if flags & TERMINAL:
                freq = data[p]; p += 1
            child = None
            if flags & CHILDREN_3_BYTES:
                size = flags >> 6
                child = p + int.from_bytes(data[p:p + size], "big")
                p += size
            shortcuts = []
            if flags & SHORTCUTS:
                end = p + struct.unpack(">H", data[p:p + 2])[0]
                q = p + 2
                while q < end:
                    shortcut_flags = data[q]
                    target, q = read_string(q + 1)
                    shortcuts.append((shortcut_flags & 0x7F, target))
                p = end
            if flags & BIGRAMS:
                sys.exit("bigrams are not supported")
            if freq is not None:
                words[word] = (freq, bool(flags & NOT_A_WORD), shortcuts)
            if child is not None:
                walk(child, word)

    walk(header_size, "")
    return data[:header_size], words


def transliterate(words):
    result = {}
    for word, (freq, not_a_word, shortcuts) in words.items():
        latin = "".join(TRANSLIT.get(c, c) for c in word)
        if latin in result:  # merge, e.g. a Cyrillic keyword whose Latin form already exists
            old_freq, old_not_a_word, old_shortcuts = result[latin]
            targets = {t for _, t in old_shortcuts}
            shortcuts = old_shortcuts + [s for s in shortcuts if s[1] not in targets]
            result[latin] = (max(freq, old_freq), old_not_a_word and not_a_word, shortcuts)
        else:
            result[latin] = (freq, not_a_word, list(shortcuts))
    return result


def encode_char(c):
    cp = ord(c)
    return bytes([cp]) if 0x20 <= cp <= 0xFF else bytes([cp >> 16 & 0xFF, cp >> 8 & 0xFF, cp & 0xFF])


def encode_string(s):
    return b"".join(encode_char(c) for c in s) + b"\x1f"


def write_dict(header, words):
    trie = {}  # char -> [children, entry]
    for word, entry in words.items():
        node = None
        children = trie
        for c in word:
            node = children.setdefault(c, [{}, None])
            children = node[0]
        node[1] = entry

    def compress(children):
        """Merges chains of single non-terminal children into multi-char nodes."""
        nodes = []
        for c, (sub, entry) in sorted(children.items()):
            chars = c
            while entry is None and len(sub) == 1:
                (c2, (sub2, entry2)), = sub.items()
                chars += c2
                sub, entry = sub2, entry2
            nodes.append([chars, entry, compress(sub) if sub else None])
        return nodes

    def node_size(chars, entry, has_children):
        size = 1 + (len(encode_string(chars)) if len(chars) > 1 else len(encode_char(chars)))
        if entry:
            size += 1
            if entry[2]:
                size += 2 + sum(1 + len(encode_string(t)) for _, t in entry[2])
        return size + (3 if has_children else 0)

    # lay out node arrays depth-first; all child addresses use 3 bytes so sizes are known up front
    arrays = []

    def collect(nodes):
        arrays.append(nodes)
        for n in nodes:
            if n[2]:
                collect(n[2])
    collect(compress(trie))
    positions = {}
    pos = len(header)
    for nodes in arrays:
        positions[id(nodes)] = pos
        pos += (1 if len(nodes) < 0x80 else 2) + sum(node_size(c, e, ch is not None) for c, e, ch in nodes)

    out = bytearray(header)
    for nodes in arrays:
        out += bytes([len(nodes)]) if len(nodes) < 0x80 else struct.pack(">H", 0x8000 | len(nodes))
        for chars, entry, children in nodes:
            flags = (MULTIPLE_CHARS if len(chars) > 1 else 0) | (CHILDREN_3_BYTES if children else 0)
            if entry:
                flags |= TERMINAL | (NOT_A_WORD if entry[1] else 0) | (SHORTCUTS if entry[2] else 0)
            out.append(flags)
            out += encode_string(chars) if len(chars) > 1 else encode_char(chars)
            if entry:
                out.append(entry[0])
            if children:
                out += (positions[id(children)] - len(out)).to_bytes(3, "big")
            if entry and entry[2]:
                body = bytearray()
                for i, (shortcut_flags, target) in enumerate(entry[2]):
                    body.append(shortcut_flags | (0x80 if i < len(entry[2]) - 1 else 0))
                    body += encode_string(target)
                out += struct.pack(">H", len(body) + 2) + body
    return bytes(out)


if __name__ == "__main__":
    dicts = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "dicts")
    with open(os.path.join(dicts, "emoji_sr.dict"), "rb") as f:
        header, words = read_dict(f.read())
    latin = transliterate(words)
    data = write_dict(header, latin)
    _, check = read_dict(data)
    if check != latin:
        sys.exit("round trip failed")
    with open(os.path.join(dicts, "emoji_sr_ZZ.dict"), "wb") as f:
        f.write(data)
    print(f"{len(words)} words -> {len(latin)} Latin words, {len(data)} bytes")
