#!/usr/bin/env python3
"""Собирает текстуры кувшина из исходного арта игрока.

Источник: scripts/jug_texture_source.png — присланный владельцем рисунок
40x40 (терракота, тёмный «зев» сосуда и светлые полосы). Скрипт НЕ рисует
поверх чужого арта: он раскладывает его пиксели по понятным участкам листа
и добавляет только недостающие связные тона (светлая глина, тёмный поддон,
жидкость), полученные смешением цветов из того же источника.

Результат (в resourcepack/assets/f8resurs/textures/):
  block/gor4ok.png        — лист 40x40 для модели кувшина (пустого и полного)
                         (участок 28..31 x 32..35 — жидкость)

Запуск: python3 scripts/build_jug_texture.py
"""
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts" / "jug_texture_source.png"
TEXTURES = ROOT / "resourcepack" / "assets" / "f8resurs" / "textures"
SHEET = TEXTURES / "block" / "gor4ok.png"

SIZE = 40

TERRA = (150, 93, 67)      # основной тон терракоты
TERRA_LIGHT = (179, 138, 119)
TERRA_PALE = (200, 171, 157)
TERRA_DARK = (94, 58, 41)
TERRA_DEEP = (70, 43, 30)
LIQUID_GOLD = (196, 140, 47)
LIQUID_LIGHT = (232, 187, 90)


# --------------------------------------------------------------------- PNG

def png_read(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    offset, compressed, header = 8, bytearray(), None
    while offset < len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        offset += length + 12
    width, height, bits, colour, _, _, interlace = header
    assert (bits, colour, interlace) == (8, 6, 0), (path, header)
    stride = width * 4
    raw = zlib.decompress(bytes(compressed))
    rows, previous = [], bytearray(stride)
    cursor = 0
    for _ in range(height):
        method = raw[cursor]
        cursor += 1
        row = bytearray(raw[cursor:cursor + stride])
        cursor += stride
        for x in range(stride):
            left = row[x - 4] if x >= 4 else 0
            above = previous[x]
            corner = previous[x - 4] if x >= 4 else 0
            if method == 0:
                predictor = 0
            elif method == 1:
                predictor = left
            elif method == 2:
                predictor = above
            elif method == 3:
                predictor = (left + above) // 2
            else:
                estimate = left + above - corner
                predictor = min((left, above, corner), key=lambda v: abs(estimate - v))
            row[x] = (row[x] + predictor) & 255
        previous = row
        rows.append(bytes(row))
    return width, height, rows


def png_write(path, width, height, pixels):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * width * 4:(y + 1) * width * 4])

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    data = b"\x89PNG\r\n\x1a\n"
    data += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    data += chunk(b"IEND", b"")
    Path(path).write_bytes(data)


# ------------------------------------------------------------ работа с пикселями

