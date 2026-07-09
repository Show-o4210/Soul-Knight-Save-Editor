from __future__ import annotations

from typing import Any

from core.merge import union_materials
from core.stores.base_shard_store import BaseShardStore
from core.weapon_util import is_weapon_id


class StatisticStore(BaseShardStore):
    DATA_TYPE = "statistic"

    @staticmethod
    def _weapon_bucket(data: dict[str, Any], field: str) -> dict[str, Any]:
        stats = data.setdefault("StatisticsData", {})
        if not isinstance(stats, dict):
            stats = {}
            data["StatisticsData"] = stats
        wgs = stats.setdefault("WeaponGameStatisticData", {})
        if not isinstance(wgs, dict):
            wgs = {}
            stats["WeaponGameStatisticData"] = wgs
        bucket = wgs.setdefault(field, {})
        if not isinstance(bucket, dict):
            bucket = {}
            wgs[field] = bucket
        return bucket

    def weapon_used_times(self) -> dict[str, Any]:
        return self._weapon_bucket(self.data, "_weaponUsedTimes")

    def weapon_built_times(self) -> dict[str, Any]:
        return self._weapon_bucket(self.data, "_weaponBuiltTimes")

    def patch_weapon_counts(
        self,
        field: str,
        picks: dict[str, int],
        *,
        max_qty: int = 99_999,
    ) -> int:
        bucket = self._weapon_bucket(self.data, field)
        changed = 0
        for key, qty in picks.items():
            if not is_weapon_id(key):
                continue
            qty = max(0, min(int(qty), max_qty))
            if bucket.get(key) != qty:
                bucket[key] = qty
                changed += 1
        return changed

    def apply_weapon_used_floor(self, floor: int) -> int:
        """将本号已有武器 ID 的获取次数抬到 floor。"""
        if floor <= 0:
            return 0
        bucket = self.weapon_used_times()
        changed = 0
        for key in list(bucket):
            if not is_weapon_id(key):
                continue
            try:
                cur = int(bucket[key])
            except (TypeError, ValueError):
                cur = 0
            if cur < floor:
                bucket[key] = floor
                changed += 1
        return changed

    def apply_weapon_used_plus(self, delta: int) -> int:
        """已有武器 ID 的获取次数在现值基础上 +delta。"""
        if delta == 0:
            return 0
        bucket = self.weapon_used_times()
        changed = 0
        for key in list(bucket):
            if not is_weapon_id(key):
                continue
            try:
                cur = int(bucket.get(key, 0))
            except (TypeError, ValueError):
                cur = 0
            bucket[key] = cur + delta
            changed += 1
        return changed

    def merge_weapon_used_from_reference(
        self,
        ref: dict[str, Any],
        *,
        use_union: bool = True,
        use_max: bool = False,
    ) -> dict[str, int]:
        ref_stats = ref.get("StatisticsData") or {}
        ref_wgs = ref_stats.get("WeaponGameStatisticData") or {}
        ref_bucket = ref_wgs.get("_weaponUsedTimes") or {}
        if not isinstance(ref_bucket, dict):
            return {"added": 0, "maxed": 0}
        base = self.weapon_used_times()
        base_weapon: dict[str, Any] = {k: v for k, v in base.items() if is_weapon_id(k)}
        ref_weapon: dict[str, Any] = {k: v for k, v in ref_bucket.items() if is_weapon_id(k)}
        if not use_union and not use_max:
            return {"added": 0, "maxed": 0}
        st = union_materials(base_weapon, ref_weapon, use_max=use_max)
        for k, v in base_weapon.items():
            base[k] = v
        return st

    def snapshot(self) -> dict[str, Any]:
        used = self.weapon_used_times()
        built = self.weapon_built_times()
        used_weapon = {k: int(v) for k, v in used.items() if is_weapon_id(k)}
        built_weapon = {k: int(v) for k, v in built.items() if is_weapon_id(k)}
        hall = (
            (self.data.get("StatisticsData") or {}).get("HallGameStatisticData") or {}
        )
        forge_times = hall.get("_hallForgeWeaponTimes") or {}
        hall_any = int(forge_times.get("Any", 0)) if isinstance(forge_times, dict) else 0
        return {
            "weapon_used_count": len(used_weapon),
            "weapon_built_count": len(built_weapon),
            "weapon_used_times": used_weapon,
            "weapon_built_times": built_weapon,
            "hall_forge_any": hall_any,
        }