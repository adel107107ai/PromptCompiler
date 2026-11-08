#!/usr/bin/env python3
"""Draw the launcher icon and write it as real PNGs (no image library needed).

Run: python3 tools/gen_icon.py
"""

import math
import pathlib
import struct
import sys
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "android" / "res"

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def clamp(value, low=0.0, high=1.0):
    return low if value < low else (high if value > high else value)


def round_rect_sdf(px, py, hw, hh, radius):
    qx = abs(px) - (hw - radius)
    qy = abs(py) - (hh - radius)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    return outside + min(max(qx, qy), 0.0) - radius


def segment_distance(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    length2 = vx * vx + vy * vy
    t = 0.0 if length2 == 0 else clamp((wx * vx + wy * vy) / length2)
    return math.hypot(wx - t * vx, wy - t * vy)


def blend(dst, src, alpha):
    return tuple(dst[i] + (src[i] - dst[i]) * alpha for i in range(3))


CHEVRONS = [
    ((0.34, 0.30), (0.58, 0.50), (0.34, 0.70), (0.22, 0.61, 0.97)),
    ((0.54, 0.30), (0.78, 0.50), (0.54, 0.70), (0.55, 0.40, 0.95)),
]
STROKE = 0.052


def render(size):
    rows = []
    top = (0.09, 0.15, 0.26)
    bottom = (0.04, 0.07, 0.13)

    for y in range(size):
        row = []
        py = (y + 0.5) / size - 0.5
        for x in range(size):
            px = (x + 0.5) / size - 0.5

            # rounded-square plate with a vertical gradient
            plate = round_rect_sdf(px, py, 0.47, 0.47, 0.21)
            base = tuple(top[i] + (bottom[i] - top[i]) * ((py + 0.5)) for i in range(3))

            alpha = clamp(0.5 - plate * size)
            color = (0.0, 0.0, 0.0)
            color = tuple(base[i] * alpha for i in range(3))

            # 1px rim
            rim = clamp(0.5 - abs(plate + 0.006) * size) if plate > -0.02 else 0.0
            if rim > 0:
                color = blend(color, (0.15, 0.20, 0.30), rim)

            for (a, b, c, tint) in CHEVRONS:
                distance = min(
                    segment_distance(px, py, a[0], a[1], b[0], b[1]),
                    segment_distance(px, py, b[0], b[1], c[0], c[1]),
                ) - STROKE * 0.5
                coverage = clamp(0.5 - distance * size)
                if coverage > 0:
                    color = blend(color, tint, coverage)

            row.append((
                int(round(clamp(color[0]) * 255)),
                int(round(clamp(color[1]) * 255)),
                int(round(clamp(color[2]) * 255)),
                int(round(alpha * 255)),
            ))
        rows.append(row)
    return rows


def write_png(path, width, height, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))

    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def main():
    for folder, size in DENSITIES.items():
        rows = render(size)
        target = RES / folder / "ic_launcher.png"
        write_png(target, size, size, rows)
        print("gen_icon: %s (%dx%d)" % (target, size, size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
