"""武器 ID / 蓝图键解析与聚合。"""
from __future__ import annotations

import re
from typing import Any

WEAPON_BLUEPRINT_PREFIXES = (
    "blueprint_weapon_",
    "blueprint_evolution_weapon_",
    "blueprint_transform_weapon_",
)

_WEAPON_ID_RE = re.compile(r"^weapon_[\w]+$")


def is_weapon_blueprint(key: str) -> bool:
    return any(key.startswith(p) for p in WEAPON_BLUEPRINT_PREFIXES)


def weapon_id_from_blueprint(key: str) -> str | None:
    if key.startswith("blueprint_weapon_"):
        return f"weapon_{key.removeprefix('blueprint_weapon_')}"
    if key.startswith("blueprint_evolution_weapon_"):
        return f"weapon_{key.removeprefix('blueprint_evolution_weapon_')}"
    if key.startswith("blueprint_transform_weapon_"):
        return key.removeprefix("blueprint_transform_weapon_")
    return None


def is_weapon_id(key: str) -> bool:
    return bool(_WEAPON_ID_RE.match(key))


def filter_weapon_blueprints(blueprints: dict[str, str]) -> dict[str, str]:
    return {k: v for k, v in blueprints.items() if is_weapon_blueprint(k)}


def collect_weapon_ids(
    *,
    used_times: dict[str, Any] | None = None,
    built_times: dict[str, Any] | None = None,
    blueprints: dict[str, str] | None = None,
    forge_weapons: list[Any] | None = None,
    evolution_weapons: dict[str, Any] | None = None,
    ref_used: dict[str, Any] | None = None,
) -> list[str]:
    """合并各来源的武器 ID，排序去重（不含 Any 等汇总键）。"""
    ids: set[str] = set()
    for bucket in (used_times, built_times, ref_used):
        if isinstance(bucket, dict):
            ids.update(k for k in bucket if is_weapon_id(k))
    if isinstance(blueprints, dict):
        for key in blueprints:
            wid = weapon_id_from_blueprint(key)
            if wid and is_weapon_id(wid):
                ids.add(wid)
            elif is_weapon_id(key):
                ids.add(key)
    if isinstance(forge_weapons, list):
        ids.update(w for w in forge_weapons if w and is_weapon_id(str(w)))
    if isinstance(evolution_weapons, dict):
        ids.update(k for k in evolution_weapons if is_weapon_id(k))
    return sorted(ids)