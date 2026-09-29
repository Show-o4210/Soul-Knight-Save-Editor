"""客厅 — 子 Tab：设施等级 / 秘密钥匙。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QHeaderView,
    QLabel,
    QLineEdit,
    QScrollArea,
    QSpinBox,
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from gui.i18n import entity_name, tr
from gui.widgets.table_section import TableSection
from gui.widgets.table_style import SPIN_CELL_MIN_WIDTH, cell_spinbox, wrap_spinbox

DEFAULT_ROOM_KEYS = ("Chest", "Safe", "Book", "Planet", "CatFood")
ROOM_LEVEL_MIN = 0
ROOM_LEVEL_MAX = 5


class RoomTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._original: dict[str, int] = {}

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(12)

        hint = QLabel(tr("room.hint"))
        hint.setProperty("role", "hint")
        hint.setWordWrap(True)
        root.addWidget(hint)

        self.sub_tabs = QTabWidget()
        self.sub_tabs.setProperty("role", "sub")
        self.sub_tabs.addTab(self._build_levels_page(), tr("room.sub.levels"))
        self.sub_tabs.addTab(self._build_keys_page(), tr("room.sub.keys"))
        root.addWidget(self.sub_tabs, 1)

    def _build_levels_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(4, 12, 4, 8)

        self.levels_table = QTableWidget(0, 2)
        self.levels_table.setHorizontalHeaderLabels([
            tr("room.levels.col.key"),
            tr("room.levels.col.level"),
        ])
        section = TableSection(
            tr("room.levels.title"),
            self.levels_table,
            min_visible_rows=len(DEFAULT_ROOM_KEYS),
            stretch_col=0,
        )
        self.levels_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Fixed)
        self.levels_table.setColumnWidth(1, SPIN_CELL_MIN_WIDTH + 28)
        layout.addWidget(section, 1)
        return page

    def _build_keys_page(self) -> QWidget:
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.NoFrame)

        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(4, 12, 4, 12)
        layout.setSpacing(12)

        title = QLabel(tr("room.keys.title"))
        title.setProperty("role", "section-inline")
        layout.addWidget(title)
        self.keys_edit = QLineEdit()
        self.keys_edit.setPlaceholderText(tr("room.keys.placeholder"))
        layout.addWidget(self.keys_edit)
        keys_hint = QLabel(tr("room.keys.hint"))
        keys_hint.setProperty("role", "hint")
        keys_hint.setWordWrap(True)
        layout.addWidget(keys_hint)
        layout.addStretch()

        scroll.setWidget(page)
        wrapper = QWidget()
        wl = QVBoxLayout(wrapper)
        wl.setContentsMargins(0, 0, 0, 0)
        wl.addWidget(scroll)
        return wrapper

    def load_from_snapshot(self, room_levels: dict[str, int]) -> None:
        keys = list(dict.fromkeys([*DEFAULT_ROOM_KEYS, *room_levels.keys()]))
        self._original = {k: int(room_levels.get(k, 0)) for k in keys}
        self.levels_table.setRowCount(len(keys))
        for i, key in enumerate(keys):
            label = entity_name(key)
            key_item = QTableWidgetItem(label if label != key else key)
            key_item.setData(Qt.UserRole, key)
            key_item.setFlags(Qt.ItemIsEnabled)
            self.levels_table.setItem(i, 0, key_item)
            spin = QSpinBox()
            spin.setRange(ROOM_LEVEL_MIN, ROOM_LEVEL_MAX)
            spin.setValue(self._original[key])
            self.levels_table.setCellWidget(i, 1, wrap_spinbox(spin))
        self.keys_edit.clear()

    def levels_delta(self) -> dict[str, int]:
        picks: dict[str, int] = {}
        for row in range(self.levels_table.rowCount()):
            key_item = self.levels_table.item(row, 0)
            spin = cell_spinbox(self.levels_table, row, 1)
            if not key_item or spin is None:
                continue
            key = key_item.data(Qt.UserRole) or key_item.text()
            val = spin.value()
            if self._original.get(key, 0) != val:
                picks[key] = val
        return picks

    def secret_keys_append(self) -> list[str]:
        text = self.keys_edit.text().strip()
        if not text:
            return []
        return [x.strip() for x in text.replace("，", ",").split(",") if x.strip()]