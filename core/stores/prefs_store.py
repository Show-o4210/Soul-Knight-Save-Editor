from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any
from urllib.parse import unquote

from core.constants import PREFS_NAME

UID_KEY_RE = re.compile(r"^(\d+)_c\d+_unlock$")


def _bool_pref_str(value: bool) -> str:
    """Unity PlayerPrefs XML 布尔字符串格式（首字母大写）。"""
    return "True" if value else "False"


class PlayerPrefsStore:
    def __init__(self, path: Path, uid_prefix: str = "") -> None:
        self.path = path
        self.uid_prefix = uid_prefix
        self._entries: list[dict[str, Any]] = []

    @classmethod
    def load(cls, path: Path, uid_prefix: str = "") -> PlayerPrefsStore:
        store = cls(path, uid_prefix)
        store._entries = store._parse(path.read_text(encoding="utf-8"))
        return store

    @staticmethod
    def _parse(xml_text: str) -> list[dict[str, Any]]:
        entries: list[dict[str, Any]] = []
        tag_re = re.compile(
            r'<(int|float|long|boolean)\s+name="([^"]+)"\s+value="([^"]*)"\s*/>'
            r'|'
            r'<(string)\s+name="([^"]+)"\s+value="([^"]*)"\s*/>'
            r'|'
            r'<(string)\s+name="([^"]+)">([^<]*)</string>',
            re.MULTILINE,
        )
        for m in tag_re.finditer(xml_text):
            if m.group(1):
                entries.append({"tag": m.group(1), "name": m.group(2), "value": m.group(3) or "", "text": None})
            elif m.group(4):
                entries.append({"tag": m.group(4), "name": m.group(5), "value": m.group(6) or "", "text": None})
            else:
                entries.append({"tag": m.group(7), "name": m.group(8), "value": None, "text": m.group(9)})
        return entries

    def get(self, key: str) -> str | None:
        full = self._full_key(key)
        for e in self._entries:
            if e["name"] == full:
                return e.get("text") if e["tag"] == "string" else e.get("value")
        return None

    def set(self, key: str, value: str | int, *, tag: str | None = None) -> bool:
        full = self._full_key(key)
        for e in self._entries:
            if e["name"] != full:
                continue
            if e["tag"] == "string" or tag == "string":
                new = str(value)
                old = (e.get("text") or e.get("value") or "")
                if old != new:
                    e["tag"] = "string"
                    e["text"] = new
                    e["value"] = None
                    return True
                return False
            new = str(value)
            if e.get("value") != new:
                e["value"] = new
                return True
            return False
        if tag == "string":
            self._entries.append({"tag": "string", "name": full, "text": str(value), "value": None})
        else:
            self._entries.append({"tag": tag or "int", "name": full, "value": str(value), "text": None})
        return True

    def _full_key(self, key: str) -> str:
        if self.uid_prefix and not key.startswith(f"{self.uid_prefix}_") and not key.startswith("Open"):
            return f"{self.uid_prefix}_{key}"
        return key

    def save(self, path: Path | None = None) -> None:
        path = path or self.path
        lines = [
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
            "<map>",
        ]
        for e in self._entries:
            name, tag = e["name"], e["tag"]
            if tag == "string":
                text = e.get("text") or e.get("value") or ""
                lines.append(f'    <string name="{name}">{text}</string>')
            else:
                val = e.get("value", "0")
                lines.append(f'    <{tag} name="{name}" value="{val}" />')
        lines.append("</map>")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def patch_characters(self, *, default_level: int = 7, game_data: dict[str, Any] | None = None) -> dict[str, int]:
        stats = {"unlock": 0, "level": 0, "skill": 0}
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: i for i, e in enumerate(self._entries)}

        for e in self._entries:
            name = e["name"]
            if not name.startswith(prefix):
                continue
            rest = name[len(prefix):]
            if re.fullmatch(r"c\d+_unlock", rest) and e["tag"] == "string":
                cur_val = (e.get("text") or e.get("value") or "")
                if cur_val.lower() != "true" or cur_val != "True":
                    e["tag"] = "string"
                    e["text"] = _bool_pref_str(True)
                    e["value"] = None
                    if cur_val.lower() != "true":
                        stats["unlock"] += 1
            elif re.fullmatch(r"c\d+_level", rest) and e["tag"] == "int":
                if int(e.get("value") or "0") < default_level:
                    e["value"] = str(default_level)
                    stats["level"] += 1
            elif re.fullmatch(r"c_.+_skill_\d+_unlock", rest) and e["tag"] == "int":
                if e.get("value") != "1":
                    e["value"] = "1"
                    stats["skill"] += 1

        if game_data:
            from core.stores.game_store import GameDataStore

            for _hero, cidx in GameDataStore.hero_index_map(game_data).items():
                key = f"{prefix}c{cidx}_unlock"
                if key not in by_name:
                    self._entries.append({
                        "tag": "string",
                        "name": key,
                        "text": _bool_pref_str(True),
                        "value": None,
                    })
                    by_name[key] = len(self._entries) - 1
                    stats["unlock"] += 1

            for hero, skills in game_data.get("heroSkillUnlock", {}).items():
                for entry in skills:
                    key = f"{prefix}c_{hero}_skill_{entry['Key']}_unlock"
                    if key in by_name:
                        e = self._entries[by_name[key]]
                        if e.get("value") != "1":
                            e["value"] = "1"
                            stats["skill"] += 1
                    else:
                        self._entries.append({"tag": "int", "name": key, "value": "1", "text": None})
                        by_name[key] = len(self._entries) - 1
                        stats["skill"] += 1
        return stats

    def patch_skins(self, owned_value: str = "1") -> int:
        count = 0
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        for e in self._entries:
            name = e["name"]
            if not name.startswith(prefix):
                continue
            if re.fullmatch(r"c\d+_skin\d+", name[len(prefix):]) and e["tag"] == "int":
                if e.get("value") != owned_value:
                    e["value"] = owned_value
                    count += 1
        return count

    def patch_skins_selective(
        self,
        picks: dict[str, dict[int, int]],
        hero_index: dict[str, int],
    ) -> int:
        count = 0
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: e for e in self._entries}

        for hero, selections in picks.items():
            if hero not in hero_index:
                continue
            cidx = hero_index[hero]
            for skin_idx, value in selections.items():
                key = f"{prefix}c{cidx}_skin{skin_idx}"
                val = str(value)
                if key in by_name:
                    e = by_name[key]
                    if e.get("value") != val:
                        e["value"] = val
                        count += 1
                else:
                    self._entries.append({"tag": "int", "name": key, "value": val, "text": None})
                    by_name[key] = self._entries[-1]
                    count += 1
        return count

    def force_legacy_format(self) -> None:
        uid = self.uid_prefix
        self.set(f"OpenRijTest_{uid}", 0, tag="int")
        self.set(f"OpenNewtonJsonTest_{uid}", 0, tag="int")

    def sync_hero_unlock_from_game(self, game_data: dict[str, Any]) -> int:
        """将 game.data.heroUnlock 同步到 XML c{n}_unlock（Legacy 模式读 XML）。"""
        from core.stores.game_store import GameDataStore

        hero_index = GameDataStore.hero_index_map(game_data)
        hero_unlock = game_data.get("heroUnlock", {})
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: i for i, e in enumerate(self._entries)}
        count = 0

        for hero, cidx in hero_index.items():
            key = f"{prefix}c{cidx}_unlock"
            want = _bool_pref_str(bool(hero_unlock.get(hero)))
            if key in by_name:
                entry = self._entries[by_name[key]]
                cur = entry.get("text") or entry.get("value") or ""
                if cur != want:
                    entry["tag"] = "string"
                    entry["text"] = want
                    entry["value"] = None
                    count += 1
            else:
                self._entries.append({"tag": "string", "name": key, "text": want, "value": None})
                by_name[key] = len(self._entries) - 1
                count += 1
        return count

    def sync_hero_skills_from_game(self, game_data: dict[str, Any]) -> int:
        from core.stores.game_store import GameDataStore

        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: i for i, e in enumerate(self._entries)}
        count = 0

        for hero, skills in game_data.get("heroSkillUnlock", {}).items():
            for entry in skills:
                if not entry.get("Value"):
                    continue
                key = f"{prefix}c_{hero}_skill_{entry['Key']}_unlock"
                if key in by_name:
                    item = self._entries[by_name[key]]
                    if item.get("value") != "1":
                        item["value"] = "1"
                        count += 1
                else:
                    self._entries.append({"tag": "int", "name": key, "value": "1", "text": None})
                    by_name[key] = len(self._entries) - 1
                    count += 1
        return count

    def sync_owned_skins_from_game(self, game_data: dict[str, Any]) -> int:
        from core.stores.game_store import GameDataStore

        hero_index = GameDataStore.hero_index_map(game_data)
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: e for e in self._entries}
        count = 0

        for hero, skins in game_data.get("skinLock", {}).items():
            if hero not in hero_index:
                continue
            cidx = hero_index[hero]
            for entry in skins:
                if entry.get("Value") != 1:
                    continue
                key = f"{prefix}c{cidx}_skin{entry['Key']}"
                if key in by_name:
                    item = by_name[key]
                    if item.get("value") != "1":
                        item["value"] = "1"
                        count += 1
                else:
                    self._entries.append({"tag": "int", "name": key, "value": "1", "text": None})
                    by_name[key] = self._entries[-1]
                    count += 1
        return count

    def sync_pets_from_game(self, game_data: dict[str, Any], pet_index: dict[str, int]) -> int:
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: e for e in self._entries}
        count = 0

        for name, idx in pet_index.items():
            unlocked = bool(game_data.get("petUnlock", {}).get(name))
            key = f"{prefix}p{idx}_unlock"
            want = _bool_pref_str(unlocked)
            if key in by_name:
                entry = by_name[key]
                cur = entry.get("text") or entry.get("value") or ""
                if cur != want:
                    entry["tag"] = "string"
                    entry["text"] = want
                    entry["value"] = None
                    count += 1
            else:
                self._entries.append({"tag": "string", "name": key, "text": want, "value": None})
                by_name[key] = self._entries[-1]
                count += 1
        return count

    def sync_legacy_progress_flags(self) -> int:
        """Legacy 切换时补齐进度标记，避免被判定为新号/重跑教程。"""
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: i for i, e in enumerate(self._entries)}
        count = 0

        global_active = next(
            (e.get("value") for e in self._entries if e["name"] == "first_active" and e["tag"] == "int"),
            None,
        )
        uid_active_key = f"{prefix}first_active"
        if global_active and uid_active_key not in by_name:
            self._entries.append({
                "tag": "int",
                "name": uid_active_key,
                "value": str(global_active),
                "text": None,
            })
            by_name[uid_active_key] = len(self._entries) - 1
            count += 1

        first_play_key = f"{prefix}first_play"
        has_tutorial_key = f"{prefix}has_tutorial"
        first_play_val = next(
            (e.get("value") for e in self._entries if e["name"] == first_play_key and e["tag"] == "int"),
            None,
        )
        if first_play_val == "1" and has_tutorial_key not in by_name:
            self._entries.append({
                "tag": "int",
                "name": has_tutorial_key,
                "value": "1",
                "text": None,
            })
            by_name[has_tutorial_key] = len(self._entries) - 1
            count += 1

        return count

    def sync_game_mirror_for_legacy(
        self,
        game_data: dict[str, Any],
        pet_index: dict[str, int],
        *,
        sync_heroes: bool = True,
        sync_skins: bool = True,
        sync_pets: bool = True,
        sync_skills: bool = True,
    ) -> dict[str, int]:
        stats: dict[str, int] = {"progress_flags": self.sync_legacy_progress_flags()}
        if sync_heroes:
            stats["heroes"] = self.sync_hero_unlock_from_game(game_data)
        if sync_skills:
            stats["skills"] = self.sync_hero_skills_from_game(game_data)
        if sync_skins:
            stats["skins"] = self.sync_owned_skins_from_game(game_data)
        if sync_pets:
            stats["pets"] = self.sync_pets_from_game(game_data, pet_index)
        return stats

    def patch_characters_selective(
        self,
        picks: dict[str, dict[str, Any]],
        hero_index: dict[str, int],
        game_data: dict[str, Any] | None = None,
    ) -> dict[str, int]:
        stats = {"unlock": 0, "level": 0, "skill": 0}
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: i for i, e in enumerate(self._entries)}

        for hero, opts in picks.items():
            if hero not in hero_index:
                continue
            cidx = hero_index[hero]
            if "unlock" in opts:
                key = f"{prefix}c{cidx}_unlock"
                want = _bool_pref_str(bool(opts["unlock"]))
                if key in by_name:
                    e = self._entries[by_name[key]]
                    cur = (e.get("text") or e.get("value") or "")
                    if cur.lower() != want.lower():
                        e["tag"] = "string"
                        e["text"] = want
                        e["value"] = None
                        stats["unlock"] += 1
                else:
                    self._entries.append({"tag": "string", "name": key, "text": want, "value": None})
                    by_name[key] = len(self._entries) - 1
                    stats["unlock"] += 1
            if "level" in opts:
                key = f"{prefix}c{cidx}_level"
                val = str(max(1, min(15, int(opts["level"]))))
                if key in by_name:
                    e = self._entries[by_name[key]]
                    if e.get("value") != val:
                        e["value"] = val
                        stats["level"] += 1
                else:
                    self._entries.append({"tag": "int", "name": key, "value": val, "text": None})
                    by_name[key] = len(self._entries) - 1
                    stats["level"] += 1
            if opts.get("skills") and game_data:
                for entry in game_data.get("heroSkillUnlock", {}).get(hero, []):
                    skey = f"{prefix}c_{hero}_skill_{entry['Key']}_unlock"
                    if skey in by_name:
                        e = self._entries[by_name[skey]]
                        if e.get("value") != "1":
                            e["value"] = "1"
                            stats["skill"] += 1
                    else:
                        self._entries.append({"tag": "int", "name": skey, "value": "1", "text": None})
                        by_name[skey] = len(self._entries) - 1
                        stats["skill"] += 1
        return stats

    def patch_pets_selective(self, picks: dict[str, bool], pet_index: dict[str, int]) -> int:
        count = 0
        prefix = f"{self.uid_prefix}_" if self.uid_prefix else ""
        by_name = {e["name"]: e for e in self._entries}

        for name, unlocked in picks.items():
            if name not in pet_index:
                continue
            key = f"{prefix}p{pet_index[name]}_unlock"
            want = _bool_pref_str(unlocked)
            if key in by_name:
                e = by_name[key]
                cur = (e.get("text") or e.get("value") or "")
                if cur != want:
                    e["tag"] = "string"
                    e["text"] = want
                    e["value"] = None
                    count += 1
            else:
                self._entries.append({"tag": "string", "name": key, "text": want, "value": None})
                by_name[key] = self._entries[-1]
                count += 1
        return count

    def patch_all_pets(self, pet_index: dict[str, int], *, unlock: bool = True) -> int:
        picks = {name: unlock for name in pet_index}
        return self.patch_pets_selective(picks, pet_index)

    def cleanup_stale_uid(self, uid: str) -> int:
        removed = 0
        kept: list[dict[str, Any]] = []
        for e in self._entries:
            m = re.match(r"^(\d+)_", e["name"])
            if m and m.group(1) != uid:
                removed += 1
                continue
            kept.append(e)
        self._entries = kept
        return removed


