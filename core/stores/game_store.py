from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from core.constants import MAX_QUANTITY
from core.crypto import decrypt_file, encrypt_game_data
from core.merge import append_unique_list
from core.stores.item_store import ITEM_MIRROR_FIELDS


class GameDataStore:
    def __init__(self, data: dict[str, Any], source_path: Path | None = None) -> None:
        self.data = data
        self.source_path = source_path

    @classmethod
    def load(cls, path: Path) -> GameDataStore:
        data, _ = decrypt_file(path)
        return cls(data, path)

    def save(self, path: Path) -> None:
        path.write_bytes(encrypt_game_data(self.data, self.source_path or path))

    def save_json(self, path: Path) -> None:
        path.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")

    def patch_characters(self, *, default_level: int = 7) -> None:
        for hero in self.data.get("heroUnlock", {}):
            self.data["heroUnlock"][hero] = True
        for hero in self.data.get("heroLevel", {}):
            if self.data["heroLevel"][hero] < default_level:
                self.data["heroLevel"][hero] = default_level
        for skills in self.data.get("heroSkillUnlock", {}).values():
            for entry in skills:
                entry["Value"] = True

    def patch_characters_selective(self, picks: dict[str, dict[str, Any]]) -> dict[str, int]:
        """picks: hero -> {unlock?, level?, skills?}，仅写入提供的字段。"""
        stats = {"unlock": 0, "level": 0, "skill": 0}
        unlock = self.data.setdefault("heroUnlock", {})
        levels = self.data.setdefault("heroLevel", {})
        skill_map = self.data.setdefault("heroSkillUnlock", {})

        for hero, opts in picks.items():
            if hero not in unlock:
                continue
            if "unlock" in opts and unlock.get(hero) != bool(opts["unlock"]):
                unlock[hero] = bool(opts["unlock"])
                stats["unlock"] += 1
            if "level" in opts:
                lvl = max(1, min(15, int(opts["level"])))
                if levels.get(hero) != lvl:
                    levels[hero] = lvl
                    stats["level"] += 1
            if opts.get("skills"):
                entries = skill_map.setdefault(hero, [])
                for entry in entries:
                    if entry.get("Value") is not True:
                        entry["Value"] = True
                        stats["skill"] += 1
        return stats

    def patch_pets(self, *, unlock: bool = True) -> int:
        pets = self.data.setdefault("petUnlock", {})
        changed = 0
        for name in pets:
            if pets[name] != unlock:
                pets[name] = unlock
                changed += 1
        return changed

    def patch_pets_selective(self, picks: dict[str, bool]) -> int:
        pets = self.data.setdefault("petUnlock", {})
        changed = 0
        for name, value in picks.items():
            if name in pets and pets[name] != value:
                pets[name] = value
                changed += 1
        return changed

    def patch_skins(self, owned: int = 1) -> None:
        for skins in self.data.get("skinLock", {}).values():
            for entry in skins:
                entry["Value"] = owned

    @staticmethod
    def hero_index_map(data: dict[str, Any]) -> dict[str, int]:
        return {name: i for i, name in enumerate(data.get("heroUnlock", {}))}

    def patch_skins_selective(self, picks: dict[str, dict[int, int]]) -> int:
        """按角色勾选皮肤；picks: hero -> {skin_idx: 0|1}。"""
        changed = 0
        skin_lock = self.data.get("skinLock", {})
        for hero, selections in picks.items():
            entries = skin_lock.get(hero, [])
            by_key = {e["Key"]: e for e in entries}
            for skin_idx, value in selections.items():
                if skin_idx in by_key:
                    if by_key[skin_idx].get("Value") != value:
                        by_key[skin_idx]["Value"] = value
                        changed += 1
                else:
                    entries.append({"Key": skin_idx, "Value": value})
                    changed += 1
            skin_lock[hero] = entries
        self.data["skinLock"] = skin_lock
        return changed

    def sync_item_mirror(self, item_data: dict[str, Any], fields: list[str] | None = None) -> None:
        """将 item_data 关键字段写回 game.data.itemData。"""
        mirror = self.data.setdefault("itemData", {})
        keys = fields or list(ITEM_MIRROR_FIELDS)
        for key in keys:
            if key in item_data:
                mirror[key] = json.loads(json.dumps(item_data[key], ensure_ascii=False))

    def patch_room_object_levels(self, picks: dict[str, int]) -> int:
        """客厅设施等级 — 仅 game.data；样本 XML 无 roomObjectLevel 镜像键。"""
        levels = self.data.setdefault("roomObjectLevel", {})
        changed = 0
        for key, val in picks.items():
            val = max(0, min(int(val), 5))
            if levels.get(key) != val:
                levels[key] = val
                changed += 1
        self.data["roomObjectLevel"] = levels
        return changed

    def patch_secret_keys_append(self, keys: list[str]) -> int:
        used = self.data.setdefault("usedSecretKeys", [])
        if not isinstance(used, list):
            used = list(used)
        added = append_unique_list(used, keys)
        self.data["usedSecretKeys"] = used
        return added

    def hero_snapshot(self) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        unlock = self.data.get("heroUnlock", {})
        levels = self.data.get("heroLevel", {})
        skills = self.data.get("heroSkillUnlock", {})
        for name in unlock:
            skill_entries = skills.get(name, [])
            rows.append({
                "name": name,
                "unlock": bool(unlock.get(name)),
                "level": int(levels.get(name, 1)),
                "skills_all": all(e.get("Value") for e in skill_entries) if skill_entries else False,
            })
        return rows

    def pet_snapshot(self) -> dict[str, bool]:
        pets = self.data.get("petUnlock", {})
        return {name: bool(v) for name, v in pets.items()}

    def summary(self) -> dict[str, Any]:
        unlock = self.data.get("heroUnlock", {})
        pets = self.data.get("petUnlock", {})
        skins_owned = sum(
            1 for ss in self.data.get("skinLock", {}).values() for s in ss if s.get("Value") == 1
        )
        skins_total = sum(len(ss) for ss in self.data.get("skinLock", {}).values())
        return {
            "heroes_total": len(unlock),
            "heroes_unlocked": sum(1 for v in unlock.values() if v),
            "skins_owned": skins_owned,
            "skins_total": skins_total,
            "pets_total": len(pets),
            "pets_unlocked": sum(1 for v in pets.values() if v),
            "gems": self.data.get("gems"),
        }