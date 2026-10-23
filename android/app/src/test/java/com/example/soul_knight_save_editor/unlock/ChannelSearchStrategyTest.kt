package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class ChannelSearchStrategyTest {
    private val packages = listOf("com.ChillyRoom.DungeonShooter", "com.liangwu.yuanqiqishi.vivo",
        "com.knight.union.bili", "com.other.channel")
    private val prefs = """<map><int name="42_c0_unlock" value="0"/><int name="77_c0_unlock" value="0"/></map>""".toByteArray()

    private class Access(val values: Map<String, ByteArray>, val strategy: ChannelSearchStrategy) : SaveScanAccess {
        var stopped = 0
        val reads = mutableListOf<String>()
        override fun searchStrategy(packageName: String) = strategy
        override fun stop(packageName: String, user: Int) { stopped++ }
        override fun exists(directory: String) = true
        override fun list(root: String, source: SaveSource) = values.keys.filter {
            val name = it.substringAfterLast('/')
            when (source) {
                SaveSource.GAME -> name == "game.data"
                SaveSource.ITEM -> name.startsWith("item_data")
                SaveSource.STATISTIC -> name.startsWith("statistic")
                SaveSource.PREFS -> name.endsWith(".xml")
                SaveSource.WEAPON_SKINS -> name.startsWith("weapon_evolution_data")
            }
        }
        override fun read(path: String) = SaveFile(path, values.getValue(path), "1000:1000", "600", "").also { reads += path }
    }

    private fun variant(original: ChannelSearchStrategy, item: SaveSearchQuery = original.source(SaveSource.ITEM),
        discovery: SaveSearchQuery = original.discoveryData, backup: SaveSearchQuery = original.backupData) =
        ChannelSearchStrategy("bili-fixture", original.sources + (SaveSource.ITEM to item), original.discoveryPrefs, discovery, backup)

    @Test fun packageRoutingIsExactAndNeverTreatsAChannelSuffixAsTheKnownPackage() {
        assertSame(OriginalChannelSearch.strategy, ChannelSearchStrategies.forPackage(packages[0]))
        assertSame(VivoChannelSearch.strategy, ChannelSearchStrategies.forPackage(packages[1]))
        assertSame(BiliChannelSearch.strategy, ChannelSearchStrategies.forPackage(packages[2]))
        assertSame(GenericChannelSearch.strategy, ChannelSearchStrategies.forPackage(packages[3]))
        for (pkg in listOf("com.knight.union.bili.extra", "com.liangwu.yuanqiqishi.vivo.extra", "com.chillyroom.dungeonshooter"))
            assertSame(GenericChannelSearch.strategy, ChannelSearchStrategies.forPackage(pkg))
        assertThrows(IllegalArgumentException::class.java) { ChannelSearchStrategies.forPackage("com.test;echo") }
        assertEquals(listOf("GAME", "ITEM", "STATISTIC", "PREFS", "WEAPON_SKINS"), SaveSource.entries.map { it.name })
    }

    @Test fun everyChannelRetainsThePublishedSearchCommandsAndOwnsItsTables() {
        val strategies = packages.map(ChannelSearchStrategies::forPackage)
        val root = "/data/user/10/com.test.game"
        for (strategy in strategies) {
            assertEquals("find '$root/files' -maxdepth 2 -type f -name 'item_data*.data*'", strategy.source(SaveSource.ITEM).findCommand(root))
            assertEquals("find '$root/shared_prefs' -maxdepth 1 -type f -name '*.xml'", strategy.discoveryPrefs.findCommand(root))
            assertEquals("find '$root/files' -maxdepth 2 -type f \\( -name 'game.data' -o -name 'item_data*.data*' \\)", strategy.discoveryData.findCommand(root))
            assertEquals("find '$root/files' -maxdepth 2 -type f \\( -name '*.data' -o -name '*.data.new' -o -name '*.data_temp' -o -name '*.data.new_temp' \\)", strategy.backupData.findCommand(root))
        }
        for (i in strategies.indices) for (j in 0 until i) {
            assertNotSame(strategies[i].sources, strategies[j].sources)
            for (source in SaveSource.entries) assertNotSame(strategies[i].source(source), strategies[j].source(source))
            assertNotSame(strategies[i].discoveryData, strategies[j].discoveryData)
            assertNotSame(strategies[i].backupData, strategies[j].backupData)
        }
    }

    @Test fun queryAndStrategyDefensivelyCopyInputsAndExposeUnmodifiableTables() {
        val globs = mutableListOf("item_data*.data*")
        val query = SaveSearchQuery("files", 2, globs)
        globs[0] = "different.data"
        assertTrue(query.matchesFilename("item_data_42_.data.new"))
        assertFalse(query.matchesFilename("different.data"))
        assertThrows(UnsupportedOperationException::class.java) { (query.globs as MutableList<String>).clear() }
        val sources = BiliChannelSearch.strategy.sources.toMutableMap()
        val strategy = ChannelSearchStrategy("copy", sources, BiliChannelSearch.strategy.discoveryPrefs,
            BiliChannelSearch.strategy.discoveryData, BiliChannelSearch.strategy.backupData)
        sources.clear()
        assertEquals(SaveSource.entries.size, strategy.sources.size)
        assertThrows(UnsupportedOperationException::class.java) { (strategy.sources as MutableMap<SaveSource, SaveSearchQuery>).clear() }
    }

    @Test fun queryBuilderRejectsShellSyntaxAndKeepsDirectoryBoundaries() {
        for (glob in listOf("*.data;echo", "'*.data'", "../*.data", "*.data\n", "$(id)"))
            assertThrows(IllegalArgumentException::class.java) { SaveSearchQuery("files", 2, listOf(glob)) }
        for (directory in listOf("../files", "files/a", "shared_prefs;echo"))
            assertThrows(IllegalArgumentException::class.java) { SaveSearchQuery(directory, 1, listOf("*.data")) }
        assertThrows(IllegalArgumentException::class.java) { SaveSearchQuery("files", 3, listOf("*.data")) }
        val query = OriginalChannelSearch.strategy.source(SaveSource.ITEM)
        val root = "/data/user/0/com.test.game"
        assertThrows(IllegalArgumentException::class.java) { query.findCommand("$root;echo") }
        assertThrows(IllegalArgumentException::class.java) { query.findCommand("/data/user/0/com.test.game/../other") }
        assertTrue(query.containsPath(root, "$root/files/sub/item_data_42_.data.new"))
        for (path in listOf("$root/files/a/b/item_data_42_.data", "$root/files/../item_data_42_.data",
            "/data/user/0/com.other.game/files/item_data_42_.data", "$root/files//item_data_42_.data"))
            assertFalse(query.containsPath(root, path))
    }

    @Test fun allChannelsScanAndRefreshBothFormatsWhileNativeRecoveryCandidatesRemainBackupOnly() {
        for (pkg in packages) {
            val root = SaveLayout.root(pkg, 0)
            val values = linkedMapOf("$root/shared_prefs/playerprefs.xml" to prefs)
            for (name in listOf("item_data_42_", "statistic_42_", "weapon_evolution_data_42_"))
                for (suffix in listOf(".data", ".data.new", ".data_temp", ".data.new_temp"))
                    values["$root/files/sub/$name$suffix"] = byteArrayOf(1, 2)
            val strategy = ChannelSearchStrategies.forPackage(pkg)
            val access = Access(values, strategy)
            val snapshot = SaveScanner.scan(access, pkg, 0)
            assertEquals(2, snapshot.items.size)
            assertEquals(2, snapshot.statistics.size)
            assertEquals(2, snapshot.weaponSkins.size)
            assertFalse(access.reads.any { it.endsWith("_temp") })
            assertEquals(snapshot.files.map { it.path }, SaveScanner.refresh(access, PinnedSaveTarget.from(snapshot, "42")).files.map { it.path })
            for (path in values.keys.filter { it.endsWith("_temp") }) {
                assertTrue(strategy.backupData.matchesFilename(path.substringAfterLast('/')))
                assertTrue(SaveLayout.backupDataPath.matches(path))
                assertFalse(SaveLayout.editablePath.matches(path))
            }
        }
    }

    @Test fun aBiliOnlyQueryChangeControlsScanAndRefreshWithoutChangingOtherStrategies() {
        val originalQueries = packages.associateWith { pkg -> ChannelSearchStrategies.forPackage(pkg).sources.mapValues { it.value.globs } }
        val pkg = "com.knight.union.bili"
        val root = SaveLayout.root(pkg, 0)
        val values = mapOf("$root/shared_prefs/playerprefs.xml" to prefs,
            "$root/files/item_data_42_.data" to byteArrayOf(1), "$root/files/item_data_77_.data.new" to byteArrayOf(2))
        val changed = variant(BiliChannelSearch.strategy, SaveSearchQuery("files", 2, listOf("item_data_77_.data*")))
        val access = Access(values, changed)
        val snapshot = SaveScanner.scan(access, pkg, 0)
        assertEquals(listOf("$root/files/item_data_77_.data.new"), snapshot.items.map { it.path })
        assertEquals(snapshot.items, SaveScanner.refresh(access, PinnedSaveTarget.from(snapshot, "77")).items)
        val oldSnapshot = SaveScanner.scan(Access(values, BiliChannelSearch.strategy), pkg, 0)
        val oldPin = PinnedSaveTarget.from(oldSnapshot, "42")
        val beforeStops = access.stopped
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.refresh(access, oldPin) }
        assertEquals(beforeStops, access.stopped)
        assertEquals(originalQueries, packages.associateWith { channel -> ChannelSearchStrategies.forPackage(channel).sources.mapValues { it.value.globs } })
        for (channel in packages.filter { it != pkg }) {
            val channelRoot = SaveLayout.root(channel, 0)
            val channelValues = values.mapKeys { (path, _) -> path.replace(root, channelRoot) }
            assertEquals(2, SaveScanner.scan(Access(channelValues, ChannelSearchStrategies.forPackage(channel)), channel, 0).items.size)
        }
    }

    @Test fun scannerAlsoEnforcesTheSelectedChannelsDepthWhenATransportReturnsExtraPaths() {
        val pkg = "com.knight.union.bili"
        val root = SaveLayout.root(pkg, 0)
        val values = mapOf("$root/files/sub/item_data_42_.data" to byteArrayOf(1))
        val depthOne = variant(BiliChannelSearch.strategy, SaveSearchQuery("files", 1, listOf("item_data*.data*")))
        val access = Access(values, depthOne)
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.scan(access, pkg, 0) }
        assertTrue(access.reads.isEmpty())
    }

    @Test fun listDiscoveryAndBackupUseTheSelectedStrategyForBothRootBackendsWithoutExtraFindCalls() {
        val pkg = "com.knight.union.bili"
        val root = SaveLayout.root(pkg, 10)
        val changed = variant(BiliChannelSearch.strategy, SaveSearchQuery("files", 1, listOf("item_data_77_.data*")),
            SaveSearchQuery("files", 1, listOf("item_data_77_.data*")),
            SaveSearchQuery("files", 1, listOf("*.data", "*.data.new")))
        for (backend in AccessBackend.entries) {
            val calls = mutableListOf<RootCommandRequest>()
            val executor = object : RootCommandExecutor {
                override fun execute(request: RootCommandRequest): RootCommandResult {
                    calls += request
                    return when {
                        request.command == "id" -> RootCommandResult(0, "uid=0(root) gid=0(root)", 1)
                        request.command.startsWith("LC_ALL=C stat -L ") -> RootCommandResult(0, "directory", 1)
                        request.command.startsWith("readlink -f ") -> RootCommandResult(0, request.command.substringAfter("readlink -f '").removeSuffix("'"), 1)
                        request.command.startsWith("find ") -> RootCommandResult(0, "", 1)
                        else -> error("Unexpected search command: ${request.stage}")
                    }
                }
                override fun assertIdleForRecovery() = Unit
                override fun close() = Unit
            }
            val storage = ShellSaveAccess(executor, backend, strategyForPackage = { channel ->
                if (channel == pkg) changed else ChannelSearchStrategies.forPackage(channel)
            })
            assertSame(changed, storage.searchStrategy(pkg))
            assertTrue(storage.list(root, SaveSource.ITEM).isEmpty())
            assertTrue(storage.probePaths(pkg, 10).isEmpty())
            assertTrue(storage.backupPaths(pkg, 10).isEmpty())
            assertEquals(listOf(changed.source(SaveSource.ITEM).findCommand(root), changed.discoveryPrefs.findCommand(root),
                changed.discoveryData.findCommand(root), changed.backupData.findCommand(root)),
                calls.filter { it.command.startsWith("find ") }.map { it.command })
            assertTrue(calls.none { it.writing })
        }
    }
}
