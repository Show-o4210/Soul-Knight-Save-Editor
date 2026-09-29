"""分片只读浏览（本地 XOR/DES 解密，不依赖 UnityPy/SKD）。"""
from __future__ import annotations

import json

from PySide6.QtWidgets import (
    QComboBox,
    QLabel,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from core.shard_registry import INSPECT_SHARDS, SHARD_SPECS
from core.workspace import SaveWorkspace
from gui.i18n import tr


class InspectTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._ws: SaveWorkspace | None = None
        self._summaries: dict = {}

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
            spec = SHARD_SPECS.get(dtype)
            # 下拉显示「类型 · 预期算法」，便于对照纯本地 crypto
            if spec is not None:
                label = f"{dtype}  ({spec.encrypt})"
            else:
                label = dtype
            self.shard_combo.addItem(label, dtype)
        self.shard_combo.currentIndexChanged.connect(self._reload)
        layout.addWidget(self.shard_combo)

        self.method_label = QLabel("")
        self.method_label.setProperty("role", "hint")
        layout.addWidget(self.method_label)

        self.body = QTextEdit()
        self.body.setReadOnly(True)
        layout.addWidget(self.body, 1)

    def bind_workspace(self, ws: SaveWorkspace | None, summaries: dict | None) -> None:
        self._ws = ws
        self._summaries = dict(summaries or {})
        if not ws:
            self.summary.setText(tr("inspect.empty"))
            self.method_label.setText("")
            self.body.setPlainText(tr("inspect.empty"))
            return
        parts: list[str] = []
        for dtype, info in sorted(self._summaries.items()):
            if "error" in info:
                parts.append(tr("inspect.summary_err", dtype=dtype))
            elif info:
                parts.append(
                    tr(
                        "inspect.summary_ok",
                        dtype=dtype,
                        method=info.get("method", "?"),
                        fields=info.get("field_count", "?"),
                    )
                )
        self.summary.setText("  |  ".join(parts) if parts else tr("inspect.no_shards"))
        self._reload()

    def _reload(self) -> None:
        if not self._ws:
            self.method_label.setText("")
            self.body.setPlainText(tr("inspect.empty"))
            return
        dtype = self.shard_combo.currentData()
        if not dtype:
            self.method_label.setText("")
            self.body.setPlainText(tr("inspect.empty"))
            return

        info = self._summaries.get(dtype) or {}
        if info.get("error"):
            self.method_label.setText(tr("inspect.summary_err", dtype=dtype))
            self.body.setPlainText(tr("inspect.error", detail=info["error"]))
            return

        try:
            data = self._ws.load_inspect_shard(dtype)
        except Exception as exc:
            self.method_label.setText(tr("inspect.summary_err", dtype=dtype))
            self.body.setPlainText(tr("inspect.error", detail=str(exc)))
            return

        if data is None:
            self.method_label.setText("")
            self.body.setPlainText(tr("inspect.missing", dtype=dtype))
            return

        method = info.get("method")
        if not method:
            # 摘要未覆盖时，从注册表提示预期算法
            spec = SHARD_SPECS.get(dtype)
            method = spec.encrypt if spec else "?"
        file_name = info.get("file") or ""
        self.method_label.setText(
            f"{file_name}  ·  {method}" if file_name else str(method)
        )

        if isinstance(data, dict) and len(json.dumps(data, ensure_ascii=False)) > 120_000:
            preview = {k: data[k] for k in list(data.keys())[:40]}
            text = json.dumps(preview, ensure_ascii=False, indent=2)
            self.body.setPlainText(
                text + f"\n\n…（仅显示前 40 个顶层键，共 {len(data)} 个）"
            )
        else:
            self.body.setPlainText(
                json.dumps(data, ensure_ascii=False, indent=2)[:200_000]
            )
