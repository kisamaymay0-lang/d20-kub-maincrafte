#!/usr/bin/env python3
"""Original 32×32 pixel medal art, no imaging libraries. Run manually, not during packaging.
Existing user-edited PNGs are otherwise preserved by build_resourcepack.py.
"""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'resourcepack/assets/f8resurs'
PALETTES = {
    'copper': ('422d26', '704435', 'a86645', 'd18c59', 'f2c38b', '925e43'),
    'silver': ('303947', '536578', '849bad', 'bccad4', 'edf5fa', '72899d'),
    'gold': ('4b351b', '896025', 'c2922d', 'edc453', 'fff0a2', 'a97b2b'),
}


def png(pixels, width, height):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    raw = b''.join(b'\0' + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def draw(palette):
    colors = [tuple(bytes.fromhex(value)) + (255,) for value in palette]
    pixels = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(2, 15):
        offset = (y - 2) // 2
        for start in (6 + offset, 21 - offset):
            for x in range(start, start + 5):
                pixels[y][x] = ((74, 33, 41, 255) if x in (start, start + 4)
                                else (197, 69, 69, 255) if x == start + 1 else (142, 43, 53, 255))
    for y in range(11, 31):
        for x in range(5, 26):
            dx, dy = abs(x - 15), abs(y - 21)
            if dx > 9 or dy > 9 or dx + dy > 14:
                continue
            if dx == 9 or dy == 9 or dx + dy == 14:
                color = colors[0]
            elif dx >= 7 or dy >= 7 or dx + dy >= 12:
                color = colors[4] if x + y < 36 else colors[1]
            elif dx == 6 or dy == 6 or dx + dy == 10:
                color = colors[5]
            else:
                color = colors[3] if x + y < 37 else colors[2]
            pixels[y][x] = color
    star = ['......#......', '.....###.....', '.....###.....', '....#####....',
            '#############', '.###########.', '..#########..', '...#######...',
            '..####.####..', '..###...###..', '..##.....##..']
    for y, row in enumerate(star):
        for x, value in enumerate(row):
            if value == '#':
                pixels[y + 16][x + 9] = colors[1]
    pixels[14][11] = colors[4]
    pixels[15][10] = colors[4]
    return pixels


def main():
    for metal, palette in PALETTES.items():
        name = f'medal_{metal}'
        for folder in ('items', 'models/item', 'textures/item'):
            (ROOT / folder).mkdir(parents=True, exist_ok=True)
        (ROOT / 'items' / f'{name}.json').write_text(json.dumps({'model': {
            'type': 'minecraft:model', 'model': f'f8resurs:item/{name}'}}, indent=2) + '\n')
        (ROOT / 'models/item' / f'{name}.json').write_text(json.dumps({
            'parent': 'minecraft:item/generated', 'textures': {'layer0': f'f8resurs:item/{name}'}}, indent=2) + '\n')
        (ROOT / 'textures/item' / f'{name}.png').write_bytes(png(draw(palette), 32, 32))
    print('Drawn copper, silver and gold pixel medals.')


if __name__ == '__main__':
    main()
