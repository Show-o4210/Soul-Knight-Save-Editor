"""元气骑士存档编辑器 — 全局样式。"""

STYLESHEET = """
QMainWindow, QWidget {
    background-color: #f4f1eb;
    color: #3d3832;
    font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif;
    font-size: 14px;
}

/* —— 主 Tab —— */
QTabWidget::pane {
    border: none;
    background: transparent;
    top: -1px;
}
QTabBar::tab {
    background: transparent;
    color: #8a8278;
    padding: 14px 26px;
    border: none;
    border-bottom: 3px solid transparent;
    margin-right: 4px;
}
QTabBar::tab:selected {
    color: #2a6f8f;
    border-bottom: 3px solid #2a6f8f;
    font-weight: 600;
}
QTabBar::tab:hover:!selected {
    color: #5c564e;
    background: rgba(232, 228, 220, 0.55);
    border-radius: 6px 6px 0 0;
}

/* —— 子 Tab —— */
QTabWidget[role="sub"]::pane {
    background: transparent;
    border: none;
    padding: 0;
}
QTabWidget[role="sub"] > QTabBar::tab {
    padding: 10px 22px;
    background: transparent;
    border: 1px solid #ddd6ca;
    border-bottom: none;
    border-radius: 8px 8px 0 0;
    margin-right: 6px;
    font-size: 13px;
}
QTabWidget[role="sub"] > QTabBar::tab:selected {
    background: transparent;
    color: #2a6f8f;
    border-color: #a8c9d8;
    border-bottom: 2px solid #f4f1eb;
}

/* —— 分组卡片 —— */
QGroupBox {
    background: transparent;
    border: 1px solid #ddd6ca;
    border-radius: 12px;
    margin-top: 16px;
    padding: 22px 20px 18px 20px;
    font-weight: 600;
    font-size: 14px;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 16px;
    padding: 0 10px;
    color: #6b645a;
    background: transparent;
}
QGroupBox[role="lite"] {
    border: 1px solid #e8e2d8;
    margin-top: 8px;
    padding: 16px 14px 12px 14px;
}
QGroupBox[role="hero-card"] {
    border: 1px solid #e0dbd2;
    margin-top: 0;
    padding: 14px 16px 12px 16px;
}
QGroupBox[role="hero-card"]::title {
    color: #2a6f8f;
    font-size: 15px;
}
QLabel[role="hero-key"] {
    font-size: 12px;
    color: #9a9288;
    background: transparent;
}
QLabel[role="hero-meta"] {
    font-size: 12px;
    color: #8a8278;
    background: transparent;
}

/* —— 信息卡片 —— */
QFrame[role="section-card"] {
    background: #faf8f4;
    border: 1px solid #e8e2d8;
    border-radius: 12px;
}
QLabel[role="section-title"] {
    font-size: 12px;
    font-weight: 600;
    color: #9a9288;
    letter-spacing: 0.4px;
    padding: 14px 18px 2px 18px;
    background: transparent;
}
QLabel[role="section-inline"] {
    font-size: 13px;
    font-weight: 600;
    color: #6b645a;
    background: transparent;
}
QLabel[role="kv-key"] {
    font-size: 12px;
    color: #9a9288;
    min-width: 88px;
    background: transparent;
}
QLabel[role="kv-value"] {
    font-size: 13px;
    color: #3d3832;
    background: transparent;
}
QLabel[role="chip-ok"] {
    font-size: 12px;
    font-weight: 500;
    color: #1e5f78;
    background: #e8f3f8;
    border: 1px solid #b8d4e3;
    border-radius: 6px;
    padding: 5px 12px;
}
QLabel[role="chip-miss"] {
    font-size: 12px;
    font-weight: 500;
    color: #8a8278;
    background: transparent;
    border: 1px dashed #d4cdc2;
    border-radius: 6px;
    padding: 5px 12px;
}
QLabel[role="notice-info"] {
    font-size: 13px;
    color: #4a6a7a;
    background: #eef5f8;
    border-radius: 8px;
    padding: 8px 12px;
}
QLabel[role="notice-warn"] {
    font-size: 13px;
    color: #7a5a20;
    background: #faf3e6;
    border-radius: 8px;
    padding: 8px 12px;
}

QFrame[role="info-panel"] {
    background: #faf8f4;
    border: 1px solid #e8e2d8;
    border-radius: 12px;
}
QLabel[role="info-title"] {
    font-size: 12px;
    font-weight: 600;
    color: #9a9288;
    padding: 16px 18px 4px 18px;
    background: transparent;
}
QLabel[role="info-content"] {
    font-size: 13px;
    color: #5c564e;
    padding: 6px 18px 16px 18px;
    background: transparent;
}

/* —— 统计块 —— */
QFrame[role="stat"] {
    background: #faf8f4;
    border: 1px solid #e0d9ce;
    border-radius: 12px;
    padding: 14px 18px;
}
QLabel[role="stat-value"] {
    font-size: 20px;
    font-weight: 700;
    color: #2a6f8f;
    background: transparent;
}
QLabel[role="stat-label"] {
    font-size: 12px;
    color: #8a8278;
    font-weight: 500;
    background: transparent;
}

/* —— 工具栏条 —— */
QWidget[role="toolbar"] {
    background: #faf8f4;
    border: 1px solid #e8e2d8;
    border-radius: 10px;
}

/* —— 文字层级 —— */
QLabel[role="hint"] { color: #8a8278; font-size: 13px; background: transparent; }
QLabel[role="title"] { font-size: 18px; font-weight: 700; color: #3d3832; background: transparent; }
QLabel[role="guide"] { color: #5c564e; font-size: 13px; background: transparent; }
QLabel[role="path-label"] { color: #6b645a; font-weight: 600; background: transparent; }

/* —— 输入控件 —— */
QLineEdit, QSpinBox, QTextEdit {
    background: #fffcf8;
    border: 1px solid #ddd6ca;
    border-radius: 8px;
    padding: 10px 14px;
    min-height: 22px;
    selection-background-color: #c5dde8;
}
QLineEdit:focus, QSpinBox:focus {
    border: 1px solid #2a6f8f;
}
QSpinBox {
    min-width: 108px;
    padding: 8px 10px;
}

/* —— 勾选框 —— */
QCheckBox {
    spacing: 10px;
    padding: 6px 2px;
    font-size: 14px;
    background: transparent;
}
QCheckBox::indicator {
    width: 20px;
    height: 20px;
    border-radius: 5px;
    border: 2px solid #ccc4b8;
    background: #fffcf8;
}
QCheckBox::indicator:checked {
    background: #2a6f8f;
    border-color: #2a6f8f;
}
QCheckBox::indicator:hover {
    border-color: #7eb3c9;
}
QCheckBox[role="skin-chip"] {
    min-width: 96px;
    min-height: 36px;
    padding: 6px 12px 6px 8px;
    margin: 0;
    spacing: 8px;
    border-radius: 8px;
    background: rgba(255, 252, 248, 0.6);
}
QCheckBox[role="skin-chip"]::indicator {
    width: 18px;
    height: 18px;
    margin-right: 2px;
}
QCheckBox[role="skin-chip"]:hover {
    background: rgba(232, 228, 220, 0.45);
}

/* —— 按钮 —— */
QPushButton {
    background: #fffcf8;
    border: 1px solid #ddd6ca;
    border-radius: 8px;
    padding: 10px 20px;
    font-weight: 500;
    min-height: 20px;
}
QPushButton:hover {
    background: #f5f0e8;
    border-color: #b8aea0;
}
QPushButton[primary="true"] {
    background: #2a6f8f;
    border: none;
    color: #ffffff;
    font-weight: 600;
    padding: 12px 32px;
}
QPushButton[primary="true"]:hover { background: #235f7a; }
QPushButton[role="ghost"] {
    background: transparent;
    border: 1px solid #ddd6ca;
    padding: 10px 16px;
    color: #6b645a;
}

/* —— 表格 —— */
QTableWidget {
    background: transparent;
    border: 1px solid #e8e2d8;
    border-radius: 10px;
    gridline-color: transparent;
    outline: none;
}
QTableWidget::item {
    padding: 6px 10px;
    border-bottom: 1px solid rgba(232, 226, 216, 0.7);
    background: transparent;
}
QTableWidget::item:selected {
    background: rgba(197, 221, 232, 0.75);
    color: #1e4a5f;
}
QTableWidget::item:alternate {
    background: rgba(250, 248, 244, 0.5);
}
QHeaderView::section {
    background: #faf8f4;
    color: #6b645a;
    padding: 12px 10px;
    border: none;
    border-bottom: 2px solid #e0d9ce;
    font-weight: 600;
    font-size: 13px;
}

QScrollArea { border: none; background: transparent; }
QScrollArea > QWidget > QWidget { background: transparent; }
QScrollBar:vertical {
    background: transparent;
    width: 10px;
    border-radius: 5px;
    margin: 2px;
}
QScrollBar::handle:vertical {
    background: #ccc4b8;
    border-radius: 5px;
    min-height: 32px;
}
QScrollBar::handle:vertical:hover { background: #b0a698; }
QScrollBar:horizontal {
    height: 0px;
}

QTextEdit {
    font-family: Consolas, "Cascadia Mono", monospace;
    font-size: 12px;
    background: #faf8f4;
    border: 1px solid #e8e2d8;
    border-radius: 10px;
    color: #5c564e;
}
"""