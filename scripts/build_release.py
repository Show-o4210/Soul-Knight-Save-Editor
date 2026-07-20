#!/usr/bin/env python3
"""打包 Windows 发行版：PyInstaller onedir + 配套目录 + zip。"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
BUILD = ROOT / "build"
APP_NAME = "SoulKnightSaveEditor"
RELEASE_DIR = DIST / "Soul-Knight-Save-Editor"
ZIP_PATH = DIST / "Soul-Knight-Save-Editor-Windows.zip"


def main() -> int:
    if sys.platform != "win32":
        print("当前仅提供 Windows 打包流程")
        return 1

    # 清理旧产物
    for p in (BUILD, DIST / APP_NAME, RELEASE_DIR, ZIP_PATH):
        if p.is_dir():
            shutil.rmtree(p)
        elif p.is_file():
            p.unlink()

    strings = ROOT / "gui" / "i18n" / "strings.json"
    if not strings.is_file():
        print("缺少 gui/i18n/strings.json")
        return 1

    # 不 collect-all 整个 PySide6（会拖进 WebEngine/3D 等百兆级无用组件）
    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--windowed",
        "--name",
        APP_NAME,
        "--paths",
        str(ROOT),
        # Windows 分隔符为 ;
        f"--add-data={strings};gui/i18n",
        "--exclude-module",
        "PySide6.QtWebEngineCore",
        "--exclude-module",
        "PySide6.QtWebEngineWidgets",
        "--exclude-module",
        "PySide6.QtWebEngineQuick",
        "--exclude-module",
        "PySide6.Qt3DCore",
        "--exclude-module",
        "PySide6.Qt3DRender",
        "--exclude-module",
        "PySide6.Qt3DAnimation",
        "--exclude-module",
        "PySide6.Qt3DInput",
        "--exclude-module",
        "PySide6.Qt3DLogic",
        "--exclude-module",
        "PySide6.Qt3DExtras",
        "--exclude-module",
        "PySide6.QtMultimedia",
        "--exclude-module",
        "PySide6.QtMultimediaWidgets",
        "--exclude-module",
        "PySide6.QtBluetooth",
        "--exclude-module",
        "PySide6.QtNfc",
        "--exclude-module",
        "PySide6.QtPdf",
        "--exclude-module",
        "PySide6.QtPdfWidgets",
        "--exclude-module",
        "PySide6.QtCharts",
        "--exclude-module",
        "PySide6.QtDataVisualization",
        "--exclude-module",
        "PySide6.QtGraphs",
        "--exclude-module",
        "PySide6.QtGraphsWidgets",
        "--exclude-module",
        "PySide6.QtQuick3D",
        "--exclude-module",
        "PySide6.QtLocation",
        "--exclude-module",
        "PySide6.QtPositioning",
        "--exclude-module",
        "PySide6.QtSensors",
        "--exclude-module",
        "PySide6.QtSerialBus",
        "--exclude-module",
        "PySide6.QtSerialPort",
        "--exclude-module",
        "PySide6.QtSpatialAudio",
        "--exclude-module",
        "PySide6.QtTextToSpeech",
        "--exclude-module",
        "PySide6.QtWebSockets",
        "--exclude-module",
        "PySide6.QtWebChannel",
        "--exclude-module",
        "PySide6.QtWebView",
        "--exclude-module",
        "PySide6.QtRemoteObjects",
        "--exclude-module",
        "PySide6.QtScxml",
        "--exclude-module",
        "PySide6.QtHttpServer",
        "--exclude-module",
        "PySide6.QtDesigner",
        "--exclude-module",
        "PySide6.QtUiTools",
        "--exclude-module",
        "PySide6.QtTest",
        "--exclude-module",
        "PySide6.QtSql",
        "--exclude-module",
        "tkinter",
        "--exclude-module",
        "unittest",
        "--hidden-import",
        "Crypto",
        "--hidden-import",
        "Crypto.Cipher",
        "--hidden-import",
        "Crypto.Cipher.DES",
        "--hidden-import",
        "Crypto.Util.Padding",
        str(ROOT / "run.py"),
    ]
    print("运行:", " ".join(cmd))
    subprocess.check_call(cmd, cwd=str(ROOT))

    built = DIST / APP_NAME
    if not (built / f"{APP_NAME}.exe").is_file():
        print("未找到打包产物", built)
        return 1

    # 组装可分发目录：exe 目录 + 用户数据文件夹 + 说明
    RELEASE_DIR.mkdir(parents=True)
    # 拷贝整个 onedir
    for item in built.iterdir():
        dest = RELEASE_DIR / item.name
        if item.is_dir():
            shutil.copytree(item, dest)
        else:
            shutil.copy2(item, dest)

    for name in ("输入", "输出", "参考"):
        d = RELEASE_DIR / name
        d.mkdir(exist_ok=True)
        keep = d / ".gitkeep"
        if not keep.exists():
            keep.write_text("", encoding="utf-8")

    # 把仓库里的参考样例一并带上（可选，方便试合并）
    ref_src = ROOT / "参考"
    ref_dst = RELEASE_DIR / "参考"
    if ref_src.is_dir():
        for f in ref_src.iterdir():
            if f.is_file() and f.suffix.lower() in {".data", ".xml"}:
                shutil.copy2(f, ref_dst / f.name)

    # 说明文件
    readme_src = ROOT / "README.md"
    if readme_src.is_file():
        shutil.copy2(readme_src, RELEASE_DIR / "README.md")

    (RELEASE_DIR / "使用说明.txt").write_text(
        "\n".join(
            [
                "元气骑士存档编辑器 — Windows 便携版",
                "",
                "1. 双击 SoulKnightSaveEditor.exe 启动（无需安装 Python）",
                "2. 将手机取出的存档放入「输入」文件夹：",
                "   - 必备: game.data、*playerprefs*.xml",
                "   - 按需: item_data_{UID}_.data、weapon_evolution_data_{UID}_.data、",
                "           statistic_{UID}_.data、setting_{UID}_.data 等",
                "   手机路径:",
                "   - .data → /data/data/com.ChillyRoom.DungeonShooter/files/",
                "   - XML  → /data/data/com.ChillyRoom.DungeonShooter/shared_prefs/",
                "3. 如需「参考号增量合并」，把全满号对应文件放入「参考」",
                "4. 在软件内编辑后点「应用并输出」，结果在「输出」目录",
                "5. 按「输出」内 部署说明.json 写回手机（覆盖原路径）",
                "",
                "【注意】修改前请手动备份原始存档！",
                "详细说明见 README.md",
                "",
            ]
        ),
        encoding="utf-8",
    )

    # zip
    base_name = str(ZIP_PATH.with_suffix(""))
    archive = shutil.make_archive(base_name, "zip", root_dir=str(DIST), base_dir=RELEASE_DIR.name)
    print("发行目录:", RELEASE_DIR)
    print("压缩包:", archive)
    size_mb = Path(archive).stat().st_size / (1024 * 1024)
    print(f"大小: {size_mb:.1f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
