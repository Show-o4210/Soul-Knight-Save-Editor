#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.stores.game_store import GameDataStore
from core.stores.prefs_store import PlayerPrefsStore, detect_uid


def main() -> None:
    base = ROOT / "输入"
    prefs = base / "com.ChillyRoom.DungeonShooter.v2.playerprefs.xml"
    uid = detect_uid(prefs)
    entries = PlayerPrefsStore._parse(prefs.read_text(encoding="utf-8"))
    prefix = f"{uid}_"

    xml_skins: dict[tuple[int, int], str] = {}
    for entry in entries:
        match = re.fullmatch(rf"{re.escape(prefix)}c(\d+)_skin(\d+)", entry["name"])
        if match and entry["tag"] == "int":
            xml_skins[(int(match.group(1)), int(match.group(2)))] = entry.get("value") or "0"

    game = GameDataStore.load(base / "game.data")
    hero_index = GameDataStore.hero_index_map(game.data)
    game_owned = 0
    mismatches = 0
    for hero, skins in game.data.get("skinLock", {}).items():
        if hero not in hero_index:
            continue
        cidx = hero_index[hero]
        for entry in skins:
            key = (cidx, int(entry["Key"]))
            game_val = str(entry.get("Value", 0))
            xml_val = xml_skins.get(key, "MISSING")
            if game_val == "1":
                game_owned += 1
            if xml_val != game_val:
                mismatches += 1

    print(f"uid={uid}")
    print(f"game owned skins: {game_owned}")
    print(f"xml skin mismatches vs game: {mismatches}")


if __name__ == "__main__":
    main()