from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from core.constants import DEFAULT_LEVEL, PLANT_POT_SLOTS, WEAPON_FORGE_UNLOCK_PLUS


@dataclass
class PatchPlan:
    """补丁计划 — 仅勾选项生效；输出也只含被触及的分片。"""

    all_characters: bool = False
    all_skins: bool = False
    all_pets: bool = False
    plant_pots: bool = False
    cleanup_stale_uid: bool = False
    force_legacy_format: bool = True
    sync_item_mirror: bool = True

    merge_blueprints: bool = False
    merge_seeds: bool = False
    merge_item_unlock: bool = False
    merge_mythic_weapons: bool = False
    merge_materials: bool = False
    merge_materials_max: bool = False
    merge_forge_weapons: bool = False
    merge_jewelry: bool = False
    merge_room_decorate: bool = False
    merge_weapon_evolution: bool = False
    merge_weapon_evolution_max: bool = False
    merge_weapon_used_times: bool = False
    merge_weapon_used_max: bool = False

    skin_picks: dict[str, dict[int, int]] = field(default_factory=dict)
    hero_picks: dict[str, dict] = field(default_factory=dict)
    pet_picks: dict[str, bool] = field(default_factory=dict)

    item_unlock_append: list[str] = field(default_factory=list)
    item_unlock_picks: dict[str, bool] = field(default_factory=dict)
    material_picks: dict[str, int] = field(default_factory=dict)
    seed_picks: dict[str, int] = field(default_factory=dict)
    blueprint_picks: dict[str, bool] = field(default_factory=dict)
    weapon_used_picks: dict[str, int] = field(default_factory=dict)
    unlock_weapon_forge: bool = False
    plant_picks: list[dict[str, Any]] = field(default_factory=list)

    room_object_levels: dict[str, int] = field(default_factory=dict)
    secret_keys_append: list[str] = field(default_factory=list)

    default_level: int = DEFAULT_LEVEL
    plant_pot_slots: list[int] = field(default_factory=list)

    def effective_pot_slots(self) -> list[int]:
        if self.plant_pots:
            return list(PLANT_POT_SLOTS)
        return list(self.plant_pot_slots)

    def touches_item(self) -> bool:
        return (
            bool(self.effective_pot_slots())
            or bool(self.item_unlock_append)
            or bool(self.item_unlock_picks)
            or bool(self.plant_picks)
            or bool(self.material_picks)
            or bool(self.seed_picks)
            or bool(self.blueprint_picks)
            or self.touches_item_reference_merge()
        )

    def touches_statistic(self) -> bool:
        return (
            bool(self.weapon_used_picks)
            or self.unlock_weapon_forge
            or self.merge_weapon_used_times
            or self.merge_weapon_used_max
        )

    def touches_item_reference_merge(self) -> bool:
        return (
            self.merge_blueprints
            or self.merge_seeds
            or self.merge_item_unlock
            or self.merge_mythic_weapons
            or self.merge_materials
            or self.merge_forge_weapons
            or self.merge_jewelry
            or self.merge_room_decorate
        )

    def touches_weapon_evolution(self) -> bool:
        return self.merge_weapon_evolution or self.merge_weapon_evolution_max

    def touches_game_room(self) -> bool:
        return bool(self.room_object_levels) or bool(self.secret_keys_append)

    def touches_game(self) -> bool:
        return (
            self.all_characters
            or bool(self.hero_picks)
            or self.all_skins
            or bool(self.skin_picks)
            or self.all_pets
            or bool(self.pet_picks)
            or self.touches_game_room()
            or (self.sync_item_mirror and self.touches_item())
        )

    def touches_prefs(self) -> bool:
        return (
            self.all_characters
            or bool(self.hero_picks)
            or self.all_skins
            or bool(self.skin_picks)
            or self.all_pets
            or bool(self.pet_picks)
            or self.cleanup_stale_uid
            or (self.force_legacy_format and self.touches_item())
        )

    def touches_setting(self) -> bool:
        return self.force_legacy_format and self.touches_item()

    def touches_reference_merge(self) -> bool:
        return (
            self.touches_item_reference_merge()
            or self.touches_weapon_evolution()
            or self.merge_weapon_used_times
            or self.merge_weapon_used_max
        )

    def any_change(self) -> bool:
        return (
            self.touches_game()
            or self.touches_prefs()
            or self.touches_item()
            or self.touches_weapon_evolution()
            or self.touches_statistic()
        )

    def describe(self) -> list[str]:
        items: list[str] = []
        if self.all_characters:
            items.append(f"全角色等级 {self.default_level}")
        if self.hero_picks:
            items.append(f"自选角色 {len(self.hero_picks)} 项")
        if self.all_pets:
            items.append("全宠物解锁")
        if self.pet_picks:
            items.append(f"自选宠物 {len(self.pet_picks)} 项")
        if self.all_skins:
            items.append("全皮肤")
        if self.skin_picks:
            n = sum(len(v) for v in self.skin_picks.values())
            items.append(f"自选皮肤 {n} 项")
        slots = self.effective_pot_slots()
        if slots:
            items.append(f"花圃槽位 {slots}")
        if self.plant_picks:
            items.append(f"花圃植物 {len(self.plant_picks)} 槽")
        if self.item_unlock_append:
            items.append(f"追加 itemUnlock {len(self.item_unlock_append)} 项")
        if self.item_unlock_picks:
            items.append(f"itemUnlock 勾选 {len(self.item_unlock_picks)} 项")
        if self.material_picks:
            items.append(f"材料 {len(self.material_picks)} 项")
        if self.seed_picks:
            items.append(f"种子 {len(self.seed_picks)} 项")
        if self.blueprint_picks:
            items.append(f"蓝图 {len(self.blueprint_picks)} 项")
        if self.unlock_weapon_forge:
            items.append(f"常规武器锻造解锁（获取次数 +{WEAPON_FORGE_UNLOCK_PLUS}）")
        if self.weapon_used_picks:
            items.append(f"武器获取次数 {len(self.weapon_used_picks)} 项")
        if self.merge_weapon_used_times:
            items.append("参考 union 武器获取次数" + (" + max" if self.merge_weapon_used_max else ""))
        if self.room_object_levels:
            items.append(f"客厅设施 {len(self.room_object_levels)} 项")
        if self.secret_keys_append:
            items.append(f"秘密钥匙 +{len(self.secret_keys_append)}")
        if self.cleanup_stale_uid:
            items.append("清理 XML / PList 他号键")
        if self.force_legacy_format and self.touches_item():
            items.append("Legacy 格式开关 → 0")
        if self.merge_blueprints:
            items.append("参考 union 蓝图")
        if self.merge_seeds:
            items.append("参考 union 种子")
        if self.merge_item_unlock:
            items.append("参考 append itemUnlock")
        if self.merge_mythic_weapons:
            items.append("参考 union 神话武器")
        if self.merge_materials:
            items.append("参考 union 材料" + (" + max" if self.merge_materials_max else ""))
        elif self.merge_materials_max:
            items.append("参考材料 max（需同时勾选 union 材料）")
        if self.merge_forge_weapons:
            items.append("参考 append 锻造武器")
        if self.merge_jewelry:
            items.append("参考 union 饰品")
        if self.merge_room_decorate:
            items.append("参考 union 客厅装修")
        if self.merge_weapon_evolution:
            items.append("参考 union 武器进化" + (" + Level max" if self.merge_weapon_evolution_max else ""))
        return items
