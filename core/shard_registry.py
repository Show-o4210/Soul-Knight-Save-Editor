"""分片类型注册 — 文件名模式、加密方式、Store 类。"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

EncryptKind = Literal["game", "item", "setting", "des_iambo", "des_crst1", "plain"]


@dataclass(frozen=True)
class ShardSpec:
    data_type: str
    encrypt: EncryptKind
    writable: bool = True
    inspect_only: bool = False


# 带 UID 的分片：{data_type}_{uid}_.data
SHARD_SPECS: dict[str, ShardSpec] = {
    "item_data": ShardSpec("item_data", "item"),
    "weapon_evolution_data": ShardSpec("weapon_evolution_data", "des_iambo"),
    "setting": ShardSpec("setting", "setting"),
    "bp_data": ShardSpec("bp_data", "des_iambo", inspect_only=True),
    "season_data": ShardSpec("season_data", "des_iambo", inspect_only=True),
    "statistic": ShardSpec("statistic", "des_crst1"),
    "task": ShardSpec("task", "des_iambo", inspect_only=True),
    "pvp_data": ShardSpec("pvp_data", "des_iambo", inspect_only=True),
    "monsrise_data": ShardSpec("monsrise_data", "des_iambo", inspect_only=True),
    "misc_data": ShardSpec("misc_data", "des_iambo", inspect_only=True),
    "mall_reload_data": ShardSpec("mall_reload_data", "des_iambo", inspect_only=True),
    # battles 为明文 JSON（与 core.crypto.PLAINTEXT_TYPES 一致；非 DES）
    "battles": ShardSpec("battles", "plain", inspect_only=True),
    "sandbox_config": ShardSpec("sandbox_config", "plain", inspect_only=True),
    "sandbox_maps": ShardSpec("sandbox_maps", "plain", inspect_only=True),
}

WRITABLE_SHARDS = tuple(k for k, s in SHARD_SPECS.items() if s.writable and not s.inspect_only)
INSPECT_SHARDS = tuple(k for k, s in SHARD_SPECS.items() if s.inspect_only or k in WRITABLE_SHARDS)


def shard_filename(data_type: str, uid: str) -> str:
    if data_type == "game":
        return "game.data"
    return f"{data_type}_{uid}_.data"


def discover_shard_path(directory, data_type: str, uid: str):
    from pathlib import Path

    d = Path(directory)
    if data_type == "game":
        p = d / "game.data"
        return p if p.is_file() else None
    exact = d / shard_filename(data_type, uid)
    if exact.is_file():
        return exact
    matches = sorted(d.glob(f"{data_type}_{uid}*.data"))
    return matches[0] if matches else None