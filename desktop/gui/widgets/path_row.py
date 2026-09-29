"""目录路径行 — 标签 + 输入 + 浏览。"""
from __future__ import annotations

from pathlib import Path

from PySide6.QtWidgets import QFileDialog, QHBoxLayout, QLabel, QLineEdit, QPushButton, QWidget

from gui.i18n import tr


class PathRow(QWidget):
    def __init__(self, label: str, default: Path, *, is_file: bool = False) -> None:
        super().__init__()
        self._is_file = is_file
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 4, 0, 4)
        layout.setSpacing(12)

        lbl = QLabel(label)
        lbl.setMinimumWidth(56)
        lbl.setProperty("role", "path-label")
        layout.addWidget(lbl)

        self.edit = QLineEdit(str(default))
        self.edit.setPlaceholderText(tr("path.placeholder"))
        layout.addWidget(self.edit, 1)

        btn = QPushButton(tr("btn.browse"))
        btn.setProperty("role", "ghost")
        btn.clicked.connect(self._browse)
        layout.addWidget(btn)

    def path(self) -> Path:
        return Path(self.edit.text().strip()).expanduser().resolve()

    def _browse(self) -> None:
        current = self.edit.text().strip()
        start = str(Path(current).parent) if current else str(Path.home())
        if self._is_file:
            chosen, _ = QFileDialog.getOpenFileName(self, "选择文件", start)
        else:
            chosen = QFileDialog.getExistingDirectory(self, "选择目录", start)
        if chosen:
            self.edit.setText(chosen)