def discover_prefs(input_dir: Path) -> Path:
    prefs = input_dir / PREFS_NAME
    if prefs.is_file():
        return prefs
    candidates = sorted(input_dir.glob("*.xml"))
    if len(candidates) == 1:
        return candidates[0]
    if candidates:
        return max(candidates, key=lambda p: p.stat().st_size)
    raise FileNotFoundError(f"未找到 playerprefs.xml: {input_dir}")


def detect_uid(prefs_path: Path) -> str:
    entries = PlayerPrefsStore._parse(prefs_path.read_text(encoding="utf-8"))
    by_name = {e["name"]: e for e in entries}

    def text(entry: dict[str, Any]) -> str:
        return (entry.get("text") or entry.get("value") or "").strip()

    last_id = text(by_name.get("last_account_id", {}))
    if last_id.isdigit():
        return last_id

    cloud = text(by_name.get("cloudSaveId", {}))
    m = re.fullmatch(r"TapTap_(\d+)", cloud)
    if m:
        return m.group(1)

    for entry in entries:
        if "SdkStateCache" not in entry["name"]:
            continue
        raw = unquote(text(entry))
        if raw.startswith("{"):
            try:
                uid = json.loads(raw).get("User", {}).get("Id")
                if uid is not None:
                    return str(uid)
            except json.JSONDecodeError:
                pass

    unlock_uids = [UID_KEY_RE.match(e["name"]).group(1) for e in entries if UID_KEY_RE.match(e["name"])]
    if unlock_uids:
        from collections import Counter
        return Counter(unlock_uids).most_common(1)[0][0]
    raise ValueError(f"无法从 {prefs_path.name} 识别 UID")