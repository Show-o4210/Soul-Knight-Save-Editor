from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any, Callable

from core.constants import DEVICE_FILES_DIR, IOS_DEVICE_FILES_DIR, PREFS_NAME, IOS_PREFS_NAME, Platform, format_missing_file
from core.crypto import encrypt_setting_data
from core.deploy import checklist_lines
from core.diff import dict_key_diff, list_append_preview, summarize_changes
from core.input_rules import audit_input, manifest_for_plan
from core.pet_map import pet_index_map
from core.shard_registry import discover_shard_path, shard_filename
from core.stores.prefs_store import detect_uid, discover_prefs
from core.workspace import SaveWorkspace
from engine.patch_plan import PatchPlan


class PatchRunner:
    def __init__(self, input_dir: Path, output_dir: Path, reference_dir: Path | None = None) -> None:
        self.input_dir = input_dir
        self.output_dir = output_dir
        self.reference_dir = reference_dir

    def _shard_flags(self, ws: SaveWorkspace) -> dict[str, bool]:
        return {
            "weapon_evolution_data": True,
            "statistic": ws.statistic_path is not None and ws.statistic_path.is_file(),
        }

    def validate(self, plan: PatchPlan) -> list[str]:
        ws = SaveWorkspace(self.input_dir, self.reference_dir)
        snap = ws.load()
        audit = audit_input(
            self.input_dir,
            snap.uid,
            platform=snap.platform,
            needs_item=plan.touches_item(),
        )
        issues: list[str] = []
        if not (ws.prefs_path and Path(ws.prefs_path).is_file()):
            issues.append(format_missing_file(PREFS_NAME, reason="识别账号与写入 XML 的硬依赖"))
        # 角色/皮肤/宠物/客厅必须真有 game.data；仅改 item 时无 game 可跳过镜像
        needs_game_body = (
            plan.all_characters
            or bool(plan.hero_picks)
            or plan.all_skins
            or bool(plan.skin_picks)
            or plan.all_pets
            or bool(plan.pet_picks)
            or plan.touches_game_room()
        )
        if needs_game_body and not snap.game_loaded:
            issues.append(
                format_missing_file("game.data", reason="当前计划需要（角色/皮肤/宠物/客厅）")
            )
        if plan.touches_item() and not snap.item_path and not snap.game_loaded:
            item_name = shard_filename("item_data", snap.uid)
            issues.append(
                format_missing_file(
                    item_name,
                    reason=f"改物品需要该分片，或改用 {DEVICE_FILES_DIR if snap.platform == Platform.Android else IOS_DEVICE_FILES_DIR}/game.data 镜像",
                )
            )
        if audit.missing_for_plan:
            issues.extend(audit.missing_for_plan)

        ref_dir = self.reference_dir
        if plan.touches_item_reference_merge():
            ref_item = None
            if ref_dir and ref_dir.is_dir():
                try:
                    ref_uid = detect_uid(discover_prefs(ref_dir))
                    ref_item = discover_shard_path(ref_dir, "item_data", ref_uid)
                except Exception:
                    ref_item = None
            if not ref_item or not ref_item.is_file():
                issues.append(
                    format_missing_file(
                        "item_data_{UID}_.data",
                        reason="物品参考合并需要",
                        place_in="参考",
                    )
                )

        if plan.touches_weapon_evolution():
            ref_we = None
            if ref_dir and ref_dir.is_dir():
                try:
                    ref_uid = detect_uid(discover_prefs(ref_dir))
                    ref_we = discover_shard_path(ref_dir, "weapon_evolution_data", ref_uid)
                except Exception:
                    ref_we = None
            if not ref_we or not ref_we.is_file():
                issues.append(
                    format_missing_file(
                        "weapon_evolution_data_{UID}_.data",
                        reason="武器进化合并需要",
                        place_in="参考",
                    )
                )

        if plan.touches_statistic():
            stat_name = shard_filename("statistic", snap.uid)
            stat_path = discover_shard_path(self.input_dir, "statistic", snap.uid)
            if not stat_path or not stat_path.is_file():
                issues.append(
                    format_missing_file(stat_name, reason="改武器获取次数 / 锻造 +8 请放入输入")
                )
            if plan.merge_weapon_used_times or plan.merge_weapon_used_max:
                ref_stat = None
                if ref_dir and ref_dir.is_dir():
                    try:
                        ref_uid = detect_uid(discover_prefs(ref_dir))
                        ref_stat = discover_shard_path(ref_dir, "statistic", ref_uid)
                    except Exception:
                        ref_stat = None
                if not ref_stat or not ref_stat.is_file():
                    issues.append(
                        format_missing_file(
                            "statistic_{UID}_.data",
                            reason="武器次数参考合并需要",
                            place_in="参考",
                        )
                    )
        return issues

    def preview(self, plan: PatchPlan) -> dict[str, Any]:
        issues = self.validate(plan)
        ws = SaveWorkspace(self.input_dir, self.reference_dir)
        ws.load()
        ws = ws.clone_for_patch()
        uid = ws.uid or "?"
        platform = ws.platform
        manifest = self._build_manifest(ws, plan)

        before_item = {
            "materials": dict(ws.item.data.get("materials") or {}),
            "seeds": dict(ws.item.data.get("seeds") or {}),
            "blueprints": dict(ws.item.data.get("blueprints") or {}),
            "itemUnlock": list(ws.item.data.get("itemUnlock") or []),
            "mythicWeapons": len(ws.item.data.get("mythicWeapons") or []),
        }
        before_we = len((ws.weapon_evolution.data.get("weapons") or {}))
        before_summary = ws.game.summary()

        stats, _ = self._execute_plan(ws, plan, log=None)

        after_item = {
            "materials": dict(ws.item.data.get("materials") or {}),
            "seeds": dict(ws.item.data.get("seeds") or {}),
            "blueprints": dict(ws.item.data.get("blueprints") or {}),
            "itemUnlock": list(ws.item.data.get("itemUnlock") or []),
            "mythicWeapons": len(ws.item.data.get("mythicWeapons") or []),
        }
        after_we = len((ws.weapon_evolution.data.get("weapons") or {}))
        after_summary = ws.game.summary()

        detail: list[str] = list(plan.describe())
        if plan.hero_picks:
            for hero, opts in sorted(plan.hero_picks.items()):
                detail.append(f"  角色 {hero}: {opts}")
        if plan.pet_picks:
            detail.append(f"  宠物自选 {len(plan.pet_picks)} 项")
        detail.extend(dict_key_diff(before_item["materials"], after_item["materials"], prefix="材料.", limit=12))
        detail.extend(dict_key_diff(before_item["seeds"], after_item["seeds"], prefix="种子.", limit=12))
        detail.extend(dict_key_diff(
            {k: v for k, v in before_item["blueprints"].items()},
            {k: v for k, v in after_item["blueprints"].items()},
            prefix="蓝图.",
            limit=12,
        ))
        detail.extend(list_append_preview(
            before_item["itemUnlock"], after_item["itemUnlock"], label="itemUnlock"
        ))
        if before_item["mythicWeapons"] != after_item["mythicWeapons"]:
            detail.append(f"神话武器: {before_item['mythicWeapons']} -> {after_item['mythicWeapons']}")
        if before_we != after_we:
            detail.append(f"武器进化: {before_we} -> {after_we} 把")
        detail.extend(summarize_changes(
            {k: str(v) for k, v in before_summary.items()},
            {k: str(v) for k, v in after_summary.items()},
        ))

        output_files = manifest.filenames(str(uid), platform)
        return {
            "uid": uid,
            "issues": issues,
            "stats": stats,
            "describe": plan.describe(),
            "detail_lines": detail,
            "output_files": output_files,
            "deploy_checklist": checklist_lines(output_files=output_files, platform=platform),
            "before_summary": before_summary,
            "after_summary": after_summary,
        }

    def _build_manifest(self, ws: SaveWorkspace, plan: PatchPlan):
        has_item = ws.item_path is not None and ws.item_path.is_file()
        has_setting = ws.setting_path is not None and ws.setting_path.is_file()
        manifest = manifest_for_plan(
            plan,
            has_item_file=has_item,
            has_setting_file=has_setting,
            has_shards=self._shard_flags(ws),
        )
        # 无 game.data 时绝不写出空壳主档
        if not ws.game_loaded:
            manifest.game = False
        return manifest

    def apply(self, plan: PatchPlan, log: Callable[[str], None] | None = None) -> dict[str, Any]:
        def say(msg: str) -> None:
            if log:
                log(msg)

        issues = self.validate(plan)
        if issues:
            raise ValueError("无法应用：\n" + "\n".join(f"• {x}" for x in issues))

        ws = SaveWorkspace(self.input_dir, self.reference_dir)
        ws.load()
        ws = ws.clone_for_patch()
        uid = ws.uid
        platform = ws.platform
        assert uid

        manifest = self._build_manifest(ws, plan)

        before_game = {
            "heroes_unlocked": ws.game.summary()["heroes_unlocked"],
            "skins_owned": ws.game.summary()["skins_owned"],
            "pets_unlocked": ws.game.summary()["pets_unlocked"],
            "itemUnlock_len": len(ws.item.data.get("itemUnlock") or []),
            "blueprints_len": len(ws.item.data.get("blueprints") or {}),
            "mythic_len": len(ws.item.data.get("mythicWeapons") or []),
            "weapons_len": len(ws.weapon_evolution.data.get("weapons") or {}),
        }

        report: dict[str, Any] = {
            "uid": uid,
            "stats": {},
            "deploy": [],
            "output_files": manifest.filenames(uid, platform),
            "before": before_game,
            "deploy_checklist": checklist_lines(output_files=manifest.filenames(uid, platform), platform=platform),
        }
        say(f"将输出 {len(manifest.filenames(uid, platform))} 个文件: {', '.join(manifest.filenames(uid, platform))}")

        stats, _ = self._execute_plan(ws, plan, log=say)
        report["stats"] = stats

        if self.output_dir.exists():
            shutil.rmtree(self.output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

        if manifest.game:
            game_out = self.output_dir / "game.data"
            ws.game.save(game_out)
            ws.game.save_json(self.output_dir / "game.data.json")
            report["deploy"].append(f"files/{game_out.name}")
            say(f"→ {game_out.name}")

        if manifest.prefs:
            prefs_out = self.output_dir / PREFS_NAME
            ios_prefs_out = self.output_dir / IOS_PREFS_NAME
            if ws.prefs.platform == Platform.Android:
                ws.prefs.save(prefs_out)
                report["deploy"].append(f"shared_prefs/{prefs_out.name}")
                say(f"→ {prefs_out.name}")
            else:
                ws.prefs.save(ios_prefs_out)
                report["deploy"].append(f"Preferences/{ios_prefs_out.name}")
                say(f"→ {ios_prefs_out.name}")

        if manifest.item:
            item_out = self.output_dir / shard_filename("item_data", uid)
            ws.item.save(item_out)
            ws.item.save_json(self.output_dir / f"item_data_{uid}_.json")
            report["deploy"].append(f"{'files' if ws.prefs.platform == Platform.Android else 'Documents'}/{item_out.name}")
            say(f"→ {item_out.name}")

        if manifest.setting and ws.setting_data is not None:
            setting_out = self.output_dir / shard_filename("setting", uid)
            enc = encrypt_setting_data(ws.setting_data, ws.setting_path)
            setting_out.write_text(enc, encoding="utf-8")
            report["deploy"].append(f"{'files' if ws.prefs.platform == Platform.Android else 'Documents'}/{setting_out.name}")
            say(f"→ {setting_out.name}")

        if manifest.shards.get("weapon_evolution_data"):
            we_out = self.output_dir / shard_filename("weapon_evolution_data", uid)
            ws.weapon_evolution.save(we_out)
            ws.weapon_evolution.save_json(self.output_dir / f"weapon_evolution_data_{uid}_.json")
            report["deploy"].append(f"{'files' if ws.prefs.platform == Platform.Android else 'Documents'}/{we_out.name}")
            say(f"→ {we_out.name}")

        if manifest.shards.get("statistic"):
            stat_out = self.output_dir / shard_filename("statistic", uid)
            ws.statistic.save(stat_out)
            ws.statistic.save_json(self.output_dir / f"statistic_{uid}_.json")
            report["deploy"].append(f"{'files' if ws.prefs.platform == Platform.Android else 'Documents'}/{stat_out.name}")
            say(f"→ {stat_out.name}")

        after_game = {
            "heroes_unlocked": ws.game.summary()["heroes_unlocked"],
            "skins_owned": ws.game.summary()["skins_owned"],
            "pets_unlocked": ws.game.summary()["pets_unlocked"],
            "itemUnlock_len": len(ws.item.data.get("itemUnlock") or []),
            "blueprints_len": len(ws.item.data.get("blueprints") or {}),
            "mythic_len": len(ws.item.data.get("mythicWeapons") or []),
            "weapons_len": len(ws.weapon_evolution.data.get("weapons") or {}),
        }
        report["after"] = after_game
        report["changes"] = summarize_changes(
            {k: str(v) for k, v in before_game.items()},
            {k: str(v) for k, v in after_game.items()},
        )
        report["game_summary"] = ws.game.summary()

        preview_blob = {
            "plan": plan.describe(),
            "changes": report["changes"],
            "stats": stats,
            "output_files": report["output_files"],
        }
        (self.output_dir / "部署说明.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        (self.output_dir / "changes_preview.json").write_text(
            json.dumps(preview_blob, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        say("完成")
        return report

    def _execute_plan(
        self,
        ws: SaveWorkspace,
        plan: PatchPlan,
        log: Callable[[str], None] | None,
    ) -> tuple[dict[str, Any], bool]:
        from core.stores.game_store import GameDataStore

        def say(msg: str) -> None:
            if log:
                log(msg)

        uid = ws.uid
        assert uid
        has_setting = ws.setting_path is not None and ws.setting_path.is_file()
        stats: dict[str, Any] = {}

        if plan.cleanup_stale_uid:
            n = ws.prefs.cleanup_stale_uid(uid)
            stats["cleanup"] = n
            say(f"XML / PList 清理他号键: −{n}")

        if plan.all_characters:
            ws.game.patch_characters(default_level=plan.default_level)
            st = ws.prefs.patch_characters(default_level=plan.default_level, game_data=ws.game.data)
            stats["characters"] = st
            say(f"角色/技能: unlock={st['unlock']} level={st['level']} skill={st['skill']}")
        elif plan.hero_picks:
            st = ws.game.patch_characters_selective(plan.hero_picks)
            hero_idx = GameDataStore.hero_index_map(ws.game.data)
            st_xml = ws.prefs.patch_characters_selective(plan.hero_picks, hero_idx, ws.game.data)
            stats["characters_selective"] = {"game": st, "xml": st_xml}
            say(f"自选角色: game {st}, xml {st_xml}")

        if plan.all_skins:
            ws.game.patch_skins()
            n = ws.prefs.patch_skins()
            stats["skins"] = n
            say(f"全皮肤: xml 更新 {n} 处")
        elif plan.skin_picks:
            hero_idx = GameDataStore.hero_index_map(ws.game.data)
            n_game = ws.game.patch_skins_selective(plan.skin_picks)
            n_xml = ws.prefs.patch_skins_selective(plan.skin_picks, hero_idx)
            stats["skins_selective"] = {"game": n_game, "xml": n_xml}
            say(f"自选皮肤: game {n_game} 处, xml {n_xml} 处")

        if plan.all_pets:
            n_game = ws.game.patch_pets(unlock=True)
            pet_idx = pet_index_map(ws.game.data)
            n_xml = ws.prefs.patch_all_pets(pet_idx, unlock=True)
            stats["pets"] = {"game": n_game, "xml": n_xml}
            say(f"全宠物: game {n_game}, xml {n_xml}")
        elif plan.pet_picks:
            n_game = ws.game.patch_pets_selective(plan.pet_picks)
            pet_idx = pet_index_map(ws.game.data)
            n_xml = ws.prefs.patch_pets_selective(plan.pet_picks, pet_idx)
            stats["pets_selective"] = {"game": n_game, "xml": n_xml}
            say(f"自选宠物: game {n_game}, xml {n_xml}")

        if plan.room_object_levels:
            n = ws.game.patch_room_object_levels(plan.room_object_levels)
            stats["room_object_levels"] = n
            say(f"客厅设施等级: 更新 {n} 项（仅 game.data）")

        if plan.secret_keys_append:
            n = ws.game.patch_secret_keys_append(plan.secret_keys_append)
            stats["secret_keys"] = n
            say(f"秘密钥匙: 追加 {n} 项")

        item_changed = False
        pot_slots = plan.effective_pot_slots()
        if pot_slots:
            n = ws.item.patch_plant_pots(pot_slots)
            stats["plant_pots"] = n
            item_changed = item_changed or n > 0
            say(f"花圃槽位: 新增 {n} 项 itemUnlock")

        if plan.item_unlock_picks:
            n = ws.item.patch_item_unlock_toggle(plan.item_unlock_picks)
            stats["item_unlock_toggle"] = n
            item_changed = item_changed or n > 0
            say(f"itemUnlock 勾选: 更新 {n} 项")

        if plan.item_unlock_append:
            n = ws.item.patch_item_unlock_append(plan.item_unlock_append)
            stats["item_unlock_append"] = n
            item_changed = item_changed or n > 0
            say(f"itemUnlock 追加: {n} 项")

        if plan.plant_picks:
            n = ws.item.patch_plants(plan.plant_picks)
            stats["plant_picks"] = n
            item_changed = item_changed or n > 0
            say(f"花圃植物: 更新 {n} 槽")

        if plan.material_picks:
            n = ws.item.patch_quantities("materials", plan.material_picks)
            stats["materials"] = n
            item_changed = item_changed or n > 0
            say(f"材料: 更新 {n} 项")

        if plan.seed_picks:
            n = ws.item.patch_quantities("seeds", plan.seed_picks)
            stats["seeds"] = n
            item_changed = item_changed or n > 0
            say(f"种子: 更新 {n} 项")

        if plan.blueprint_picks:
            n = ws.item.patch_blueprints(plan.blueprint_picks)
            stats["blueprints"] = n
            item_changed = item_changed or n > 0
            say(f"蓝图: 更新 {n} 项")

        if plan.touches_item_reference_merge():
            ref = ws.reference_item
            if ref:
                st = ws.item.merge_from_reference(
                    ref,
                    blueprints=plan.merge_blueprints,
                    seeds=plan.merge_seeds,
                    item_unlock=plan.merge_item_unlock,
                    mythic_weapons=plan.merge_mythic_weapons,
                    materials=plan.merge_materials,
                    materials_max=plan.merge_materials_max,
                    forge_weapons=plan.merge_forge_weapons,
                    jewelry=plan.merge_jewelry,
                    room_decorate=plan.merge_room_decorate,
                )
                stats["merge_item"] = st
                stats["merge"] = st
                item_changed = item_changed or bool(st)
                say(f"物品参考合并: {st}")

        we_changed = False
        if plan.touches_weapon_evolution():
            ref_we = ws.reference_weapon_evolution
            if ref_we:
                st = ws.weapon_evolution.merge_from_reference(
                    ref_we,
                    use_union=plan.merge_weapon_evolution,
                    use_max_level=plan.merge_weapon_evolution_max,
                )
                stats["merge_weapon_evolution"] = st
                we_changed = bool(st.get("added") or st.get("maxed"))
                say(f"武器进化参考合并: {st}")

        stat_changed = False
        if plan.weapon_used_picks:
            n = ws.statistic.patch_weapon_counts("_weaponUsedTimes", plan.weapon_used_picks)
            stats["weapon_used_picks"] = n
            stat_changed = stat_changed or n > 0
            say(f"武器获取次数: 更新 {n} 项")

        if plan.unlock_weapon_forge:
            from core.constants import WEAPON_FORGE_UNLOCK_PLUS

            n = ws.statistic.apply_weapon_used_plus(WEAPON_FORGE_UNLOCK_PLUS)
            stats["unlock_weapon_forge"] = n
            stat_changed = stat_changed or n > 0
            say(f"常规武器锻造: {n} 项获取次数 +{WEAPON_FORGE_UNLOCK_PLUS}")

        if plan.merge_weapon_used_times or plan.merge_weapon_used_max:
            ref_stat = ws.reference_statistic
            if ref_stat:
                st = ws.statistic.merge_weapon_used_from_reference(
                    ref_stat,
                    use_union=plan.merge_weapon_used_times,
                    use_max=plan.merge_weapon_used_max,
                )
                stats["merge_weapon_used"] = st
                stat_changed = stat_changed or bool(st.get("added") or st.get("maxed"))
                say(f"武器获取次数参考合并: {st}")

        if item_changed and plan.sync_item_mirror:
            if ws.game_loaded:
                ws.game.sync_item_mirror(ws.item.data)
                say("已同步 game.data.itemData 镜像")
            else:
                say("跳过 game 镜像（输入无 game.data）")

        if plan.force_legacy_format and plan.touches_item():
            ws.prefs.force_legacy_format()
            if ws.setting_data is not None:
                ws.setting_data["UseRijData"] = False
                ws.setting_data["UseNewtonJsonData"] = False
            mirror_stats = ws.prefs.sync_game_mirror_for_legacy(
                ws.game.data,
                pet_index_map(ws.game.data),
                sync_heroes=not plan.hero_picks and not plan.all_characters,
                sync_skins=not plan.skin_picks and not plan.all_skins,
                sync_pets=not plan.pet_picks and not plan.all_pets,
                sync_skills=not plan.hero_picks and not plan.all_characters,
            )
            stats["legacy_format"] = True
            stats["xml_mirror_sync"] = mirror_stats
            say(
                "已写入 Legacy 格式开关 (xml"
                + (" + setting)" if has_setting else ")")
                + f"，XML 镜像同步 {mirror_stats}"
            )

        return stats, item_changed or we_changed or stat_changed
