"""柔和纯文字展示块 — 用于存档详情、输入状态等。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QFrame, QLabel, QVBoxLayout


class InfoPanel(QFrame):
    def __init__(self, title: str, *, placeholder: str = "—") -> None:
        super().__init__()
        self.setProperty("role", "info-panel")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self._title = QLabel(title)
        self._title.setProperty("role", "info-title")
        layout.addWidget(self._title)

        self._content = QLabel(placeholder)
        self._content.setProperty("role", "info-content")
        self._content.setWordWrap(True)
        self._content.setTextInteractionFlags(Qt.TextSelectableByMouse)
        layout.addWidget(self._content)

    def set_text(self, text: str) -> None:
        self._content.setText(text)

    def set_title(self, title: str) -> None:
        self._title.setText(title)