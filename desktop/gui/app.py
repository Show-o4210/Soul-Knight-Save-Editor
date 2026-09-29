#!/usr/bin/env python3
"""存档编辑器 GUI — 完整版。"""
from __future__ import annotations

import json
import sys
import traceback
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import QThread, Signal
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QTabWidget,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from core.constants import INPUT_DIR, OUTPUT_DIR, REF_DIR
from core.workspace import SaveWorkspace
from engine.patch_plan import PatchPlan
from engine.patch_runner import PatchRunner
from gui.i18n import tr
from gui.i18n.workspace_fmt import (
    input_file_chips,
    notice_lines,
    reference_file_chips,
    summary_pairs,
)
from gui.styles import STYLESHEET
from gui.tabs.garden_tab import GardenTab
from gui.tabs.heroes_skins_tab import HeroesSkinsTab
from gui.tabs.inspect_tab import InspectTab
from gui.tabs.materials_tab import MaterialsTab
from gui.tabs.pets_tab import PetsTab
from gui.tabs.room_tab import RoomTab
from gui.tabs.settings_tab import SettingsTab
from gui.tabs.weapons_tab import WeaponsTab
from gui.widgets.stat_card import StatCard
from gui.widgets.status_board import ChipRow, KeyValueGrid, NoticeList


class PreviewDialog(QDialog):
    def __init__(self, parent: QWidget, text: str) -> None:
        super().__init__(parent)
        self.setWindowTitle(tr("dialog.preview.title"))
        self.resize(680, 540)
        layout = QVBoxLayout(self)
        box = QTextEdit()
        box.setReadOnly(True)
        box.setPlainText(text)
        layout.addWidget(box)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok)
        buttons.accepted.connect(self.accept)
        layout.addWidget(buttons)


