"""花圃 — 子 Tab：植物槽位 / 盆位与解锁。"""
from __future__ import annotations

from typing import Any

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QGridLayout,
    QHBoxLayout,
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

from core.constants import PLANT_POT_SLOTS
from gui.i18n import entity_name, tr
from gui.widgets.table_section import TableSection
from gui.widgets.table_style import cell_spinbox, wrap_spinbox


class GardenTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._original_unlock: list[str] = []
        self._original_plants: list[Any] = []
        self._pot_boxes: dict[int, QCheckBox] = {}
        self._extra_boxes: dict[str, QCheckBox] = {}

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(12)

        hint = QLabel(tr("garden.hint"))
        hint.setProperty("role", "hint")
        hint.setWordWrap(True)
        root.addWidget(hint)

        self.sub_tabs = QTabWidget()
        self.sub_tabs.setProperty("role", "sub")
        self.sub_tabs.addTab(self._build_plants_page(), tr("garden.sub.plants"))
        self.sub_tabs.addTab(self._build_pots_page(), tr("garden.sub.pots"))
        root.addWidget(self.sub_tabs, 1)

    def _build_plants_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(4, 12, 4, 8)
        layout.setSpacing(10)

        state_hint = QLabel(tr("garden.state.warning"))
        state_hint.setProperty("role", "notice-warn")
        state_hint.setWordWrap(True)
        layout.addWidget(state_hint)

        self.plants_table = QTableWidget(0, 6)
        self.plants_table.setHorizontalHeaderLabels([
            tr("garden.plants.col.slot"),
            tr("garden.plants.col.name"),
            tr("garden.plants.col.state"),
            tr("garden.plants.col.watered"),
            tr("garden.plants.col.fertilized"),
            tr("garden.plants.col.clear"),
        ])
        section = TableSection(
            tr("garden.plants.edit.title"),
            self.plants_table,
            min_visible_rows=8,
            stretch_col=1,
        )
        layout.addWidget(section, 1)
        return page

    def _build_pots_page(self) -> QWidget:
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.NoFrame)

        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(4, 12, 4, 12)
        layout.setSpacing(16)

        pots_title = QLabel(tr("garden.pots.title"))
        pots_title.setProperty("role", "section-inline")
        layout.addWidget(pots_title)
        pots_hint = QLabel(tr("garden.pots.hint"))
        pots_hint.setProperty("role", "hint")
        pots_hint.setWordWrap(True)
        layout.addWidget(pots_hint)

        pot_grid_host = QWidget()
        pot_grid = QGridLayout(pot_grid_host)
        pot_grid.setHorizontalSpacing(16)
        pot_grid.setVerticalSpacing(10)
        self._pot_boxes.clear()
        for i, n in enumerate(PLANT_POT_SLOTS):
            cb = QCheckBox(entity_name(f"plant_pot{n}"))
            self._pot_boxes[n] = cb
            pot_grid.addWidget(cb, i // 3, i % 3)
        layout.addWidget(pot_grid_host)

        self._extra_title = QLabel(tr("garden.unlock.extra"))
        self._extra_title.setProperty("role", "section-inline")
        layout.addWidget(self._extra_title)
        self._extra_host = QWidget()
        self._extra_grid = QGridLayout(self._extra_host)
        self._extra_grid.setHorizontalSpacing(16)
        self._extra_grid.setVerticalSpacing(10)
        layout.addWidget(self._extra_host)

        append_title = QLabel(tr("garden.append.title"))
        append_title.setProperty("role", "section-inline")
        layout.addWidget(append_title)
        self.append_edit = QLineEdit()
        self.append_edit.setPlaceholderText(tr("garden.append.placeholder"))
        layout.addWidget(self.append_edit)
        add_hint = QLabel(tr("garden.append.hint"))
        add_hint.setProperty("role", "hint")
        add_hint.setWordWrap(True)
        layout.addWidget(add_hint)
        layout.addStretch()

        scroll.setWidget(page)
        wrapper = QWidget()
        wl = QVBoxLayout(wrapper)
        wl.setContentsMargins(0, 0, 0, 0)
        wl.addWidget(scroll)
        return wrapper

    def load_from_snapshot(self, item_unlock: list[str], plants: list | None = None) -> None:
        self._original_unlock = list(item_unlock)
        self._original_plants = list(plants or [])

        for n, cb in self._pot_boxes.items():
            cb.setChecked(f"plant_pot{n}" in item_unlock)

        self._rebuild_extra_unlock(item_unlock)
        self.append_edit.clear()
        self._fill_plants_table(self._original_plants)

    def _rebuild_extra_unlock(self, item_unlock: list[str]) -> None:
        while self._extra_grid.count():
            item = self._extra_grid.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self._extra_boxes.clear()

        extras = [k for k in item_unlock if not k.startswith("plant_pot")]
        if not extras:
            self._extra_title.hide()
            self._extra_host.hide()
            return
        self._extra_title.show()
        self._extra_host.show()
        for i, key in enumerate(extras):
            cb = QCheckBox(entity_name(key))
            cb.setChecked(True)
            self._extra_boxes[key] = cb
            self._extra_grid.addWidget(cb, i // 2, i % 2)

    def _fill_plants_table(self, plants: list[Any]) -> None:
        rows = max(len(plants), 8)
        self.plants_table.setRowCount(rows)
        for i in range(rows):
            p = plants[i] if i < len(plants) else None
            slot_item = QTableWidgetItem(str(i))
            slot_item.setFlags(Qt.ItemIsEnabled)
            self.plants_table.setItem(i, 0, slot_item)

            name_edit = QLineEdit()
            if isinstance(p, dict):
                name_edit.setText(str(p.get("plantName") or ""))
                name_edit.setPlaceholderText(entity_name(str(p.get("plantName") or "")))
            name_w = QWidget()
            nl = QHBoxLayout(name_w)
            nl.setContentsMargins(6, 2, 6, 2)
            nl.addWidget(name_edit)
            self.plants_table.setCellWidget(i, 1, name_w)

            state_spin = QSpinBox()
            state_spin.setRange(0, 4)
            state_spin.setValue(int(p.get("state", 0)) if isinstance(p, dict) else 0)
            self.plants_table.setCellWidget(i, 2, wrap_spinbox(state_spin))

            watered = QCheckBox()
            watered.setChecked(bool(p.get("watered")) if isinstance(p, dict) else False)
            ww = QWidget()
            wl = QHBoxLayout(ww)
            wl.addWidget(watered)
            wl.setAlignment(Qt.AlignCenter)
            wl.setContentsMargins(0, 0, 0, 0)
            self.plants_table.setCellWidget(i, 3, ww)

            fert = QCheckBox()
            fert.setChecked(bool(p.get("fertilized")) if isinstance(p, dict) else False)
            fw = QWidget()
            fl = QHBoxLayout(fw)
            fl.addWidget(fert)
            fl.setAlignment(Qt.AlignCenter)
            fl.setContentsMargins(0, 0, 0, 0)
            self.plants_table.setCellWidget(i, 4, fw)

            clear_cb = QCheckBox()
            cw = QWidget()
            cl = QHBoxLayout(cw)
            cl.addWidget(clear_cb)
            cl.setAlignment(Qt.AlignCenter)
            cl.setContentsMargins(0, 0, 0, 0)
            self.plants_table.setCellWidget(i, 5, cw)

    def _row_widget(self, row: int, col: int):
        cell = self.plants_table.cellWidget(row, col)
        if cell and cell.layout() and cell.layout().count():
            return cell.layout().itemAt(0).widget()
        return cell

    def item_unlock_picks(self) -> dict[str, bool]:
        picks: dict[str, bool] = {}
        for n, cb in self._pot_boxes.items():
            key = f"plant_pot{n}"
            want = cb.isChecked()
            if want != (key in self._original_unlock):
                picks[key] = want
        for key, cb in self._extra_boxes.items():
            want = cb.isChecked()
            if want != (key in self._original_unlock):
                picks[key] = want
        return picks

    def item_unlock_append(self) -> list[str]:
        text = self.append_edit.text().strip()
        if not text:
            return []
        items = [x.strip() for x in text.replace("，", ",").split(",") if x.strip()]
        return [x for x in items if x not in self._original_unlock]

    def plants_delta(self) -> list[dict[str, Any]]:
        picks: list[dict[str, Any]] = []
        for row in range(self.plants_table.rowCount()):
            clear_cb = self._row_widget(row, 5)
            if isinstance(clear_cb, QCheckBox) and clear_cb.isChecked():
                orig = self._original_plants[row] if row < len(self._original_plants) else None
                if orig is not None:
                    picks.append({"index": row, "clear": True})
                continue

            name_edit = self._row_widget(row, 1)
            state_spin = cell_spinbox(self.plants_table, row, 2)
            watered = self._row_widget(row, 3)
            fert = self._row_widget(row, 4)
            if not isinstance(name_edit, QLineEdit) or state_spin is None:
                continue

            orig = self._original_plants[row] if row < len(self._original_plants) else None
            new_name = name_edit.text().strip() or None
            new_state = state_spin.value()
            new_watered = watered.isChecked() if isinstance(watered, QCheckBox) else False
            new_fert = fert.isChecked() if isinstance(fert, QCheckBox) else False

            if orig is None and not new_name:
                continue
            if isinstance(orig, dict):
                if (
                    orig.get("plantName") == new_name
                    and int(orig.get("state", 0)) == new_state
                    and bool(orig.get("watered")) == new_watered
                    and bool(orig.get("fertilized")) == new_fert
                ):
                    continue

            picks.append({
                "index": row,
                "plantName": new_name,
                "state": new_state,
                "watered": new_watered,
                "fertilized": new_fert,
            })
        return picks