# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 R. Kravcov

"""Renders the site pictograms straight out of `Glyphs.kt`, at the size they are actually drawn.

Parses the Kotlin rather than re-describing the shapes in Python, because a second copy of the
geometry would drift from the first and then the preview would be reassuring about a glyph that no
longer exists. The dial draws these into a box of `0.096 r`, which is about 22 device pixels on a
454 px watch, and that is the size worth judging them at -- most of what looks crisp at 100 px is
gone by then.

Usage:  python tools/poi/preview_glyphs.py [--out FILE]
"""

import argparse
import os
import re

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
GLYPHS_KT = os.path.normpath(os.path.join(
    HERE, "..", "..", "app", "src", "main", "kotlin", "com", "avdesign", "mfd24",
    "render", "Glyphs.kt"))

NUM = r"(-?[\d.]+)f"
SUPERSAMPLE = 16


def bezier(p0, p1, p2, p3, steps=24):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        out.append((
            u*u*u*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t*t*t*p3[0],
            u*u*u*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t*t*t*p3[1],
        ))
    return out


def parse(source, name):
    """Returns a list of sub-shapes: ('poly', points) or ('ellipse', bbox)."""
    match = re.search(
        r"private fun %s\(\): Path = Path\(\)\.apply \{(.*?)\n    \}" % name, source, re.S)
    if not match:
        raise SystemExit("no such glyph function: %s" % name)
    body = match.group(1)

    shapes, current, cursor = [], [], (0.0, 0.0)
    for line in body.splitlines():
        line = line.strip()
        m = re.match(r"moveTo\(%s, %s\)" % (NUM, NUM), line)
        if m:
            if len(current) > 2:
                shapes.append(("poly", current))
            cursor = (float(m.group(1)), float(m.group(2)))
            current = [cursor]
            continue
        m = re.match(r"lineTo\(%s, %s\)" % (NUM, NUM), line)
        if m:
            cursor = (float(m.group(1)), float(m.group(2)))
            current.append(cursor)
            continue
        m = re.match(r"cubicTo\(%s, %s, %s, %s, %s, %s\)" % ((NUM,) * 6), line)
        if m:
            v = [float(g) for g in m.groups()]
            pts = bezier(cursor, (v[0], v[1]), (v[2], v[3]), (v[4], v[5]))
            current.extend(pts)
            cursor = pts[-1]
            continue
        m = re.match(r"quadTo\(%s, %s, %s, %s\)" % ((NUM,) * 4), line)
        if m:
            v = [float(g) for g in m.groups()]
            c1 = (cursor[0] + 2.0/3.0*(v[0]-cursor[0]), cursor[1] + 2.0/3.0*(v[1]-cursor[1]))
            c2 = (v[2] + 2.0/3.0*(v[0]-v[2]), v[3] + 2.0/3.0*(v[1]-v[3]))
            pts = bezier(cursor, c1, c2, (v[2], v[3]))
            current.extend(pts)
            cursor = pts[-1]
            continue
        m = re.match(r"addOval\(%s, %s, %s, %s," % ((NUM,) * 4), line)
        if m:
            shapes.append(("ellipse", tuple(float(g) for g in m.groups())))
            continue
        m = re.match(r"addCircle\(%s, %s, %s," % ((NUM,) * 3), line)
        if m:
            x, y, r = (float(g) for g in m.groups())
            shapes.append(("ellipse", (x - r, y - r, x + r, y + r)))
            continue
        if line.startswith("close()") and len(current) > 2:
            shapes.append(("poly", current))
            current = []
    if len(current) > 2:
        shapes.append(("poly", current))
    return shapes


def render(shapes, px):
    img = Image.new("L", (100 * SUPERSAMPLE, 100 * SUPERSAMPLE), 0)
    draw = ImageDraw.Draw(img)
    for kind, value in shapes:
        if kind == "poly":
            draw.polygon([(x * SUPERSAMPLE, y * SUPERSAMPLE) for x, y in value], fill=255)
        else:
            draw.ellipse([c * SUPERSAMPLE for c in value], fill=255)
    return img.resize((px, px), Image.LANCZOS)


def font(size):
    for candidate in (r"C:\Windows\Fonts\segoeui.ttf", r"C:\Windows\Fonts\arial.ttf"):
        if os.path.exists(candidate):
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=os.path.join(HERE, "glyph_preview.png"))
    parser.add_argument("--only", default="", help="comma-separated glyph function names")
    args = parser.parse_args()

    with open(GLYPHS_KT, encoding="utf-8") as handle:
        source = handle.read()

    entries = [
        ("merchantShip", "PORT\ncivil"),
        ("warship", "PORT\nmilitary"),
        ("helicopter", "HELIPAD\nany owner"),
        ("aircraft", "AIRFIELD\ncivil"),
        ("fighter", "AIRFIELD\nmilitary"),
        ("rocket", "SPACEPORT\nany owner"),
        # Not site pictograms: the two that label the optional sensor slots beside the hub.
        # They are drawn a shade smaller than the site set, at about 17 px rather than 22.
        ("heart", "SLOT\npulse"),
        ("pedestrian", "SLOT\nsteps"),
    ]
    if args.only:
        wanted = {n.strip() for n in args.only.split(",")}
        entries = [e for e in entries if e[0] in wanted]

    cell, pad = 150, 14
    sheet = Image.new("L", (len(entries) * cell, 300), 0)
    draw = ImageDraw.Draw(sheet)
    label_font, small_font = font(17), font(14)

    for i, (name, caption) in enumerate(entries):
        shapes = parse(source, name)
        x = i * cell
        sheet.paste(render(shapes, 110), (x + pad + 8, 8))
        # True size, and the same raster blown up so the pixels themselves can be read.
        sheet.paste(render(shapes, 22), (x + pad + 10, 132))
        sheet.paste(render(shapes, 22).resize((80, 80), Image.NEAREST), (x + pad + 42, 132))
        draw.text((x + pad + 8, 222), caption, fill=190, font=label_font)
        draw.text((x + pad + 8, 272), name, fill=110, font=small_font)

    Image.merge("RGB", (sheet, sheet, sheet)).save(args.out)
    print("wrote", args.out)


if __name__ == "__main__":
    main()
