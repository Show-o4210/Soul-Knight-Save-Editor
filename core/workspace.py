from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from core.constants import DEVICE_FILES_DIR, PREFS_NAME, IOS_PREFS_NAME, Platform, format_missing_file_compact
from core.crypto import decrypt_file
from core.input_rules import audit_input
from core.reference_diff import compute_reference_diff
from core.shard_registry import INSPECT_SHARDS, WRITABLE_SHARDS, discover_shard_path
from core.stores.game_store import GameDataStore
from core.stores.item_store import ItemDataStore
from core.stores.prefs_store import PlayerPrefsStore, detect_uid, discover_prefs
from core.stores.statistic_store import StatisticStore
from core.stores.weapon_evolution_store import WeaponEvolutionStore
from core.weapon_util import filter_weapon_blueprints

@dataclass
class WorkspaceSnapshot:
    platform: Platform
    uid: str
    input_dir: str
    game_path: str | None
    prefs_path: str
    item_path: str | None
    setting_path: str | None
    weapon_evolution_path: str | None
    statistic_path: str | None
    cloud_save_id: str | None
    open_rij_test: str | None
    open_newton_test: str | None
    xml_gems: int | None
    game_summary: dict[str, Any] = field(default_factory=dict)
    item_snapshot: dict[str, Any] = field(default_factory=dict)
    weapon_evolution_snapshot: dict[str, Any] = field(default_factory=dict)
    statistic_snapshot: dict[str, Any] = field(default_factory=dict)
    weapon_blueprints: dict[str, str] = field(default_factory=dict)
    ref_weapon_used_times: dict[str, int] = field(default_factory=dict)
    shard_summaries: dict[str, dict[str, Any]] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)
    ref_diff_lines: list[str] = field(default_factory=list)
    input_audit_lines: list[str] = field(default_factory=list)
    skin_data: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    hero_data: list[dict[str, Any]] = field(default_factory=list)
    pet_data: dict[str, bool] = field(default_factory=dict)
    room_object_levels: dict[str, int] = field(default_factory=dict)
    ref_materials: dict[str, int] = field(default_factory=dict)
    ref_seeds: dict[str, int] = field(default_factory=dict)
    ref_blueprints: dict[str, str] = field(default_factory=dict)
    ref_item_loaded: bool = False
    ref_weapon_loaded: bool = False
    ref_statistic_loaded: bool = False
    statistic_loaded: bool = False
    game_loaded: bool = False


