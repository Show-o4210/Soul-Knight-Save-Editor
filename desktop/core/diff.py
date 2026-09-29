"""变更预览摘要。"""
from __future__ import annotations

from typing import Any


def summarize_changes(before: dict[str, Any], after: dict[str, Any]) -> list[str]:
    lines: list[str] = []
    for key in sorted(set(before) | set(after)):
        b, a = before.get(key), after.get(key)
        if b == a:
            continue
        if isinstance(b, (int, float, str, bool)) or b is None:
            lines.append(f"{key}: {b!r} → {a!r}")
        elif isinstance(b, list) and isinstance(a, list):
            lines.append(f"{key}: list {len(b)} → {len(a)}")
        elif isinstance(b, dict) and isinstance(a, dict):
            lines.append(f"{key}: dict {len(b)} → {len(a)} keys")
        else:
            lines.append(f"{key}: 已变更")
    return lines


def dict_key_diff(
    before: dict[str, Any],
    after: dict[str, Any],
    *,
    prefix: str = "",
    limit: int = 30,
) -> list[str]:
    lines: list[str] = []
    keys = sorted(set(before) | set(after))
    for key in keys:
        b, a = before.get(key), after.get(key)
        if b == a:
            continue
        label = f"{prefix}{key}" if prefix else key
        lines.append(f"{label}: {b!r} → {a!r}")
        if len(lines) >= limit:
            lines.append(f"… 另有 {len(keys) - limit} 项未列出")
            break
    return lines


def list_append_preview(before: list[Any], after: list[Any], *, label: str, limit: int = 15) -> list[str]:
    if before == after:
        return []
    bset = set(before)
    added = [x for x in after if x not in bset]
    lines = [f"{label}: {len(before)} → {len(after)} 项"]
    if added:
        shown = added[:limit]
        lines.append(f"  新增: {', '.join(map(str, shown))}")
        if len(added) > limit:
            lines.append(f"  … 另有 {len(added) - limit} 项")
    return lines