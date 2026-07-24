#!/usr/bin/env python3
"""Generates Noksidian's emoji glyph pack: res/emoji/p*.png + res/emoji/index.bin.

Renders every fully-qualified RGI emoji from the system Noto Color Emoji font
(Apache 2.0) at GLYPH px, packed as PER_PAGE-glyph horizontal strip PNGs
(palette-quantized), plus a binary index mapping raw UTF-16 key sequences to
glyph ids. Minimally-qualified and unqualified variants from emoji-test.txt
are indexed as aliases of their fully-qualified glyph, so the MIDlet does pure
greedy longest-match on raw code units with no normalization pass at runtime.

index.bin layout (big-endian, readable with DataInputStream):
  4 bytes  magic "NKEM"
  u8       version = 1
  u8       glyph px (16)
  u8       glyphs per page (32)
  u8       max key length in UTF-16 code units (for the matcher's lookahead)
  u16      pageCount
  u16      singleCount   # keys that are exactly one code UNIT (BMP, no FE0F)
  u16      seqCount      # all other keys (surrogate pairs count as 2 units)
  u16      glyphCount    # distinct rendered glyphs (= 32*full pages + tail)
  singleCount * { u16 unit, u16 glyphId }        # sorted by unit
  (seqCount+1) * u32                             # offsets into blob, in units
  seqCount * u16                                 # glyphId per key, same order
  blob: u16 units, keys concatenated, sorted lexicographically by unit values

Requires: Pillow with raqm (ZWJ shaping), /usr/share/fonts/noto/NotoColorEmoji.ttf,
tools/emoji-test.txt (committed; refresh from unicode.org/Public/emoji/latest/).
Deterministic for a given font file + emoji-test.txt + Pillow version.
"""
import io
import os
import struct
import sys

from PIL import Image, ImageDraw, ImageFont, features

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_PATH = "/usr/share/fonts/noto/NotoColorEmoji.ttf"
TEST_FILE = os.path.join(ROOT, "tools", "emoji-test.txt")
OUT_DIR = os.path.join(ROOT, "res", "emoji")
NOTO_SIZE = 109  # the only size Pillow renders CBDT bitmap strikes at
GLYPH = 16
PER_PAGE = 32
QUANT = 64  # palette colors per page strip

assert features.check("raqm"), "Pillow lacks raqm; ZWJ sequences would not shape"


def parse_emoji_test(path):
    """Returns (fq, aliases): fq = list of cp-tuples in file order
    (fully-qualified only); aliases = list of (cp-tuple, fq-index)."""
    fq = []
    fq_by_norm = {}
    aliases = []
    pending = []  # non-FQ lines seen before their FQ line resolves via norm
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if ";" not in line:
            continue
        cps_part, rest = line.split(";", 1)
        status = rest.split("#", 1)[0].strip()
        if status not in ("fully-qualified", "minimally-qualified",
                          "unqualified"):
            continue
        cps = tuple(int(c, 16) for c in cps_part.split())
        norm = tuple(c for c in cps if c != 0xFE0F)
        if status == "fully-qualified":
            fq_by_norm[norm] = len(fq)
            fq.append(cps)
        else:
            pending.append((cps, norm))
    for cps, norm in pending:
        idx = fq_by_norm.get(norm)
        if idx is None:
            print("WARN: no fully-qualified parent for", cps, file=sys.stderr)
            continue
        aliases.append((cps, idx))
    return fq, aliases


def to_units(cps):
    """Codepoint tuple -> tuple of UTF-16 code units."""
    units = []
    for cp in cps:
        if cp >= 0x10000:
            cp -= 0x10000
            units.append(0xD800 + (cp >> 10))
            units.append(0xDC00 + (cp & 0x3FF))
        else:
            units.append(cp)
    return tuple(units)


