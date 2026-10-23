package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional private 8.7 fixture. Plans stay in memory; no game or fixture writes. */
class Version87CompatibilityTest {
    @Test fun actual87SnapshotSupportsQuickAndExpertPlansWithoutTouchingOtherShards() {
        val path = System.getProperty("save87.baseline").orEmpty()
        assumeTrue("Optional private 8.7 fixture not supplied", path.isNotBlank())
        val directory = File(path)
        val originals = directory.walkTopDown().filter { it.isFile }.associateWith { it.readBytes() }
        val data = originals.keys.filter { it.parentFile?.name == "files" }
        fun save(file: File, folder: String = "files") = SaveFile(
            "/data/user/0/com.ChillyRoom.DungeonShooter/$folder/${file.name}", originals.getValue(file), "1000:1000", "600", "")
        val prefs = save(originals.keys.single { it.parentFile?.name == "shared_prefs" && it.name.endsWith("playerprefs.xml") }, "shared_prefs")
        val game = save(data.single { it.name == "game.data" })
        val item = save(data.single { it.name.startsWith("item_data_") && it.name.endsWith("_.data") })
        val statistic = save(data.single { it.name.startsWith("statistic_") && it.name.endsWith("_.data") })
        val skinFile = save(data.single { it.name.startsWith("weapon_evolution_data_") && it.name.endsWith("_.data") })
        val snapshot = SaveSnapshot("com.ChillyRoom.DungeonShooter", 0, prefs, game, listOf(item), listOf(statistic), listOf(skinFile))
        val account = EditEngine.accounts(snapshot).single()
        val inspection = SaveInspector.inspect(snapshot, account)
        val catalog = inspection.characters.getOrThrow()
        assertEquals(42, catalog.heroes.size)
        assertEquals(792, catalog.heroes.sumOf { it.skins.size })
        val progression = inspection.progression.getOrThrow()
        assertTrue(progression.all { it.levelError == null && it.skillError == null })
        assertEquals(104, progression.sumOf { it.skills.size })
        val arcane = catalog.heroes.single { it.name == "ArcaneKnight" }.index
        assertTrue(2 in progression.single { it.hero == arcane }.skills)
        val pets = inspection.pets.getOrThrow()
        assertEquals(57, pets.size)
        assertEquals(57, pets.single { it.definition.id == "FishChipKing" }.definition.index)
        val oldItem = inspection.items.getOrThrow()
        assertEquals(JsonPrimitive(80700), oldItem["AppVersion"])
        assertTrue("trainingJourney" in oldItem)
        val oldWeapons = inspection.weapons.getOrThrow()
        assertTrue(oldWeapons.all { it.error == null })
        val named = setOf("weapon_hammerhead", "weapon_lighter", "weapon_void_hand")
        assertTrue(oldWeapons.map { it.id }.containsAll(named))
        val cell = ItemKey("materials", "material_cell")
        val quick = EditEngine.preview(snapshot, account, UnlockSelection(
            heroes = catalog.heroes.filter { it.unlocked != null }.map { it.index }.toSet(),
            skins = catalog.heroes.flatMap { hero -> hero.skins.keys.map { SkinId(hero.index, it) } }.toSet(),
            levels = true, skills = true, pets = true), ItemSelection(
            tapes = true, blueprints = true, facilities = true, jewelry = true,
            increments = ItemCatalog.ofKind(ItemKind.QUANTITY).associate { it.key to 1000 },
            amounts = ItemCatalog.ofKind(ItemKind.AMOUNT).associate { it.key to 123 }), WeaponSelection(allPlusEight = true),
            WeaponSkinSelection(unlockAll = true))
        fun output(plan: SavePlan, file: SaveFile) = plan.outputs[file.path] ?: file.bytes
        val quickGame = output(quick, game)
        val quickPrefs = output(quick, prefs)
        assertTrue(UnlockEngine.catalog(quickGame, quickPrefs, account).heroes.all { hero ->
            hero.unlocked != false && hero.skins.values.all { it == 1 }
        })
        assertTrue(UnlockEngine.progression(quickGame, quickPrefs, account).all { hero ->
            hero.levelError == null && hero.level!! >= 7 && hero.skillError == null && hero.skills.values.all { it }
        })
        assertTrue(PetEngine.inspect(quickGame, quickPrefs, account).all { it.unlocked })
        val changedGameFields = setOf("heroUnlock", "skinLock", "heroLevel", "heroSkillUnlock", "petUnlock")
        assertEquals(UnlockEngine.game(game.bytes).filterKeys { it !in changedGameFields },
            UnlockEngine.game(quickGame).filterKeys { it !in changedGameFields })
        val quickItem = ItemEngine.inspect(output(quick, item))
        val itemFields = setOf("materials", "seeds", "tokenTickets", "blueprints", "itemUnlock", "jewelryData")
        assertEquals(oldItem.filterKeys { it !in itemFields }, quickItem.filterKeys { it !in itemFields })
        for (field in itemFields - "itemUnlock") {
            oldItem.getValue(field).jsonObject.filterKeys { ItemKey(field, it) !in ItemCatalog.byKey }.forEach { (id, value) ->
                assertEquals(value, quickItem.getValue(field).jsonObject[id])
            }
        }
        assertEquals(ItemEngine.quantity(oldItem, cell) + 1000, ItemEngine.quantity(quickItem, cell))
        val quickWeapons = WeaponEngine.states(WeaponEngine.inspect(output(quick, statistic), item.bytes))
        oldWeapons.forEach { old -> assertEquals(old.count!! + 8, quickWeapons.single { it.id == old.id }.count) }
        assertEquals(StatisticCodec.decode(statistic.bytes) - "object2ObtainTime",
            StatisticCodec.decode(output(quick, statistic)) - "object2ObtainTime")
        val originalSkins = inspection.weaponSkins.getOrThrow()
        val quickSkins = WeaponSkinEngine.inspect(output(quick, skinFile), item.bytes)
        assertTrue(quickSkins.states.all { state -> state.skins.all { it.id in state.unlocked } })
        originalSkins.states.filterNot { it.missing }.forEach { original ->
            val actual = quickSkins.states.single { it.weapon == original.weapon }
            assertEquals(original.level, actual.level)
            assertEquals(original.current, actual.current)
        }

        val expert = EditEngine.preview(snapshot, account,
            UnlockSelection(skillIds = setOf(SkillId(arcane, 2)), petIds = setOf("FishChipKing")),
            ItemSelection(increments = mapOf(cell to 7)), WeaponSelection(increments = named.associateWith { 8 }),
            WeaponSkinSelection(unlock = setOf("weapon_007_s_2"), current = mapOf("weapon_007" to 2)))
        val expertPets = PetEngine.inspect(output(expert, game), output(expert, prefs), account)
        assertTrue(expertPets.single { it.definition.id == "FishChipKing" }.unlocked)
        assertEquals(pets.filter { it.definition.id != "FishChipKing" }, expertPets.filter { it.definition.id != "FishChipKing" })
        val expertProgress = UnlockEngine.progression(output(expert, game), output(expert, prefs), account)
        assertEquals(true, expertProgress.single { it.hero == arcane }.skills[2])
        assertEquals(progression.filter { it.hero != arcane }, expertProgress.filter { it.hero != arcane })
        val expertWeapons = WeaponEngine.states(WeaponEngine.inspect(output(expert, statistic), item.bytes))
        oldWeapons.forEach { old ->
            assertEquals(old.count!! + if (old.id in named) 8 else 0, expertWeapons.single { it.id == old.id }.count)
        }
        assertEquals(ItemEngine.quantity(oldItem, cell) + 7, ItemEngine.quantity(ItemEngine.inspect(output(expert, item)), cell))
        val expertSkins = WeaponSkinEngine.inspect(output(expert, skinFile), item.bytes)
        assertEquals(2, expertSkins.states.single { it.weapon == "weapon_007" }.current)
        assertEquals(originalSkins.states.filter { it.weapon != "weapon_007" }, expertSkins.states.filter { it.weapon != "weapon_007" })
        val allFeatures = EditEngine.preview(snapshot, account, UnlockSelection(), ItemSelection(blueprints = true,
            increments = ItemCatalog.ofKind(ItemKind.QUANTITY).associate { it.key to 1000 }), WeaponSelection(),
            WeaponSkinSelection(unlockAll = true), WeaponEvolutionSelection(allMax = true), MythicSelection(maxAll = true))
        val evolved = WeaponEvolutionEngine.inspect(output(allFeatures, skinFile), item.bytes)
        assertEquals(121, evolved.states.size)
        assertTrue(evolved.states.all { it.error == null && it.level!! >= 1 })
        val finalMythics = MythicEngine.inspect(output(allFeatures, item))
        assertEquals(28, finalMythics.states.size)
        assertTrue(finalMythics.states.all { it.error == null && it.level!! >= 3 })
        val allItem = ItemCodec.decode(output(allFeatures, item))
        val allFields = setOf("materials", "seeds", "tokenTickets", "blueprints", "mythicWeapons")
        assertEquals(oldItem.filterKeys { it !in allFields }, allItem.filterKeys { it !in allFields })
        assertEquals(JsonPrimitive("Researched"), allItem.getValue("blueprints").jsonObject["blueprint_weapon_boss_void_origin"])
        val allSkins = WeaponSkinEngine.inspect(output(allFeatures, skinFile), item.bytes)
        assertTrue(allSkins.states.all { state -> state.skins.all { it.id in state.unlocked } })
        originalSkins.states.filterNot { it.missing }.forEach { original ->
            assertEquals(original.current, allSkins.states.single { it.weapon == original.weapon }.current)
        }
        for (plan in listOf(quick, expert, allFeatures)) {
            assertTrue(plan.outputs.keys.all { it in snapshot.files.map { file -> file.path } })
            for (flag in listOf("OpenRijTest", "OpenNewtonJsonTest")) {
                val key = "${flag}_$account"
                UnlockEngine.prefs(output(plan, prefs))[key]?.let { assertEquals("0", it.getAttribute("value")) }
            }
        }
        // Includes season and battle-pass shards outside the plan.
        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }
}
