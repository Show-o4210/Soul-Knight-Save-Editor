#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.stores.game_store import GameDataStore
from core.stores.prefs_store import PlayerPrefsStore, detect_uid


def analyze(label: str, base: Path) -> None:
    prefs = base / "com.ChillyRoom.DungeonShooter.v2.playerprefs.xml"
    game_path = base / "game.data"
    if not prefs.is_file():
        print(f"{label}: no xml")
        return

    uid = detect_uid(prefs)
    entries = PlayerPrefsStore._parse(prefs.read_text(encoding="utf-8"))
    prefix = f"{uid}_"
    xml_unlock: dict[int, str] = {}
    for entry in entries:
        match = re.fullmatch(rf"{re.escape(prefix)}c(\d+)_unlock", entry["name"])
        if match and entry["tag"] == "string":
            xml_unlock[int(match.group(1))] = entry.get("text") or entry.get("value") or ""

    true_count = sum(1 for value in xml_unlock.values() if str(value).lower() == "true")
    print(f"=== {label} uid={uid}")
    print(f"  entries={len(entries)} xml_unlock={len(xml_unlock)} true={true_count}")

    tutorial_keys = [
        "has_tutorial",
        "tutorial_end_time",
        "first_in_room",
        "need_guide_season_or_defence",
        "controller_show_intro_window",
        f"{uid}_first_active",
        "first_active",
        f"{uid}_first_play",
        "first_play",
    ]
    for key in tutorial_keys:
        for entry in entries:
            if entry["name"] == key:
                val = entry.get("text") if entry["tag"] == "string" else entry.get("value")
                print(f"  {key} = {val!r}")
                break
        else:
            if key in (f"{uid}_first_active", f"{uid}_first_play"):
                print(f"  {key} = MISSING")

    if not game_path.is_file():
        print("  no game.data")
        return

    game = GameDataStore.load(game_path)
    hero_index = GameDataStore.hero_index_map(game.data)
    game_unlock = game.data.get("heroUnlock", {})
    mismatches: list[tuple] = []
    for hero, cidx in sorted(hero_index.items(), key=lambda item: item[1]):
        xml_val = str(xml_unlock.get(cidx, "MISSING")).lower()
        game_val = "true" if game_unlock.get(hero) else "false"
        if xml_val != game_val:
            mismatches.append((cidx, hero, game_val, xml_val))

    print(
        f"  game unlocked {sum(1 for v in game_unlock.values() if v)} / {len(game_unlock)}"
    )
    print(f"  game/xml mismatches: {len(mismatches)}")
    for row in mismatches[:12]:
        print(f"    c{row[0]:02d} {row[1]:15s} game={row[2]} xml={row[3]}")


def main() -> None:
    targets = [
        ("input", ROOT / "输入"),
        ("output", ROOT / "输出"),
        ("scratch", ROOT / "scratch" / "test_out"),
        ("preset", ROOT / "_test_preset_output"),
    ]
    for label, path in targets:
        analyze(label, path)
        print()


if __name__ == "__main__":
    main()