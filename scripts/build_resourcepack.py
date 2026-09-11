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


def png_header(data: bytes) -> tuple[int, int, int]:
    """Ширина, высота и цветовой тип PNG из заголовка IHDR."""
    assert data.startswith(b"\x89PNG\r\n\x1a\n")
    width, height, bits, colour, _, _, interlace = struct.unpack_from(">IIBBBBB", data, 16)
    assert (bits, interlace) == (8, 0)
    return width, height, colour


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

    # Нот-блок: правила multipart должны покрывать ЛЮБОЕ состояние ровно один раз.
    # Иначе часть состояний (например, нот-блок на медь/голову моба) останется
    # без модели и покажется фиолетовым «missing model».
    classic = ["harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime",
               "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"]
    heads = ["zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head"]
    vanilla = "minecraft:block/note_block"
    blockstates = json.loads(files["assets/minecraft/blockstates/note_block.json"])
    cases = blockstates["multipart"]

    def matched_models(note, instrument):
        hits = []
        for case in cases:
            when = case["when"]
            notes = when.get("note")
            if notes is not None and str(note) not in notes.split("|"):
                continue
            instruments = when.get("instrument")
            if instruments is not None and instrument not in instruments.split("|"):
                continue
            hits.append(case["apply"]["model"])
        return hits

    for note in range(25):
        for instrument in classic + heads:
            hits = matched_models(note, instrument)
            assert len(hits) == 1, f"note={note} instrument={instrument} matched {hits}"
            if note == 24 and instrument in classic and instrument not in ("flute", "banjo"):
                assert hits[0] == "f8resurs:block/copper_note_block"
            elif note == 24 and instrument == "flute":
                assert hits[0] == "f8resurs:block/ancient_jug"
            elif 1 <= note <= 9 and instrument == "banjo":
                assert hits[0] == "f8resurs:block/ancient_jug_filled"
            else:
                assert hits[0] == vanilla, f"note={note} instrument={instrument} -> {hits[0]}"

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

    # Лист кувшина и UV. Каждая грань обязана лежать внутри листа: выход за
    # границы — это «невидимые» или растянутые грани в игре.
    jug_texture = files["assets/f8resurs/textures/block/gor4ok.png"]
    jug_width, jug_height, _ = png_header(jug_texture)
    assert (jug_width, jug_height) == (40, 40), f"Лист кувшина должен быть 40x40, а он {jug_width}x{jug_height}"
    for jug in ("ancient_jug", "ancient_jug_filled"):
        model = json.loads(files[f"assets/f8resurs/models/block/{jug}.json"])
        assert model["texture_size"] == [40, 40], f"{jug}: texture_size должен совпадать с листом"
        assert model["elements"], f"{jug}: модель без элементов"
        for element in model["elements"]:
            frm, to = element["from"], element["to"]
            assert all(-16 <= value <= 32 for value in frm + to), f"{jug}: координаты вне допустимых"
            assert all(frm[i] < to[i] for i in range(3)), f"{jug}: {element.get('name')} нулевого размера"
            for direction, face in element["faces"].items():
                u1, v1, u2, v2 = face["uv"]
                assert 0 <= u1 < u2 <= jug_width and 0 <= v1 < v2 <= jug_height, \
                    f"{jug}: {element.get('name')} {direction} UV {face['uv']} выходит за лист"

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
    # Плагин предлагает игрокам ровно этот архив по ссылке из config.yml,
    # поэтому ZIP в корне репозитория — единственная раздаваемая копия.
    with ZipFile(ARCHIVE) as archive:
        assert len(archive.namelist()) == len(files), "Duplicate or unexpected ZIP entries"
        assert set(archive.namelist()) == set(files), "Stale resource pack archive"
        for name, contents in files.items():
            assert archive.read(name) == contents, f"Stale resource pack file: {name}"
    print(f"OK: {len(files)} files, note_block multipart is non-overlapping; ZIP matches resourcepack/")


if __name__ == "__main__":
    main()
