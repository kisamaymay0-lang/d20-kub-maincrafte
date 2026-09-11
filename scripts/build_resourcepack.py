#!/usr/bin/env python3
"""Validate and reproducibly build the resource pack; --check checks the existing ZIP."""
import argparse
import json
import struct
import zlib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "resourcepack"
ARCHIVE = ROOT / "f8resurs-resourcepack.zip"


def rgba_rows(data: bytes) -> list[bytes]:
    """Decode the small, non-interlaced RGBA beam textures using only the stdlib."""
    assert data.startswith(b"\x89PNG\r\n\x1a\n")
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
    assert header is not None
    width, height, bits, colour, compression, filtering, interlace = header
    assert (bits, colour, compression, filtering, interlace) == (8, 6, 0, 0, 0)
    stride = width * 4
    raw = zlib.decompress(compressed)
    assert len(raw) == height * (stride + 1)
    previous, rows = bytes(stride), []
    for y in range(height):
        start = y * (stride + 1)
        method, row = raw[start], bytearray(raw[start + 1:start + stride + 1])
        assert method in range(5)
        for x in range(stride):
            left = row[x - 4] if x >= 4 else 0
            above = previous[x]
            upper_left = previous[x - 4] if x >= 4 else 0
            if method == 0:
                predictor = 0
            elif method == 1:
                predictor = left
            elif method == 2:
                predictor = above
            elif method == 3:
                predictor = (left + above) // 2
            else:
                estimate = left + above - upper_left
                predictor = min((left, above, upper_left), key=lambda value: abs(estimate - value))
            row[x] = (row[x] + predictor) & 255
        previous = bytes(row)
        rows.append(previous)
    return rows


