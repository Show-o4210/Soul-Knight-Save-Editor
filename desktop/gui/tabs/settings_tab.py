"""目录与输入说明 — 设置 Tab。"""
from __future__ import annotations

from pathlib import Path

from PySide6.QtWidgets import (
    QGroupBox,
    QLabel,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from gui.i18n import tr
from gui.widgets.info_panel import InfoPanel
from gui.widgets.path_row import PathRow


class SettingsTab(QWidget):
    def __init__(self, input_dir: Path, output_dir: Path, ref_dir: Path) -> None:
        super().__init__()

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.NoFrame)
        body = QWidget()
        layout = QVBoxLayout(body)
        layout.setSpacing(18)
        layout.setContentsMargins(4, 8, 4, 16)

        intro = QLabel(tr("settings.intro"))
        intro.setProperty("role", "hint")
        intro.setWordWrap(True)
        layout.addWidget(intro)

        g_paths = QGroupBox(tr("settings.paths.title"))
        pl = QVBoxLayout(g_paths)
        pl.setSpacing(10)
        self.row_input = PathRow(tr("settings.path.input"), input_dir)
        self.row_output = PathRow(tr("settings.path.output"), output_dir)
        self.row_ref = PathRow(tr("settings.path.ref"), ref_dir)
        for row in (self.row_input, self.row_output, self.row_ref):
            pl.addWidget(row)
        layout.addWidget(g_paths)

        source_panel = InfoPanel(tr("settings.source.title"))
        source_panel.set_text(tr("settings.source.body"))
        layout.addWidget(source_panel)

        guide_panel = InfoPanel(tr("settings.guide.title"))
        guide_panel.set_text(tr("settings.guide.body"))
        layout.addWidget(guide_panel)

        crypto_panel = InfoPanel(tr("settings.crypto.title"))
        crypto_panel.set_text(tr("settings.crypto.body"))
        layout.addWidget(crypto_panel)

        deploy_panel = InfoPanel(tr("settings.deploy.title"))
        deploy_panel.set_text(tr("settings.deploy.body"))
        layout.addWidget(deploy_panel)

        layout.addStretch()
        scroll.setWidget(body)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(scroll)