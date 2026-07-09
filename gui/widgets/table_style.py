"""表格通用视觉与行高配置。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor
from PySide6.QtWidgets import QHeaderView, QHBoxLayout, QSpinBox, QTableWidget, QTableWidgetItem, QWidget


ROW_HEIGHT = 46
HEADER_HEIGHT = 40
SPIN_CELL_MIN_WIDTH = 112


def configure_table(table: QTableWidget, *, stretch_col: int = 1) -> None:
    table.setAlternatingRowColors(True)
    table.setShowGrid(False)
    table.setSelectionBehavior(QTableWidget.SelectRows)
    table.setSelectionMode(QTableWidget.SingleSelection)
    table.verticalHeader().setVisible(False)
    table.verticalHeader().setDefaultSectionSize(ROW_HEIGHT)
    table.verticalHeader().setMinimumSectionSize(ROW_HEIGHT)
    table.horizontalHeader().setStretchLastSection(False)
    table.horizontalHeader().setFixedHeight(HEADER_HEIGHT)
    if stretch_col >= 0:
        table.horizontalHeader().setSectionResizeMode(stretch_col, QHeaderView.Stretch)
    for col in range(table.columnCount()):
        if col != stretch_col:
            table.horizontalHeader().setSectionResizeMode(col, QHeaderView.ResizeToContents)


def cell_spinbox(table: QTableWidget, row: int, col: int) -> QSpinBox | None:
    """从表格单元格取出 SpinBox（兼容 wrap_spinbox 包装）。"""
    cell = table.cellWidget(row, col)
    if cell is None:
        return None
    if isinstance(cell, QSpinBox):
        return cell
    layout = cell.layout()
    if layout and layout.count():
        widget = layout.itemAt(0).widget()
        if isinstance(widget, QSpinBox):
            return widget
    return None


def wrap_spinbox(spin: QSpinBox, *, min_width: int = SPIN_CELL_MIN_WIDTH) -> QWidget:
    """表格单元格内 SpinBox，避免与行高/列宽遮挡。"""
    spin.setMinimumWidth(min_width)
    spin.setMaximumWidth(min_width + 24)
    spin.setAlignment(Qt.AlignCenter)
    wrap = QWidget()
    lay = QHBoxLayout(wrap)
    lay.setContentsMargins(8, 4, 8, 4)
    lay.addWidget(spin)
    lay.setAlignment(Qt.AlignCenter)
    return wrap


def mark_encoded_row(table: QTableWidget, row: int) -> None:
    tint = QColor("#fff8e6")
    for col in range(table.columnCount()):
        item = table.item(row, col)
        if item:
            item.setBackground(tint)


def readonly_cell(text: str) -> QTableWidgetItem:
    item = QTableWidgetItem(text)
    item.setFlags(item.flags() & ~Qt.ItemIsEditable)
    return item