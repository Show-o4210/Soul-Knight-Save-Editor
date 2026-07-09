"""统一加解密：game(XOR)、item/season/setting/task(DES-iambo)、statistic(DES-crst1)。"""
from __future__ import annotations

import base64
import json
import re
from pathlib import Path
from typing import Any

from Crypto.Cipher import DES
from Crypto.Util.Padding import unpad
from SKD.core import Convert

from core.constants import EXTRA_DES_IAMBO_TYPES

DES_KEY_IAMBO = bytes([0x69, 0x61, 0x6D, 0x62, 0x6F, 0x00, 0x00, 0x00])
DES_KEY_CRST1 = bytes([0x63, 0x72, 0x73, 0x74, 0x31, 0x00, 0x00, 0x00])
DES_IV = bytes([0x41, 0x68, 0x62, 0x6F, 0x6F, 0x6C, 0x00, 0x00])

UID_DATA_RE = re.compile(r"^(.+)_(\d+)_\.data$")


def data_type_from_name(filename: str) -> str:
    if filename == "game.data":
        return "game"
    m = UID_DATA_RE.match(filename)
    if m:
        return m.group(1)
    return filename.replace(".data", "")


def decrypt_des_blob(blob: bytes, key: bytes) -> str:
    cipher_bytes = base64.b64decode(blob)
    cipher = DES.new(key, DES.MODE_CBC, DES_IV)
    result_bytes = cipher.decrypt(cipher_bytes)
    return unpad(result_bytes, DES.block_size).decode("utf-8")


def encrypt_des_text(text: str, key: bytes = DES_KEY_IAMBO) -> str:
    from Crypto.Util.Padding import pad

    cipher = DES.new(key, DES.MODE_CBC, DES_IV)
    result_bytes = cipher.encrypt(pad(text.encode("utf-8"), DES.block_size))
    return base64.b64encode(result_bytes).decode("utf-8")


def decrypt_file(path: Path) -> tuple[dict[str, Any] | list[Any], str]:
    dtype = data_type_from_name(path.name)
    blob = path.read_bytes()

    if dtype == "game":
        conv = Convert()
        conv.FilePath = str(path)
        return json.loads(conv.de_open()), "XOR"

    if dtype == "statistic":
        conv = Convert()
        conv.FilePath = str(path)
        return json.loads(conv.de_open()), "DES-crst1"

    if dtype in {"item_data", "season_data", "setting", "task", "battles"}:
        conv = Convert()
        conv.FilePath = str(path)
        return json.loads(conv.de_open()), "DES-iambo"

    if dtype in EXTRA_DES_IAMBO_TYPES:
        return json.loads(decrypt_des_blob(blob, DES_KEY_IAMBO)), "DES-iambo"

    try:
        conv = Convert()
        conv.FilePath = str(path)
        return json.loads(conv.de_open()), "SKD"
    except json.JSONDecodeError:
        pass
    for key, label in ((DES_KEY_IAMBO, "DES-iambo"), (DES_KEY_CRST1, "DES-crst1")):
        try:
            return json.loads(decrypt_des_blob(blob, key)), label
        except Exception:
            continue
    raise ValueError(f"无法解密: {path.name}")


def encrypt_game_data(data: dict[str, Any], source_path: Path | None = None) -> bytes:
    conv = Convert()
    conv.FilePath = str(source_path or "game.data")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return conv.File.encrypt(payload, "game.data")


def encrypt_item_data(data: dict[str, Any], source_path: Path | None = None) -> str:
    conv = Convert()
    conv.FilePath = str(source_path or "item_data.data")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    enc = conv.File.encrypt(payload, "item_data")
    return enc if isinstance(enc, str) else enc.decode("utf-8")


def encrypt_setting_data(data: dict[str, Any], source_path: Path | None = None) -> str:
    conv = Convert()
    conv.FilePath = str(source_path or "setting.data")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    enc = conv.File.encrypt(payload, "setting.data")
    return enc if isinstance(enc, str) else enc.decode("utf-8")


def encrypt_des_iambo(data: dict[str, Any] | list[Any], source_path: Path | None = None) -> str:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return encrypt_des_text(payload, DES_KEY_IAMBO)


def encrypt_statistic(data: dict[str, Any], source_path: Path | None = None) -> str:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return encrypt_des_text(payload, DES_KEY_CRST1)


def encrypt_shard_data(
    data: dict[str, Any] | list[Any],
    data_type: str,
    source_path: Path | None = None,
) -> str | bytes:
    """按分片类型选择加密方式。"""
    if data_type == "game":
        return encrypt_game_data(data, source_path)  # type: ignore[arg-type]
    if data_type == "item_data":
        return encrypt_item_data(data, source_path)  # type: ignore[arg-type]
    if data_type == "setting":
        return encrypt_setting_data(data, source_path)  # type: ignore[arg-type]
    if data_type == "statistic":
        return encrypt_statistic(data, source_path)  # type: ignore[arg-type]
    if data_type in {"sandbox_config", "sandbox_maps", "battles"}:
        return json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return encrypt_des_iambo(data, source_path)