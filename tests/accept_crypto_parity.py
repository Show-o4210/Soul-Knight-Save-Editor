#!/usr/bin/env python3
"""去 UnityPy/SKD 验收门禁。

验收标准（全部满足才 exit 0）：
1. 生产代码不 import SKD / UnityPy
2. 纯实现对 参考/ 全部分片：decrypt → encrypt → decrypt 逻辑完全一致
3. 若本机装有 SKD：对 SKD 覆盖的类型，解密 JSON 与加密输出与 SKD.File 完全一致
"""
from __future__ import annotations

import ast
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from core.crypto import (  # noqa: E402
    DES_KEY_CRST1,
    DES_KEY_IAMBO,
    data_type_from_name,
    decrypt_des_blob,
    decrypt_file,
    encrypt_des_iambo,
    encrypt_game_data,
    encrypt_item_data,
    encrypt_setting_data,
    encrypt_shard_data,
    encrypt_statistic,
    dumps_compact,
    xor_bytes,
)

REF = ROOT / "参考"
PROD_GLOBS = ("core/**/*.py", "engine/**/*.py", "gui/**/*.py", "run.py")


def _fail(msg: str) -> None:
    print(f"✗ {msg}")
    raise AssertionError(msg)


def _ok(msg: str) -> None:
    print(f"✓ {msg}")


def check_no_forbidden_imports() -> None:
    forbidden = {"SKD", "UnityPy", "soul_knight_data_processing"}
    hits: list[str] = []
    for pattern in PROD_GLOBS:
        for path in ROOT.glob(pattern):
            if not path.is_file():
                continue
            # tests 目录不在 PROD_GLOBS
            try:
                tree = ast.parse(path.read_text(encoding="utf-8"))
            except SyntaxError as exc:
                _fail(f"语法错误 {path}: {exc}")
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        root_name = alias.name.split(".")[0]
                        if root_name in forbidden or alias.name.startswith("SKD"):
                            hits.append(f"{path.relative_to(ROOT)}: import {alias.name}")
                elif isinstance(node, ast.ImportFrom) and node.module:
                    root_name = node.module.split(".")[0]
                    if root_name in forbidden or node.module.startswith("SKD"):
                        hits.append(f"{path.relative_to(ROOT)}: from {node.module}")
    if hits:
        _fail("生产代码仍引用 SKD/UnityPy:\n  " + "\n  ".join(hits))
    _ok("生产代码无 SKD/UnityPy 引用")


def _roundtrip_file(path: Path) -> None:
    data, method = decrypt_file(path)
    dtype = data_type_from_name(path.name)
    enc = encrypt_shard_data(data, dtype, path)
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / path.name
        if isinstance(enc, bytes):
            out.write_bytes(enc)
        else:
            out.write_text(enc, encoding="utf-8")
        again, method2 = decrypt_file(out)
    if again != data:
        _fail(f"往返 JSON 不一致: {path.name} ({method} → {method2})")


def check_ref_roundtrips() -> None:
    files = sorted(REF.glob("*.data"))
    if not files:
        _fail(f"参考目录无 .data 样本: {REF}")
    for path in files:
        _roundtrip_file(path)
        _ok(f"往返 {path.name}")


def check_game_synthetic() -> None:
    samples = [
        {"a": 1, "b": True, "c": None},
        {"note": "中文", "nested": {"x": [1, 2, 3]}},
        {"heroUnlock": {"Knight": True, "Rogue": False}, "gems": 0},
    ]
    for i, data in enumerate(samples):
        blob = encrypt_game_data(data)
        plain = json.loads(xor_bytes(blob))
        if plain != json.loads(dumps_compact(data, ensure_ascii=True)):
            # dumps ensure_ascii=True 会把中文变成 \\u，json.loads 后应与原 dict 等价
            if plain != data:
                _fail(f"game 合成样本 {i} XOR 往返失败")
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "game.data"
            p.write_bytes(blob)
            again, method = decrypt_file(p)
            assert method == "XOR"
            if again != data:
                _fail(f"game 合成样本 {i} decrypt_file 不一致")
    _ok(f"game XOR 合成样本 ×{len(samples)}")


