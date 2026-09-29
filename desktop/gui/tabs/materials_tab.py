"""材料 / 种子 / 蓝图 — 子 Tab + 大表格滚动区。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QTabWidget,
    QTableWidget,
    QVBoxLayout,
    QWidget,
)

from core.constants import GARDEN_MATERIAL_KEYS, MAX_QUANTITY, is_encoded_quantity
from gui.i18n import entity_name, tr
from gui.widgets.item_catalog import (
    is_blueprint_owned,
    is_quantity_owned,
    sort_blueprint_keys,
    sort_quantity_keys,
)
from gui.widgets.table_style import configure_table, mark_encoded_row, readonly_cell


def encoded_change_warnings(
    picks: dict[str, int],
    original: dict[str, int],
) -> list[str]:
    warnings: list[str] = []
    for key, new_val in picks.items():
        old_val = int(original.get(key, 0))
        if is_encoded_quantity(old_val) and new_val != old_val:
            warnings.append(f"{key}: {old_val} → {new_val}")
    return warnings


class _QuantityPage(QWidget):
    """固定工具栏 + 可滚动大表格。"""

    def __init__(self, *, show_garden_fill: bool = False) -> None:
        super().__init__()
        self._save: dict[str, int] = {}
        self._original: dict[str, int] = {}
        self._rows: dict[str, tuple[QCheckBox, QSpinBox]] = {}

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(12)

        toolbar = QWidget()
        toolbar.setProperty("role", "toolbar")
        bar = QHBoxLayout(toolbar)
        bar.setContentsMargins(12, 10, 12, 10)
        bar.setSpacing(12)

        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText(tr("materials.filter"))
        self.filter_edit.textChanged.connect(self._apply_filter)
        bar.addWidget(self.filter_edit, 1)

        if show_garden_fill:
            btn = QPushButton(tr("materials.garden_fill"))
            btn.clicked.connect(self._fill_garden)
            bar.addWidget(btn)

        self.count_label = QLabel("")
        self.count_label.setProperty("role", "hint")
        bar.addWidget(self.count_label)
        root.addWidget(toolbar)

        self.table = QTableWidget(0, 4)
        self.table.setHorizontalHeaderLabels([
            tr("materials.col.enable"),
            tr("materials.col.key"),
            tr("materials.col.qty"),
            tr("materials.col.note"),
        ])
        configure_table(self.table, stretch_col=1)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Fixed)
        self.table.setColumnWidth(0, 72)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Fixed)
        self.table.setColumnWidth(2, 140)
        self.table.horizontalHeader().setSectionResizeMode(3, QHeaderView.Fixed)
        self.table.setColumnWidth(3, 96)
        root.addWidget(self.table, 1)

    def load_data(self, data: dict[str, int], ref: dict[str, int] | None = None) -> None:
        ref = ref or {}
        self._save = {k: int(v) for k, v in data.items()}
        self._original = dict(self._save)
        self._rows.clear()
        self.table.setRowCount(0)

        keys = sort_quantity_keys(self._save, ref)
        for row, key in enumerate(keys):
            owned = is_quantity_owned(self._save, key)
            value = int(self._save.get(key, 0))
            display_val = value if owned else 0

            self.table.insertRow(row)
            cb = QCheckBox()
            cb.setChecked(owned)
            spin = QSpinBox()
            spin.setRange(0, MAX_QUANTITY)
            spin.setValue(display_val)
            spin.setButtonSymbols(QSpinBox.PlusMinus)

            notes: list[str] = []
            if not owned:
                notes.append(tr("materials.note.not_owned"))
            elif is_encoded_quantity(value):
                notes.append(tr("materials.note.encoded"))
            note = " · ".join(notes)

            self.table.setCellWidget(row, 0, self._center_widget(cb))
            display_key = f"{entity_name(key)}\n{key}" if entity_name(key) != key else key
            self.table.setItem(row, 1, readonly_cell(display_key))
            self.table.setCellWidget(row, 2, self._wrap_spin(spin))
            note_item = readonly_cell(note)
            if tr("materials.note.encoded") in note:
                note_item.setForeground(Qt.darkYellow)
            elif tr("materials.note.not_owned") in note:
                note_item.setForeground(Qt.gray)
            self.table.setItem(row, 3, note_item)

            if owned and is_encoded_quantity(value):
                mark_encoded_row(self.table, row)

            self._rows[key] = (cb, spin)

        self._apply_filter(self.filter_edit.text())

    def picks_delta(self) -> dict[str, int]:
        delta: dict[str, int] = {}
        for key, (cb, spin) in self._rows.items():
            if not cb.isChecked():
                continue
            new_val = spin.value()
            orig = int(self._original.get(key, 0))
            if new_val != orig:
                delta[key] = new_val
        return delta

    def _fill_garden(self) -> None:
        for key in GARDEN_MATERIAL_KEYS:
            row = self._rows.get(key)
            if not row:
                continue
            cb, spin = row
            cb.setChecked(True)
            spin.setValue(MAX_QUANTITY)

    def _apply_filter(self, text: str) -> None:
        needle = text.strip().lower()
        visible = 0
        for row in range(self.table.rowCount()):
            item = self.table.item(row, 1)
            key = item.text().lower() if item else ""
            hidden = bool(needle) and needle not in key
            self.table.setRowHidden(row, hidden)
            if not hidden:
                visible += 1
        total = self.table.rowCount()
        self.count_label.setText(tr("materials.count", visible=visible, total=total))

    @staticmethod
    def _center_widget(widget: QWidget) -> QWidget:
        wrap = QWidget()
        wrap.setAttribute(Qt.WA_TranslucentBackground, True)
        lay = QHBoxLayout(wrap)
        lay.addWidget(widget)
        lay.setAlignment(Qt.AlignCenter)
        lay.setContentsMargins(4, 0, 4, 0)
        return wrap

    @staticmethod
    def _wrap_spin(spin: QSpinBox) -> QWidget:
        wrap = QWidget()
        wrap.setAttribute(Qt.WA_TranslucentBackground, True)
        lay = QHBoxLayout(wrap)
        lay.setContentsMargins(6, 4, 6, 4)
        lay.addWidget(spin)
        return wrap


class _BlueprintPage(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._save: dict[str, str] = {}
        self._original: dict[str, str] = {}
        self._rows: dict[str, QCheckBox] = {}

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(12)

        toolbar = QWidget()
        toolbar.setProperty("role", "toolbar")
        bar = QHBoxLayout(toolbar)
        bar.setContentsMargins(12, 10, 12, 10)
        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText(tr("materials.filter"))
        self.filter_edit.textChanged.connect(self._apply_filter)
        bar.addWidget(self.filter_edit, 1)
        self.count_label = QLabel("")
        self.count_label.setProperty("role", "hint")
        bar.addWidget(self.count_label)
        root.addWidget(toolbar)

        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels([
            tr("materials.col.owned"),
            tr("materials.col.key"),
            tr("materials.col.note"),
        ])
        configure_table(self.table, stretch_col=1)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Fixed)
        self.table.setColumnWidth(0, 88)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Fixed)
        self.table.setColumnWidth(2, 96)
        root.addWidget(self.table, 1)

    def load_data(self, data: dict[str, str], ref: dict[str, str] | None = None) -> None:
        ref = ref or {}
        self._save = dict(data)
        self._original = dict(self._save)
        self._rows.clear()
        self.table.setRowCount(0)

        keys = sort_blueprint_keys(self._save, ref)
        for row, key in enumerate(keys):
            owned = is_blueprint_owned(self._save, key)
            self.table.insertRow(row)
            cb = QCheckBox()
            cb.setChecked(owned)
            self.table.setCellWidget(row, 0, _QuantityPage._center_widget(cb))
            display_key = f"{entity_name(key)}\n{key}" if entity_name(key) != key else key
            self.table.setItem(row, 1, readonly_cell(display_key))
            note_item = readonly_cell("" if owned else tr("materials.note.not_owned"))
            if not owned:
                note_item.setForeground(Qt.gray)
            self.table.setItem(row, 2, note_item)
            self._rows[key] = cb

        self._apply_filter(self.filter_edit.text())

    def picks_delta(self) -> dict[str, bool]:
        delta: dict[str, bool] = {}
        for key, cb in self._rows.items():
            new_owned = cb.isChecked()
            old_owned = is_blueprint_owned(self._original, key)
            if new_owned != old_owned:
                delta[key] = new_owned
        return delta

    def _apply_filter(self, text: str) -> None:
        needle = text.strip().lower()
        visible = 0
        for row in range(self.table.rowCount()):
            item = self.table.item(row, 1)
            key = item.text().lower() if item else ""
            hidden = bool(needle) and needle not in key
            self.table.setRowHidden(row, hidden)
            if not hidden:
                visible += 1
        total = self.table.rowCount()
        self.count_label.setText(tr("materials.count", visible=visible, total=total))


class MaterialsTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(10)

        hint = QLabel(tr("materials.hint"))
        hint.setProperty("role", "hint")
        hint.setWordWrap(True)
        hint.setContentsMargins(12, 8, 12, 0)
        layout.addWidget(hint)

        self.sub_tabs = QTabWidget()
        self.sub_tabs.setProperty("role", "sub")

        self.materials = _QuantityPage(show_garden_fill=True)
        self.seeds = _QuantityPage()
        self.blueprints = _BlueprintPage()

        self.sub_tabs.addTab(self.materials, tr("materials.tab.materials"))
        self.sub_tabs.addTab(self.seeds, tr("materials.tab.seeds"))
        self.sub_tabs.addTab(self.blueprints, tr("materials.tab.blueprints"))
        layout.addWidget(self.sub_tabs, 1)

    def load_from_snapshot(
        self,
        materials: dict[str, int],
        seeds: dict[str, int],
        blueprints: dict[str, str],
        *,
        ref_materials: dict[str, int] | None = None,
        ref_seeds: dict[str, int] | None = None,
        ref_blueprints: dict[str, str] | None = None,
    ) -> None:
        self.materials.load_data(materials, ref_materials)
        self.seeds.load_data(seeds, ref_seeds)
        self.blueprints.load_data(blueprints, ref_blueprints)

    def materials_delta(self) -> dict[str, int]:
        return self.materials.picks_delta()

    def seeds_delta(self) -> dict[str, int]:
        return self.seeds.picks_delta()

    def encoded_warnings(self) -> list[str]:
        lines = encoded_change_warnings(
            self.materials.picks_delta(),
            self.materials._original,
        )
        lines.extend(encoded_change_warnings(
            self.seeds.picks_delta(),
            self.seeds._original,
        ))
        return lines

    def blueprints_delta(self) -> dict[str, bool]:
        return self.blueprints.picks_delta()