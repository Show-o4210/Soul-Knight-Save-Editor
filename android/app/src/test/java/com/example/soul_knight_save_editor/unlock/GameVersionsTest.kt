package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class GameVersionsTest {
    private fun channel(pkg: String = "com.liangwu.yuanqiqishi.vivo", version: String = "8.7.0") =
        GameCandidate(pkg, "元气骑士 vivo", version, listOf("XML 角色／皮肤特征"))

    @Test fun originalIsAlwaysAvailableAndLegacySelectionIsKeptAsAnAdditionalVersion() {
        assertEquals(listOf(GameVersions.ORIGINAL), GameVersions.restore(null).map { it.packageName })
        val games = GameVersions.restore(null, channel().packageName)
        assertEquals(listOf(GameVersions.ORIGINAL, channel().packageName), games.map { it.packageName })
    }

    @Test fun multipleChannelsAndTheirDisplayMetadataSurviveRestart() {
        val games = GameVersions.add(GameVersions.add(GameVersions.restore(null), channel()),
            channel("com.other.soulknight", "8.6.0"))
        val restored = GameVersions.restore(GameVersions.encode(games), channel().packageName)
        assertEquals(games.map { Triple(it.packageName, it.label, it.version) },
            restored.map { Triple(it.packageName, it.label, it.version) })
        assertEquals(3, restored.size)
    }

    @Test fun rediscoveryUpdatesOnePackageWithoutLosingOtherChannels() {
        val initial = GameVersions.add(GameVersions.restore(null), channel(version = "8.6.0"))
        val updated = GameVersions.add(initial, channel())
        assertEquals(2, updated.size)
        assertEquals(GameVersions.ORIGINAL, updated.first().packageName)
        assertEquals("8.7.0", updated.last().version)
        val selectedAgain = GameVersions.add(updated, GameCandidate(channel().packageName, evidence = emptyList()))
        assertEquals("元气骑士 vivo", selectedAgain.last().label)
        assertEquals("8.7.0", selectedAgain.last().version)
    }

    @Test fun corruptAndInvalidEntriesCannotRemoveOriginalOrInjectPackageNames() {
        assertEquals(GameVersions.ORIGINAL, GameVersions.restore("broken", "../../escape").single().packageName)
        val restored = GameVersions.restore("""[null,{"package":"com.valid.game","label":"渠道版"},
            {"package":"bad;command"},{"package":"com.valid.game","version":"8.7.0"}]""")
        assertEquals(listOf(GameVersions.ORIGINAL, "com.valid.game"), restored.map { it.packageName })
        assertEquals("渠道版", restored.last().label)
        assertThrows(IllegalArgumentException::class.java) {
            GameVersions.add(restored, GameCandidate("com.game;echo", evidence = emptyList()))
        }
    }

    @Test fun changingTargetsKeepsTheLibraryButClearsLoadedContentAndDrafts() {
        val games = GameVersions.add(GameVersions.restore(null), channel())
        val state = AssistantState(knownGames = games, mode = AssistantMode.FREE,
            freeSelected = setOf("/data/user/0/${GameVersions.ORIGINAL}/files/game.data"),
            choices = ModeChoices(quickRoles = true), candidates = listOf(channel()))
        val selected = state.selectPackage(channel().packageName)
        assertEquals(games, selected.knownGames)
        assertEquals(listOf(channel()), selected.candidates)
        assertTrue(selected.freeSelected.isEmpty())
        assertEquals(ModeChoices(), selected.choices)
        assertNull(selected.pinnedTarget)
        assertEquals(WorkspaceTabs.saves.id, selected.workspaceTab)
    }
}
