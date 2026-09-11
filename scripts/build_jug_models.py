#!/usr/bin/env python3
"""Собирает модели кувшина: пустого, наполненного и предметные ссылки.

Геометрия описана один раз, UV каждой грани считаются автоматически и берут
пиксели из листа resourcepack/assets/f8resurs/textures/block/gor4ok.png
(его собирает scripts/build_jug_texture.py).

Лист 40x40 размечен так:
  0..12   x 0..40   — полосатая терракота (бока тулова), 3 полосы по 4 px
  16..28  x 16..28  — светлая глина (горло, венчик, ручки)
  16..28  x 28..40  — тёмная глина (поддон и дно)
  28..36  x 2..10   — тёмный зев сосуда
  28..32  x 32..36  — жидкость

Запуск: python3 scripts/build_jug_models.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "resourcepack" / "assets" / "f8resurs" / "models"
ITEMS = ROOT / "resourcepack" / "assets" / "f8resurs" / "items"

TEXTURE_FACE = (0, 0)       # полосатая терракота тулова
TEXTURE_LIGHT = (16, 16)    # светлая глина
TEXTURE_DARK = (16, 28)     # тёмная глина
TEXTURE_MAW = (28, 2)       # тёмный зев
TEXTURE_LIQUID = (28, 32)   # жидкость

STRIPE_TOP = 8.0    # высота, до которой полосы идут непрерывно
STRIPE_PERIOD = 4.0  # период полос на листе


class Element:
    """Кубоид, у которого для каждой грани задан участок листа текстуры."""

    def __init__(self, name, frm, size, sides=None, top=None, bottom=None,
                 all_faces=None):
        self.name = name
        self.frm = [float(value) for value in frm]
        self.to = [self.frm[i] + float(size[i]) for i in range(3)]
        self.size = [float(value) for value in size]
        self.sides = sides
        self.top = top if top is not None else sides
        self.bottom = bottom if bottom is not None else sides
        self.all_faces = all_faces

    def faces(self):
        width, height, depth = self.size
        if self.all_faces is not None:
            return {name: _face(self.all_faces, 1, 1)
                    for name in ("north", "south", "east", "west", "up", "down")}
        return {
            "north": _face(self.sides, width, height),
            "south": _face(self.sides, width, height),
            "east": _face(self.sides, depth, height),
            "west": _face(self.sides, depth, height),
            "up": _face(self.top, width, depth),
            "down": _face(self.bottom, width, depth),
        }

    def json(self):
        return {"name": self.name, "from": _num(self.frm), "to": _num(self.to),
                "faces": self.faces()}


def _face(patch, width, depth):
    """Грань использует участок листа целиком (растягивается по грани)."""
    return {"uv": _num(patch.uv()), "texture": "#0"}


def _num(values):
    out = []
    for value in values:
        value = round(float(value), 4)
        out.append(int(value) if value == int(value) else value)
    return out


class Patch:
    """Участок листа: точка и размер в пикселях."""

    def __init__(self, point, size):
        self.point = point
        self.size = size

    def uv(self):
        u, v = self.point
        w, h = self.size
        return [u, v, u + w, v + h]


def build_elements(with_liquid):
    """Амфора: ступени тулова, светлое горло, полый венчик, ручки-ушки."""
    # (имя, ширина, низ, высота, светлая глина?)
    profile = [
        ("base",      11.0, 0.0,  1.2, False),
        ("belly_low", 13.0, 1.2,  1.6, False),
        ("belly",     14.0, 2.8,  3.4, False),
        ("belly_top", 13.0, 6.2,  1.8, False),
        ("shoulder",  11.0, 8.0,  1.6, False),
        ("neck_low",   9.0, 9.6,  1.6, True),
        ("neck",       7.0, 11.2, 2.2, True),
        ("flange",     9.0, 13.4, 1.0, True),
    ]
    elements = []
    for name, width, y0, height, light in profile:
        inset = (16 - width) / 2
        if light:
            sides = Patch(TEXTURE_LIGHT, (12, height))
            top = Patch(TEXTURE_LIGHT, (12, 12))
            bottom = Patch(TEXTURE_LIGHT, (12, 12))
        else:
            # полоса тулова на этой высоте: v отсчитывается от STRIPE_TOP вниз,
            # а поскольку рисунок листа повторяется каждые 4 px, остаток от
            # деления продолжает полосы без швов и без выхода за лист
            v = (STRIPE_TOP - (y0 + height)) % STRIPE_PERIOD
            sides = Patch((TEXTURE_FACE[0], v), (12, height))
            top = Patch((TEXTURE_FACE[0], v), (12, 12))
            bottom = Patch(TEXTURE_DARK, (12, 12))
        elements.append(Element(name, (inset, y0, inset), (width, height, width),
                                sides=sides, top=top, bottom=bottom))
    # венчик: кольцо из четырёх боксов, внутри видно зев или жидкость
    rim_y, rim_h, wall = 14.4, 1.4, 1.8
    rim_outer, rim_in, well = 10.0, 6.4, 6.4
    lo, hi = (16 - rim_outer) / 2, (16 + rim_outer) / 2
    inner_lo, inner_hi = (16 - rim_in) / 2, (16 + rim_in) / 2
    rim_patch = Patch(TEXTURE_LIGHT, (12, rim_h))
    rim_cap = Patch(TEXTURE_LIGHT, (12, 12))
    for name, frm, size in (
        ("rim_north", (lo, rim_y, lo), (rim_outer, rim_h, wall)),
        ("rim_south", (lo, rim_y, inner_hi), (rim_outer, rim_h, wall)),
        ("rim_west", (lo, rim_y, inner_lo), (wall, rim_h, rim_in)),
        ("rim_east", (inner_hi, rim_y, inner_lo), (wall, rim_h, rim_in)),
    ):
        elements.append(Element(name, frm, size, sides=rim_patch, top=rim_cap,
                                bottom=rim_cap))
    # содержимое венчика: тёмный зев у пустого, жидкость у наполненного
    well_lo = (16 - well) / 2
    if with_liquid:
        elements.append(Element("liquid", (well_lo, rim_y + 0.45, well_lo),
                                (well, 0.45, well),
                                all_faces=Patch(TEXTURE_LIQUID, (4, 4))))
    else:
        elements.append(Element("maw", (well_lo, rim_y + 0.45, well_lo),
                                (well, 0.45, well),
                                all_faces=Patch(TEXTURE_MAW, (8, 8))))
    # ручки-ушки: от плеч вверх, к венчику
    handle = Patch(TEXTURE_LIGHT, (6, 6))
    for name, x in (("handle_left", 1.6), ("handle_right", 12.0)):
        elements.append(Element(name, (x, 10.0, 6.9), (2.4, 3.4, 2.2),
                                all_faces=handle))
    return elements


def display_block():
    """Как кувшин выглядит в руках, на земле и в рамке."""
    return {
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                                  "scale": [0.375, 0.375, 0.375]},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0],
                                 "scale": [0.375, 0.375, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "scale": [0.4, 0.4, 0.4]},
        "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]},
        "fixed": {"rotation": [0, 90, 0], "scale": [0.5, 0.5, 0.5]},
    }


def model_json(with_liquid):
    return {
        "format_version": "1.21.11",
        "credit": "Сгенерировано scripts/build_jug_models.py",
        "texture_size": [40, 40],
        "textures": {
            "0": "f8resurs:block/gor4ok",
            "particle": "f8resurs:block/gor4ok",
        },
        "elements": [element.json() for element in build_elements(with_liquid)],
        "display": display_block(),
    }


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")


def main():
    write_json(MODELS / "block" / "ancient_jug.json", model_json(False))
    write_json(MODELS / "block" / "ancient_jug_filled.json", model_json(True))
    write_json(ITEMS / "ancient_jug.json",
               {"model": {"type": "minecraft:model", "model": "f8resurs:item/ancient_jug"}})
    write_json(ITEMS / "ancient_jug_filled.json",
               {"model": {"type": "minecraft:model", "model": "f8resurs:item/ancient_jug_filled"}})
    write_json(MODELS / "item" / "ancient_jug.json", {"parent": "f8resurs:block/ancient_jug"})
    write_json(MODELS / "item" / "ancient_jug_filled.json",
               {"parent": "f8resurs:block/ancient_jug_filled"})
    print("OK: модели кувшина собраны")


if __name__ == "__main__":
    main()
