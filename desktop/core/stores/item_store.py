from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from core.constants import MAX_QUANTITY, is_encoded_quantity
from core.crypto import decrypt_file, encrypt_item_data
from core.merge import (
    append_forge_weapons,
    append_unique_list,
    dedupe_preserve_order,
    deep_copy,
    union_dict,
    union_list_unique,
    union_materials,
    union_mythic_weapons,
)


ITEM_MIRROR_FIELDS = (
    "itemUnlock",
    "materials",
    "seeds",
    "blueprints",
    "plants",
    "mythicWeapons",
    "forgeWeapons",
    "jewelryData",
    "jewelryBlueprints",
    "roomDecorateDic",
    "roomDecorateCurSkinDic",
)


class ItemDataStore:
    def __init__(self, data: dict[str, Any], source_path: Path | None = None) -> None:
        self.data = data
        self.source_path = source_path

    @classmethod
    def load(cls, path: Path) -> ItemDataStore:
        data, _ = decrypt_file(path)
        return cls(data, path)

    @classmethod
    def from_game_mirror(cls, game_data: dict[str, Any]) -> ItemDataStore:
        return cls(deep_copy(game_data.get("itemData", {})))

    def save(self, path: Path) -> None:
        enc = encrypt_item_data(self.data, self.source_path or path)
        path.write_text(enc, encoding="utf-8")

    def save_json(self, path: Path) -> None:
        path.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")

    def patch_plant_pots(self, slots: list[int]) -> int:
        unlock = self.data.setdefault("itemUnlock", [])
        if not isinstance(unlock, list):
            unlock = list(unlock)
        additions = [f"plant_pot{n}" for n in slots if n > 2]
        added = append_unique_list(unlock, additions)
        self.data["itemUnlock"] = unlock
        dedupe_preserve_order(unlock)
        return added

    def patch_item_unlock_append(self, items: list[str]) -> int:
        unlock = self.data.setdefault("itemUnlock", [])
        if not isinstance(unlock, list):
            unlock = list(unlock)
        added = append_unique_list(unlock, items)
        self.data["itemUnlock"] = unlock
        dedupe_preserve_order(unlock)
        return added

    def patch_item_unlock_toggle(self, picks: dict[str, bool]) -> int:
        """勾选/取消 itemUnlock 项（含花圃盆位）。"""
        unlock = self.data.setdefault("itemUnlock", [])
        if not isinstance(unlock, list):
            unlock = list(unlock)
        changed = 0
        for key, want in picks.items():
            has = key in unlock
            if want and not has:
                unlock.append(key)
                changed += 1
            elif not want and has:
                unlock[:] = [x for x in unlock if x != key]
                changed += 1
        dedupe_preserve_order(unlock)
        self.data["itemUnlock"] = unlock
        return changed

    def patch_quantities(self, field: str, picks: dict[str, int]) -> int:
        bucket = self.data.setdefault(field, {})
        if not isinstance(bucket, dict):
            bucket = dict(bucket)
        changed = 0
        for key, qty in picks.items():
            qty = max(0, min(int(qty), MAX_QUANTITY))
            if bucket.get(key) != qty:
                bucket[key] = qty
                changed += 1
        self.data[field] = bucket
        return changed

    def patch_blueprints(self, picks: dict[str, bool]) -> int:
        bucket = self.data.setdefault("blueprints", {})
        if not isinstance(bucket, dict):
            bucket = dict(bucket)
        changed = 0
        for key, owned in picks.items():
            if owned:
                if bucket.get(key) != "Got":
                    bucket[key] = "Got"
                    changed += 1
            elif key in bucket:
                del bucket[key]
                changed += 1
        self.data["blueprints"] = bucket
        return changed

    def patch_plants(self, picks: list[dict[str, Any]]) -> int:
        """按槽位索引更新 plants；state 错值可能导致存档异常，调用方需提示用户。"""
        plants = self.data.setdefault("plants", [])
        if not isinstance(plants, list):
            plants = list(plants)
        changed = 0
        for pick in picks:
            idx = int(pick["index"])
            while len(plants) <= idx:
                plants.append(None)
            cur = plants[idx]
            if pick.get("clear"):
                if cur is not None:
                    plants[idx] = None
                    changed += 1
                continue
            entry = dict(cur) if isinstance(cur, dict) else {}
            for key in ("plantName", "state", "watered", "fertilized"):
                if key in pick:
                    entry[key] = pick[key]
            if plants[idx] != entry:
                plants[idx] = entry
                changed += 1
        self.data["plants"] = plants
        return changed

    def merge_from_reference(
        self,
        ref: dict[str, Any],
        *,
        blueprints: bool = False,
        seeds: bool = False,
        item_unlock: bool = False,
        mythic_weapons: bool = False,
        materials: bool = False,
        materials_max: bool = False,
        forge_weapons: bool = False,
        jewelry: bool = False,
        room_decorate: bool = False,
    ) -> dict[str, int]:
        stats: dict[str, int] = {}
        if blueprints and isinstance(ref.get("blueprints"), dict):
            stats["blueprints"] = union_dict(self.data.setdefault("blueprints", {}), ref["blueprints"])
        if seeds and isinstance(ref.get("seeds"), dict):
            stats["seeds"] = union_dict(self.data.setdefault("seeds", {}), ref["seeds"])
        if item_unlock and isinstance(ref.get("itemUnlock"), list):
            unlock = self.data.setdefault("itemUnlock", [])
            stats["itemUnlock"] = append_unique_list(unlock, ref["itemUnlock"])
            stats["itemUnlock_deduped"] = dedupe_preserve_order(unlock)
            self.data["itemUnlock"] = unlock
        if mythic_weapons and isinstance(ref.get("mythicWeapons"), list):
            myth = self.data.setdefault("mythicWeapons", [])
            stats["mythicWeapons"] = union_mythic_weapons(myth, ref["mythicWeapons"])
            self.data["mythicWeapons"] = myth
        if materials and isinstance(ref.get("materials"), dict):
            mats = self.data.setdefault("materials", {})
            skip = {
                k for k, v in mats.items()
                if isinstance(v, (int, float)) and is_encoded_quantity(int(v))
            }
            mst = union_materials(mats, ref["materials"], use_max=materials_max, skip_keys=skip)
            stats["materials_added"] = mst["added"]
            if materials_max:
                stats["materials_maxed"] = mst["maxed"]
        if forge_weapons and isinstance(ref.get("forgeWeapons"), list):
            forge = self.data.setdefault("forgeWeapons", [])
            stats["forgeWeapons"] = append_forge_weapons(forge, ref["forgeWeapons"])
            self.data["forgeWeapons"] = forge
        if jewelry:
            if isinstance(ref.get("jewelryData"), dict):
                stats["jewelryData"] = union_dict(self.data.setdefault("jewelryData", {}), ref["jewelryData"])
            if isinstance(ref.get("jewelryBlueprints"), list):
                jb = self.data.setdefault("jewelryBlueprints", [])
                stats["jewelryBlueprints"] = union_list_unique(jb, ref["jewelryBlueprints"])
                self.data["jewelryBlueprints"] = jb
        if room_decorate:
            if isinstance(ref.get("roomDecorateDic"), dict):
                stats["roomDecorateDic"] = union_dict(
                    self.data.setdefault("roomDecorateDic", {}), ref["roomDecorateDic"]
                )
            if isinstance(ref.get("roomDecorateCurSkinDic"), dict):
                stats["roomDecorateCurSkinDic"] = union_dict(
                    self.data.setdefault("roomDecorateCurSkinDic", {}), ref["roomDecorateCurSkinDic"]
                )
        return stats

    def snapshot(self) -> dict[str, Any]:
        materials = self.data.get("materials") or {}
        seeds = self.data.get("seeds") or {}
        blueprints = self.data.get("blueprints") or {}
        mythic = self.data.get("mythicWeapons") or []
        return {
            "itemUnlock": list(self.data.get("itemUnlock") or []),
            "materials": dict(materials) if isinstance(materials, dict) else {},
            "seeds": dict(seeds) if isinstance(seeds, dict) else {},
            "blueprints": dict(blueprints) if isinstance(blueprints, dict) else {},
            "materials_count": len(materials),
            "blueprints_count": len(blueprints),
            "seeds_count": len(seeds),
            "mythic_weapons_count": len(mythic) if isinstance(mythic, list) else 0,
            "forge_weapons_count": len([x for x in (self.data.get("forgeWeapons") or []) if x]),
            "plants": list(self.data.get("plants") or []),
            "plants_active": sum(1 for p in (self.data.get("plants") or []) if p),
        }