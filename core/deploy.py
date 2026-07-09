"""部署前检查清单。"""
from __future__ import annotations

DEPLOY_CHECKLIST: tuple[str, ...] = (
    "关闭云存档同步（避免覆盖本地修改）",
    "开启飞行模式或断网后再覆盖文件",
    "确认输出含 OpenRijTest=0（改动物品时工具自动写入）",
    "仅覆盖输出目录列出的文件，勿动未修改分片",
    "gems 完全不可改：不读写 game.data.gems，不同步 XML 宝石键",
    "覆盖后进游戏验证，再决定是否恢复联网",
)


def checklist_lines(*, output_files: list[str] | None = None) -> list[str]:
    lines = ["【部署前检查】", *[f"☐ {item}" for item in DEPLOY_CHECKLIST]]
    if output_files:
        lines.append("")
        lines.append("【将覆盖的文件】")
        lines.extend(f"• {name}" for name in output_files)
    return lines