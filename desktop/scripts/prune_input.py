#!/usr/bin/env python3
"""将输入目录精简为最小必备文件集。"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from core.constants import PREFS_NAME
from core.stores.prefs_store import detect_uid, discover_prefs

INPUT = ROOT / "输入"
KEEP = {"game.data", PREFS_NAME}


def main() -> None:
    prefs = discover_prefs(INPUT)
    uid = detect_uid(prefs)
    KEEP.add(f"item_data_{uid}_.data")
    setting = INPUT / f"setting_{uid}_.data"
    if setting.is_file():
        KEEP.add(setting.name)

    removed = []
    for p in list(INPUT.iterdir()):
        if p.is_file() and p.name not in KEEP:
            p.unlink()
            removed.append(p.name)
    print("保留:", sorted(KEEP))
    print("已移除:", removed)


if __name__ == "__main__":
    main()