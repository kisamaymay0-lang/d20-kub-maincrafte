#!/usr/bin/env python3
"""Оффлайн-рендер моделей ресурспака: посмотреть вид без запуска Minecraft.

Инструмент только для разработки: он разбирает block-модели (JSON) и PNG
текстуры из resourcepack/, проецирует кубоиды в изометрии и складывает
картинку с затенением по сторонам света, как это делает клиент.

Запуск:
    python3 scripts/preview_models.py                 # все модели ниже
    python3 scripts/preview_models.py <путь.json> ... # свои модели

Результат: artifacts/preview/<имя>.png (папка в .gitignore).
"""
import json
import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "resourcepack"
OUT = ROOT / "artifacts" / "preview"

DEFAULT_MODELS = [
    "assets/f8resurs/models/block/ancient_jug.json",
    "assets/f8resurs/models/block/ancient_jug_filled.json",
    "assets/f8resurs/models/block/copper_note_block.json",
]

FACE_NORMAL = {
    "up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1),
    "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0),
}
# затенение граней: свет сверху, бока темнее (как в клиенте)
FACE_SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def face_quad(frm, to, direction):
    """Четыре угла грани по часовой стрелке, если смотреть снаружи."""
    x1, y1, z1 = frm
    x2, y2, z2 = to
    if direction == "north":
        return [(x2, y2, z1), (x1, y2, z1), (x1, y1, z1), (x2, y1, z1)]
    if direction == "south":
        return [(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)]
    if direction == "west":
        return [(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)]
    if direction == "east":
        return [(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)]
    if direction == "up":
        return [(x1, y2, z2), (x2, y2, z2), (x2, y2, z1), (x1, y2, z1)]
    if direction == "down":
        return [(x1, y1, z1), (x2, y1, z1), (x2, y1, z2), (x1, y1, z2)]
    raise ValueError(direction)


def read_png(path):
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
    rows, previous, cursor = [], bytearray(stride), 0
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


def write_png(path, width, height, pixels):
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
    path.parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_bytes(data)


def texture_rows(model, pack, cache):
    """Все текстуры модели, с которыми могут работать грани."""
    out = {}
    for key, ref in model.get("textures", {}).items():
        if not isinstance(ref, str) or ref.startswith("#"):
            continue
        namespace, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
        png = pack / "assets" / namespace / "textures" / (path + ".png")
        if not png.exists():
            out[key] = None
            continue
        if png not in cache:
            cache[png] = read_png(png)
        out[key] = cache[png]
    return out


def collect_quads(model, pack, cache):
    """Список квадов: углы, UV, текстура, затенение."""
    texture_size = model.get("texture_size", [16, 16])
    textures = texture_rows(model, pack, cache)
    quads = []
    for element in model.get("elements", []):
        frm, to = [float(v) for v in element["from"]], [float(v) for v in element["to"]]
        for direction, face in element.get("faces", {}).items():
            reference = face.get("texture", "")
            texture = textures.get(reference.lstrip("#")) if reference.startswith("#") else None
            u1, v1, u2, v2 = face["uv"]
            quads.append({
                "corners": face_quad(frm, to, direction),
                "uvs": [(u1, v1), (u2, v1), (u2, v2), (u1, v2)],
                "texture": texture,
                "texture_size": texture_size,
                "shade": FACE_SHADE[direction],
            })
    return quads


