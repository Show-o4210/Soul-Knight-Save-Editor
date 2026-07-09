"""宠物名 ↔ xml p 索引映射。"""
from __future__ import annotations

from typing import Any

# 与 game.data.petUnlock 键序一致（TapTap / 4399 实测相同）
CANONICAL_PET_ORDER: tuple[str, ...] = (
    "Cat", "Dog", "Pig", "Slime", "Robot", "Panda", "Rabbit", "CanPig",
    "TapTap", "Pet4399", "Gugu", "Owl", "Seal", "Bug", "Bat", "Hippo",
    "Tengo", "Hide", "Pug", "Corgi", "Cat1", "Cat2", "Rabbit1", "Pig1",
    "LiangSir", "Blagny", "Tortoise", "Serenade", "Dog1", "Cat3", "Cat4",
    "Dog2", "Dog3", "dragon", "Cat5", "Cat6", "TenOff", "RushDuck",
    "SacaSaca", "Cat7", "Hammer", "Zongzi", "JiaoDirector", "FeatureBug",
    "CoinPig", "FireCat", "MiniSlime", "ZongziBro", "Mahhmot", "Capybara",
    "ShanghaiTower", "TrainerRobot", "FireSnowman", "ChillyGo", "Nian",
)


def pet_index_map(game_data: dict[str, Any]) -> dict[str, int]:
    """优先用存档内 petUnlock 键序；缺失时回退 canonical。"""
    unlock = game_data.get("petUnlock")
    if isinstance(unlock, dict) and unlock:
        return {name: i for i, name in enumerate(unlock)}
    return {name: i for i, name in enumerate(CANONICAL_PET_ORDER)}