class SaveWorkspace:
    def __init__(self, input_dir: Path, reference_dir: Path | None = None) -> None:
        self.input_dir = input_dir.resolve()
        self.reference_dir = reference_dir.resolve() if reference_dir else None
        self.platform: Platform = Platform.Android
        self.uid: str | None = None
        self.game_path: Path | None = None
        self.prefs_path: Path | None = None
        self.item_path: Path | None = None
        self.setting_path: Path | None = None
        self.weapon_evolution_path: Path | None = None
        self.statistic_path: Path | None = None
        self.shard_paths: dict[str, Path | None] = {}
        self.game_loaded: bool = False
        self._game: GameDataStore | None = None
        self._prefs: PlayerPrefsStore | None = None
        self._item: ItemDataStore | None = None
        self._setting_data: dict[str, Any] | None = None
        self._weapon_evolution: WeaponEvolutionStore | None = None
        self._statistic: StatisticStore | None = None
        self._inspect_cache: dict[str, dict[str, Any]] = {}
        self._ref_item: dict[str, Any] | None = None
        self._ref_weapon_evolution: dict[str, Any] | None = None
        self._ref_statistic: dict[str, Any] | None = None
        self._ref_platform: Platform = Platform.Android
        self._ref_uid: str | None = None

    def load(self) -> WorkspaceSnapshot:
        if not self.input_dir.is_dir():
            raise FileNotFoundError(f"输入目录不存在: {self.input_dir}")

        warnings: list[str] = []

        # prefs 是识别 UID 的硬依赖；game.data 可缺（分片编辑仍可用）
        self.prefs_path = discover_prefs(self.input_dir)
        self.platform = Platform.IOS if self.prefs_path.suffix == '.plist' else Platform.Android
        self.uid = detect_uid(self.prefs_path, self.platform)
        self._prefs = PlayerPrefsStore.load(self.prefs_path, self.uid, self.platform)

        candidate_game = self.input_dir / "game.data"
        if candidate_game.is_file():
            self.game_path = candidate_game
            self._game = GameDataStore.load(self.game_path)
            self.game_loaded = True
        else:
            self.game_path = None
            self._game = GameDataStore({}, None)
            self.game_loaded = False
            warnings.append(
                f"{format_missing_file_compact('game.data', reason='角色/皮肤/宠物/客厅无法编辑')}；"
                f"材料/花圃/武器分片仍可从 item/statistic 载入（{DEVICE_FILES_DIR}/）"
            )

        self.item_path = discover_shard_path(self.input_dir, "item_data", self.uid)
        if self.item_path:
            self._item = ItemDataStore.load(self.item_path)
        elif self.game_loaded:
            self._item = ItemDataStore.from_game_mirror(self._game.data)
        else:
            self._item = ItemDataStore({})
            warnings.append(
                f"{format_missing_file_compact(f'item_data_{self.uid}_.data', reason='且无 game.data 可镜像')}；"
                "材料/花圃为空"
            )

        self.setting_path = discover_shard_path(self.input_dir, "setting", self.uid)
        if self.setting_path:
            self._setting_data, _ = decrypt_file(self.setting_path)
        else:
            self._setting_data = None

        self.weapon_evolution_path = discover_shard_path(
            self.input_dir, "weapon_evolution_data", self.uid
        )
        if self.weapon_evolution_path:
            self._weapon_evolution = WeaponEvolutionStore.load(self.weapon_evolution_path)
        else:
            self._weapon_evolution = WeaponEvolutionStore({"weapons": {}})

        self.statistic_path = discover_shard_path(self.input_dir, "statistic", self.uid)
        if self.statistic_path:
            self._statistic = StatisticStore.load(self.statistic_path)
        else:
            self._statistic = StatisticStore({"StatisticsData": {}})

        self.shard_paths = {
            dtype: discover_shard_path(self.input_dir, dtype, self.uid)
            for dtype in INSPECT_SHARDS
        }

        self._load_reference_shards()

        audit = audit_input(self.input_dir, self.uid, platform=self.platform)
        prefs = self._prefs
        skin_data = {
            hero: list(skins)
            for hero, skins in self._game.data.get("skinLock", {}).items()
        }

        xml_gems_raw = prefs.get(f"{self.uid}_gems")
        xml_gems = int(xml_gems_raw) if xml_gems_raw is not None else None
        game_gems = self._game.data.get("gems") if self.game_loaded else None
        rij = prefs.get(f"OpenRijTest_{self.uid}")
        if rij is None:
            warnings.append("XML / PList 缺少 OpenRijTest，改物品时将写入 0")
        elif rij not in ("0", 0):
            warnings.append("OpenRijTest 非 0，改物品时将强制 Legacy")
        if (
            self.game_loaded
            and xml_gems is not None
            and game_gems is not None
            and int(xml_gems) != int(game_gems)
        ):
            warnings.append(
                f"宝石双轨不同步：game.data={game_gems}，XML={xml_gems}（工具不修改 gems）"
            )

        ref_diff = compute_reference_diff(
            self._item.data,
            self._ref_item,
            self._weapon_evolution.data if self._weapon_evolution else None,
            self._ref_weapon_evolution,
        )

        shard_summaries = self._build_inspect_summaries()

        ref_used: dict[str, int] = {}
        if self._ref_statistic:
            ref_used = dict(StatisticStore(self._ref_statistic).snapshot().get("weapon_used_times", {}))

        return WorkspaceSnapshot(
            platform=self.platform,
            uid=self.uid,
            input_dir=str(self.input_dir),
            game_path=str(self.game_path) if self.game_path else None,
            prefs_path=str(self.prefs_path),
            item_path=str(self.item_path) if self.item_path else None,
            setting_path=str(self.setting_path) if self.setting_path else None,
            weapon_evolution_path=str(self.weapon_evolution_path) if self.weapon_evolution_path else None,
            statistic_path=str(self.statistic_path) if self.statistic_path else None,
            cloud_save_id=prefs.get("cloudSaveId") or prefs.get(f"{self.uid}_cloudSaveId"),
            open_rij_test=prefs.get(f"OpenRijTest_{self.uid}"),
            open_newton_test=prefs.get(f"OpenNewtonJsonTest_{self.uid}"),
            xml_gems=xml_gems,
            game_summary=self._game.summary(),
            item_snapshot=self._item.snapshot(),
            weapon_evolution_snapshot=self._weapon_evolution.snapshot(),
            statistic_snapshot=self._statistic.snapshot(),
            weapon_blueprints=filter_weapon_blueprints(
                dict((self._item.data.get("blueprints") or {}))
            ),
            ref_weapon_used_times=ref_used,
            shard_summaries=shard_summaries,
            warnings=warnings,
            ref_diff_lines=list(ref_diff.get("lines", [])),
            input_audit_lines=audit.summary_lines(),
            skin_data=skin_data,
            hero_data=self._game.hero_snapshot(),
            pet_data=self._game.pet_snapshot(),
            room_object_levels={
                k: int(v) for k, v in (self._game.data.get("roomObjectLevel") or {}).items()
            },
            ref_materials={
                k: int(v) for k, v in (self._ref_item or {}).get("materials", {}).items()
            },
            ref_seeds={
                k: int(v) for k, v in (self._ref_item or {}).get("seeds", {}).items()
            },
            ref_blueprints=dict((self._ref_item or {}).get("blueprints") or {}),
            ref_item_loaded=self._ref_item is not None,
            ref_weapon_loaded=self._ref_weapon_evolution is not None,
            ref_statistic_loaded=self._ref_statistic is not None,
            game_loaded=self.game_loaded,
            statistic_loaded=self.statistic_path is not None,
        )

    def _load_reference_shards(self) -> None:
        self._ref_item = None
        self._ref_weapon_evolution = None
        self._ref_statistic = None
        self._ref_uid = None
        if not self.reference_dir or not self.reference_dir.is_dir():
            return
        ref_prefs = self.reference_dir / PREFS_NAME
        ios_ref_prefs = self.reference_dir / IOS_PREFS_NAME
        if ref_prefs.is_file():
            self._ref_platform = Platform.Android
        if ios_ref_prefs.is_file():
            self._ref_platform = Platform.IOS
        if ref_prefs.is_file() and ios_ref_prefs.is_file():
            return
        try:
            self._ref_uid = detect_uid(ref_prefs if self._ref_platform == Platform.Android else ios_ref_prefs, self._ref_platform)
            ref_item_path = discover_shard_path(self.reference_dir, "item_data", self._ref_uid)
            if ref_item_path:
                self._ref_item = ItemDataStore.load(ref_item_path).data
            ref_we_path = discover_shard_path(
                self.reference_dir, "weapon_evolution_data", self._ref_uid
            )
            if ref_we_path:
                self._ref_weapon_evolution = WeaponEvolutionStore.load(ref_we_path).data
            ref_stat_path = discover_shard_path(self.reference_dir, "statistic", self._ref_uid)
            if ref_stat_path:
                self._ref_statistic = StatisticStore.load(ref_stat_path).data
        except Exception:
            pass

    def _build_inspect_summaries(self) -> dict[str, dict[str, Any]]:
        summaries: dict[str, dict[str, Any]] = {}
        assert self.uid
        for dtype, path in self.shard_paths.items():
            if not path or not path.is_file():
                continue
            try:
                data, method = decrypt_file(path)
                if isinstance(data, dict):
                    summaries[dtype] = {
                        "file": path.name,
                        "method": method,
                        "field_count": len(data),
                        "keys": list(data.keys())[:12],
                    }
                elif isinstance(data, list):
                    summaries[dtype] = {
                        "file": path.name,
                        "method": method,
                        "field_count": len(data),
                        "keys": [],
                    }
            except Exception as exc:
                summaries[dtype] = {"file": path.name, "error": str(exc)}
        return summaries

    def load_inspect_shard(self, data_type: str) -> dict[str, Any] | list[Any] | None:
        if data_type in self._inspect_cache:
            return self._inspect_cache[data_type]
        assert self.uid
        path = self.shard_paths.get(data_type) or discover_shard_path(
            self.input_dir, data_type, self.uid
        )
        if not path or not path.is_file():
            return None
        data, _ = decrypt_file(path)
        if isinstance(data, dict):
            self._inspect_cache[data_type] = data
        return data

    @property
    def game(self) -> GameDataStore:
        assert self._game
        return self._game

    @property
    def prefs(self) -> PlayerPrefsStore:
        assert self._prefs
        return self._prefs

    @property
    def item(self) -> ItemDataStore:
        assert self._item
        return self._item

    @property
    def weapon_evolution(self) -> WeaponEvolutionStore:
        assert self._weapon_evolution
        return self._weapon_evolution

    @property
    def statistic(self) -> StatisticStore:
        assert self._statistic
        return self._statistic

    @property
    def setting_data(self) -> dict[str, Any] | None:
        return self._setting_data

    @property
    def reference_item(self) -> dict[str, Any] | None:
        return self._ref_item

    @property
    def reference_weapon_evolution(self) -> dict[str, Any] | None:
        return self._ref_weapon_evolution

    @property
    def reference_statistic(self) -> dict[str, Any] | None:
        return self._ref_statistic

    def clone_for_patch(self) -> SaveWorkspace:
        import json

        ws = SaveWorkspace(self.input_dir, self.reference_dir)
        ws.uid = self.uid
        ws.game_path = self.game_path
        ws.prefs_path = self.prefs_path
        ws.item_path = self.item_path
        ws.setting_path = self.setting_path
        ws.weapon_evolution_path = self.weapon_evolution_path
        ws.statistic_path = self.statistic_path
        ws.shard_paths = dict(self.shard_paths)
        ws.game_loaded = self.game_loaded
        ws._game = GameDataStore(
            json.loads(json.dumps(self._game.data if self._game else {})),
            self.game_path,
        )
        ws._prefs = PlayerPrefsStore.load(self.prefs_path, self.uid or "", self.platform)
        ws._item = ItemDataStore(
            json.loads(json.dumps(self._item.data if self._item else {})),
            self.item_path,
        )
        ws._setting_data = (
            json.loads(json.dumps(self._setting_data)) if self._setting_data else None
        )
        ws._weapon_evolution = WeaponEvolutionStore(
            json.loads(json.dumps(self._weapon_evolution.data)),
            self.weapon_evolution_path,
        )
        ws._statistic = StatisticStore(
            json.loads(json.dumps(self._statistic.data)),
            self.statistic_path,
        )
        ws._ref_item = self._ref_item
        ws._ref_weapon_evolution = self._ref_weapon_evolution
        ws._ref_statistic = self._ref_statistic
        ws._ref_uid = self._ref_uid
        return ws
