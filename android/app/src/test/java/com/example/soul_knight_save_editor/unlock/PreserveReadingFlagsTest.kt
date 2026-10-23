package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PreserveReadingFlagsTest {
    private fun prefs(body: String) = """<map>
        <string name="AccountSaveSettingsIds">%7B%2242%22%3A%22700%22%7D</string>
        <int name="OpenRijTest_700" value="1"/><int name="OpenNewtonJsonTest_700" value="1"/>
        <int name="OpenRijTest_900" value="1"/><int name="OpenNewtonJsonTest_900" value="1"/>
        $body
    </map>""".trimIndent().toByteArray()
    private fun assertFlags(bytes: ByteArray, selected: String) {
        val nodes = UnlockEngine.prefs(bytes)
        assertEquals(selected, nodes.getValue("OpenRijTest_700").getAttribute("value"))
        assertEquals(selected, nodes.getValue("OpenNewtonJsonTest_700").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenRijTest_900").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenNewtonJsonTest_900").getAttribute("value"))
    }

    @Test fun unlockCanPreserveTheModernRouteWithoutLosingCharacterChanges() {
        val bytes = prefs("""<string name="42_c0_unlock">false</string><int name="42_c0_skin1" value="-13"/>""")
        val selection = UnlockSelection(heroes = setOf(0), skins = setOf(SkinId(0, 1)))
        val result = UnlockEngine.unlock(null, bytes, "42", selection, preserveReadingFlags = true)
        assertFlags(result.prefs, "1")
        val hero = UnlockEngine.catalog(null, result.prefs, "42").heroes.single()
        assertEquals(true, hero.unlocked)
        assertEquals(1, hero.skins[1])
        assertFalse(result.changes.any { "切换为本地读取" in it })
        assertFlags(UnlockEngine.unlock(null, bytes, "42", selection).prefs, "0")
    }

    @Test fun relockCanPreserveTheModernRouteWithoutDiscardingLocksOrCurrentChoiceChanges() {
        val scope = RelockScope("com.test.game", 0)
        val bytes = prefs("""<string name="42_c0_unlock">true</string><string name="42_c1_unlock">true</string>
            <int name="42_c0_skin0" value="1"/><int name="42_c1_skin0" value="1"/><int name="42_c1_skin1" value="1"/>""")
        val original = RelockOriginal(scope.packageName, scope.user, "42", prefs("""<string name="42_c0_unlock">true</string>
            <string name="42_c1_unlock">false</string><int name="42_c0_skin0" value="1"/>
            <int name="42_c1_skin0" value="1"/><int name="42_c1_skin1" value="-13"/>"""))
        val game = UnlockEngine.xor("""{"heroUnlock":{"Knight":true,"Ranger":true},
            "skinLock":{"Knight":[{"Key":0,"Value":1}],"Ranger":[{"Key":0,"Value":1},{"Key":1,"Value":1}]},
            "currentSkinDatas":{"Ranger":1}}""".trimIndent().toByteArray())
        val selection = UnlockSelection(heroes = setOf(1), skins = setOf(SkinId(1, 1)), relock = true)
        val result = RelockEngine.patch(game, bytes, "42", scope, listOf(original), selection, preserveReadingFlags = true)
        assertFlags(result.prefs, "1")
        val hero = UnlockEngine.catalog(result.game, result.prefs, "42").heroes[1]
        assertEquals(false, hero.unlocked)
        assertEquals(-13, hero.skins[1])
        assertEquals("0", UnlockEngine.game(result.game!!).getValue("currentSkinDatas").jsonObject.getValue("Ranger").jsonPrimitive.content)
        assertFalse(result.changes.any { "切换为本地读取" in it })
        assertFlags(RelockEngine.patch(game, bytes, "42", scope, listOf(original), selection).prefs, "0")
    }

    @Test fun petUnlockCanPreserveTheModernRouteAndStillChangeTheSelectedPet() {
        val bytes = prefs("""<string name="42_p0_unlock">false</string>""")
        val result = PetEngine.unlock(null, bytes, "42", setOf("Cat"), preserveReadingFlags = true)
        assertFlags(result.prefs, "1")
        assertTrue(PetEngine.inspect(null, result.prefs, "42").single().unlocked)
        assertFalse(result.changes.any { "切换为本地读取" in it })
        assertFlags(PetEngine.unlock(null, bytes, "42", setOf("Cat")).prefs, "0")
    }
}
