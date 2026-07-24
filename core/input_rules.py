"""输入最小化与按需输出规则。"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from core.constants import (
    DEVICE_FILES_DIR,
    IOS_DEVICE_FILES_DIR,
    DEVICE_PREFS_GLOB_HINT,
    IOS_DEVICE_PREFS_GLOB_HINT,
    DEVICE_SHARED_PREFS_DIR,
    IOS_DEVICE_SHARED_PREFS_DIR,
    DUPLICATE_BASENAMES,
    PREFS_NAME,
    IOS_PREFS_NAME,
    Platform,
    format_missing_file_compact,
)
from core.shard_registry import WRITABLE_SHARDS, shard_filename

CORE_FILES = ("game.data", PREFS_NAME)
IOS_CORE_FILES = ("game.data", IOS_PREFS_NAME)

JUNK_NAMES = frozenset({
    "bugly_last_us_up_tm",
    "ace_shell_di.dat",
})

JUNK_SUFFIXES = (".dat",)


@dataclass
class InputAudit:
    platform: Platform
    uid: str
    present_core: list[str] = field(default_factory=list)
    present_optional: list[str] = field(default_factory=list)
    junk: list[str] = field(default_factory=list)
    redundant: list[str] = field(default_factory=list)
    missing_for_plan: list[str] = field(default_factory=list)
    # 缺失的必备文件（文件名列表，便于 GUI 定位）
    missing_core: list[str] = field(default_factory=list)

    def summary_lines(self) -> list[str]:
        if self.platform == Platform.Android:
            lines = [
                "【Android源路径】",
                f"  .data 分片 → {DEVICE_FILES_DIR}/",
                f"  PlayerPrefs XML → {DEVICE_SHARED_PREFS_DIR}/",
                f"  XML 文件名 → {DEVICE_PREFS_GLOB_HINT}",
            ]
        else:
            lines = ["【IOS源路径】",
                f"  .data 分片 → {IOS_DEVICE_FILES_DIR}/",
                f"  PlayerPrefs PList → {IOS_DEVICE_SHARED_PREFS_DIR}/",
                f"  PList 文件名 → {IOS_DEVICE_PREFS_GLOB_HINT}",
                "【必备】",
            ]
        for name in CORE_FILES if self.platform == Platform.Android else IOS_CORE_FILES:
            if name in self.present_core:
                lines.append(f"  ✓  {name}")
            else:
                lines.append(f"  ✗  {format_missing_file_compact(name)}")
        if self.present_optional:
            lines.append("【已放入的可选】")
            for n in self.present_optional:
                lines.append(f"  ✓  {n}")
        if self.junk:
            lines.append("【建议移出输入目录】")
            for n in self.junk:
                lines.append(f"  ⚠  {n}")
        if self.redundant:
            lines.append("【冗余副本（可删）】")
            for n in self.redundant:
                lines.append(f"  ⚠  {n}")
        if self.missing_for_plan:
            lines.append("【当前操作缺少】")
            for n in self.missing_for_plan:
                lines.append(f"  ✗  {n}")
        return lines


@dataclass
class OutputManifest:
    game: bool = False
    prefs: bool = False
    item: bool = False
    setting: bool = False
    shards: dict[str, bool] = field(default_factory=dict)

    def filenames(self, uid: str, platform: Platform) -> list[str]:
        names: list[str] = []
        if self.game:
            names.append("game.data")
        if self.prefs:
            names.append(PREFS_NAME if platform == Platform.Android else IOS_PREFS_NAME)
        if self.item:
            names.append(shard_filename("item_data", uid))
        if self.setting:
            names.append(shard_filename("setting", uid))
        for dtype in WRITABLE_SHARDS:
            if dtype in ("item_data", "setting"):
                continue
            if self.shards.get(dtype):
                names.append(shard_filename(dtype, uid))
        return names


def _is_junk(path: Path) -> bool:
    if path.name in JUNK_NAMES:
        return True
    if path.suffix in JUNK_SUFFIXES and not path.name.endswith(".data"):
        return True
    return False


def audit_input(input_dir: Path, uid: str, *, platform: Platform = Platform.Android, needs_item: bool = False, needs_shards: list[str] | None = None) -> InputAudit:
    audit = InputAudit(uid=uid,platform=platform)
    temp_core_files = CORE_FILES if platform == Platform.Android else IOS_CORE_FILES
    if not input_dir.is_dir():
        audit.missing_core = list(temp_core_files)
        return audit

    names = {p.name for p in input_dir.iterdir() if p.is_file()}
    for core in temp_core_files:
        if core in names:
            audit.present_core.append(core)
        else:
            # prefs 允许类似命名的 xml 顶替
            if core == PREFS_NAME:
                xmls = [n for n in names if n.lower().endswith(".xml")]
                if xmls:
                    audit.present_core.append(core)
                    continue
            # prefs 允许类似命名的 plist 顶替
            if core == IOS_PREFS_NAME:
                plists = [n for n in names if n.lower().endswith(".plist")]
                if plists:
                    audit.present_core.append(core)
                    continue
            audit.missing_core.append(core)

    item_name = shard_filename("item_data", uid)
    setting_name = shard_filename("setting", uid)
    if item_name in names:
        audit.present_optional.append(item_name)
    if setting_name in names:
        audit.present_optional.append(setting_name)

    for p in sorted(input_dir.iterdir()):
        if not p.is_file():
            continue
        if p.name in temp_core_files or p.name in audit.present_optional:
            continue
        if p.name in DUPLICATE_BASENAMES:
            audit.redundant.append(p.name)
        elif _is_junk(p):
            audit.junk.append(p.name)
        elif p.name.endswith(".data"):
            audit.junk.append(p.name)

    if needs_item and item_name not in names:
        audit.missing_for_plan.append(
            format_missing_file_compact(item_name, reason="改物品请放入输入")
        )

    for dtype in needs_shards or []:
        if dtype in ("item_data", "setting"):
            continue
        fname = shard_filename(dtype, uid)
        if fname not in names:
            audit.missing_for_plan.append(
                format_missing_file_compact(fname, reason="当前操作需要")
            )

    return audit


def manifest_for_plan(plan, *, has_item_file: bool, has_setting_file: bool, has_shards: dict[str, bool] | None = None) -> OutputManifest:
    m = OutputManifest()
    game_touch = plan.touches_game()
    item_touch = plan.touches_item()
    prefs_touch = plan.touches_prefs()

    if game_touch or (item_touch and plan.sync_item_mirror):
        m.game = True
    if prefs_touch:
        m.prefs = True
    if item_touch:
        m.item = True
    if plan.touches_setting() and has_setting_file:
        m.setting = True

    shard_flags = has_shards or {}
    if plan.touches_weapon_evolution() and shard_flags.get("weapon_evolution_data", True):
        m.shards["weapon_evolution_data"] = True
    if plan.touches_statistic() and shard_flags.get("statistic", True):
        m.shards["statistic"] = True
    return m
