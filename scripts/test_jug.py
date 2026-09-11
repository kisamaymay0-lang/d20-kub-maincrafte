#!/usr/bin/env python3
"""Проверки кувшина без Minecraft: лист текстур, модели, предметы и нот-блок.

Тесты используют только stdlib и не требуют клиента/сервера.

Запуск: python3 scripts/test_jug.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_resourcepack as pack  # noqa: E402  (валидатор ресурспака)
from build_jug_texture import Canvas, SOURCE, SHEET, png_read  # noqa: E402

PACK = ROOT / "resourcepack"


def test_source_is_preserved():
    """Ни один пиксель присланного арта не потерян: лист собран поверх него."""
    source = Canvas.from_rows(*png_read(SOURCE))
    sheet = Canvas.from_rows(*png_read(SHEET))
    assert (source.width, source.height) == (sheet.width, sheet.height) == (40, 40)
    for y in range(40):
        for x in range(40):
            original = source.get(x, y)
            if original[3] == 0:
                continue
            assert sheet.get(x, y)[3] == 255, f"пиксель {x},{y} потерян"


def test_liquid_is_present_and_golden():
    sheet = Canvas.from_rows(*png_read(SHEET))
    golds = 0
    for y in range(32, 36):
        for x in range(28, 32):
            red, green, blue, alpha = sheet.get(x, y)
            assert alpha == 255, "жидкость обязана быть непрозрачной"
            assert red > green > blue, "жидкость должна быть золотистой"
            golds += 1
    assert golds == 16


def test_models_reference_existing_texture():
    for name in ("ancient_jug", "ancient_jug_filled"):
        model = json.loads((PACK / f"assets/f8resurs/models/block/{name}.json").read_text())
        assert model["textures"]["0"] == "f8resurs:block/gor4ok"
        assert (PACK / "assets/f8resurs/textures/block/gor4ok.png").exists()
        item = json.loads((PACK / f"assets/f8resurs/items/{name}.json").read_text())
        assert item["model"]["model"] == f"f8resurs:item/{name}"
        item_model = json.loads((PACK / f"assets/f8resurs/models/item/{name}.json").read_text())
        assert item_model["parent"] == f"f8resurs:block/{name}"


def test_differences_between_empty_and_filled():
    empty = json.loads((PACK / "assets/f8resurs/models/block/ancient_jug.json").read_text())
    filled = json.loads((PACK / "assets/f8resurs/models/block/ancient_jug_filled.json").read_text())
    empty_names = {element["name"] for element in empty["elements"]}
    filled_names = {element["name"] for element in filled["elements"]}
    assert {"maw", "liquid"} & empty_names == {"maw"}, "у пустого кувшина должен быть зев"
    assert {"maw", "liquid"} & filled_names == {"liquid"}, "у полного должна быть жидкость"
    # геометрия одинаковая: отличается только «дно» венчика
    assert empty_names ^ filled_names == {"maw", "liquid"}


def test_uv_inside_sheet():
    width, height, _ = pack.png_header((PACK / "assets/f8resurs/textures/block/gor4ok.png").read_bytes())
    for name in ("ancient_jug", "ancient_jug_filled"):
        model = json.loads((PACK / f"assets/f8resurs/models/block/{name}.json").read_text())
        for element in model["elements"]:
            for direction, face in element["faces"].items():
                u1, v1, u2, v2 = face["uv"]
                assert 0 <= u1 < u2 <= width, f"{name} {element['name']} {direction}: u"
                assert 0 <= v1 < v2 <= height, f"{name} {element['name']} {direction}: v"


def test_note_block_states_are_complete():
    """Каждое состояние нот-блока получает ровно одну модель."""
    blockstates = json.loads((PACK / "assets/minecraft/blockstates/note_block.json").read_text())
    cases = blockstates["multipart"]
    classic = ["harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime",
               "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"]
    heads = ["zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head"]
    for note in range(25):
        for instrument in classic + heads:
            hits = []
            for case in cases:
                when = case["when"]
                if "note" in when and str(note) not in when["note"].split("|"):
                    continue
                if "instrument" in when and instrument not in when["instrument"].split("|"):
                    continue
                hits.append(case["apply"]["model"])
            assert len(hits) == 1, f"note={note} instrument={instrument}: {hits}"


def test_resourcepack_validation():
    files = pack.validate()
    assert "assets/f8resurs/models/block/ancient_jug.json" in files


def test_config_documents_pack_url():
    config = (ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8")
    assert re.search(r"^resource-pack:", config, re.M), "в config.yml нет секции resource-pack"
    assert "releases/latest/download/f8resurs-resourcepack.zip" in config


def test_no_embedded_http_server():
    """Свой HTTP-сервер больше не поднимается: раздачей занимается сам Minecraft."""
    source = (ROOT / "src/main/java/com/yourserver/adaptation/ResourcePackPusher.java").read_text()
    assert "com.sun.net.httpserver" not in source
    assert "addResourcePack" in source
    assert "resource-pack.url" in source


def test_plugin_version_and_artifact():
    plugin = (ROOT / "src/main/resources/plugin.yml").read_text(encoding="utf-8")
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    assert re.search(r"^version: '10\.2'", plugin, re.M)
    assert "<version>10.2</version>" in pom
    assert "f8-plugin-10.2" in workflow and "softprops/action-gh-release" in workflow


def main():
    tests = [value for name, value in sorted(globals().items()) if name.startswith("test_")]
    failures = 0
    for test in tests:
        try:
            test()
        except AssertionError as error:
            failures += 1
            print(f"FAIL {test.__name__}: {error}")
        else:
            print(f"ok   {test.__name__}")
    print(f"\n{len(tests) - failures}/{len(tests)} проверок пройдено")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
