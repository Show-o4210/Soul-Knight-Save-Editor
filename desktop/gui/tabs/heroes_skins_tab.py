"""角色与皮肤 — 按角色分组，皮肤挂在角色下方。"""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from gui.i18n import entity_name, tr
from gui.widgets.table_style import wrap_spinbox

_SKIN_COLS = 6


class HeroesSkinsTab(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self._original_heroes: dict[str, dict] = {}
        self._original_skins: dict[str, dict[int, int]] = {}
        self._cards: dict[str, _HeroCard] = {}

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(12)

        hint = QLabel(tr("heroes_skins.hint"))
        hint.setProperty("role", "hint")
        hint.setWordWrap(True)
        root.addWidget(hint)

        toolbar = QWidget()
        toolbar.setProperty("role", "toolbar")
        bar = QHBoxLayout(toolbar)
        bar.setContentsMargins(12, 10, 12, 10)
        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText(tr("heroes_skins.filter"))
        self.filter_edit.textChanged.connect(self._apply_filter)
        bar.addWidget(self.filter_edit, 1)
        self.btn_heroes_on = QPushButton(tr("heroes_skins.btn.heroes_on"))
        self.btn_heroes_off = QPushButton(tr("heroes_skins.btn.heroes_off"))
        self.btn_skins_on = QPushButton(tr("heroes_skins.btn.skins_on"))
        self.btn_skins_off = QPushButton(tr("heroes_skins.btn.skins_off"))
        self.btn_heroes_on.clicked.connect(lambda: self._set_visible_heroes(True))
        self.btn_heroes_off.clicked.connect(lambda: self._set_visible_heroes(False))
        self.btn_skins_on.clicked.connect(lambda: self._set_visible_skins(True))
        self.btn_skins_off.clicked.connect(lambda: self._set_visible_skins(False))
        bar.addWidget(self.btn_heroes_on)
        bar.addWidget(self.btn_heroes_off)
        bar.addWidget(self.btn_skins_on)
        bar.addWidget(self.btn_skins_off)
        root.addWidget(toolbar)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self._inner = QWidget()
        self._list = QVBoxLayout(self._inner)
        self._list.setSpacing(10)
        self._list.setContentsMargins(0, 0, 4, 0)
        scroll.setWidget(self._inner)
        root.addWidget(scroll, 1)

    def load_from_snapshot(self, hero_data: list[dict], skin_data: dict[str, list]) -> None:
        self._clear_cards()
        self._original_heroes = {
            h["name"]: {
                "unlock": h["unlock"],
                "level": h["level"],
                "skills": h.get("skills_all", False),
            }
            for h in hero_data
        }
        self._original_skins = {
            hero: {e["Key"]: e.get("Value", 0) for e in skins}
            for hero, skins in skin_data.items()
        }

        hero_order = [h["name"] for h in hero_data]
        extra = [k for k in skin_data if k not in hero_order]
        for hero in [*hero_order, *extra]:
            skins = skin_data.get(hero, [])
            card = _HeroCard(hero, self._original_heroes.get(hero, {}), skins)
            self._cards[hero] = card
            self._list.addWidget(card.group)
        self._list.addStretch()
        self._apply_filter(self.filter_edit.text())

    def hero_picks_delta(self) -> dict[str, dict]:
        delta: dict[str, dict] = {}
        for hero, card in self._cards.items():
            opts = card.hero_delta(self._original_heroes.get(hero, {}))
            if opts:
                delta[hero] = opts
        return delta

    def skin_picks_delta(self, skin_data: dict[str, list]) -> dict[str, dict[int, int]]:
        original = {
            hero: {e["Key"]: e.get("Value", 0) for e in skins}
            for hero, skins in skin_data.items()
        }
        delta: dict[str, dict[int, int]] = {}
        for hero, card in self._cards.items():
            part = card.skin_delta(original.get(hero, {}))
            if part:
                delta[hero] = part
        return delta

    def _clear_cards(self) -> None:
        self._cards.clear()
        while self._list.count():
            item = self._list.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

    def _apply_filter(self, text: str) -> None:
        needle = text.strip().lower()
        for hero, card in self._cards.items():
            label = entity_name(hero).lower()
            visible = not needle or needle in hero.lower() or needle in label
            card.group.setVisible(visible)

    def _set_visible_heroes(self, checked: bool) -> None:
        for card in self._cards.values():
            if card.group.isVisible():
                card.unlock_cb.setChecked(checked)

    def _set_visible_skins(self, checked: bool) -> None:
        for card in self._cards.values():
            if card.group.isVisible():
                for cb in card.skin_boxes.values():
                    cb.setChecked(checked)


class _HeroCard:
    def __init__(self, hero: str, hero_state: dict, skins: list[dict]) -> None:
        self.hero = hero
        self.skin_boxes: dict[int, QCheckBox] = {}

        self.group = QGroupBox(entity_name(hero))
        self.group.setProperty("role", "hero-card")
        layout = QVBoxLayout(self.group)
        layout.setSpacing(10)

        header = QHBoxLayout()
        header.setSpacing(12)
        self.unlock_cb = QCheckBox(tr("heroes.col.unlock"))
        self.unlock_cb.setChecked(bool(hero_state.get("unlock")))
        header.addWidget(self.unlock_cb)

        name_lbl = QLabel(hero)
        name_lbl.setProperty("role", "hero-key")
        header.addWidget(name_lbl)
        header.addStretch()

        level_lbl = QLabel(tr("heroes.col.level"))
        level_lbl.setProperty("role", "hero-meta")
        header.addWidget(level_lbl)
        level_spin = QSpinBox()
        level_spin.setRange(1, 15)
        level_spin.setValue(int(hero_state.get("level", 1)))
        self.level_spin = level_spin
        header.addWidget(wrap_spinbox(level_spin, min_width=88))

        self.skill_cb = QCheckBox(tr("heroes.col.skills"))
        self.skill_cb.setChecked(bool(hero_state.get("skills")))
        header.addWidget(self.skill_cb)
        layout.addLayout(header)

        owned = sum(1 for s in skins if s.get("Value") == 1)
        skin_title = QLabel(tr("heroes_skins.skins_row", owned=owned, total=len(skins)))
        skin_title.setProperty("role", "hero-meta")
        layout.addWidget(skin_title)

        skin_grid_host = QWidget()
        skin_grid = QGridLayout(skin_grid_host)
        skin_grid.setContentsMargins(0, 0, 0, 0)
        skin_grid.setHorizontalSpacing(14)
        skin_grid.setVerticalSpacing(8)
        for i, entry in enumerate(skins):
            key = int(entry.get("Key", 0))
            cb = QCheckBox(tr("skins.chip", index=key))
            cb.setChecked(entry.get("Value", 0) == 1)
            self.skin_boxes[key] = cb
            skin_grid.addWidget(cb, i // _SKIN_COLS, i % _SKIN_COLS)
        layout.addWidget(skin_grid_host)

    def hero_delta(self, original: dict) -> dict:
        opts: dict = {}
        if self.unlock_cb.isChecked() != original.get("unlock"):
            opts["unlock"] = self.unlock_cb.isChecked()
        if self.level_spin.value() != original.get("level"):
            opts["level"] = self.level_spin.value()
        orig_skills = bool(original.get("skills"))
        if self.skill_cb.isChecked() != orig_skills:
            if self.skill_cb.isChecked():
                opts["skills"] = True
        return opts

    def skin_delta(self, original: dict[int, int]) -> dict[int, int]:
        delta: dict[int, int] = {}
        for key, cb in self.skin_boxes.items():
            new_val = 1 if cb.isChecked() else 0
            if new_val != original.get(key, 0):
                delta[key] = new_val
        return delta