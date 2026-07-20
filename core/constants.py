from __future__ import annotations

import sys
from pathlib import Path


def _app_root() -> Path:
    """项目根目录；PyInstaller 打包后为 exe 所在目录（输入/输出/参考 放在旁边）。"""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


ROOT = _app_root()
INPUT_DIR = ROOT / "输入"
OUTPUT_DIR = ROOT / "输出"
REF_DIR = ROOT / "参考"

PREFS_NAME = "com.ChillyRoom.DungeonShooter.v2.playerprefs.xml"

# Android 设备上的游戏包数据目录（提取输入 / 部署输出时对照）
DEVICE_PACKAGE = "com.ChillyRoom.DungeonShooter"
DEVICE_FILES_DIR = f"/data/data/{DEVICE_PACKAGE}/files"
DEVICE_SHARED_PREFS_DIR = f"/data/data/{DEVICE_PACKAGE}/shared_prefs"
# PlayerPrefs 常见文件名；游戏版本不同时可能略有差异
DEVICE_PREFS_GLOB_HINT = f"{PREFS_NAME}（或同目录名字类似的 *.playerprefs.xml / *playerprefs*）"

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


def is_prefs_filename(name: str) -> bool:
    """是否视为 PlayerPrefs XML（含类似命名）。"""
    n = (name or "").lower()
    return n.endswith(".xml") or "playerprefs" in n


def device_source_dir(name: str) -> str:
    """该文件在手机上的目录路径。"""
    if is_prefs_filename(name):
        return DEVICE_SHARED_PREFS_DIR
    return DEVICE_FILES_DIR


def device_source_path(name: str) -> str:
    """该文件在手机上的完整路径提示。"""
    if is_prefs_filename(name):
        # XML 文件名可能因版本略有不同，目录固定
        return f"{DEVICE_SHARED_PREFS_DIR}/{name if name.endswith('.xml') else PREFS_NAME}"
    return f"{DEVICE_FILES_DIR}/{name}"


def device_deploy_relpath(name: str) -> str:
    """部署时相对包数据的路径（files/… 或 shared_prefs/…）。"""
    if is_prefs_filename(name):
        return f"shared_prefs/{name if name.endswith('.xml') else PREFS_NAME}"
    return f"files/{name}"


def format_missing_file(name: str, *, reason: str = "", place_in: str = "输入") -> str:
    """友好的缺文件说明：缺哪个 + 手机哪里取 + 放到哪。"""
    if is_prefs_filename(name):
        where = (
            f"手机目录: {DEVICE_SHARED_PREFS_DIR}/\n"
            f"  文件名: {DEVICE_PREFS_GLOB_HINT}"
        )
        display = name if name.endswith(".xml") else PREFS_NAME
    else:
        display = name
        where = f"手机路径: {DEVICE_FILES_DIR}/{name}"
    head = f"缺少「{display}」"
    if reason:
        head = f"{head} — {reason}"
    return (
        f"{head}\n"
        f"  {where}\n"
        f"  → 复制到本工具「{place_in}」目录后点「刷新载入」"
    )


def format_missing_file_compact(name: str, *, reason: str = "") -> str:
    """单行缺文件提示（用于列表/审计）。"""
    if is_prefs_filename(name):
        loc = f"{DEVICE_SHARED_PREFS_DIR}/ · {DEVICE_PREFS_GLOB_HINT}"
        display = name if name.endswith(".xml") else PREFS_NAME
    else:
        display = name
        loc = f"{DEVICE_FILES_DIR}/{name}"
    base = f"缺少 {display}"
    if reason:
        base = f"{base}（{reason}）"
    return f"{base} · 从手机 {loc} 复制到「输入」"