def validate() -> dict[str, bytes]:
    files = {
        path.relative_to(PACK).as_posix(): path.read_bytes()
        for path in sorted(PACK.rglob("*"))
        if path.is_file() and path.name != "README.md"
    }
    assert "pack.mcmeta" in files and "pack.png" in files
    json.loads(files["pack.mcmeta"])
    for name, contents in files.items():
        if name.endswith(".json"):
            json.loads(contents)

    # Медный блок — нота 24 (14 инструментов). Кувшин: пустой — нота 24 + флейта,
    # наполненный — ноты 1..9 (нота = количество жидкости = сигнал компаратора)
    # + банджо. Каждый блок-стейт нот-блока матчится ровно одним кейсом.
    instruments = {"harp", "basedrum", "snare", "hat", "bass", "bell", "guitar", "chime",
                   "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "pling", "flute", "banjo"}
    vanilla = "minecraft:block/note_block"
    blockstates = json.loads(files["assets/minecraft/blockstates/note_block.json"])
    cases = blockstates["multipart"]
    assert len(cases) == 14, "note_block multipart must define 14 non-overlapping cases"

    bulk = cases[0]
    assert set(bulk["when"]["note"].split("|")) == {"0"} | {str(note) for note in range(10, 24)}
    assert "instrument" not in bulk["when"] and bulk["apply"]["model"] == vanilla

    for index, note in enumerate(range(1, 10), start=1):
        case = cases[index]
        assert case["when"]["note"] == str(note), f"Case {index} must pin note={note}"
        assert set(case["when"]["instrument"].split("|")) == instruments - {"banjo"}, \
            f"Note {note} must stay vanilla for every instrument except banjo"
        assert case["apply"]["model"] == vanilla

    copper = cases[10]
    copper_instruments = set(copper["when"]["instrument"].split("|"))
    assert copper["when"]["note"] == "24" and copper_instruments == instruments - {"flute", "banjo"}, \
        "Copper covers note=24 for all instruments except the jug's two"
    assert copper["apply"]["model"] == "f8resurs:block/copper_note_block"

    jug_empty = cases[11]
    assert jug_empty["when"] == {"note": "24", "instrument": "flute"}
    assert jug_empty["apply"]["model"] == "f8resurs:block/ancient_jug"

    jug_filled = cases[12]
    assert jug_filled["when"] == {"note": "|".join(str(note) for note in range(1, 10)), "instrument": "banjo"}
    assert jug_filled["apply"]["model"] == "f8resurs:block/ancient_jug_filled"

    banjo_marker = cases[13]
    assert banjo_marker["when"] == {"note": "24", "instrument": "banjo"}
    assert banjo_marker["apply"]["model"] == vanilla

    for name, contents in files.items():
        if name.startswith("assets/f8resurs/items/") and name.endswith(".json"):
            item = json.loads(contents)["model"]
            assert item["type"] == "minecraft:model"
            namespace, model = item["model"].split(":", 1)
            assert f"assets/{namespace}/models/{model}.json" in files, f"Missing model: {name}"
        if name.startswith("assets/f8resurs/models/") and name.endswith(".json"):
            model = json.loads(contents)
            references = list(model.get("textures", {}).values())
            parent = model.get("parent", "")
            if parent.startswith("f8resurs:"):
                assert f"assets/f8resurs/models/{parent.split(':', 1)[1]}.json" in files
            for ref in references:
                if ref.startswith("f8resurs:"):
                    texture = f"assets/f8resurs/textures/{ref.split(':', 1)[1]}.png"
                    assert texture in files, f"Missing texture: {texture}"
                    assert files[texture].startswith(b"\x89PNG\r\n\x1a\n"), f"Not a PNG: {texture}"
    # Custom profile glyphs are images, and must stay available for transparent UI fades.
    for name, contents in files.items():
        if name.startswith("assets/f8resurs/font/") and name.endswith(".json"):
            for provider in json.loads(contents)["providers"]:
                if provider["type"] != "bitmap":
                    continue
                namespace, texture = provider["file"].split(":", 1)
                assert f"assets/{namespace}/textures/{texture}" in files, f"Missing font texture: {texture}"
                assert provider["ascent"] <= provider["height"]

    # GUI medals must ship together with their item definitions (native PNGs can be replaced).
    for metal in ("copper", "silver", "gold"):
        name = f"medal_{metal}"
        assert f"assets/f8resurs/items/{name}.json" in files
        assert f"assets/f8resurs/models/item/{name}.json" in files
        assert f"assets/f8resurs/textures/item/{name}.png" in files

    for item in ("icy_rime", "rime", "depleted_rime", "ice_caviar", "ice_caviar_sandwich"):
        assert f"assets/f8resurs/items/{item}.json" in files
        assert f"assets/f8resurs/models/item/{item}.json" in files
        assert f"assets/f8resurs/textures/item/{item}.png" in files

    # Древний кувшин: предмет (пустой/наполненный) + блок-модели для поставки.
    for jug in ("ancient_jug", "ancient_jug_filled"):
        assert f"assets/f8resurs/items/{jug}.json" in files
        assert f"assets/f8resurs/models/item/{jug}.json" in files
        assert f"assets/f8resurs/models/block/{jug}.json" in files

    # Both beams must be flat and unshaded: no rod base, side faces or AO.
    for beam in ("star_beam", "star_beam_preview"):
        model = json.loads(files[f"assets/f8resurs/models/item/{beam}.json"])
        assert model["ambientocclusion"] is False and model["gui_light"] == "front"
        assert len(model["elements"]) == 1
        plane = model["elements"][0]
        assert plane["from"] == [0, 0, 8] and plane["to"] == [16, 16, 8]
        assert plane["shade"] is False and plane["light_emission"] == 15
        assert set(plane["faces"]) == {"north", "south"}
        rows = rgba_rows(files[f"assets/f8resurs/textures/item/{beam}.png"])
        opacity = []
        for row in rows:
            alpha = set(row[3::4])
            assert alpha in ({0}, {255}), "Beam width/opacity must be uniform within each row"
            opacity.append(next(iter(alpha)))
        if beam == "star_beam":
            assert all(alpha == 255 for alpha in opacity), "Completed beam must be solid"
        else:
            assert 0 in opacity and 255 in opacity, "Preview needs transparent gaps"
            dash_starts = sum(alpha == 255 and (i == 0 or opacity[i - 1] == 0)
                              for i, alpha in enumerate(opacity))
            assert dash_starts >= 3, "Preview must contain several separate dashes"
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    files = validate()
    if not args.check:
        with ZipFile(ARCHIVE, "w", compression=ZIP_DEFLATED) as archive:
            for name, contents in files.items():
                info = ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, contents)
    # Плагин пак не раздаёт: архив в корне репозитория — единственная копия,
    # его подключает сам сервер (server.properties: resource-pack).
    with ZipFile(ARCHIVE) as archive:
        assert len(archive.namelist()) == len(files), "Duplicate or unexpected ZIP entries"
        assert set(archive.namelist()) == set(files), "Stale resource pack archive"
        for name, contents in files.items():
            assert archive.read(name) == contents, f"Stale resource pack file: {name}"
    print(f"OK: {len(files)} files, note_block multipart is non-overlapping; ZIP matches resourcepack/")


if __name__ == "__main__":
    main()
