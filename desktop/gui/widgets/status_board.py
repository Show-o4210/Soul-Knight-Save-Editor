"""工作台结构化信息展示。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QFrame, QGridLayout, QHBoxLayout, QLabel, QVBoxLayout, QWidget


class SectionCard(QFrame):
    """带标题的内容卡片。"""

    def __init__(self, title: str) -> None:
        super().__init__()
        self.setProperty("role", "section-card")
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        self._title = QLabel(title)
        self._title.setProperty("role", "section-title")
        root.addWidget(self._title)

        self._body = QWidget()
        self._body_layout = QVBoxLayout(self._body)
        self._body_layout.setContentsMargins(16, 10, 16, 14)
        self._body_layout.setSpacing(8)
        root.addWidget(self._body)

    def clear_body(self) -> None:
        while self._body_layout.count():
            item = self._body_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

    def set_placeholder(self, text: str) -> None:
        self.clear_body()
        lab = QLabel(text)
        lab.setProperty("role", "hint")
        lab.setWordWrap(True)
        self._body_layout.addWidget(lab)


class KeyValueGrid(SectionCard):
    """两列键值网格。"""

    def set_pairs(self, pairs: list[tuple[str, str]]) -> None:
        self.clear_body()
        if not pairs:
            self.set_placeholder("—")
            return
        grid_host = QWidget()
        grid = QGridLayout(grid_host)
        grid.setContentsMargins(0, 0, 0, 0)
        grid.setHorizontalSpacing(20)
        grid.setVerticalSpacing(6)
        for i, (key, val) in enumerate(pairs):
            k = QLabel(key)
            k.setProperty("role", "kv-key")
            v = QLabel(val)
            v.setProperty("role", "kv-value")
            v.setWordWrap(True)
            v.setTextInteractionFlags(Qt.TextSelectableByMouse)
            grid.addWidget(k, i, 0, Qt.AlignTop)
            grid.addWidget(v, i, 1, Qt.AlignTop)
        grid.setColumnStretch(1, 1)
        self._body_layout.addWidget(grid_host)


class ChipRow(SectionCard):
    """文件/标签状态行。"""

    def set_chips(self, chips: list[tuple[str, str, bool]]) -> None:
        """(短名, 完整名, 是否已载入)"""
        self.clear_body()
        if not chips:
            self.set_placeholder("—")
            return
        row = QWidget()
        flow = QHBoxLayout(row)
        flow.setContentsMargins(0, 0, 0, 0)
        flow.setSpacing(8)
        for short, full, ok in chips:
            chip = QLabel(short)
            chip.setProperty("role", "chip-ok" if ok else "chip-miss")
            chip.setToolTip(full)
            flow.addWidget(chip)
        flow.addStretch()
        self._body_layout.addWidget(row)


class NoticeList(SectionCard):
    """告警 / 提示列表（无杂乱符号）。"""

    def set_lines(self, lines: list[tuple[str, str]]) -> None:
        """(级别 info|warn, 文本)"""
        self.clear_body()
        if not lines:
            self.hide()
            return
        self.show()
        for level, text in lines:
            lab = QLabel(text)
            lab.setProperty("role", f"notice-{level}")
            lab.setWordWrap(True)
            self._body_layout.addWidget(lab)