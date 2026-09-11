#!/usr/bin/env python3
"""Starter icons for prefix cosmetics (pref1..prefN). Placeholder art, no imaging
libraries — the user can replace PNGs/models later; build_resourcepack.py keeps files."""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'resourcepack/assets/f8resurs'
SIZE = 32
CENTER = (SIZE - 1) / 2

PREFIXES = [
    ('pref1', '8FE3F5'),
    ('pref2', 'B9C3CE'),
    ('pref3', 'C0A6FF'),
    ('pref4', '6FB1FF'),
    ('pref5', 'E6B94B'),
    ('pref6', 'A9E2F2'),
    ('pref7', '7BE0A0'),
    ('pref8', 'C9CDD4'),
    ('pref9', '7FF0D2'),
    ('pref10', 'FFB347'),
]


def png(pixels):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    raw = b''.join(b'\0' + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', SIZE, SIZE, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def dark(hex_color, factor=0.55):
    rgb = tuple(bytes.fromhex(hex_color))
    return tuple(max(0, round(channel * factor)) for channel in rgb)


def shape(index, dx, dy):
    """Simple distinct geometric emblem per prefix index."""
    radius = (dx * dx + dy * dy) ** 0.5
    ring = lambda inner, outer: inner <= radius <= outer
    if index == 0:
        return ring(7.5, 11.5) or ring(13.5, 14.5)
    if index == 1:
        return abs(dx) + abs(dy) <= 12.5
    if index == 2:
        return abs(dx) <= 3.5 and abs(dy) <= 13 or abs(dy) <= 3.5 and abs(dx) <= 13
    if index == 3:
        return abs(dx - dy) <= 4 and abs(dx + dy) <= 16 or abs(dx + dy) <= 4 and abs(dx - dy) <= 16
    if index == 4:
        return ring(10.5, 13.5)
    if index == 5:
        return abs(dx) + abs(dy) <= 13.5
    if index == 6:
        return radius <= 13.5
    if index == 7:
        return ring(5.5, 9.5)
    if index == 8:
        return dy >= 0 and radius <= 13.5
    return ring(8.5, 12.5)


def main():
    for file_id, color in PREFIXES:
        base = tuple(bytes.fromhex(color)) + (255,)
        edge = dark(color) + (255,)
        pixels = [[(0, 0, 0, 0)] * SIZE for _ in range(SIZE)]
        def inside(xx, yy):
            return shape(PREFIXES.index((file_id, color)), xx - CENTER, yy - CENTER)

        for y in range(SIZE):
            for x in range(SIZE):
                if not inside(x, y):
                    continue
                # Thin darker contour on the rim of the emblem.
                rim = not inside(x + 1, y) or not inside(x - 1, y) or \
                      not inside(x, y + 1) or not inside(x, y - 1)
                pixels[y][x] = edge if rim else base
        (ROOT / 'textures/item').mkdir(parents=True, exist_ok=True)
        (ROOT / 'textures/item' / f'{file_id}.png').write_bytes(png(pixels))
        (ROOT / 'items').mkdir(parents=True, exist_ok=True)
        (ROOT / 'items' / f'{file_id}.json').write_text(json.dumps({
            'model': {'type': 'minecraft:model', 'model': f'f8resurs:item/{file_id}'}
        }, indent=2) + '\n')
        (ROOT / 'models/item').mkdir(parents=True, exist_ok=True)
        (ROOT / 'models/item' / f'{file_id}.json').write_text(json.dumps({
            'parent': 'minecraft:item/generated',
            'textures': {'layer0': f'f8resurs:item/{file_id}'}
        }, indent=2) + '\n')
    print(f'Prefix icons: {len(PREFIXES)} PNG/JSON written')


if __name__ == '__main__':
    main()