class Canvas:
    def __init__(self, width, height):
        self.width = width
        self.height = height
        self.pixels = bytearray(width * height * 4)

    @classmethod
    def from_rows(cls, width, height, rows):
        canvas = cls(width, height)
        for y in range(height):
            canvas.pixels[y * width * 4:(y + 1) * width * 4] = rows[y]
        return canvas

    def get(self, x, y):
        x, y = int(x), int(y)
        if not (0 <= x < self.width and 0 <= y < self.height):
            return (0, 0, 0, 0)
        i = (y * self.width + x) * 4
        return tuple(self.pixels[i:i + 4])

    def put(self, x, y, colour):
        x, y = int(x), int(y)
        if not (0 <= x < self.width and 0 <= y < self.height):
            return
        i = (y * self.width + x) * 4
        self.pixels[i:i + 4] = bytes(colour)

    def rect(self, x0, y0, w, h, colour):
        for y in range(int(y0), int(y0 + h)):
            for x in range(int(x0), int(x0 + w)):
                self.put(x, y, colour)

    def blit(self, source, sx, sy, dx, dy, w, h):
        for y in range(int(h)):
            for x in range(int(w)):
                colour = source.get(sx + x, sy + y)
                if colour[3] == 0:
                    continue
                self.put(dx + x, dy + y, colour)

    def scale_into(self, source, sx, sy, sw, sh, dx, dy, dw, dh):
        """Ближайшее соседство: переносит участок с изменением размера."""
        for y in range(dh):
            for x in range(dw):
                colour = source.get(sx + x * sw // dw, sy + y * sh // dh)
                if colour[3] == 0:
                    continue
                self.put(dx + x, dy + y, colour)


def mix(a, b, t):
    """Линейное смешение двух цветов (RGB или RGBA), t=0 -> a, t=1 -> b."""
    a = tuple(a) + (255,) * (4 - len(a))
    b = tuple(b) + (255,) * (4 - len(b))
    return tuple(max(0, min(255, int(round(a[i] * (1 - t) + b[i] * t)))) for i in range(4))


def shade(colour, target, t):
    """Сдвигает цвет к целевому тону на долю t."""
    return mix(colour, target, t)


def noise(x, y, salt=0):
    """Детерминированный псевдослучайный шум 0..1 (без random)."""
    value = (x * 374761393 + y * 668265263 + salt * 2246822519) & 0xFFFFFFFF
    value = (value ^ (value >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((value ^ (value >> 16)) & 0xFFFF) / 65535.0


# ------------------------------------------------------------------ лист кувшина

BAND_ROWS = 12          # сколько строк листа занимает тулово
BODY_SIDE = 12          # ширина «лицевой» терракоты на листе
BANDS = ("light", "terra", "terra", "dark")   # период полос, сверху вниз


def band_tone(step):
    return {"light": TERRA_LIGHT, "terra": TERRA, "dark": TERRA_DARK}[BANDS[step % len(BANDS)]]


def build_sheet():
    source = Canvas.from_rows(*png_read(SOURCE))
    sheet = Canvas(SIZE, SIZE)
    # 1. переносим арт владельца как есть — он остаётся материалом кувшина
    sheet.blit(source, 0, 0, 0, 0, sheet.width, sheet.height)
    # 2. «лицевая» терракота (0..12 x 0..12): горизонтальный рисунок берём
    #    из источника, а полосы выравниваем по его же палитре, чтобы
    #    одинаковые тона шли по всему тулову от дна до горла
    for y in range(BAND_ROWS):
        for x in range(BODY_SIDE):
            base = source.get(x, y)
            if base[3] == 0:
                base = TERRA + (255,)
            sheet.put(x, y, mix(base, band_tone(y), 0.62))
    # 3. светлая глина: (16..27, 16..27) — плечи, горло, венчик, ручки
    for y in range(16, 28):
        for x in range(16, 28):
            base = source.get(x - 16, y - 16)
            if base[3] == 0:
                base = TERRA + (255,)
            t = 0.55 + 0.12 * noise(x, y, 1)
            sheet.put(x, y, shade(base, TERRA_PALE, t))
    # 4. тёмный поддон: (16..27, 28..39) — нижний пояс и дно
    for y in range(28, 40):
        for x in range(16, 28):
            base = source.get(x - 16, y - 16)
            if base[3] == 0:
                base = TERRA + (255,)
            t = 0.45 + 0.15 * noise(x, y, 2)
            sheet.put(x, y, shade(base, TERRA_DEEP, t))
    # 5. зев сосуда (28..36, 2..12): ровный тёмный тон с мягким градиентом,
    #    без шума — так он читается как настоящее отверстие
    for y in range(2, 12):
        for x in range(28, 36):
            depth = 0.72 - 0.06 * (y - 2)
            tone = mix(TERRA_DEEP, (24, 15, 10), depth)
            sheet.put(x, y, tone)
    # 6. светлая кромка венчика вокруг зева
    for x in range(27, 37):
        sheet.put(x, 1, shade(TERRA_LIGHT, TERRA_PALE, 0.4))
    paint_liquid(sheet)
    png_write(SHEET, sheet.width, sheet.height, sheet.pixels)
    return sheet


# ------------------------------------------------------------------- жидкость

def paint_liquid(sheet):
    """Жидкость лежит в том же листе: 4x4 в точке (28, 32)."""
    for y in range(32, 36):
        for x in range(28, 32):
            tone = mix(LIQUID_GOLD, LIQUID_LIGHT, 0.45 * noise(x, y, 4))
            if (x % 4, y % 4) in ((0, 0), (1, 0)):
                tone = mix(tone, LIQUID_LIGHT, 0.45)
            sheet.put(x, y, tone + (255,))


# ------------------------------------------------------- значки для инвентаря

def main():
    build_sheet()
    print("OK: лист текстур кувшина собран из scripts/jug_texture_source.png")


if __name__ == "__main__":
    main()
