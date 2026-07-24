"""工作台展示格式化 — 统一文案与结构。"""
from __future__ import annotations

from core.constants import (
    DEVICE_FILES_DIR,
    IOS_DEVICE_FILES_DIR,
    DEVICE_SHARED_PREFS_DIR,
    IOS_DEVICE_SHARED_PREFS_DIR,
    PREFS_NAME,
    IOS_PREFS_NAME,
    device_source_path,
    format_missing_file_compact,
)
from core.shard_registry import shard_filename
from gui.i18n.loader import tr


def summary_pairs(snap) -> list[tuple[str, str]]:
    gs = snap.game_summary
    is_ = snap.item_snapshot
    we_ = snap.weapon_evolution_snapshot
    pairs = [
        (tr("workspace.kv.channel"), snap.cloud_save_id or "—"),
        (tr("workspace.kv.materials"), str(is_.get("materials_count", 0))),
        (tr("workspace.kv.blueprints"), str(is_.get("blueprints_count", 0))),
        (tr("workspace.kv.seeds"), str(is_.get("seeds_count", 0))),
        (tr("workspace.kv.plants"), tr(
            "workspace.kv.plants_fmt",
            active=is_.get("plants_active", 0),
            total=len(is_.get("plants") or []),
        )),
        (tr("workspace.kv.mythic"), str(is_.get("mythic_weapons_count", 0))),
        (tr("workspace.kv.weapons"), str(we_.get("weapon_count", 0))),
        (tr("workspace.kv.open_rij"), str(snap.open_rij_test if snap.open_rij_test is not None else "—")),
        (tr("workspace.kv.gems_game"), str(gs.get("gems", "—"))),
        (tr("workspace.kv.gems_xml"), str(snap.xml_gems if snap.xml_gems is not None else "—")),
    ]
    unlock = is_.get("itemUnlock") or []
    if unlock:
        pairs.insert(4, (tr("workspace.kv.unlock"), str(len(unlock))))
    return pairs


def _chip_tip(name: str, ok: bool) -> str:
    """芯片悬浮提示：文件名 + 手机路径（缺失时额外说明）。"""
    path = device_source_path(name)
    if ok:
        return f"{name}\n手机: {path}"
    return format_missing_file_compact(name)


def input_file_chips(snap) -> list[tuple[str, str, bool]]:
    uid = snap.uid
    specs = [
        ("game.data", "game.data", bool(snap.game_path)),
        ("XML", PREFS_NAME, bool(snap.prefs_path)),
        ("item", shard_filename("item_data", uid), snap.item_path is not None),
        ("setting", shard_filename("setting", uid), snap.setting_path is not None),
        (
            tr("workspace.chip.weapon"),
            shard_filename("weapon_evolution_data", uid),
            snap.weapon_evolution_path is not None,
        ),
        (
            "statistic",
            shard_filename("statistic", uid),
            bool(getattr(snap, "statistic_path", None)),
        ),
    ]
    return [(short, _chip_tip(name, ok), ok) for short, name, ok in specs]


def reference_file_chips(snap) -> list[tuple[str, str, bool]]:
    return [
        (tr("workspace.chip.ref_item"), tr("workspace.chip.ref_item_tip"), snap.ref_item_loaded),
        (tr("workspace.chip.ref_weapon"), tr("workspace.chip.ref_weapon_tip"), snap.ref_weapon_loaded),
    ]


def notice_lines(snap) -> list[tuple[str, str]]:
    lines: list[tuple[str, str]] = []
    # 始终提示手机源路径（便于首次使用者）
    lines.append((
        "info",
        tr(
            "workspace.device_paths",
            files=DEVICE_FILES_DIR,
            prefs=DEVICE_SHARED_PREFS_DIR,
        ),
    ))
    lines.append((
        "info",
        tr(
            "workspace.ios_device_paths",
            files=IOS_DEVICE_FILES_DIR,
            prefs=IOS_DEVICE_SHARED_PREFS_DIR,
        ),
    ))
    for w in snap.warnings or []:
        lines.append(("warn", w))
    for d in snap.ref_diff_lines or []:
        if d.strip():
            lines.append(("info", d))
    return lines
