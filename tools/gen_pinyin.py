"""Builds app/src/main/assets/pinyin.bin from pinyin-data (https://github.com/mozillazg/pinyin-data, MIT).

Usage: python3 tools/gen_pinyin.py path/to/pinyin.txt

Layout (big-endian):
  u32 magic 'PYN1', u16 syllable count, then each syllable as u8 length + ASCII
  u16 first code point, u16 code point count, u16 primary syllable index + 1 per code point (0 = none)
  u16 polyphone count, then per entry: u16 code point offset, u8 n, n x u16 extra syllable index
Readings are toneless, ü written as v, at most three per character, most common first.
"""
import struct
import sys
import unicodedata

FIRST, LAST = 0x3400, 0x9FFF
MAX_READINGS = 3


def toneless(s):
    s = s.replace("ü", "v").replace("ǖ", "v").replace("ǘ", "v").replace("ǚ", "v").replace("ǜ", "v")
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")
    return s.lower().replace("ü", "v")


def main(src, out):
    table = {}
    for line in open(src, encoding="utf-8"):
        line = line.split("#")[0].strip()
        if not line.startswith("U+"):
            continue
        cp, readings = line.split(":")
        cp = int(cp[2:], 16)
        if not FIRST <= cp <= LAST:
            continue
        seen = []
        for r in readings.split(","):
            t = toneless(r.strip())
            if t and t.isalpha() and t not in seen:
                seen.append(t)
        if seen:
            table[cp] = seen[:MAX_READINGS]
    syllables = sorted({s for v in table.values() for s in v})
    index = {s: i for i, s in enumerate(syllables)}
    buf = bytearray(struct.pack(">IH", 0x50594E31, len(syllables)))
    for s in syllables:
        buf += struct.pack(">B", len(s)) + s.encode()
    count = LAST - FIRST + 1
    buf += struct.pack(">HH", FIRST, count)
    for cp in range(FIRST, LAST + 1):
        v = table.get(cp)
        buf += struct.pack(">H", index[v[0]] + 1 if v else 0)
    poly = [(cp, v[1:]) for cp, v in sorted(table.items()) if len(v) > 1]
    buf += struct.pack(">H", len(poly))
    for cp, extra in poly:
        buf += struct.pack(">HB", cp - FIRST, len(extra))
        for s in extra:
            buf += struct.pack(">H", index[s])
    open(out, "wb").write(buf)
    print(f"{len(table)} chars, {len(syllables)} syllables, {len(poly)} polyphones, {len(buf)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/pinyin.bin")