class PatchWorker(QThread):
    finished_ok = Signal(dict)
    failed = Signal(str)
    log_line = Signal(str)

    def __init__(self, runner: PatchRunner, plan: PatchPlan) -> None:
        super().__init__()
        self.runner = runner
        self.plan = plan

    def run(self) -> None:
        try:
            report = self.runner.apply(self.plan, log=lambda m: self.log_line.emit(m))
            self.finished_ok.emit(report)
        except Exception:
            self.failed.emit(traceback.format_exc())


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle(tr("app.title"))
        self.resize(1120, 860)
        self.setMinimumSize(900, 640)
        self.worker: PatchWorker | None = None
        self._snap = None
        self._ws = None
        self._skin_data: dict = {}

        wrap = QWidget()
        self.setCentralWidget(wrap)
        root = QVBoxLayout(wrap)
        root.setContentsMargins(24, 18, 24, 18)
        root.setSpacing(12)

        header = QHBoxLayout()
        title_box = QVBoxLayout()
        title = QLabel(tr("app.heading"))
        title.setProperty("role", "title")
        sub = QLabel(tr("app.subtitle"))
        sub.setProperty("role", "hint")
        title_box.addWidget(title)
        title_box.addWidget(sub)
        header.addLayout(title_box, 1)
        root.addLayout(header)

        self.tabs = QTabWidget()
        root.addWidget(self.tabs, 1)

        self.settings_tab = SettingsTab(INPUT_DIR, OUTPUT_DIR, REF_DIR)
        self._build_workspace_tab()
        self.heroes_skins_tab = HeroesSkinsTab()
        self.tabs.addTab(self.heroes_skins_tab, tr("tab.heroes_skins"))
        self.pets_tab = PetsTab()
        self.tabs.addTab(self.pets_tab, tr("tab.pets"))
        self.garden_tab = GardenTab()
        self.tabs.addTab(self.garden_tab, tr("tab.garden"))
        self.room_tab = RoomTab()
        self.tabs.addTab(self.room_tab, tr("tab.room"))
        self.materials_tab = MaterialsTab()
        self.tabs.addTab(self.materials_tab, tr("tab.materials"))
        self.weapons_tab = WeaponsTab()
        self.tabs.addTab(self.weapons_tab, tr("tab.weapons"))
        self.inspect_tab = InspectTab()
        self.tabs.addTab(self.inspect_tab, tr("tab.inspect"))
        self.tabs.addTab(self.settings_tab, tr("tab.settings"))
        self._build_log_tab()

        bar = QHBoxLayout()
        bar.setSpacing(12)
        self.btn_refresh = QPushButton(tr("btn.refresh"))
        self.btn_refresh.clicked.connect(self.refresh)
        self.btn_preview = QPushButton(tr("btn.preview"))
        self.btn_preview.clicked.connect(self.preview_plan)
        self.btn_apply = QPushButton(tr("btn.apply"))
        self.btn_apply.setProperty("primary", "true")
        self.btn_apply.clicked.connect(self.run_patch)
        bar.addWidget(self.btn_refresh)
        bar.addWidget(self.btn_preview)
        bar.addStretch()
        bar.addWidget(self.btn_apply)
        root.addLayout(bar)

        self.status = QLabel(tr("status.ready"))
        self.status.setProperty("role", "hint")
        root.addWidget(self.status)

        self.refresh()

    @property
    def row_input(self):
        return self.settings_tab.row_input

    @property
    def row_output(self):
        return self.settings_tab.row_output

    @property
    def row_ref(self):
        return self.settings_tab.row_ref

    def _build_workspace_tab(self) -> None:
        page = QWidget()
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.NoFrame)
        inner = QWidget()
        layout = QVBoxLayout(inner)
        layout.setSpacing(16)
        layout.setContentsMargins(4, 8, 4, 12)

        stats_row = QHBoxLayout()
        stats_row.setSpacing(12)
        self.stat_uid = StatCard(tr("stat.uid"))
        self.stat_heroes = StatCard(tr("stat.heroes"))
        self.stat_skins = StatCard(tr("stat.skins"))
        self.stat_pets = StatCard(tr("stat.pets"))
        self.stat_gems = StatCard(tr("stat.gems"))
        for card in (
            self.stat_uid,
            self.stat_heroes,
            self.stat_skins,
            self.stat_pets,
            self.stat_gems,
        ):
            stats_row.addWidget(card, 1)
        layout.addLayout(stats_row)

        overview_row = QHBoxLayout()
        overview_row.setSpacing(16)
        self.summary_board = KeyValueGrid(tr("workspace.summary.title"))
        overview_row.addWidget(self.summary_board, 3)

        files_col = QVBoxLayout()
        files_col.setSpacing(12)
        self.input_chips = ChipRow(tr("workspace.files.input"))
        self.ref_chips = ChipRow(tr("workspace.files.ref"))
        files_col.addWidget(self.input_chips)
        files_col.addWidget(self.ref_chips)
        files_wrap = QWidget()
        files_wrap.setLayout(files_col)
        overview_row.addWidget(files_wrap, 2)
        layout.addLayout(overview_row)

        self.notice_board = NoticeList(tr("workspace.notice.title"))
        layout.addWidget(self.notice_board)

        actions = QHBoxLayout()
        actions.setSpacing(16)

        g_preset = QGroupBox(tr("workspace.preset.title"))
        pl = QVBoxLayout(g_preset)
        self.chk_chars = QCheckBox(tr("workspace.preset.chars"))
        self.chk_skins = QCheckBox(tr("workspace.preset.skins"))
        self.chk_pets = QCheckBox(tr("workspace.preset.pets"))
        self.chk_pots = QCheckBox(tr("workspace.preset.pots"))
        self.chk_weapon_forge = QCheckBox(tr("workspace.preset.weapon_forge"))
        self.chk_cleanup = QCheckBox(tr("workspace.preset.cleanup"))

        self.spin_level = QSpinBox()
        self.spin_level.setRange(1, 15)
        self.spin_level.setValue(7)
        level_row = QHBoxLayout()
        level_row.setSpacing(12)
        level_row.addWidget(QLabel(tr("workspace.preset.level")))
        level_row.addWidget(self.spin_level)
        level_row.addStretch()

        preset_hint = QLabel(tr("workspace.preset.hint"))
        preset_hint.setProperty("role", "hint")
        pl.addWidget(preset_hint)
        pl.addWidget(self.chk_chars)
        pl.addWidget(self.chk_skins)
        pl.addWidget(self.chk_pets)
        pl.addWidget(self.chk_pots)
        pl.addWidget(self.chk_weapon_forge)
        pl.addWidget(self.chk_cleanup)
        pl.addLayout(level_row)
        actions.addWidget(g_preset, 1)

        g_merge = QGroupBox(tr("workspace.merge.title"))
        ml = QVBoxLayout(g_merge)
        merge_hint = QLabel(tr("workspace.merge.hint"))
        merge_hint.setProperty("role", "hint")
        ml.addWidget(merge_hint)
        merge_grid = QGridLayout()
        merge_grid.setHorizontalSpacing(16)
        merge_grid.setVerticalSpacing(4)
        self.chk_merge_bp = QCheckBox(tr("workspace.merge.bp"))
        self.chk_merge_seeds = QCheckBox(tr("workspace.merge.seeds"))
        self.chk_merge_unlock = QCheckBox(tr("workspace.merge.unlock"))
        self.chk_merge_mythic = QCheckBox(tr("workspace.merge.mythic"))
        self.chk_merge_materials = QCheckBox(tr("workspace.merge.materials"))
        self.chk_merge_materials_max = QCheckBox(tr("workspace.merge.materials_max"))
        self.chk_merge_materials_max.setEnabled(False)
        self.chk_merge_materials.toggled.connect(self._on_merge_materials_toggled)
        self.chk_merge_forge = QCheckBox(tr("workspace.merge.forge"))
        self.chk_merge_jewelry = QCheckBox(tr("workspace.merge.jewelry"))
        self.chk_merge_decorate = QCheckBox(tr("workspace.merge.decorate"))
        self.chk_merge_weapon_evo = QCheckBox(tr("workspace.merge.weapon_evo"))
        self.chk_merge_weapon_evo_max = QCheckBox(tr("workspace.merge.weapon_evo_max"))
        self.chk_merge_weapon_used = QCheckBox(tr("workspace.merge.weapon_used"))
        self.chk_merge_weapon_used_max = QCheckBox(tr("workspace.merge.weapon_used_max"))
        self.chk_merge_weapon_used_max.setEnabled(False)
        self.chk_merge_weapon_used.toggled.connect(self._on_merge_weapon_used_toggled)
        merge_checks = (
            self.chk_merge_bp,
            self.chk_merge_seeds,
            self.chk_merge_unlock,
            self.chk_merge_mythic,
            self.chk_merge_materials,
            self.chk_merge_materials_max,
            self.chk_merge_forge,
            self.chk_merge_jewelry,
            self.chk_merge_decorate,
            self.chk_merge_weapon_evo,
            self.chk_merge_weapon_evo_max,
            self.chk_merge_weapon_used,
            self.chk_merge_weapon_used_max,
        )
        for i, w in enumerate(merge_checks):
            merge_grid.addWidget(w, i // 2, i % 2)
        ml.addLayout(merge_grid)
        actions.addWidget(g_merge, 1)

        layout.addLayout(actions)
        layout.addStretch()

        scroll.setWidget(inner)
        outer = QVBoxLayout(page)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(scroll)
        self.tabs.insertTab(0, page, tr("tab.workspace"))

    def _on_merge_materials_toggled(self, checked: bool) -> None:
        self.chk_merge_materials_max.setEnabled(checked)
        if not checked:
            self.chk_merge_materials_max.setChecked(False)

    def _on_merge_weapon_used_toggled(self, checked: bool) -> None:
        self.chk_merge_weapon_used_max.setEnabled(checked)
        if not checked:
            self.chk_merge_weapon_used_max.setChecked(False)

    def _build_log_tab(self) -> None:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        self.log = QTextEdit()
        self.log.setReadOnly(True)
        layout.addWidget(self.log)
        self.tabs.addTab(page, tr("tab.log"))

    def _log(self, msg: str) -> None:
        ts = datetime.now().strftime("%H:%M:%S")
        self.log.append(f"[{ts}] {msg}")

    def _log_plan(self, plan: PatchPlan, title: str) -> None:
        self._log(f"—— {title} ——")
        for line in plan.describe():
            self._log(f"  · {line}")

    def _log_preview_detail(self, preview: dict) -> None:
        for line in preview.get("detail_lines", []):
            self._log(f"  {line}")
        for line in preview.get("changes", []) or []:
            self._log(f"  Δ {line}")
        stats = preview.get("stats") or {}
        if stats:
            self._log(f"  统计: {stats}")

    def _log_report(self, report: dict) -> None:
        self._log("—— 应用完成 ——")
        for name in report.get("output_files", []):
            self._log(f"  输出 → {name}")
        for key, val in (report.get("stats") or {}).items():
            self._log(f"  {key}: {val}")
        for line in report.get("changes") or []:
            self._log(f"  Δ {line}")

    def _build_plan(self) -> PatchPlan:
        skin_picks = {}
        if not self.chk_skins.isChecked() and self._skin_data:
            skin_picks = self.heroes_skins_tab.skin_picks_delta(self._skin_data)

        hero_picks = {}
        if not self.chk_chars.isChecked():
            hero_picks = self.heroes_skins_tab.hero_picks_delta()

        pet_picks = {}
        if not self.chk_pets.isChecked():
            pet_picks = self.pets_tab.picks_delta()

        pot_slots: list[int] = []
        item_unlock_picks: dict[str, bool] = {}
        item_append: list[str] = []
        if not self.chk_pots.isChecked():
            item_unlock_picks = self.garden_tab.item_unlock_picks()
            item_append = self.garden_tab.item_unlock_append()

        return PatchPlan(
            all_characters=self.chk_chars.isChecked(),
            all_skins=self.chk_skins.isChecked(),
            all_pets=self.chk_pets.isChecked(),
            plant_pots=self.chk_pots.isChecked(),
            unlock_weapon_forge=self.chk_weapon_forge.isChecked(),
            plant_pot_slots=pot_slots,
            item_unlock_append=item_append,
            item_unlock_picks=item_unlock_picks,
            plant_picks=self.garden_tab.plants_delta(),
            cleanup_stale_uid=self.chk_cleanup.isChecked(),
            force_legacy_format=True,
            merge_blueprints=self.chk_merge_bp.isChecked(),
            merge_seeds=self.chk_merge_seeds.isChecked(),
            merge_item_unlock=self.chk_merge_unlock.isChecked(),
            merge_mythic_weapons=self.chk_merge_mythic.isChecked(),
            merge_materials=self.chk_merge_materials.isChecked(),
            merge_materials_max=self.chk_merge_materials_max.isChecked(),
            merge_forge_weapons=self.chk_merge_forge.isChecked(),
            merge_jewelry=self.chk_merge_jewelry.isChecked(),
            merge_room_decorate=self.chk_merge_decorate.isChecked(),
            merge_weapon_evolution=self.chk_merge_weapon_evo.isChecked(),
            merge_weapon_evolution_max=self.chk_merge_weapon_evo_max.isChecked(),
            merge_weapon_used_times=self.chk_merge_weapon_used.isChecked(),
            merge_weapon_used_max=self.chk_merge_weapon_used_max.isChecked(),
            skin_picks=skin_picks,
            hero_picks=hero_picks,
            pet_picks=pet_picks,
            material_picks=self.materials_tab.materials_delta(),
            seed_picks=self.materials_tab.seeds_delta(),
            blueprint_picks=self.materials_tab.blueprints_delta(),
            weapon_used_picks=self.weapons_tab.used_delta(),
            room_object_levels=self.room_tab.levels_delta(),
            secret_keys_append=self.room_tab.secret_keys_append(),
            default_level=self.spin_level.value(),
        )

    def refresh(self) -> None:
        try:
            ws = SaveWorkspace(self.row_input.path(), self.row_ref.path())
            snap = ws.load()
        except Exception as e:
            self._on_refresh_failed(e)
            return

        self._snap = snap
        self._ws = ws
        self._skin_data = snap.skin_data
        is_full = snap.item_snapshot or {}

        # 各 Tab 独立装载：单个 Tab 异常不拖垮整次刷新
        tab_errors: list[str] = []
        for label, fn in (
            ("heroes_skins", lambda: self.heroes_skins_tab.load_from_snapshot(
                snap.hero_data, snap.skin_data
            )),
            ("pets", lambda: self.pets_tab.load_from_snapshot(snap.pet_data)),
            ("garden", lambda: self.garden_tab.load_from_snapshot(
                is_full.get("itemUnlock", []),
                is_full.get("plants"),
            )),
            ("room", lambda: self.room_tab.load_from_snapshot(snap.room_object_levels)),
            ("materials", lambda: self.materials_tab.load_from_snapshot(
                is_full.get("materials", {}),
                is_full.get("seeds", {}),
                is_full.get("blueprints", {}),
                ref_materials=snap.ref_materials,
                ref_seeds=snap.ref_seeds,
                ref_blueprints=snap.ref_blueprints,
            )),
            ("weapons", lambda: self.weapons_tab.load_from_snapshot(
                snap.statistic_snapshot,
                ref_weapon_used=snap.ref_weapon_used_times,
                statistic_loaded=snap.statistic_loaded,
            )),
            ("inspect", lambda: self.inspect_tab.bind_workspace(ws, snap.shard_summaries)),
        ):
            try:
                fn()
            except Exception as exc:
                tab_errors.append(f"{label}: {exc}")
                self._log(f"  ⚠ Tab {label} 装载失败: {exc}")

        gs = snap.game_summary or {}
        self.stat_uid.set_value(str(snap.uid))
        if snap.game_loaded:
            self.stat_heroes.set_value(f"{gs.get('heroes_unlocked', 0)}/{gs.get('heroes_total', 0)}")
            self.stat_skins.set_value(f"{gs.get('skins_owned', 0)}/{gs.get('skins_total', 0)}")
            self.stat_pets.set_value(
                f"{gs.get('pets_unlocked', 0)}/{gs.get('pets_total', 0)}"
            )
            self.stat_gems.set_value(str(gs.get("gems", "—")))
        else:
            self.stat_heroes.set_value("无 game")
            self.stat_skins.set_value("无 game")
            self.stat_pets.set_value("无 game")
            self.stat_gems.set_value(str(snap.xml_gems if snap.xml_gems is not None else "—"))

        self.summary_board.set_pairs(summary_pairs(snap))
        self.input_chips.set_chips(input_file_chips(snap))
        self.ref_chips.set_chips(reference_file_chips(snap))
        notices = notice_lines(snap)
        if notices:
            self.notice_board.set_lines(notices)
            self.notice_board.show()
        else:
            self.notice_board.hide()

        if snap.game_loaded:
            self.status.setText(tr("status.loading", uid=snap.uid))
        else:
            self.status.setText(tr("status.loading_partial", uid=snap.uid))
        self._log(f"刷新载入 UID={snap.uid}（本地加解密） game={'✓' if snap.game_loaded else '✗'}")
        seed_total = len(set(is_full.get("seeds", {})) | set(snap.ref_seeds))
        self._log(
            f"  种子目录 {seed_total} 项（本号 {is_full.get('seeds_count')} + 参考补充）"
        )
        for dtype, info in sorted((snap.shard_summaries or {}).items()):
            if info.get("error"):
                self._log(f"  ⚠ 分片 {dtype}: {info['error']}")
            elif info.get("method"):
                self._log(
                    f"  · {dtype}: {info.get('method')} · {info.get('field_count', '?')} 字段"
                )
        if tab_errors:
            self._log(f"  部分 Tab 装载失败 {len(tab_errors)} 处")

    def _on_refresh_failed(self, e: BaseException) -> None:
        self._snap = None
        self._ws = None
        self._skin_data = {}
        self.stat_uid.set_value("—")
        self.stat_heroes.set_value("—")
        self.stat_skins.set_value("—")
        self.stat_pets.set_value("—")
        self.stat_gems.set_value("—")
        detail = self._format_load_error(e)
        self.summary_board.set_placeholder(detail)
        self.input_chips.set_chips([])
        self.ref_chips.set_chips([])
        self.notice_board.hide()
        try:
            self.inspect_tab.bind_workspace(None, {})
        except Exception:
            pass
        self.status.setText(tr("status.failed"))
        self._log(tr("dialog.refresh.fail", detail=detail))
        self._log(traceback.format_exc())

    @staticmethod
    def _format_load_error(exc: BaseException) -> str:
        """把加解密/缺文件等错误收成短文案，避免 GUI 只显示晦涩堆栈首行。"""
        msg = str(exc).strip() or exc.__class__.__name__
        if isinstance(exc, FileNotFoundError):
            return msg
        if isinstance(exc, ValueError) and ("无法解密" in msg or "decrypt" in msg.lower()):
            return msg
        if isinstance(exc, json.JSONDecodeError):
            return f"JSON 解析失败（文件可能损坏或算法不匹配）: {msg}"
        return f"{exc.__class__.__name__}: {msg}"

    def preview_plan(self) -> None:
        plan = self._build_plan()
        if not plan.any_change():
            QMessageBox.information(self, tr("dialog.preview.title"), tr("dialog.preview.empty"))
            return

        runner = PatchRunner(self.row_input.path(), self.row_output.path(), self.row_ref.path())
        try:
            preview = runner.preview(plan)
        except Exception as e:
            detail = self._format_load_error(e)
            self._log(tr("dialog.preview.fail", detail=detail))
            QMessageBox.warning(
                self,
                tr("dialog.preview.title"),
                tr("dialog.preview.fail", detail=detail),
            )
            return

        self._log_plan(plan, "预览变更")
        self._log_preview_detail(preview)
        lines = ["【计划】", *preview["describe"], ""]
        if preview.get("detail_lines"):
            lines.append("【键级摘要】")
            lines.extend(preview["detail_lines"])
        lines.append("")
        lines.extend(preview.get("deploy_checklist", []))
        if preview.get("issues"):
            lines.append("")
            lines.append("⚠ 输入问题（缺哪个 / 手机哪里取）:")
            for issue in preview["issues"]:
                lines.append("")
                lines.extend(issue.splitlines())
        PreviewDialog(self, "\n".join(lines)).exec()

    def _confirm_encoded_changes(self) -> bool:
        warnings = self.materials_tab.encoded_warnings()
        if not warnings:
            return True
        shown = warnings[:12]
        extra = (
            tr("dialog.encoded.more", count=len(warnings) - 12)
            if len(warnings) > 12
            else ""
        )
        reply = QMessageBox.warning(
            self,
            tr("dialog.encoded.title"),
            tr("dialog.encoded.body", items="\n".join(shown) + extra),
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.No,
        )
        return reply == QMessageBox.Yes

    def run_patch(self) -> None:
        if self.worker and self.worker.isRunning():
            return
        plan = self._build_plan()
        if not plan.any_change():
            QMessageBox.warning(self, tr("dialog.apply.title"), tr("dialog.apply.empty"))
            return
        if not self._confirm_encoded_changes():
            self._log("已取消：编码值修改未确认")
            return

        runner = PatchRunner(self.row_input.path(), self.row_output.path(), self.row_ref.path())
        issues = runner.validate(plan)
        if issues:
            # 多行缺文件说明：块与块之间空一行，便于对照手机路径
            body = tr("dialog.incomplete.hint") + "\n\n" + "\n\n".join(issues)
            QMessageBox.warning(self, tr("dialog.incomplete.title"), body)
            self._log("应用中止：输入不完整")
            for issue in issues:
                for line in issue.splitlines():
                    self._log(f"  ⚠ {line}")
            return

        self.btn_apply.setEnabled(False)
        self.status.setText(tr("status.running"))
        self._log_plan(plan, "开始应用")
        for i in range(self.tabs.count()):
            if self.tabs.tabText(i) == tr("tab.log"):
                self.tabs.setCurrentIndex(i)
                break

        self.worker = PatchWorker(runner, plan)
        self.worker.log_line.connect(self.log.append)
        self.worker.finished_ok.connect(self._on_ok)
        self.worker.failed.connect(self._on_fail)
        self.worker.start()

    def _on_ok(self, report: dict) -> None:
        self.btn_apply.setEnabled(True)
        self.status.setText(tr("status.done"))
        self._log_report(report)
        files = ", ".join(report.get("output_files", []))
        checklist = "\n".join(report.get("deploy_checklist", [])[:4])
        QMessageBox.information(
            self,
            tr("dialog.done.title"),
            tr(
                "dialog.done.body",
                files=files,
                path=self.row_output.path(),
                checklist=checklist,
            ),
        )
        self.refresh()

    def _on_fail(self, tb: str) -> None:
        self.btn_apply.setEnabled(True)
        self.status.setText(tr("status.error"))
        self._log("应用失败")
        self.log.append(tb)
        QMessageBox.critical(self, tr("dialog.fail.title"), tb[:2000])


def run_gui() -> None:
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    app.setStyleSheet(STYLESHEET)
    win = MainWindow()
    win.show()
    sys.exit(app.exec())