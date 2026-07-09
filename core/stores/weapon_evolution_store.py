from __future__ import annotations

from typing import Any

from core.merge import deep_copy, union_weapons_dict
from core.stores.base_shard_store import BaseShardStore


class WeaponEvolutionStore(BaseShardStore):
    DATA_TYPE = "weapon_evolution_data"

    def merge_from_reference(
        self,
        ref: dict[str, Any],
        *,
        use_union: bool = True,
        use_max_level: bool = False,
    ) -> dict[str, int]:
        ref_weapons = ref.get("weapons")
        if not isinstance(ref_weapons, dict):
            return {}
        weapons = self.data.setdefault("weapons", {})
        if not isinstance(weapons, dict):
            weapons = {}
            self.data["weapons"] = weapons
        return union_weapons_dict(
            weapons, ref_weapons, use_union=use_union, use_max_level=use_max_level
        )

    def snapshot(self) -> dict[str, Any]:
        weapons = self.data.get("weapons") or {}
        levels = [
            int(w.get("Level", 0))
            for w in weapons.values()
            if isinstance(w, dict)
        ]
        return {
            "weapon_count": len(weapons),
            "avg_level": round(sum(levels) / len(levels), 2) if levels else 0,
        }