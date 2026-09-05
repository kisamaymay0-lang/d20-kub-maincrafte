#!/usr/bin/env python3
"""Validate and reproducibly build the resource pack; --check checks the existing ZIP."""
import argparse
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "resourcepack"
ARCHIVE = ROOT / "f8resurs-resourcepack.zip"


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

    # Empty-string fallback overlaps note=24. Every note must match exactly
    # one variant, regardless of instrument and powered=true/false.
    variants = json.loads(files["assets/minecraft/blockstates/note_block.json"])["variants"]
    assert set(variants) == {f"note={note}" for note in range(25)}, "Overlapping/missing note variants"
    for note in range(25):
        expected = "f8resurs:block/copper_note_block" if note == 24 else "minecraft:block/note_block"
        assert variants[f"note={note}"]["model"] == expected

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
    with ZipFile(ARCHIVE) as archive:
        assert len(archive.namelist()) == len(files), "Duplicate or unexpected ZIP entries"
        assert set(archive.namelist()) == set(files), "Stale resource pack archive"
        for name, contents in files.items():
            assert archive.read(name) == contents, f"Stale resource pack file: {name}"
    print(f"OK: {len(files)} files, 25 non-overlapping block variants; ZIP matches resourcepack/")


if __name__ == "__main__":
    main()
