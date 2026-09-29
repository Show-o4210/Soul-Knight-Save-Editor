"""宠物解锁细调。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from gui.i18n import entity_name, tr


class PetsTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._original: dict[str, bool] = {}
        self._boxes: dict[str, QCheckBox] = {}

        layout = QVBoxLayout(self)
        hint = QLabel(tr("pets.hint"))
        hint.setProperty("role", "hint")
        hint.setWordWrap(True)
        layout.addWidget(hint)

        bar = QHBoxLayout()
        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText(tr("pets.filter"))
        self.filter_edit.textChanged.connect(self._apply_filter)
        bar.addWidget(self.filter_edit, 1)
        self.btn_all = QPushButton(tr("btn.all_unlock"))
        self.btn_none = QPushButton(tr("btn.all_off"))
        self.btn_all.clicked.connect(lambda: self._set_all(True))
        self.btn_none.clicked.connect(lambda: self._set_all(False))
        bar.addWidget(self.btn_all)
        bar.addWidget(self.btn_none)
        layout.addLayout(bar)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        self._inner = QWidget()
        self._grid = QGridLayout(self._inner)
        self._grid.setSpacing(8)
        scroll.setWidget(self._inner)
        layout.addWidget(scroll, 1)

    def load_from_snapshot(self, pets: dict[str, bool]) -> None:
        self._original = dict(pets)
        self._boxes.clear()
        while self._grid.count():
            item = self._grid.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        cols = 3
        for i, (name, unlocked) in enumerate(sorted(pets.items())):
            cb = QCheckBox(entity_name(name))
            cb.setChecked(unlocked)
            cb.setProperty("pet_name", name)
            self._boxes[name] = cb
            self._grid.addWidget(cb, i // cols, i % cols)

        self._apply_filter(self.filter_edit.text())

    def picks_delta(self) -> dict[str, bool]:
        delta: dict[str, bool] = {}
        for name, cb in self._boxes.items():
            new_val = cb.isChecked()
            if new_val != self._original.get(name):
                delta[name] = new_val
        return delta

    def _set_all(self, checked: bool) -> None:
        for cb in self._boxes.values():
            if not cb.isHidden():
                cb.setChecked(checked)

    def _apply_filter(self, text: str) -> None:
        needle = text.strip().lower()
        for name, cb in self._boxes.items():
            label = entity_name(name).lower()
            cb.setVisible(
                not needle or needle in name.lower() or needle in label
            )