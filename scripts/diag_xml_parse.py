#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.crypto import decrypt_file
from core.stores.prefs_store import PlayerPrefsStore, discover_prefs


TAG_RE = re.compile(
    r"<(int|float|long|boolean)\s+name=\"([^\"]+)\"\s+value=\"([^\"]*)\"\s*/>"
    r"|"
    r"<(string)\s+name=\"([^\"]+)\"\s+value=\"([^\"]*)\"\s*/>"
    r"|"
    r"<(string)\s+name=\"([^\"]+)\">([^<]*)</string>",
    re.MULTILINE,
)


def count_raw_tags(text: str) -> int:
    patterns = [
        re.compile(r"<(int|float|long|boolean)\s+name="),
        re.compile(r"<string\s+name="),
    ]
    return sum(len(p.findall(text)) for p in patterns)


def main() -> None:
    for label, path in [
        ("input", ROOT / "输入"),
        ("output", ROOT / "输出"),
        ("ref", ROOT / "参考"),
        ("scratch", ROOT / "scratch" / "test_out"),
    ]:
        prefs = path / "com.ChillyRoom.DungeonShooter.v2.playerprefs.xml"
        if not prefs.is_file():
            continue
        text = prefs.read_text(encoding="utf-8")
        parsed = PlayerPrefsStore._parse(text)
        raw = count_raw_tags(text)
        print(f"{label}: raw_tags={raw} parsed={len(parsed)} delta={raw - len(parsed)}")
        if raw != len(parsed):
            for match in TAG_RE.finditer(text):
                pass
            # find lines that look like entries but weren't parsed
            for line in text.splitlines():
                if "<int " in line or "<float " in line or "<string " in line:
                    if not TAG_RE.search(line):
                        print("  unparsed:", line[:120])

        setting = next(path.glob("setting_*_.data"), None)
        if setting and setting.is_file():
            data, _ = decrypt_file(setting)
            print(
                f"  setting UseRij={data.get('UseRijData')} "
                f"Newton={data.get('UseNewtonJsonData')}"
            )


if __name__ == "__main__":
    main()