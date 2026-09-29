"""可滚动 Tab 页容器 — 各编辑 Tab 统一外边距与滚动行为。"""
from __future__ import annotations

from PySide6.QtWidgets import QScrollArea, QVBoxLayout, QWidget


def wrap_scroll_page(content: QWidget, *, margins: tuple[int, int, int, int] = (0, 4, 0, 8)) -> QWidget:
    page = QWidget()
    outer = QVBoxLayout(page)
    outer.setContentsMargins(0, 0, 0, 0)

    scroll = QScrollArea()
    scroll.setWidgetResizable(True)
    scroll.setFrameShape(QScrollArea.NoFrame)

    inner = QWidget()
    layout = QVBoxLayout(inner)
    layout.setContentsMargins(*margins)
    layout.setSpacing(16)
    layout.addWidget(content)
    layout.addStretch()

    scroll.setWidget(inner)
    outer.addWidget(scroll)
    return page