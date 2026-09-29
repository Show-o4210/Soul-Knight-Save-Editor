#!/usr/bin/env python3
"""纯 crypto 回归：无 SKD/UnityPy；用 参考/ 分片做往返与可选 SKD 对照。"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from core.crypto import (  # noqa: E402
    DES_KEY_CRST1,
    DES_KEY_IAMBO,
    decrypt_des_blob,
    decrypt_file,
    encrypt_des_iambo,
    encrypt_game_data,
    encrypt_item_data,
    encrypt_setting_data,
    encrypt_shard_data,
    encrypt_statistic,
    xor_bytes,
)

REF = ROOT / "参考"


def _ref(name: str) -> Path:
    p = REF / name
    assert p.is_file(), f"缺少样本: {p}"
    return p


def test_game_xor_roundtrip() -> None:
    data = {"heroUnlock": {"Knight": True}, "note": "测试中文"}
    blob = encrypt_game_data(data)
    assert isinstance(blob, bytes)
    # 密文应可被再次 XOR 还原
    plain = xor_bytes(blob).decode("utf-8")
    loaded = json.loads(plain)
    assert loaded["heroUnlock"]["Knight"] is True
    assert loaded["note"] == "测试中文"
    # 写出确保 ensure_ascii 路径：中文在密文前为 \\u 转义（与 SKD 一致）
    assert "\\u" in plain or "测试" not in plain
    with tempfile.TemporaryDirectory() as td:
        path = Path(td) / "game.data"
        path.write_bytes(blob)
        again, method = decrypt_file(path)
        assert method == "XOR"
        assert again == loaded
    print("✓ game XOR 往返")


def test_item_des_roundtrip_ref() -> None:
    path = _ref("item_data_77614388_.data")
    data, method = decrypt_file(path)
    assert method == "DES-iambo"
    assert isinstance(data, dict) and "materials" in data
    enc = encrypt_item_data(data)
    again = json.loads(decrypt_des_blob(enc.encode("utf-8"), DES_KEY_IAMBO))
    assert again == data
    print("✓ item_data DES 往返（参考样本）")


def test_statistic_roundtrip_ref() -> None:
    path = _ref("statistic_77614388_.data")
    data, method = decrypt_file(path)
    assert method == "DES-crst1"
    enc = encrypt_statistic(data)
    again = json.loads(decrypt_des_blob(enc.encode("utf-8"), DES_KEY_CRST1))
    assert again == data
    print("✓ statistic DES-crst1 往返")


def test_weapon_evolution_and_season() -> None:
    for name, dtype in (
        ("weapon_evolution_data_77614388_.data", "weapon_evolution_data"),
        ("season_data_77614388_.data", "season_data"),
        ("bp_data_77614388_.data", "bp_data"),
        ("pvp_data_77614388_.data", "pvp_data"),
    ):
        path = _ref(name)
        data, method = decrypt_file(path)
        assert method == "DES-iambo", (name, method)
        enc = encrypt_shard_data(data, dtype)
        assert isinstance(enc, str)
        again = json.loads(decrypt_des_blob(enc.encode("utf-8"), DES_KEY_IAMBO))
        assert again == data
    print("✓ 其它 DES-iambo 分片往返")


def test_setting_shape() -> None:
    data = {"OpenRijTest": 0, "label": "测"}
    enc = encrypt_setting_data(data)
    again = json.loads(decrypt_des_blob(enc.encode("utf-8"), DES_KEY_IAMBO))
    assert again == data
    print("✓ setting DES 往返")


def test_optional_skd_parity() -> None:
    """若本机仍装有 SKD，对照密文逻辑等价（JSON 相等即可，密文可因 ensure_ascii 不同）。"""
    try:
        from SKD.core import File
    except ImportError:
        print("· 跳过 SKD 对照（未安装 soul-knight-data-processing）")
        return

    path = _ref("item_data_77614388_.data")
    blob = path.read_bytes()
    pure, _ = decrypt_file(path)
    skd_txt = File().decrypt(blob, "item_data.data")
    skd = json.loads(skd_txt)
    assert pure == skd

    payload = json.dumps(pure, ensure_ascii=False, separators=(",", ":"))
    assert encrypt_item_data(pure) == File().encrypt(payload, "item_data")

    # game：与 SKD File.encrypt(..., "game.data") 字节一致
    sample = {"a": 1, "zh": "中"}
    our = encrypt_game_data(sample)
    skd_enc = File().encrypt(
        json.dumps(sample, ensure_ascii=False, separators=(",", ":")),
        "game.data",
    )
    assert our == skd_enc
    print("✓ 与 SKD 逻辑/字节对照通过")


def main() -> int:
    tests = [
        test_game_xor_roundtrip,
        test_item_des_roundtrip_ref,
        test_statistic_roundtrip_ref,
        test_weapon_evolution_and_season,
        test_setting_shape,
        test_optional_skd_parity,
    ]
    failed = 0
    for fn in tests:
        try:
            fn()
        except Exception as exc:
            failed += 1
            print(f"✗ {fn.__name__}: {exc}")
    if failed:
        print(f"失败 {failed}/{len(tests)}")
        return 1
    print(f"全部通过 {len(tests)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
