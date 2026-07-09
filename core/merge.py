"""增量合并策略 — 扩展而非替换。"""
from __future__ import annotations

import json
from typing import Any


def append_unique_list(target: list[Any], additions: list[Any]) -> int:
    if not isinstance(target, list):
        target = list(target)
    seen = set(target)
    added = 0
    for item in additions:
        if item not in seen:
            target.append(item)
            seen.add(item)
            added += 1
    return added


def union_dict(base: dict[str, Any], other: dict[str, Any]) -> int:
    added = 0
    for k, v in other.items():
        if k not in base:
            base[k] = json.loads(json.dumps(v, ensure_ascii=False))
            added += 1
    return added


def deep_copy(obj: Any) -> Any:
    return json.loads(json.dumps(obj, ensure_ascii=False))


def dedupe_preserve_order(items: list[Any]) -> int:
    """去重并保留首次出现顺序；返回移除的重复项数。"""
    if not isinstance(items, list):
        return 0
    seen: set[Any] = set()
    out: list[Any] = []
    removed = 0
    for item in items:
        if item in seen:
            removed += 1
            continue
        seen.add(item)
        out.append(item)
    if removed:
        items[:] = out
    return removed


def union_materials(
    base: dict[str, Any],
    other: dict[str, Any],
    *,
    use_max: bool = False,
    skip_keys: set[str] | None = None,
) -> dict[str, int]:
    """材料 union 或 max；返回各操作计数。"""
    stats = {"added": 0, "maxed": 0}
    skip = skip_keys or set()
    for k, v in other.items():
        if k in skip:
            continue
        if k not in base:
            base[k] = json.loads(json.dumps(v, ensure_ascii=False))
            stats["added"] += 1
        elif use_max:
            try:
                cur, ref = int(base[k]), int(v)
                if ref > cur:
                    base[k] = ref
                    stats["maxed"] += 1
            except (TypeError, ValueError):
                pass
    return stats


def union_mythic_weapons(base: list[Any], other: list[Any]) -> int:
    """按 id union 神话武器；新项使用参考完整对象（含 level）。"""
    if not isinstance(base, list):
        base = list(base) if base else []
    by_id = {int(w.get("id")): w for w in base if isinstance(w, dict) and "id" in w}
    added = 0
    for w in other:
        if not isinstance(w, dict) or "id" not in w:
            continue
        wid = int(w["id"])
        if wid not in by_id:
            base.append(deep_copy(w))
            by_id[wid] = w
            added += 1
    return added


def append_forge_weapons(base: list[Any], other: list[Any]) -> int:
    if not isinstance(base, list):
        base = []
    existing = {x for x in base if x}
    added = 0
    for w in other:
        if w and w not in existing:
            base.append(w)
            existing.add(w)
            added += 1
    return added


def union_weapons_dict(
    base: dict[str, Any],
    other: dict[str, Any],
    *,
    use_union: bool = True,
    use_max_level: bool = False,
) -> dict[str, int]:
    """武器进化 weapons 字典 union；可选对共有键取 Level max。"""
    stats = {"added": 0, "maxed": 0}
    for wid, ref_w in other.items():
        if wid not in base:
            if use_union:
                base[wid] = deep_copy(ref_w)
                stats["added"] += 1
        elif use_max_level and isinstance(base[wid], dict) and isinstance(ref_w, dict):
            try:
                cur_lv = int(base[wid].get("Level", 0))
                ref_lv = int(ref_w.get("Level", 0))
                if ref_lv > cur_lv:
                    base[wid]["Level"] = ref_lv
                    stats["maxed"] += 1
            except (TypeError, ValueError):
                pass
    return stats


def union_list_unique(base: list[Any], other: list[Any]) -> int:
    return append_unique_list(base, [x for x in other if x not in base])