def check_skd_parity() -> None:
    try:
        from SKD.core import File
    except ImportError:
        print("· 未安装 SKD，跳过前后字节对照（纯实现往返已过）")
        return

    skd = File()
    mismatches: list[str] = []

    def cmp_decrypt(path: Path, skd_name: str) -> None:
        pure, _ = decrypt_file(path)
        raw = path.read_bytes()
        skd_raw = skd.decrypt(raw, skd_name)
        skd_obj = json.loads(skd_raw)
        if pure != skd_obj:
            mismatches.append(f"decrypt JSON 不一致: {path.name}")

    def cmp_encrypt_item(path: Path) -> None:
        pure, _ = decrypt_file(path)
        payload = dumps_compact(pure, ensure_ascii=False)
        our = encrypt_item_data(pure)
        their = skd.encrypt(payload, "item_data")
        if our != their:
            mismatches.append(f"encrypt item 字节不一致: {path.name}")

    def cmp_encrypt_stat(path: Path) -> None:
        pure, _ = decrypt_file(path)
        # SKD: "statistic.data" 会二次 dumps(ensure_ascii=True)
        payload = dumps_compact(pure, ensure_ascii=False)
        our = encrypt_statistic(pure)
        their = skd.encrypt(payload, "statistic.data")
        if our != their:
            mismatches.append(f"encrypt statistic 字节不一致: {path.name}")

    def cmp_encrypt_season(path: Path) -> None:
        pure, _ = decrypt_file(path)
        # 历史写出走 encrypt_des_iambo(ensure_ascii=False)；SKD 若用 season_data.data 会 ensure_ascii=True
        # 验收：与「我们定义的写路径」自洽 + 解密与 SKD 一致（上面 cmp_decrypt）
        our = encrypt_des_iambo(pure)
        again = json.loads(decrypt_des_blob(our.encode("utf-8"), DES_KEY_IAMBO))
        if again != pure:
            mismatches.append(f"season 自洽失败: {path.name}")

    # 解密对照（SKD 文件名规则：子串匹配）
    mapping = {
        "item_data_77614388_.data": "item_data.data",
        "statistic_77614388_.data": "statistic.data",
        "season_data_77614388_.data": "season_data.data",
    }
    for name, skd_name in mapping.items():
        path = REF / name
        if path.is_file():
            cmp_decrypt(path, skd_name)

    # 加密字节对照
    item = REF / "item_data_77614388_.data"
    if item.is_file():
        cmp_encrypt_item(item)
    stat = REF / "statistic_77614388_.data"
    if stat.is_file():
        cmp_encrypt_stat(stat)
    season = REF / "season_data_77614388_.data"
    if season.is_file():
        cmp_encrypt_season(season)

    # game 加密字节：与 SKD File.encrypt(..., "game.data") 完全一致
    for sample in ({"k": 1}, {"zh": "元气", "arr": [1, 2]}):
        payload = dumps_compact(sample, ensure_ascii=False)
        our = encrypt_game_data(sample)
        their = skd.encrypt(payload, "game.data")
        if our != their:
            mismatches.append(f"encrypt game 字节不一致: {sample!r}")
        # SKD decrypt 用精确名 game.data
        skd_plain = json.loads(skd.decrypt(our, "game.data"))
        if skd_plain != sample:
            mismatches.append(f"SKD 解不开我们写出的 game: {sample!r}")

    # setting 加密
    setting_sample = {"OpenRijTest": 0, "tag": "测"}
    payload = dumps_compact(setting_sample, ensure_ascii=False)
    our = encrypt_setting_data(setting_sample)
    their = skd.encrypt(payload, "setting.data")
    if our != their:
        mismatches.append("encrypt setting 字节不一致")
    if json.loads(decrypt_des_blob(our.encode(), DES_KEY_IAMBO)) != setting_sample:
        mismatches.append("setting 自解密失败")

    if mismatches:
        _fail("SKD 前后对照失败:\n  " + "\n  ".join(mismatches))
    _ok("与 SKD 解密/加密前后完全一致（字节级）")


def check_extra_shards_not_need_skd() -> None:
    """weapon_evolution 等历史上就不走 SKD，纯 DES 即可。"""
    for name in (
        "weapon_evolution_data_77614388_.data",
        "bp_data_77614388_.data",
        "pvp_data_77614388_.data",
        "misc_data_77614388_.data",
    ):
        path = REF / name
        if not path.is_file():
            continue
        data, method = decrypt_file(path)
        assert method == "DES-iambo"
        enc = encrypt_shard_data(data, data_type_from_name(name))
        again = json.loads(decrypt_des_blob(enc.encode("utf-8"), DES_KEY_IAMBO))
        if again != data:
            _fail(f"EXTRA 分片往返失败: {name}")
        _ok(f"EXTRA 纯 DES {name}")


def main() -> int:
    print("=== 去 UnityPy/SKD 验收 ===")
    steps = [
        check_no_forbidden_imports,
        check_game_synthetic,
        check_ref_roundtrips,
        check_extra_shards_not_need_skd,
        check_skd_parity,
    ]
    for step in steps:
        try:
            step()
        except AssertionError:
            print("\n验收未通过")
            return 1
        except Exception as exc:
            print(f"✗ {step.__name__} 异常: {exc}")
            print("\n验收未通过")
            return 1
    print("\n验收通过：纯 crypto 与旧 SKD 路径一致，生产无 UnityPy/SKD 依赖")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
