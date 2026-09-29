"""将输入审计结果格式化为本地化展示文本。"""
from __future__ import annotations

from core.constants import (
    DEVICE_FILES_DIR,
    DEVICE_PREFS_GLOB_HINT,
    DEVICE_SHARED_PREFS_DIR,
    PREFS_NAME,
    format_missing_file_compact,
)
from core.input_rules import InputAudit
from gui.i18n.loader import tr

_CORE_FILES = ("game.data", PREFS_NAME)


def format_audit(audit: InputAudit) -> list[str]:
    lines = [
        tr("audit.device_paths"),
        f"  .data → {DEVICE_FILES_DIR}/",
        f"  XML   → {DEVICE_SHARED_PREFS_DIR}/",
        f"  文件名 → {DEVICE_PREFS_GLOB_HINT}",
        tr("audit.required"),
    ]
    for name in _CORE_FILES:
        if name in audit.present_core:
            lines.append(f"  {tr('audit.present')}  {name}")
        else:
            lines.append(f"  {tr('audit.missing_mark')}  {format_missing_file_compact(name)}")
    if audit.present_optional:
        lines.append(tr("audit.optional"))
        for name in audit.present_optional:
            lines.append(f"  {tr('audit.present')}  {name}")
    if audit.junk:
        lines.append(tr("audit.junk"))
        for name in audit.junk:
            lines.append(f"  ⚠  {name}")
    if audit.redundant:
        lines.append(tr("audit.redundant"))
        for name in audit.redundant:
            lines.append(f"  ⚠  {name}")
    if audit.missing_for_plan:
        lines.append(tr("audit.missing"))
        for name in audit.missing_for_plan:
            lines.append(f"  ✗  {name}")
    return lines
