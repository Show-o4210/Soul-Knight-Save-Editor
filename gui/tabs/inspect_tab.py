"""分片只读浏览。"""
from __future__ import annotations

import json

from PySide6.QtWidgets import (
    QComboBox,
    QLabel,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from core.shard_registry import INSPECT_SHARDS
from core.workspace import SaveWorkspace
from gui.i18n import tr


class InspectTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._ws: SaveWorkspace | None = None

        layout = QVBoxLayout(self)
        hint = QLabel(tr("inspect.hint"))
        hint.setProperty("role", "hint")
        hint.setWordWrap(True)
        layout.addWidget(hint)

        self.summary = QLabel(tr("inspect.empty"))
        self.summary.setProperty("role", "kv-value")
        self.summary.setWordWrap(True)
        layout.addWidget(self.summary)

        self.shard_combo = QComboBox()
        for dtype in INSPECT_SHARDS:
            self.shard_combo.addItem(dtype, dtype)
        self.shard_combo.currentIndexChanged.connect(self._reload)
        layout.addWidget(self.shard_combo)

        self.body = QTextEdit()
        self.body.setReadOnly(True)
        layout.addWidget(self.body, 1)

    def bind_workspace(self, ws: SaveWorkspace, summaries: dict) -> None:
        self._ws = ws
        parts = []
        for dtype, info in sorted(summaries.items()):
            if "error" in info:
                parts.append(f"{dtype} · 无法读取")
            elif info:
                parts.append(f"{dtype} · {info.get('field_count', '?')} 字段")
        self.summary.setText("  |  ".join(parts) if parts else tr("inspect.no_shards"))
        self._reload()

    def _reload(self) -> None:
        if not self._ws:
            self.body.setPlainText(tr("inspect.empty"))
            return
        dtype = self.shard_combo.currentData()
        data = self._ws.load_inspect_shard(dtype)
        if data is None:
            self.body.setPlainText(tr("inspect.missing", dtype=dtype))
            return
        if isinstance(data, dict) and len(json.dumps(data)) > 120_000:
            preview = {k: data[k] for k in list(data.keys())[:40]}
            text = json.dumps(preview, ensure_ascii=False, indent=2)
            self.body.setPlainText(text + f"\n\n…（仅显示前 40 个顶层键，共 {len(data)} 个）")
        else:
            self.body.setPlainText(json.dumps(data, ensure_ascii=False, indent=2)[:200_000])