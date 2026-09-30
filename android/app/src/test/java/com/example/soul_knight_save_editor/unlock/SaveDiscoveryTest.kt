package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SaveDiscoveryTest {
    @Test fun installedPackagesAreValidatedDeduplicatedAndPrioritizedWithoutMergingVersions() {
        val output = """package:com.vendor.channel
            |package:com.ChillyRoom.DungeonShooter
            |package:com.vendor.channel
            |package:com.vendor.another
            |package:com.assistant.app
            |package:bad;command
            |package:../../escape
            |error: no package
        """.trimMargin()
        val result = SaveDiscovery.packages(output, "com.vendor.channel", "com.assistant.app")
        assertEquals(listOf("com.vendor.channel", "com.ChillyRoom.DungeonShooter", "com.vendor.another"), result)
    }
    @Test fun singleGenericXmlKeyDoesNotClaimAnApp() {
        assertFalse(SaveDiscovery.xml("""<map><int name="c0_unlock" value="1"/></map>""".toByteArray()))
        assertFalse(SaveDiscovery.xml("""<map><string name="unrelated">c0_unlock c0_skin1 c0_skin2</string></map>""".toByteArray()))
    }
    @Test fun severalCoherentCharacterKeysIdentifyXmlRegardlessOfFilename() {
        assertTrue(SaveDiscovery.xml("""<map><int name="42_c0_unlock" value="1"/><int name="42_c0_skin1" value="1"/><int name="42_c0_skin2" value="0"/></map>""".toByteArray()))
        assertTrue(SaveDiscovery.xml("""<map><int name="c0_unlock" value="1"/><int name="c0_skin1" value="1"/><int name="OpenRijTest" value="0"/></map>""".toByteArray()))
    }
    @Test fun unrelatedAccountsAndOtherAccountsFlagsAreNotCombined() {
        assertFalse(SaveDiscovery.xml("""<map><int name="42_c0_unlock" value="1"/><int name="99_c0_skin1" value="1"/><int name="99_c0_skin2" value="1"/></map>""".toByteArray()))
        assertFalse(SaveDiscovery.xml("""<map><int name="42_c0_unlock" value="1"/><int name="42_c0_skin1" value="1"/><int name="OpenRijTest_99" value="0"/></map>""".toByteArray()))
    }
    @Test fun malformedXmlIsNotAValidCandidate() {
        assertFalse(SaveDiscovery.xml("""<!DOCTYPE map><map/>""".toByteArray()))
        assertFalse(SaveDiscovery.xml("not xml".toByteArray()))
    }
    @Test fun standaloneItemDiscoveryDoesNotDependOnXmlOrSupportedEditingVersion() {
        val root = buildJsonObject {
            put("AppVersion", 90000)
            for (field in listOf("materials", "seeds", "blueprints", "tokenTickets")) put(field, JsonObject(emptyMap()))
        }
        val bytes = ItemCodec.encode(root)
        assertTrue(SaveDiscovery.item(bytes))
        assertThrows(IllegalArgumentException::class.java) { ItemEngine.inspect(bytes) }
        assertFalse(SaveDiscovery.item(ItemCodec.encode(JsonObject(root - "seeds"))))
    }
    @Test fun gameStructureAndItemCipherMustActuallyDecode() {
        val game = UnlockEngine.xor("""{"heroUnlock":{"Knight":true},"skinLock":{"Knight":[]}}""".toByteArray())
        assertTrue(SaveDiscovery.game(game))
        assertFalse(SaveDiscovery.game("game.data".toByteArray()))
        assertFalse(SaveDiscovery.item("item_data_42_.data".toByteArray()))
    }
}
