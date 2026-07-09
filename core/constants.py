from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INPUT_DIR = ROOT / "输入"
OUTPUT_DIR = ROOT / "输出"
REF_DIR = ROOT / "参考"

PREFS_NAME = "com.ChillyRoom.DungeonShooter.v2.playerprefs.xml"

DUPLICATE_BASENAMES = frozenset({"item_data.data", "season_data.data", "statistic.data"})

EXTRA_DES_IAMBO_TYPES = frozenset({
    "bp_data", "misc_data", "pvp_data", "monsrise_data",
    "weapon_evolution_data", "mall_reload_data",
})

MAX_QUANTITY = 99_999
DEFAULT_LEVEL = 7
PLANT_POT_SLOTS = [3, 4, 5, 6, 7]
DEFAULT_WEAPON_RESEARCH_COUNT = 2
WEAPON_FORGE_UNLOCK_PLUS = 8

GARDEN_MATERIAL_KEYS = (
    "material_wood",
    "material_iron",
    "material_fertilize",
    "material_grain",
    "material_cell",
)


def is_encoded_quantity(value: int) -> bool:
    """游戏内部分材料/种子用约 10000 的编码显示值，非普通库存数量。"""
    return 9_990 <= value <= 10_020