"""武器获取次数（statistic._weaponUsedTimes）。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QSpinBox,
    QTableWidget,
    QVBoxLayout,
    QWidget,
)

from core.constants import MAX_QUANTITY
from core.weapon_util import collect_weapon_ids
from gui.i18n import entity_name, tr
from gui.widgets.table_style import configure_table, readonly_cell


def _center_widget(w: QWidget) -> QWidget:
    wrap = QWidget()
    lay = QHBoxLayout(wrap)
    lay.setContentsMargins(0, 0, 0, 0)
    lay.addStretch()
    lay.addWidget(w)
    lay.addStretch()
    return wrap


class WeaponsTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._save: dict[str, int] = {}
        self._original: dict[str, int] = {}
        self._rows: dict[str, tuple[QCheckBox, QSpinBox]] = {}

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(10)

        hint = QLabel(tr("weapons.hint"))
        hint.setProperty("role", "hint")
        hint.setWordWrap(True)
        hint.setContentsMargins(12, 8, 12, 0)
        layout.addWidget(hint)

        toolbar = QWidget()
        toolbar.setProperty("role", "toolbar")
        bar = QHBoxLayout(toolbar)
        bar.setContentsMargins(12, 10, 12, 10)
        bar.setSpacing(12)

        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText(tr("weapons.filter"))
        self.filter_edit.textChanged.connect(self._apply_filter)
        bar.addWidget(self.filter_edit, 1)

        self.stat_label = QLabel("")
        self.stat_label.setProperty("role", "hint")
        bar.addWidget(self.stat_label)

        self.count_label = QLabel("")
        self.count_label.setProperty("role", "hint")
        bar.addWidget(self.count_label)
        layout.addWidget(toolbar)

        self.table = QTableWidget(0, 4)
        self.table.setHorizontalHeaderLabels([
            tr("weapons.col.enable"),
            tr("weapons.col.id"),
            tr("weapons.col.count"),
            tr("weapons.col.ref"),
        ])
        configure_table(self.table, stretch_col=1)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Fixed)
        self.table.setColumnWidth(0, 72)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Fixed)
        self.table.setColumnWidth(2, 88)
        self.table.horizontalHeader().setSectionResizeMode(3, QHeaderView.Fixed)
        self.table.setColumnWidth(3, 64)
        layout.addWidget(self.table, 1)

    def load_from_snapshot(
        self,
        statistic_snapshot: dict,
        *,
        ref_weapon_used: dict[str, int] | None = None,
        statistic_loaded: bool = False,
    ) -> None:
        used = dict(statistic_snapshot.get("weapon_used_times") or {})
        ref = ref_weapon_used or {}
        self._save = {k: int(v) for k, v in used.items()}
        self._original = dict(self._save)
        self._rows.clear()
        self.table.setRowCount(0)

        keys = collect_weapon_ids(used_times=used, ref_used=ref)
        if not keys:
            keys = sorted(self._save.keys())

        for row, key in enumerate(keys):
            cur = int(self._save.get(key, 0))
            self.table.insertRow(row)
            cb = QCheckBox()
            cb.setChecked(False)
            spin = QSpinBox()
            spin.setRange(0, MAX_QUANTITY)
            spin.setValue(cur)
            spin.setEnabled(False)
            cb.toggled.connect(spin.setEnabled)

            self.table.setCellWidget(row, 0, _center_widget(cb))
            display = f"{entity_name(key)}\n{key}" if entity_name(key) != key else key
            self.table.setItem(row, 1, readonly_cell(display))
            self.table.setCellWidget(row, 2, _center_widget(spin))
            ref_val = ref.get(key)
            ref_item = readonly_cell(str(ref_val) if ref_val is not None else "—")
            if ref_val is None:
                ref_item.setForeground(Qt.gray)
            self.table.setItem(row, 3, ref_item)
            self._rows[key] = (cb, spin)

        parts = [tr("weapons.stat.used", n=statistic_snapshot.get("weapon_used_count", 0))]
        if not statistic_loaded:
            parts.append(tr("weapons.stat.no_statistic"))
        self.stat_label.setText(" · ".join(parts))
        self._apply_filter(self.filter_edit.text())

    def used_delta(self) -> dict[str, int]:
        delta: dict[str, int] = {}
        for key, (cb, spin) in self._rows.items():
            if not cb.isChecked():
                continue
            new_val = spin.value()
            if new_val != int(self._original.get(key, 0)):
                delta[key] = new_val
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
        self.count_label.setText(tr("weapons.used.count", visible=visible, total=self.table.rowCount()))