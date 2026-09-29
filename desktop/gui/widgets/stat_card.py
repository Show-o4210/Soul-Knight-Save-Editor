"""工作台摘要统计卡片。"""
from __future__ import annotations

from PySide6.QtWidgets import QFrame, QLabel, QVBoxLayout


class StatCard(QFrame):
    def __init__(self, label: str, value: str = "—") -> None:
        super().__init__()
        self.setProperty("role", "stat")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(4, 2, 4, 2)
        layout.setSpacing(4)
        self.value_label = QLabel(value)
        self.value_label.setProperty("role", "stat-value")
        self.title_label = QLabel(label)
        self.title_label.setProperty("role", "stat-label")
        layout.addWidget(self.value_label)
        layout.addWidget(self.title_label)

    def set_value(self, text: str) -> None:
        self.value_label.setText(text)