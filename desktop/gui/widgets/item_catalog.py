"""物品目录：已拥有优先，再未拥有，均按字母序。"""
from __future__ import annotations


def sort_quantity_keys(save: dict[str, int], ref: dict[str, int]) -> list[str]:
    all_keys = set(save) | set(ref)
    owned = sorted(k for k in all_keys if int(save.get(k, 0)) > 0)
    unowned = sorted(k for k in all_keys if int(save.get(k, 0)) <= 0)
    return owned + unowned


def is_quantity_owned(save: dict[str, int], key: str) -> bool:
    return int(save.get(key, 0)) > 0


def sort_blueprint_keys(save: dict[str, str], ref: dict[str, str]) -> list[str]:
    all_keys = set(save) | set(ref)
    owned = sorted(k for k in all_keys if save.get(k) == "Got")
    unowned = sorted(k for k in all_keys if save.get(k) != "Got")
    return owned + unowned


def is_blueprint_owned(save: dict[str, str], key: str) -> bool:
    return save.get(key) == "Got"