def render(model_path, pack=PACK, out_path=None, size=420, yaw=35.0, pitch=22.0,
           scale=0.85, bg=(48, 48, 48), checker=12):
    model = json.loads(Path(model_path).read_text())
    quads = collect_quads(model, Path(pack), {})

    yaw_rad, pitch_rad = math.radians(yaw), math.radians(pitch)

    def project(point):
        """Ортографическая проекция: x/y на экране и глубина (больше — ближе)."""
        x, y, z = point[0] - 8.0, point[1] - 8.0, point[2] - 8.0
        xr = x * math.cos(yaw_rad) + z * math.sin(yaw_rad)
        zr = -x * math.sin(yaw_rad) + z * math.cos(yaw_rad)
        yr = y * math.cos(pitch_rad) - zr * math.sin(pitch_rad)
        depth = zr * math.cos(pitch_rad) + y * math.sin(pitch_rad)
        return xr, yr, depth

    width = height = size
    pixels = bytearray(width * height * 4)
    depth_buffer = [-1e9] * (width * height)
    for y in range(height):
        for x in range(width):
            tone = bg[0] + (checker if ((x // 20) + (y // 20)) % 2 else 0)
            i = (y * width + x) * 4
            pixels[i:i + 4] = bytes((tone, tone, tone, 255))

    factor = width * scale / 16.0
    for quad in quads:
        screen = []
        for corner in quad["corners"]:
            px, py, depth = project(corner)
            screen.append((width / 2 + px * factor, height / 2 - py * factor, depth))
        uv = quad["uvs"]
        tex_w, tex_h = quad["texture_size"]
        for triangle in ((0, 1, 2), (0, 2, 3)):
            a, b, c = (screen[i] for i in triangle)
            ua, ub, uc = (uv[i] for i in triangle)
            area = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])
            if abs(area) < 1e-9:
                continue
            min_x = max(0, int(min(a[0], b[0], c[0])))
            max_x = min(width - 1, int(max(a[0], b[0], c[0])) + 1)
            min_y = max(0, int(min(a[1], b[1], c[1])))
            max_y = min(height - 1, int(max(a[1], b[1], c[1])) + 1)
            for py in range(min_y, max_y + 1):
                for px in range(min_x, max_x + 1):
                    fx, fy = px + 0.5, py + 0.5
                    l0 = ((b[0] - a[0]) * (fy - a[1]) - (fx - a[0]) * (b[1] - a[1])) / area
                    l1 = ((c[0] - b[0]) * (fy - b[1]) - (fx - b[0]) * (c[1] - b[1])) / area
                    l2 = ((a[0] - c[0]) * (fy - c[1]) - (fx - c[0]) * (a[1] - c[1])) / area
                    wa, wb, wc = l1, l2, l0
                    if wa < -1e-6 or wb < -1e-6 or wc < -1e-6:
                        continue
                    depth = wa * a[2] + wb * b[2] + wc * c[2]
                    index = py * width + px
                    if depth <= depth_buffer[index]:
                        continue   # эта точка дальше уже нарисованной
                    u = wa * ua[0] + wb * ub[0] + wc * uc[0]
                    v = wa * ua[1] + wb * ub[1] + wc * uc[1]
                    if quad["texture"] is None:
                        colour = (255, 0, 255, 255)
                    else:
                        rows_w, rows_h, rows = quad["texture"]
                        tx = min(rows_w - 1, max(0, int(u * rows_w / tex_w)))
                        ty = min(rows_h - 1, max(0, int(v * rows_h / tex_h)))
                        red, green, blue, alpha = rows[ty][tx * 4:tx * 4 + 4]
                        if alpha == 0:
                            continue
                        colour = (red, green, blue, alpha)
                    depth_buffer[index] = depth
                    shade = quad["shade"]
                    i = index * 4
                    pixels[i:i + 4] = bytes((
                        min(255, int(colour[0] * shade)),
                        min(255, int(colour[1] * shade)),
                        min(255, int(colour[2] * shade)),
                        255,
                    ))

    if out_path is None:
        out_path = OUT / (Path(model_path).stem + ".png")
    write_png(Path(out_path), width, height, pixels)
    print(f"готово: {out_path}")
    return out_path


def main():
    arguments = sys.argv[1:]
    models = [Path(a) if Path(a).exists() else PACK / a for a in arguments] if arguments else \
        [PACK / name for name in DEFAULT_MODELS]
    for model in models:
        render(model)
    print("Превью лежат в artifacts/preview/")


if __name__ == "__main__":
    main()
