package com.example.soul_knight_save_editor.unlock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in root transactions against UUID-named assistant fixtures, never game files. */
@RunWith(AndroidJUnit4::class)
class RelockRootFixtureTest {
    @Test fun originalLocksCurrentSelectionsAndRestoreInsideOwnSandbox() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.example.soul_knight_save_editor")
        val user = android.os.Process.myUid() / 100000
        val token = UUID.randomUUID().toString()
        val fixture = File(context.filesDir, "relock-fixture-$token").apply { check(mkdir()) }
        require(fixture.canonicalFile.parentFile == context.filesDir.canonicalFile)
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { check(isDirectory || mkdirs()) }
        val prefs = File(prefsDir, "relock-fixture-$token.xml")
        require(prefs.canonicalFile.parentFile == prefsDir.canonicalFile)
        val game = File(fixture, "game.data")
        val root = RootStorage(helperApk = context.applicationInfo.sourceDir)
        var ownsPrefs = false
        try {
            val originalGame = UnlockEngine.xor("""{
                "heroUnlock":{"Knight":true,"Ranger":false},
                "skinLock":{"Knight":[{"Key":0,"Value":1}],"Ranger":[{"Key":0,"Value":1},{"Key":1,"Value":-13}]},
                "heroSkillUnlock":{"Knight":[{"Key":0,"Value":true}],"Ranger":[{"Key":0,"Value":true},{"Key":1,"Value":false}]},
                "heroSkill":{"Knight":0,"Ranger":0},
                "currentSkinDatas":{"Knight":0,"Ranger":0},
                "superHeroInHeroRoom":0,
                "keep":{"unrelated":19}
            }""".trimIndent().toByteArray())
            val originalPrefs = """<map>
                <string name="42_c0_unlock">true</string>
                <string name="42_c1_unlock">false</string>
                <int name="42_c0_skin0" value="1"/>
                <int name="42_c1_skin0" value="1"/>
                <int name="42_c1_skin1" value="-13"/>
                <int name="42_c_Knight_skill_0_unlock" value="1"/>
                <int name="42_c_Ranger_skill_0_unlock" value="1"/>
                <int name="42_c_Ranger_skill_1_unlock" value="0"/>
                <int name="42_c_Knight_skill" value="0"/>
                <int name="42_c_Ranger_skill" value="0"/>
                <int name="42_super_hero_in_hero_room" value="0"/>
                <int name="OpenRijTest_42" value="0"/>
                <int name="OpenNewtonJsonTest_42" value="0"/>
                <string name="fixture_keep">unchanged</string>
            </map>""".trimIndent().toByteArray()
            check(game.createNewFile())
            check(prefs.createNewFile())
            ownsPrefs = true
            prefs.writeBytes(originalPrefs)
            game.writeBytes(originalGame)
            val allowed = setOf(prefs.absolutePath, game.absolutePath)
            val storage = object : DeviceStorage {
                override fun stop(packageName: String, user: Int) {
                    require(packageName == context.packageName && user == android.os.Process.myUid() / 100000)
                    // Keep the instrumentation process alive; this synthetic fixture has no game writer.
                }
                override fun read(path: String): SaveFile {
                    require(path in allowed)
                    return root.read(path)
                }
                override fun replace(file: SaveFile, bytes: ByteArray) {
                    require(file.path in allowed)
                    root.replace(file, bytes)
                }
            }
            fun snapshot() = SaveSnapshot(context.packageName, user,
                storage.read(prefs.absolutePath), storage.read(game.absolutePath))
            val repository = SaveRepository(File(fixture, "journals"), storage)
            val original = snapshot()
            val unlockPlan = EditEngine.preview(original, "42", UnlockSelection(
                heroes = setOf(1), skins = setOf(SkinId(1, 1)), skillIds = setOf(SkillId(1, 1))), ItemSelection())
            repository.apply(original, unlockPlan)
            val unlocked = snapshot()
            val unlockedCatalog = UnlockEngine.catalog(unlocked.game!!.bytes, unlocked.prefs!!.bytes, "42")
            assertEquals(true, unlockedCatalog.heroes[1].unlocked)
            assertEquals(1, unlockedCatalog.heroes[1].skins[1])
            assertEquals(true, UnlockEngine.progression(unlocked.game.bytes, unlocked.prefs.bytes, "42")[1].skills[1])

            // Simulate choosing the unlocked content using the verified 8.7 save fields.
            val selectedGame = UnlockEngine.game(unlocked.game.bytes).let { before ->
                JsonObject(before + mapOf(
                    "currentSkinDatas" to JsonObject(before.getValue("currentSkinDatas").jsonObject + ("Ranger" to JsonPrimitive(1))),
                    "heroSkill" to JsonObject(before.getValue("heroSkill").jsonObject + ("Ranger" to JsonPrimitive(1))),
                    "superHeroInHeroRoom" to JsonPrimitive(1)))
            }
            var selectedPrefs = UnlockEngine.utf8(unlocked.prefs.bytes)
            val nodes = UnlockEngine.prefs(unlocked.prefs.bytes)
            listOf("42_c_Ranger_skill", "42_super_hero_in_hero_room").forEach {
                selectedPrefs = UnlockEngine.replaceXml(selectedPrefs, nodes.getValue(it), "1")
            }
            repository.apply(unlocked, SavePlan(mapOf(
                game.absolutePath to UnlockEngine.xor(selectedGame.toString().toByteArray()),
                prefs.absolutePath to selectedPrefs.toByteArray()), listOf("fixture: select unlocked content")))
            val beforeRelock = snapshot()
            val sources = RelockSources.load(beforeRelock, "42", repository, emptyList())
            assertEquals(1, sources.sourceCount)
            assertEquals(0, sources.skippedBackups)
            assertTrue(sources.originals.isNotEmpty())
            val scope = RelockScope(context.packageName, user)
            val inventory = RelockEngine.inspect(beforeRelock.game!!.bytes, beforeRelock.prefs!!.bytes, "42", scope, sources.originals)
            assertEquals(setOf(1), inventory.heroes)
            assertEquals(setOf(SkinId(1, 1)), inventory.skins)
            assertEquals(setOf(SkillId(1, 1)), inventory.skills)
            val plan = EditEngine.preview(beforeRelock, "42", UnlockSelection(
                heroes = setOf(0, 1), skins = setOf(SkinId(0, 0), SkinId(1, 0), SkinId(1, 1)),
                skillIds = setOf(SkillId(0, 0), SkillId(1, 0), SkillId(1, 1)), relock = true),
                ItemSelection(), WeaponSelection(), relockScope = scope, relockOriginals = sources.originals)
            assertEquals(allowed, plan.outputs.keys)
            val relockId = repository.apply(beforeRelock, plan)
            val locked = snapshot()
            assertArrayEquals(plan.outputs.getValue(game.absolutePath), locked.game!!.bytes)
            assertArrayEquals(plan.outputs.getValue(prefs.absolutePath), locked.prefs!!.bytes)
            val catalog = UnlockEngine.catalog(locked.game.bytes, locked.prefs.bytes, "42")
            assertEquals(true, catalog.heroes[0].unlocked)
            assertEquals(false, catalog.heroes[1].unlocked)
            assertEquals(1, catalog.heroes[0].skins[0])
            assertEquals(1, catalog.heroes[1].skins[0])
            assertEquals(-13, catalog.heroes[1].skins[1])
            val progress = UnlockEngine.progression(locked.game.bytes, locked.prefs.bytes, "42")
            assertEquals(true, progress[0].skills[0])
            assertEquals(true, progress[1].skills[0])
            assertEquals(false, progress[1].skills[1])
            val lockedGame = UnlockEngine.game(locked.game.bytes)
            assertEquals(0, lockedGame.getValue("currentSkinDatas").jsonObject.getValue("Ranger").jsonPrimitive.int)
            assertEquals(0, lockedGame.getValue("heroSkill").jsonObject.getValue("Ranger").jsonPrimitive.int)
            assertEquals(0, lockedGame.getValue("superHeroInHeroRoom").jsonPrimitive.int)
            val lockedPrefs = UnlockEngine.prefs(locked.prefs.bytes)
            assertEquals("0", lockedPrefs.getValue("42_c_Ranger_skill").getAttribute("value"))
            assertEquals("0", lockedPrefs.getValue("42_super_hero_in_hero_room").getAttribute("value"))
            assertEquals("unchanged", lockedPrefs.getValue("fixture_keep").textContent)
            assertEquals(UnlockEngine.game(originalGame).getValue("keep"), lockedGame.getValue("keep"))
            original.files.forEach { assertMetadataEquals(it, storage.read(it.path)) }
            assertTrue(repository.pending().isEmpty())

            repository.restore(relockId)
            val restored = snapshot()
            assertArrayEquals(beforeRelock.game.bytes, restored.game!!.bytes)
            assertArrayEquals(beforeRelock.prefs.bytes, restored.prefs!!.bytes)
            beforeRelock.files.forEach { assertMetadataEquals(it, storage.read(it.path)) }
            assertTrue(repository.pending().isEmpty())
        } finally {
            root.close()
            // Only these exact test-created paths are removed; no application preferences are read.
            require(prefs.canonicalFile.parentFile == prefsDir.canonicalFile && prefs.name == "relock-fixture-$token.xml")
            if (ownsPrefs) prefs.delete()
            require(fixture.canonicalFile.parentFile == context.filesDir.canonicalFile && fixture.name == "relock-fixture-$token")
            fixture.deleteRecursively()
        }
    }

    private fun assertMetadataEquals(expected: SaveFile, actual: SaveFile) {
        assertEquals(expected.owner, actual.owner)
        assertEquals(expected.mode, actual.mode)
        assertEquals(expected.context, actual.context)
    }
}
