"""统一加解密：game(XOR)、item/season/setting/task(DES-iambo)、statistic(DES-crst1)。

算法与 soul-knight-data-processing (SKD.File) 对齐，仅依赖 pycryptodome。
不依赖 UnityPy / SKD，便于 PyInstaller 等打包。
"""
from __future__ import annotations

import base64
import json
import re
from pathlib import Path
from typing import Any

from Crypto.Cipher import DES
from Crypto.Util.Padding import pad, unpad

from core.constants import EXTRA_DES_IAMBO_TYPES

# 与 SKD.File 常量一致
XOR_KEY = bytes([115, 108, 99, 122, 125, 103, 117, 99, 127, 87, 109, 108, 107, 74, 95])
DES_KEY_IAMBO = bytes([0x69, 0x61, 0x6D, 0x62, 0x6F, 0x00, 0x00, 0x00])  # "iambo\0\0\0"
DES_KEY_CRST1 = bytes([0x63, 0x72, 0x73, 0x74, 0x31, 0x00, 0x00, 0x00])  # "crst1\0\0\0"
DES_IV = bytes([0x41, 0x68, 0x62, 0x6F, 0x6F, 0x6C, 0x00, 0x00])  # "Ahbool\0\0"

UID_DATA_RE = re.compile(r"^(.+)_(\d+)_\.data$")

# 走 DES-iambo 的分片名（basename 解析后的 dtype）
DES_IAMBO_TYPES = frozenset({
    "item_data",
    "season_data",
    "setting",
    "task",
}) | EXTRA_DES_IAMBO_TYPES

# 明文 JSON（不加密）
PLAINTEXT_TYPES = frozenset({"sandbox_config", "sandbox_maps", "battles"})


def data_type_from_name(filename: str) -> str:
    if filename == "game.data":
        return "game"
    m = UID_DATA_RE.match(filename)
    if m:
        return m.group(1)
    return filename.replace(".data", "")


def xor_bytes(data: bytes) -> bytes:
    """game.data 使用的循环 XOR；加解密同一函数。"""
    key = XOR_KEY
    n = len(key)
    return bytes(key[i % n] ^ b for i, b in enumerate(data))


def dumps_compact(obj: Any, *, ensure_ascii: bool) -> str:
    """紧凑 JSON。SKD 对 encryptedJsonGameFiles 会再 dumps 一次（默认 ensure_ascii=True）。"""
    return json.dumps(obj, ensure_ascii=ensure_ascii, separators=(",", ":"))


def decrypt_des_blob(blob: bytes, key: bytes) -> str:
    cipher_bytes = base64.b64decode(blob)
    cipher = DES.new(key, DES.MODE_CBC, DES_IV)
    result_bytes = cipher.decrypt(cipher_bytes)
    return unpad(result_bytes, DES.block_size).decode("utf-8")


def encrypt_des_text(text: str, key: bytes = DES_KEY_IAMBO) -> str:
    cipher = DES.new(key, DES.MODE_CBC, DES_IV)
    result_bytes = cipher.encrypt(pad(text.encode("utf-8"), DES.block_size))
    return base64.b64encode(result_bytes).decode("utf-8")


def decrypt_file(path: Path) -> tuple[dict[str, Any] | list[Any], str]:
    """按文件名类型解密；返回 (json 对象, 算法标签)。"""
    dtype = data_type_from_name(path.name)
    blob = path.read_bytes()

    if dtype == "game":
        # 勿对密文做 utf-8 decode（SKD 的 File.decrypt 首行对非 ASCII 明文 XOR 会炸；
        # 写出时我们用 ensure_ascii=True，密文可按字节直接 XOR）。
        return json.loads(xor_bytes(blob)), "XOR"

    if dtype == "statistic":
        return json.loads(decrypt_des_blob(blob, DES_KEY_CRST1)), "DES-crst1"

    if dtype in DES_IAMBO_TYPES:
        return json.loads(decrypt_des_blob(blob, DES_KEY_IAMBO)), "DES-iambo"

    if dtype in PLAINTEXT_TYPES:
        return json.loads(blob.decode("utf-8")), "plain"

    # 未知类型：先试明文 JSON，再试两种 DES
    try:
        return json.loads(blob.decode("utf-8")), "plain"
    except (UnicodeDecodeError, json.JSONDecodeError):
        pass
    for key, label in ((DES_KEY_IAMBO, "DES-iambo"), (DES_KEY_CRST1, "DES-crst1")):
        try:
            return json.loads(decrypt_des_blob(blob, key)), label
        except Exception:
            continue
    # 最后尝试 XOR（兼容误命名的 game 类文件）
    try:
        return json.loads(xor_bytes(blob)), "XOR"
    except Exception as exc:
        raise ValueError(f"无法解密: {path.name}") from exc


def encrypt_game_data(data: dict[str, Any], source_path: Path | None = None) -> bytes:
    # 对齐 SKD：对 game.data 先 json.dumps(..., ensure_ascii=True) 再 XOR
    _ = source_path
    payload = dumps_compact(data, ensure_ascii=True)
    return xor_bytes(payload.encode("utf-8"))


def encrypt_item_data(data: dict[str, Any], source_path: Path | None = None) -> str:
    # 历史调用 SKD 时 filename 传 "item_data"（不在 encryptedJsonGameFiles 精确列表），
    # 不会二次 dumps，保留 ensure_ascii=False 以兼容已有含中文的存档字段。
    _ = source_path
    payload = dumps_compact(data, ensure_ascii=False)
    return encrypt_des_text(payload, DES_KEY_IAMBO)


def encrypt_setting_data(data: dict[str, Any], source_path: Path | None = None) -> str:
    # 对齐 SKD：filename "setting.data" 会二次 dumps（ensure_ascii=True）
    _ = source_path
    payload = dumps_compact(data, ensure_ascii=True)
    return encrypt_des_text(payload, DES_KEY_IAMBO)


def encrypt_des_iambo(data: dict[str, Any] | list[Any], source_path: Path | None = None) -> str:
    _ = source_path
    payload = dumps_compact(data, ensure_ascii=False)
    return encrypt_des_text(payload, DES_KEY_IAMBO)


def encrypt_statistic(data: dict[str, Any], source_path: Path | None = None) -> str:
    # 对齐 SKD：filename "statistic.data" 会二次 dumps（ensure_ascii=True）
    _ = source_path
    payload = dumps_compact(data, ensure_ascii=True)
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
    if data_type in PLAINTEXT_TYPES:
        return dumps_compact(data, ensure_ascii=False)
    return encrypt_des_iambo(data, source_path)
