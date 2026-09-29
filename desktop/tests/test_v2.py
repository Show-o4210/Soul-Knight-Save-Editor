#!/usr/bin/env python3
"""v2 集中验收。"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from core.crypto import decrypt_file
from core.merge import dedupe_preserve_order
from core.stores.item_store import ItemDataStore
from core.stores.weapon_evolution_store import WeaponEvolutionStore
from core.workspace import SaveWorkspace
from engine.patch_plan import PatchPlan
from engine.patch_runner import PatchRunner

INPUT = ROOT / "输入"
REF = ROOT / "参考"
OUT = ROOT / "tests" / "_test_v2_output"
RAW = ROOT.parent / "历史归档" / "原始dat内容"


def _clean() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)


def test_workspace_diff() -> None:
    snap = SaveWorkspace(INPUT, REF).load()
    assert snap.ref_diff_lines
    text = str(snap.ref_diff_lines)
    # 输入与参考相同时无「将新增」；不同时至少应出现某类增量字段
    if "无新增" in text or "参考未载入" in text or "不可用" in text:
        print("⊘ 参考差异摘要（输入≈参考或未载入）", snap.ref_diff_lines[0][:60])
        return
    assert "mythicWeapons" in text or "weapon_evolution" in text or "将新增" in text
    print("✓ 参考差异摘要", snap.ref_diff_lines[0][:60])


def test_dedupe_item_unlock() -> None:
    lst = ["a", "b", "a", "c", "b"]
    n = dedupe_preserve_order(lst)
    assert n == 2 and lst == ["a", "b", "c"]
    print("✓ itemUnlock 去重")


def test_item_unlock_toggle() -> None:
    _clean()
    snap = SaveWorkspace(INPUT, REF).load()
    unlock = list(snap.item_snapshot.get("itemUnlock") or [])
    picks: dict[str, bool] = {}
    if "plant_pot6" in unlock:
        picks["plant_pot6"] = False
    else:
        picks["plant_pot6"] = True
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(item_unlock_picks=picks))
    item = ItemDataStore.load(OUT / f"item_data_{report['uid']}_.data")
    after = item.data.get("itemUnlock") or []
    if picks["plant_pot6"]:
        assert "plant_pot6" in after
    else:
        assert "plant_pot6" not in after
    print("✓ itemUnlock 勾选/取消", picks)


def test_merge_mythic() -> None:
    _clean()
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(merge_mythic_weapons=True))
    item = ItemDataStore.load(OUT / f"item_data_{report['uid']}_.data")
    myth = item.data.get("mythicWeapons") or []
    assert len(myth) >= 28
    ids = {int(m["id"]) for m in myth}
    assert len(ids) == len(myth)
    print("✓ 神话武器 merge", len(myth))


def test_merge_materials_union() -> None:
    _clean()
    snap = SaveWorkspace(INPUT, REF).load()
    before = len(snap.item_snapshot["materials"])
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(merge_materials=True))
    item = ItemDataStore.load(OUT / f"item_data_{report['uid']}_.data")
    after = len(item.data.get("materials") or {})
    assert after >= before
    print("✓ 材料 union", before, "->", after)


def test_merge_weapon_evolution() -> None:
    _clean()
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(merge_weapon_evolution=True))
    uid = report["uid"]
    we_path = OUT / f"weapon_evolution_data_{uid}_.data"
    assert we_path.is_file()
    we = WeaponEvolutionStore.load(we_path)
    assert len(we.data.get("weapons") or {}) >= 90
    decrypt_file(we_path)
    print("✓ 武器进化 union", len(we.data["weapons"]))


def test_weapon_evolution_max_only() -> None:
    src = RAW
    if not (src / "weapon_evolution_data_35924932_.data").is_file():
        print("⊘ 跳过 weapon max（原始目录无武器进化档）")
        return
    _clean()
    runner = PatchRunner(src, OUT, REF)
    snap = SaveWorkspace(src, REF).load()
    before = WeaponEvolutionStore.load(
        src / f"weapon_evolution_data_{snap.uid}_.data"
    ).data["weapons"]["weapon_007"]["Level"]
    report = runner.apply(
        PatchPlan(merge_weapon_evolution=False, merge_weapon_evolution_max=True)
    )
    we = WeaponEvolutionStore.load(
        OUT / f"weapon_evolution_data_{report['uid']}_.data"
    )
    after = we.data["weapons"]["weapon_007"]["Level"]
    assert after >= before
    print("✓ 武器进化 Level max", before, "->", after)


def test_plant_patch() -> None:
    _clean()
    snap = SaveWorkspace(INPUT, REF).load()
    plants = snap.item_snapshot.get("plants") or []
    if not plants:
        print("⊘ 跳过 plant patch（无 plants）")
        return
    pick = [{"index": 0, "state": int(plants[0].get("state", 0)) if plants[0] else 1}]
    if plants[0]:
        new_state = 2 if pick[0]["state"] != 2 else 3
        pick[0]["state"] = new_state
        pick[0]["plantName"] = plants[0].get("plantName")
        pick[0]["watered"] = plants[0].get("watered", False)
        pick[0]["fertilized"] = plants[0].get("fertilized", False)
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(plant_picks=pick))
    item = ItemDataStore.load(OUT / f"item_data_{report['uid']}_.data")
    assert item.data["plants"][0]["state"] == pick[0]["state"]
    print("✓ 花圃 plants 编辑")


def test_room_levels() -> None:
    _clean()
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(room_object_levels={"Chest": 5}))
    data, _ = decrypt_file(OUT / "game.data")
    assert data["roomObjectLevel"]["Chest"] == 5
    assert "weapon_evolution" not in str(report["output_files"])
    print("✓ 客厅设施仅输出 game.data")


def test_inspect_summaries() -> None:
    snap = SaveWorkspace(INPUT, REF).load()
    assert isinstance(snap.shard_summaries, dict)
    print("✓ 分片摘要", len(snap.shard_summaries), "项")


def test_gui_room_levels_delta() -> None:
    from PySide6.QtCore import Qt
    from PySide6.QtWidgets import QApplication

    app = QApplication.instance() or QApplication([])
    snap = SaveWorkspace(INPUT, REF).load()
    from gui.tabs.room_tab import RoomTab

    tab = RoomTab()
    tab.load_from_snapshot(snap.room_object_levels)
    for row in range(tab.levels_table.rowCount()):
        item = tab.levels_table.item(row, 0)
        key = item.data(Qt.UserRole) if item else None
        if key == "Chest":
            from gui.widgets.table_style import cell_spinbox

            spin = cell_spinbox(tab.levels_table, row, 1)
            assert spin is not None
            spin.setValue(5)
            delta = tab.levels_delta()
            assert delta.get("Chest") == 5
            break
    else:
        raise AssertionError("未找到 Chest 设施行")
    print("✓ GUI 客厅设施 levels_delta")


def test_room_levels_zero_no_false_delta() -> None:
    from PySide6.QtWidgets import QApplication

    app = QApplication.instance() or QApplication([])
    snap = SaveWorkspace(INPUT, REF).load()
    from gui.tabs.room_tab import RoomTab

    tab = RoomTab()
    tab.load_from_snapshot(snap.room_object_levels)
    assert tab.levels_delta() == {}, tab.levels_delta()
    print("✓ 客厅 0 级不误报变更")


def test_one_click_plan_no_room_spillover() -> None:
    from PySide6.QtWidgets import QApplication

    app = QApplication.instance() or QApplication([])
    from gui.app import MainWindow

    win = MainWindow()
    win.chk_chars.setChecked(True)
    win.chk_skins.setChecked(True)
    win.chk_pets.setChecked(True)
    win.chk_pots.setChecked(True)
    plan = win._build_plan()
    assert not plan.room_object_levels, plan.room_object_levels
    assert plan.all_characters and plan.all_skins and plan.all_pets and plan.plant_pots
    print("✓ 一键预设不夹带客厅设施")


def test_preset_all_characters() -> None:
    _clean()
    report = PatchRunner(INPUT, OUT, REF).apply(
        PatchPlan(all_characters=True, default_level=8)
    )
    from core.stores.game_store import GameDataStore

    game = GameDataStore.load(OUT / "game.data")
    assert all(game.data["heroUnlock"].values())
    assert all(v >= 8 for v in game.data["heroLevel"].values())
    print("✓ 一键全角色", report["stats"].get("characters"))


def test_legacy_item_merge_syncs_xml_heroes() -> None:
    """改物品切 Legacy 时，须把 game.data 角色解锁同步到 XML。"""
    import re

    from core.constants import PREFS_NAME
    from core.crypto import decrypt_file
    from core.stores.game_store import GameDataStore

    _clean()
    PatchRunner(INPUT, OUT, REF).apply(PatchPlan(merge_blueprints=True))
    game, _ = decrypt_file(OUT / "game.data")
    hero_index = GameDataStore.hero_index_map(game)
    xml = (OUT / PREFS_NAME).read_text(encoding="utf-8")
    from core.stores.prefs_store import detect_uid

    uid = detect_uid(OUT / PREFS_NAME)
    prefix = f"{uid}_"
    mismatches = 0
    for hero, cidx in hero_index.items():
        game_unlocked = bool(game["heroUnlock"].get(hero))
        match = re.search(rf'{re.escape(prefix)}c{cidx}_unlock">([^<]+)', xml)
        xml_unlocked = (match.group(1) if match else "").lower() == "true"
        if game_unlocked != xml_unlocked:
            mismatches += 1
    assert mismatches == 0, f"game/xml 角色解锁不一致 {mismatches} 项"
    assert f"{prefix}has_tutorial" in xml or f"{prefix}first_play" in xml
    setting_path = OUT / f"setting_{uid}_.data"
    # setting 分片为可选：输入无 setting 时不写出（符合生产逻辑）
    if setting_path.is_file():
        setting, _ = decrypt_file(setting_path)
        assert setting.get("UseRijData") is False
        print("✓ Legacy 物品修改同步 XML 角色解锁（含 setting）")
    else:
        print("✓ Legacy 物品修改同步 XML 角色解锁（输入无 setting，已跳过 setting 断言）")


def test_xml_unlock_uses_title_case() -> None:
    """角色/宠物解锁须写 True/False，非 true/false。"""
    import re
    from collections import Counter

    from core.constants import PREFS_NAME

    _clean()
    PatchRunner(INPUT, OUT, REF).apply(PatchPlan(all_characters=True, default_level=7))
    xml = (OUT / PREFS_NAME).read_text(encoding="utf-8")
    char_vals = re.findall(r"c\d+_unlock\">([^<]+)", xml)
    assert "true" not in char_vals and "false" not in char_vals
    unlocked = [v for v in char_vals if v.lower() == "true"]
    assert unlocked and all(v == "True" for v in unlocked)

    _clean()
    PatchRunner(INPUT, OUT, REF).apply(PatchPlan(all_pets=True))
    xml = (OUT / PREFS_NAME).read_text(encoding="utf-8")
    pet_vals = re.findall(r"p\d+_unlock\">([^<]+)", xml)
    assert Counter(pet_vals) == Counter({"True": len(pet_vals)})
    print("✓ XML 解锁布尔值 True/False 格式")


def test_preset_all_skins() -> None:
    _clean()
    report = PatchRunner(INPUT, OUT, REF).apply(PatchPlan(all_skins=True))
    from core.stores.game_store import GameDataStore

    game = GameDataStore.load(OUT / "game.data")
    owned = sum(1 for ss in game.data["skinLock"].values() for s in ss if s.get("Value") == 1)
    total = sum(len(ss) for ss in game.data["skinLock"].values())
    assert owned == total
    print("✓ 一键全皮肤", owned, "xml", report["stats"].get("skins"))


def test_preset_all_pets() -> None:
    _clean()
    report = PatchRunner(INPUT, OUT, REF).apply(PatchPlan(all_pets=True))
    from core.stores.game_store import GameDataStore

    game = GameDataStore.load(OUT / "game.data")
    assert all(game.data["petUnlock"].values())
    print("✓ 一键全宠物", report["stats"].get("pets"))


def test_materials_max_requires_union() -> None:
    plan = PatchPlan(merge_materials_max=True)
    assert not plan.touches_item_reference_merge()
    assert not plan.any_change()
    _clean()
    report = PatchRunner(INPUT, OUT, REF).apply(plan)
    assert "item_data" not in str(report["output_files"])
    print("✓ 材料 max 须配合 union")


def test_gui_garden_plants_delta() -> None:
    from PySide6.QtWidgets import QApplication

    app = QApplication.instance() or QApplication([])
    snap = SaveWorkspace(INPUT, REF).load()
    plants = snap.item_snapshot.get("plants") or []
    if not plants:
        print("⊘ 跳过 GUI plants delta（无 plants）")
        return
    from gui.tabs.garden_tab import GardenTab
    from gui.widgets.table_style import cell_spinbox

    tab = GardenTab()
    tab.load_from_snapshot(snap.item_snapshot.get("itemUnlock", []), plants)
    orig = int(plants[0].get("state", 0))
    new_state = 2 if orig != 2 else 3
    spin = cell_spinbox(tab.plants_table, 0, 2)
    assert spin is not None
    spin.setValue(new_state)
    delta = tab.plants_delta()
    assert delta and delta[0]["index"] == 0 and delta[0]["state"] == new_state
    print("✓ GUI 花圃 plants_delta")


def main() -> None:
    tests = [
        test_workspace_diff,
        test_dedupe_item_unlock,
        test_item_unlock_toggle,
        test_merge_mythic,
        test_merge_materials_union,
        test_merge_weapon_evolution,
        test_weapon_evolution_max_only,
        test_plant_patch,
        test_room_levels,
        test_inspect_summaries,
        test_gui_room_levels_delta,
        test_gui_garden_plants_delta,
        test_room_levels_zero_no_false_delta,
        test_one_click_plan_no_room_spillover,
        test_preset_all_characters,
        test_legacy_item_merge_syncs_xml_heroes,
        test_xml_unlock_uses_title_case,
        test_preset_all_skins,
        test_preset_all_pets,
        test_materials_max_requires_union,
    ]
    failed = 0
    for fn in tests:
        try:
            fn()
        except Exception as e:
            failed += 1
            print("✗", fn.__name__, e)
    if failed:
        print(f"\n失败 {failed}")
        sys.exit(1)
    print(f"\nv2 全部通过")


if __name__ == "__main__":
    main()