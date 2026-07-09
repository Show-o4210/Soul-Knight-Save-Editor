"""表格区块 — 统一最小高度与表头样式。"""
from __future__ import annotations

from PySide6.QtWidgets import QGroupBox, QVBoxLayout, QTableWidget

from gui.widgets.table_style import HEADER_HEIGHT, ROW_HEIGHT, configure_table


class TableSection(QGroupBox):
    def __init__(
        self,
        title: str,
        table: QTableWidget,
        *,
        min_visible_rows: int = 6,
        stretch_col: int = 1,
    ) -> None:
        super().__init__(title)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 20, 12, 14)
        layout.setSpacing(8)

        configure_table(table, stretch_col=stretch_col)
        min_h = HEADER_HEIGHT + ROW_HEIGHT * min_visible_rows + 16
        table.setMinimumHeight(min_h)
        layout.addWidget(table)
        self.table = table