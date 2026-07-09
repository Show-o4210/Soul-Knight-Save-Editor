"""分片 Store 基类模板。"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, ClassVar

from core.crypto import decrypt_file, encrypt_shard_data


class BaseShardStore:
    DATA_TYPE: ClassVar[str] = ""

    def __init__(self, data: dict[str, Any], source_path: Path | None = None) -> None:
        self.data = data
        self.source_path = source_path

    @classmethod
    def load(cls, path: Path):
        data, _ = decrypt_file(path)
        if not isinstance(data, dict):
            raise TypeError(f"{path.name} 顶层不是对象")
        return cls(data, path)

    def save(self, path: Path) -> None:
        enc = encrypt_shard_data(self.data, self.DATA_TYPE, self.source_path or path)
        if isinstance(enc, bytes):
            path.write_bytes(enc)
        else:
            path.write_text(enc, encoding="utf-8")

    def save_json(self, path: Path) -> None:
        path.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")