def render(font, cps):
    txt = "".join(chr(c) for c in cps)
    canvas = Image.new("RGBA", (NOTO_SIZE * 8, NOTO_SIZE + 40), (0, 0, 0, 0))
    ImageDraw.Draw(canvas).text((0, 0), txt, font=font, embedded_color=True)
    box = canvas.getbbox()
    if not box:
        return None
    glyph = canvas.crop(box)
    w, h = glyph.size
    side = max(w, h)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(glyph, ((side - w) // 2, (side - h) // 2))
    return sq.resize((GLYPH, GLYPH), Image.LANCZOS)


def main():
    fq, aliases = parse_emoji_test(TEST_FILE)
    font = ImageFont.truetype(FONT_PATH, NOTO_SIZE)
    print("fully-qualified: %d, alias keys: %d" % (len(fq), len(aliases)))

    # Render in file order (thematic page locality: smileys cluster on the
    # same pages, so a typical note touches very few pages).
    glyphs = []            # list of 16x16 RGBA images; index = glyph id
    keymap = {}            # unit-tuple -> glyph id
    fq_glyph = {}          # fq list index -> glyph id (for alias resolution)
    for i, cps in enumerate(fq):
        img = render(font, cps)
        if img is None:
            print("WARN: font renders nothing for", cps, file=sys.stderr)
            continue
        gid = len(glyphs)
        glyphs.append(img)
        fq_glyph[i] = gid
        keymap[to_units(cps)] = gid
    for cps, idx in aliases:
        gid = fq_glyph.get(idx)
        if gid is not None:
            keymap.setdefault(to_units(cps), gid)

    # Split keys: single code UNIT vs everything else.
    singles = sorted((k[0], g) for k, g in keymap.items() if len(k) == 1)
    seqs = sorted((k, g) for k, g in keymap.items() if len(k) > 1)
    max_units = max(len(k) for k in keymap)
    blob = []
    offsets = [0]
    gids = []
    for k, g in seqs:
        blob.extend(k)
        offsets.append(len(blob))
        gids.append(g)
    n_pages = (len(glyphs) + PER_PAGE - 1) // PER_PAGE
    assert len(glyphs) < 0xFFFF and len(blob) < 0x7FFFFFFF

    os.makedirs(OUT_DIR, exist_ok=True)
    for old in os.listdir(OUT_DIR):
        os.remove(os.path.join(OUT_DIR, old))
    total_png = 0
    for p in range(n_pages):
        strip = Image.new("RGBA", (PER_PAGE * GLYPH, GLYPH), (0, 0, 0, 0))
        for s, img in enumerate(glyphs[p * PER_PAGE:(p + 1) * PER_PAGE]):
            strip.paste(img, (s * GLYPH, 0))
        q = strip.quantize(colors=QUANT, method=Image.FASTOCTREE)
        buf = io.BytesIO()
        q.save(buf, "PNG", optimize=True)
        total_png += buf.getbuffer().nbytes
        with open(os.path.join(OUT_DIR, "p%d.png" % p), "wb") as f:
            f.write(buf.getvalue())

    ix = io.BytesIO()
    ix.write(b"NKEM")
    ix.write(struct.pack(">BBBB", 1, GLYPH, PER_PAGE, max_units))
    ix.write(struct.pack(">HHHH", n_pages, len(singles), len(seqs),
                         len(glyphs)))
    for unit, gid in singles:
        ix.write(struct.pack(">HH", unit, gid))
    for off in offsets:
        ix.write(struct.pack(">I", off))
    for gid in gids:
        ix.write(struct.pack(">H", gid))
    for unit in blob:
        ix.write(struct.pack(">H", unit))
    with open(os.path.join(OUT_DIR, "index.bin"), "wb") as f:
        f.write(ix.getvalue())

    print("glyphs: %d  pages: %d  singles: %d  seqs: %d  maxUnits: %d"
          % (len(glyphs), n_pages, len(singles), len(seqs), max_units))
    print("strips: %.1f KB  index.bin: %.1f KB"
          % (total_png / 1024.0, len(ix.getvalue()) / 1024.0))


if __name__ == "__main__":
    main()
