#!/usr/bin/env python3
"""v3 武器系统验收。"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from core.constants import WEAPON_FORGE_UNLOCK_PLUS
from core.stores.statistic_store import StatisticStore
from core.workspace import SaveWorkspace
from engine.patch_plan import PatchPlan
from engine.patch_runner import PatchRunner

INPUT = ROOT / "输入"
REF = ROOT / "参考"
OUT = ROOT / "tests" / "_test_v3_output"
RAW_STAT = ROOT.parent / "历史归档" / "原始dat内容" / "statistic_35924932_.data"


def _ensure_statistic_in_input() -> None:
    uid = None
    prefs = list(INPUT.glob("com.ChillyRoom*.xml"))
    if prefs:
        from core.stores.prefs_store import detect_uid

        uid = detect_uid(prefs[0])
    dest = INPUT / f"statistic_{uid}_.data" if uid else None
    if dest and not dest.is_file() and RAW_STAT.is_file():
        shutil.copy2(RAW_STAT, dest)
        print(f"· 已复制测试用 statistic → {dest.name}")


def _clean() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)


def test_workspace_statistic_snapshot() -> None:
    snap = SaveWorkspace(INPUT, REF).load()
    assert "weapon_used_times" in snap.statistic_snapshot
    print("✓ statistic 快照", snap.statistic_snapshot.get("weapon_used_count"))


def test_patch_weapon_used() -> None:
    _clean()
    snap = SaveWorkspace(INPUT, REF).load()
    used = snap.statistic_snapshot.get("weapon_used_times") or {}
    target = next(iter(used), "weapon_184")
    new_val = int(used.get(target, 0)) + 3
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(weapon_used_picks={target: new_val}))
    stat = StatisticStore.load(OUT / f"statistic_{report['uid']}_.data")
    assert int(stat.weapon_used_times()[target]) == new_val
    print("✓ 写出武器获取次数", target, new_val)


def test_unlock_weapon_forge_plus8() -> None:
    _clean()
    snap = SaveWorkspace(INPUT, REF).load()
    used = snap.statistic_snapshot.get("weapon_used_times") or {}
    sample = next(iter(used), "weapon_184")
    before = int(used.get(sample, 0))
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(unlock_weapon_forge=True))
    stat = StatisticStore.load(OUT / f"statistic_{report['uid']}_.data")
    after = int(stat.weapon_used_times()[sample])
    assert after == before + WEAPON_FORGE_UNLOCK_PLUS
    print(f"✓ 常规武器锻造 +{WEAPON_FORGE_UNLOCK_PLUS}", sample, f"{before} -> {after}")


def test_merge_weapon_used_ref() -> None:
    _clean()
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(merge_weapon_used_times=True))
    stat = StatisticStore.load(OUT / f"statistic_{report['uid']}_.data")
    used = stat.weapon_used_times()
    assert len(used) > 10
    print("✓ 参考 union 武器获取次数", len([k for k in used if k.startswith("weapon_")]))


def test_statistic_only_output() -> None:
    _clean()
    runner = PatchRunner(INPUT, OUT, REF)
    report = runner.apply(PatchPlan(unlock_weapon_forge=True))
    names = set(report["output_files"])
    assert any("statistic" in n for n in names)
    assert not any("item_data" in n for n in names)
    print("✓ 仅 statistic 输出", report["output_files"])


def main() -> None:
    _ensure_statistic_in_input()
    tests = [
        test_workspace_statistic_snapshot,
        test_patch_weapon_used,
        test_unlock_weapon_forge_plus8,
        test_merge_weapon_used_ref,
        test_statistic_only_output,
    ]
    failed = 0
    for fn in tests:
        try:
            fn()
        except Exception as exc:
            failed += 1
            print(f"✗ {fn.__name__}: {exc}")
    if failed:
        sys.exit(1)
    print(f"\n全部通过 ({len(tests)}/{len(tests)})")


if __name__ == "__main__":
    main()