#!/usr/bin/env python3
"""从样本 item_data 收集键名，推断中文并合并进 strings.json entities。"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from core.stores.item_store import ItemDataStore
from gui.i18n.loader import _infer_name

STRINGS = ROOT / "gui" / "i18n" / "strings.json"
SAMPLES = [
    ROOT / "输入" / "item_data_35924932_.data",
    ROOT / "参考" / "item_data_77614388_.data",
]


def collect_keys() -> set[str]:
    keys: set[str] = set()
    for path in SAMPLES:
        if not path.is_file():
            continue
        data = ItemDataStore.load(path).data
        for field in ("materials", "seeds", "blueprints"):
            keys.update((data.get(field) or {}).keys())
        keys.update(data.get("itemUnlock") or [])
        for p in data.get("plants") or []:
            if isinstance(p, dict) and p.get("plantName"):
                keys.add(str(p["plantName"]))
    keys.update({
        "Motorcycle", "Chest", "Safe", "Book", "Planet", "CatFood",
        "ItemEggMachine", "ItemTrashCan", "ItemDrinkSeller",
    })
    return keys


def main() -> None:
    payload = json.loads(STRINGS.read_text(encoding="utf-8"))
    entities: dict[str, str] = dict(payload.get("entities", {}))
    added = 0
    for key in sorted(collect_keys()):
        if key in entities:
            continue
        name = _infer_name(key)
        if name:
            entities[key] = name
            added += 1
    payload["entities"] = dict(sorted(entities.items()))
    STRINGS.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"entities 共 {len(entities)} 项，新增 {added}")


if __name__ == "__main__":
    main()