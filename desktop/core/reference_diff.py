"""本号 vs 参考号差异摘要。"""
from __future__ import annotations

from typing import Any


def _ids_mythic(items: list[Any]) -> set[int]:
    return {int(w["id"]) for w in items if isinstance(w, dict) and "id" in w}


def compute_reference_diff(
    item_self: dict[str, Any] | None,
    item_ref: dict[str, Any] | None,
    we_self: dict[str, Any] | None,
    we_ref: dict[str, Any] | None,
) -> dict[str, Any]:
    diff: dict[str, Any] = {"lines": [], "counts": {}}
    if not item_ref:
        diff["lines"].append("参考 item_data 不可用")
        return diff

    is_ = item_self or {}
    ir = item_ref
    counts: dict[str, int] = {}

    def _dict_delta(field: str, label: str) -> None:
        a = is_.get(field) or {}
        b = ir.get(field) or {}
        if isinstance(a, dict) and isinstance(b, dict):
            n = len(set(b) - set(a))
            if n:
                counts[label] = n

    _dict_delta("blueprints", "blueprints")
    _dict_delta("seeds", "seeds")
    _dict_delta("materials", "materials")

    myth_a = _ids_mythic(is_.get("mythicWeapons") or [])
    myth_b = _ids_mythic(ir.get("mythicWeapons") or [])
    if len(myth_b - myth_a):
        counts["mythicWeapons"] = len(myth_b - myth_a)

    forge_a = {x for x in (is_.get("forgeWeapons") or []) if x}
    forge_b = {x for x in (ir.get("forgeWeapons") or []) if x}
    if len(forge_b - forge_a):
        counts["forgeWeapons"] = len(forge_b - forge_a)

    unlock_a = set(is_.get("itemUnlock") or [])
    unlock_b = set(ir.get("itemUnlock") or [])
    if len(unlock_b - unlock_a):
        counts["itemUnlock"] = len(unlock_b - unlock_a)

    we_a = (we_self or {}).get("weapons") or {}
    we_b = (we_ref or {}).get("weapons") or {}
    if isinstance(we_a, dict) and isinstance(we_b, dict):
        n = len(set(we_b) - set(we_a))
        if n:
            counts["weapon_evolution"] = n

    diff["counts"] = counts
    if counts:
        parts = [f"{k} +{v}" for k, v in sorted(counts.items())]
        diff["lines"].append("相对参考号将新增：" + " · ".join(parts))
    else:
        diff["lines"].append("相对参考号无新增项（或参考未载入）")
    return diff