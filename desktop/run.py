#!/usr/bin/env python3
"""元气骑士存档编辑器 — 独立环境入口。"""
from __future__ import annotations

import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))


def main() -> None:
    from gui.app import run_gui

    run_gui()


if __name__ == "__main__":
    main()