"""从 config/strings.json 加载 UI 与游戏实体显示名。"""
from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path

_STRINGS_PATH = Path(__file__).resolve().parent / "strings.json"

_ROOM_FACILITIES = {
    "Chest": "储物箱",
    "Safe": "保险箱",
    "Book": "书架",
    "Planet": "星球仪",
    "CatFood": "猫粮机",
    "Motorcycle": "摩托车",
    "ItemEggMachine": "扭蛋机",
    "ItemTrashCan": "垃圾桶",
    "ItemDrinkSeller": "饮料机",
}

_MAGIC_COLORS = {
    "red": "红色",
    "blue": "蓝色",
    "green": "绿色",
    "orange": "橙色",
    "purple": "紫色",
    "cyan": "青色",
    "black": "黑色",
}

_SEED_PARTS = {
    "banboo": "竹子",
    "binary_tree": "二叉树",
    "cactus": "仙人掌",
    "carrot": "胡萝卜",
    "datura": "曼陀罗",
    "defend_flower": "守护之花",
    "dragon_tree": "龙血树",
    "eator": "食人花",
    "fantastic_flower": "奇花",
    "gear_flower": "齿轮花",
    "gem_flower": "宝石花",
    "gem_tree": "宝石树",
    "heptacolor": "七色花",
    "iron_tree": "铁树",
    "lotus": "莲花",
    "lycoris": "彼岸花",
    "magic_flower": "魔法花",
    "mandrake": "曼德拉草",
    "mirror_plane_flower": "镜面花",
    "mushroom": "蘑菇",
    "pumpkin": "南瓜",
    "qilixiang": "七里香",
    "radar": "雷达花",
    "rainbow_grass": "彩虹草",
    "roselle": "洛神花",
    "shallot": "大葱",
    "strange_flower": "奇异之花",
    "tree": "树",
    "trumpet": "喇叭花",
    "vine": "藤蔓",
    "watermelon": "西瓜",
    "worm": "虫",
    "xmas": "圣诞",
}


@lru_cache(maxsize=1)
def _load() -> dict:
    with _STRINGS_PATH.open(encoding="utf-8") as fh:
        return json.load(fh)


def tr(key: str, **kwargs: object) -> str:
    """UI 文案；支持 {placeholder} 格式化。"""
    data = _load()
    text = data.get("ui", {}).get(key, key)
    if kwargs:
        return str(text).format(**kwargs)
    return str(text)


def entity_name(key: str) -> str:
    """游戏内键名 → 中文显示名；未收录则按规则推断或原样返回。"""
    data = _load()
    entities = data.get("entities", {})
    if key in entities:
        return entities[key]

    for section in ("heroes", "pets"):
        if key in data.get(section, {}):
            return data[section][key]

    inferred = _infer_name(key)
    return inferred if inferred else key


def _infer_name(key: str) -> str | None:
    if m := re.fullmatch(r"material_magic_(\w+)", key):
        color = _MAGIC_COLORS.get(m.group(1), m.group(1))
        return f"{color}魔法材料"

    if m := re.fullmatch(r"material_weapon_fragment_weapon_(\d+)", key):
        return f"武器蓝图碎片 #{m.group(1)}"

    if m := re.fullmatch(r"material_generic_weapon_fragment_(\d+)", key):
        return f"通用武器碎片 {m.group(1)}"

    if m := re.fullmatch(r"material_skin_fragment_(.+)", key):
        return f"皮肤碎片 · {m.group(1)}"

    if m := re.fullmatch(r"material_skill_(\w+)_(\d+)", key):
        hero = entity_name(m.group(1))
        return f"{hero} 技能书 {m.group(2)}"

    if m := re.fullmatch(r"material_tape_(.+)", key):
        return f"磁带 · {m.group(1).replace('_', ' ')}"

    if m := re.fullmatch(r"material_c(\d+)_book_(\d+)", key):
        return f"角色书籍 C{m.group(1)}-{m.group(2)}"

    if key in _ROOM_FACILITIES:
        return _ROOM_FACILITIES[key]

    if m := re.fullmatch(r"plant_(.+)_seed", key):
        part = m.group(1)
        label = _SEED_PARTS.get(part, part.replace("_", " "))
        return f"{label}种子"

    if m := re.fullmatch(r"plant_(.+)", key):
        part = m.group(1)
        if part.startswith("pot"):
            return None
        label = _SEED_PARTS.get(part, part.replace("_", " "))
        return label

    if m := re.fullmatch(r"material_(\w+)", key):
        slug = m.group(1)
        if slug in ("wood", "iron", "fertilize", "grain", "cell", "fish", "gear", "battery", "ticket"):
            return None
        return slug.replace("_", " ")

    if m := re.fullmatch(r"garden_skin_(\d+)", key):
        return f"花圃皮肤 {m.group(1)}"

    if m := re.fullmatch(r"work_shop_skin_(\d+)", key):
        return f"工坊皮肤 {m.group(1)}"

    if m := re.fullmatch(r"magic_area_skin_(\d+)", key):
        return f"魔法区皮肤 {m.group(1)}"

    if m := re.fullmatch(r"blueprint_evolution_weapon_(\d+)", key):
        return f"武器进化蓝图 #{m.group(1)}"

    if m := re.fullmatch(r"blueprint_weapon_(\d+)", key):
        return f"武器蓝图 #{m.group(1)}"

    if m := re.fullmatch(r"blueprint_skin_(\w+)_(\d+)", key):
        hero = entity_name(m.group(1))
        return f"{hero} 皮肤蓝图 {m.group(2)}"

    if m := re.fullmatch(r"blueprint_transform_weapon_(\w+)", key):
        hero = entity_name(m.group(1))
        return f"{hero} 变身武器蓝图"

    if m := re.fullmatch(r"blueprint_m_mech_(\d+)", key):
        return f"机甲蓝图 {m.group(1)}"

    if m := re.fullmatch(r"multi_room_skin_(\d+)", key):
        return f"客厅皮肤 {m.group(1)}"

    if m := re.fullmatch(r"plant_pot(\d+)", key):
        return f"花圃盆位 {m.group(1)}"

    if key.startswith("blueprint_room_decorate_"):
        deco = key.removeprefix("blueprint_room_decorate_").replace("_", " ")
        return f"房间装饰 · {deco}"

    return None