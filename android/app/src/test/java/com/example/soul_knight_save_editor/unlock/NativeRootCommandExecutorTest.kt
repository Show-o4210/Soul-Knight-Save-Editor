package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class NativeRootCommandExecutorTest {
    private val pkg = "com.test.game"
    private val root = "/data/user/10/$pkg"
    private val su = "/system/bin/su"
    private fun ok(text: String = "") = RootCommandResult(0, text, 1)
    private fun absent(path: String = root) = RootCommandResult(1, "stat: '$path': No such file or directory", 1)
    private fun request(stage: String = "目标目录检查", writing: Boolean = false,
        packageName: String = pkg, sensitive: Boolean = false) = RootCommandRequest("fixture-$stage", stage = stage,
        packageName = packageName, user = 10, writing = writing, sensitiveOutput = sensitive, timeoutMillis = 12_000)
    private class Executor(val reply: (RootCommandRequest) -> RootCommandResult) : RootCommandExecutor {
        val calls = mutableListOf<RootCommandRequest>()
        var closes = 0
        var idle = true
        override fun execute(request: RootCommandRequest): RootCommandResult {
            calls += request
            return reply(request)
        }
        override fun close() { closes++ }
        override fun assertIdleForRecovery() {
            if (!idle) throw RootExecutionFailure(AccessFailureKind.WRITE_NOT_IDLE, 0, uncertainWrite = true)
        }
    }
    private inner class Fixture(
        val ordinaryReply: (RootCommandRequest) -> RootCommandResult = { probe -> when {
            probe.command == "id" -> ok("uid=0(root) gid=0(root)")
            probe.stage == "普通 su 挂载视图目录检查" -> absent("/data/user/10/${probe.packageName}")
            probe.stage == "识别 Magisk su" -> ok("30.7-custom:MAGISKSU\n")
            else -> ok("ordinary")
        } },
        val masterReply: (RootCommandRequest) -> RootCommandResult = { probe -> when {
            probe.command == "id" -> ok("uid=0(root) gid=0(root)")
            probe.stage == "Magisk 全局挂载视图目录检查" -> ok("directory\n")
            probe.command.startsWith("readlink") -> ok("/data/user/10/${probe.packageName}")
            else -> ok("master")
        } }
    ) {
        val shells = mutableListOf<List<String>>()
        val ordinary = Executor(ordinaryReply)
        val candidates = mutableListOf<Executor>()
        val executor = NativeRootCommandExecutor(su) { shell ->
            shells += shell
            if (shell == listOf(su)) ordinary else {
                assertEquals(listOf(su, "--mount-master"), shell)
                Executor(masterReply).also(candidates::add)
            }
        }
        fun identity(text: String = "uid=0(root) gid=0(root)") {
            assertEquals(text, executor.execute(RootCommandRequest("id", stage = "权限身份检查")).mergedOutput)
        }
    }

    @Test fun readableOrdinarySuNeverAsksForMagiskAndReusesVerifiedIdentity() {
        val fixture = Fixture(ordinaryReply = { probe -> when {
            probe.command == "id" -> ok("uid=0(root) gid=0(root)")
            probe.stage == "普通 su 挂载视图目录检查" -> ok("directory")
            else -> ok("ordinary")
        } })
        fixture.identity()
        repeat(2) { assertEquals("ordinary", fixture.executor.execute(request()).mergedOutput) }
        assertEquals(1, fixture.ordinary.calls.count { it.command == "id" })
        assertEquals(1, fixture.ordinary.calls.count { it.stage == "普通 su 挂载视图目录检查" })
        assertTrue(fixture.ordinary.calls.none { it.stage == "识别 Magisk su" })
        assertEquals(listOf(listOf(su)), fixture.shells)
        assertEquals("", fixture.executor.diagnosticSuffix)
    }

    @Test fun missingOrdinaryTargetUsesTheSameMagiskExecutableAndPinsGlobalView() {
        val fixture = Fixture()
        fixture.identity()
        assertEquals("master", fixture.executor.execute(request()).mergedOutput)
        val ordinaryCount = fixture.ordinary.calls.size
        assertEquals("master", fixture.executor.execute(request("读取存档内容", sensitive = true)).mergedOutput)
        assertEquals("master", fixture.executor.execute(request("事务文件替换", writing = true)).mergedOutput)
        assertEquals(ordinaryCount, fixture.ordinary.calls.size)
        assertEquals("'$su' -v", fixture.ordinary.calls.single { it.stage == "识别 Magisk su" }.command)
        assertEquals(1, fixture.candidates.size)
        assertEquals(1, fixture.candidates.single().calls.count { it.command == "id" })
        assertTrue(fixture.executor.diagnosticSuffix.contains("Magisk 全局挂载视图"))
        assertTrue(fixture.candidates.single().calls.filter { it.stage.startsWith("Magisk ") }
            .all { !it.writing && it.timeoutMillis!! <= 3_000 })
    }

    @Test fun directoryErrorsAndOldStatOptionsDoNotTriggerMagiskDetection() {
        for (probe in listOf(ok("regular file"), RootCommandResult(1, "stat: Unknown option L", 1),
            RootCommandResult(1, "stat: '$root': Permission denied", 1),
            RootCommandResult(1, "stat: '$root': Input/output error", 1),
            RootCommandResult(1, "stat: /another/path: No such file or directory", 1),
            RootCommandResult(2, "stat: '$root': No such file or directory", 1),
            RootCommandResult(1, "unknown failure", 1))) {
            val fixture = Fixture(ordinaryReply = { command -> when {
                command.command == "id" -> ok("uid=0(root) gid=0(root)")
                command.stage == "普通 su 挂载视图目录检查" -> probe
                else -> ok("ordinary")
            } })
            fixture.identity()
            assertEquals("ordinary", fixture.executor.execute(request()).mergedOutput)
            assertTrue(fixture.candidates.isEmpty())
            assertTrue(fixture.ordinary.calls.none { it.stage == "识别 Magisk su" })
        }
    }

    @Test fun onlyStrictKnownMagiskSuVersionEnablesGlobalParameter() {
        for (version in listOf("30.7-custom:MAGISKSU", "27.0:MAGISKSU", "30.7-custom:MAGISKSU\nextra",
            "Magisk", "MAGISK:R", "2.82:SUPERSU", "unknown:MAGISKSU", "", "\n:MAGISKSU")) {
            val fixture = Fixture(ordinaryReply = { command -> when {
                command.command == "id" -> ok("uid=0(root) gid=0(root)")
                command.stage == "普通 su 挂载视图目录检查" -> absent()
                command.stage == "识别 Magisk su" -> ok(version)
                else -> ok("ordinary")
            } })
            fixture.identity()
            fixture.executor.execute(request())
            assertEquals(version in setOf("30.7-custom:MAGISKSU", "27.0:MAGISKSU"), fixture.candidates.isNotEmpty())
        }
    }

    @Test fun nonRootIdentityNeverTriesAnotherSuParameter() {
        val fixture = Fixture(ordinaryReply = { command -> if (command.command == "id")
            ok("uid=2000(shell) gid=2000(shell) groups=0(root)") else ok("ordinary") })
        fixture.identity("uid=2000(shell) gid=2000(shell) groups=0(root)")
        fixture.executor.execute(request())
        assertTrue(fixture.candidates.isEmpty())
        assertTrue(fixture.ordinary.calls.none { it.stage == "识别 Magisk su" || it.stage == "普通 su 挂载视图目录检查" })
    }

    @Test fun timeoutOrUnknownRootIoNeverStartsAGlobalShell() {
        for (kind in listOf(AccessFailureKind.TIMEOUT, AccessFailureKind.IO, AccessFailureKind.SESSION_DISCONNECTED)) {
            val fixture = Fixture(ordinaryReply = { command -> when {
                command.command == "id" -> ok("uid=0(root) gid=0(root)")
                else -> throw RootExecutionFailure(kind, 100)
            } })
            fixture.identity()
            assertEquals(kind, assertThrows(RootExecutionFailure::class.java) { fixture.executor.execute(request()) }.kind)
            assertTrue(fixture.candidates.isEmpty())
        }
    }

    @Test fun cancelledOrUnresolvedSuEntryNeverLaunchesAGlobalCandidate() {
        val fixture = Fixture()
        assertThrows(DiscoveryCancelled::class.java) {
            fixture.executor.execute(request().copy(isCancelled = { true }))
        }
        assertTrue(fixture.ordinary.calls.isEmpty())
        assertTrue(fixture.candidates.isEmpty())
        val shells = mutableListOf<List<String>>()
        val calls = mutableListOf<RootCommandRequest>()
        val unresolved = NativeRootCommandExecutor("su") { shell ->
            shells += shell
            Executor { command ->
                calls += command
                when {
                    command.command == "id" -> ok("uid=0(root) gid=0(root)")
                    command.stage == "普通 su 挂载视图目录检查" -> absent()
                    else -> ok("ordinary")
                }
            }
        }
        assertEquals("ordinary", unresolved.execute(request()).mergedOutput)
        assertEquals(listOf(listOf("su")), shells)
        assertTrue(calls.none { it.stage == "识别 Magisk su" })
    }

    @Test fun nonexistentGlobalTargetClosesCandidateAndKeepsOrdinaryResult() {
        val fixture = Fixture(masterReply = { command -> if (command.command == "id")
            ok("uid=0(root) gid=0(root)") else absent() })
        fixture.identity()
        assertEquals("ordinary", fixture.executor.execute(request()).mergedOutput)
        assertEquals(1, fixture.candidates.single().closes)
        assertEquals("", fixture.executor.diagnosticSuffix)
    }

    @Test fun unprivilegedGlobalCandidateAndEscapingCanonicalPathAreRejected() {
        val denied = Fixture(masterReply = { ok("uid=2000(shell) gid=2000(shell)") })
        denied.identity()
        assertEquals("ordinary", denied.executor.execute(request()).mergedOutput)
        assertEquals(1, denied.candidates.single().closes)
        val escape = Fixture(masterReply = { command -> when {
            command.command == "id" -> ok("uid=0(root) gid=0(root)")
            command.command.startsWith("readlink") -> ok("/data/user/0/com.other.game")
            else -> ok("directory")
        } })
        escape.identity()
        assertThrows(IllegalArgumentException::class.java) { escape.executor.execute(request()) }
        assertEquals(1, escape.candidates.single().closes)
        assertTrue(escape.candidates.single().calls.none { it.command.startsWith("fixture-") })
    }

    @Test fun globalProbeTimeoutClosesCandidateWithoutReplayingOriginalRequest() {
        val fixture = Fixture(masterReply = { throw RootExecutionFailure(AccessFailureKind.TIMEOUT, 3_000) })
        fixture.identity()
        assertEquals(AccessFailureKind.TIMEOUT,
            assertThrows(RootExecutionFailure::class.java) { fixture.executor.execute(request()) }.kind)
        assertEquals(1, fixture.candidates.single().closes)
        assertTrue(fixture.ordinary.calls.none { it.command.startsWith("fixture-") })
    }

    @Test fun failedWriteDoesNotSwitchOrReplayAndRecoveryUsesSelectedExecutor() {
        val fixture = Fixture(masterReply = { command -> when {
            command.command == "id" -> ok("uid=0(root) gid=0(root)")
            command.stage == "Magisk 全局挂载视图目录检查" -> ok("directory")
            command.command.startsWith("readlink") -> ok(root)
            command.writing -> throw RootExecutionFailure(AccessFailureKind.TIMEOUT, 1_000, uncertainWrite = true)
            else -> ok("master")
        } })
        fixture.identity()
        fixture.executor.execute(request())
        val ordinaryCount = fixture.ordinary.calls.size
        assertTrue(assertThrows(RootExecutionFailure::class.java) {
            fixture.executor.execute(request("事务文件替换", writing = true))
        }.uncertainWrite)
        assertEquals(ordinaryCount, fixture.ordinary.calls.size)
        assertEquals(1, fixture.candidates.single().calls.count { it.writing })
        fixture.candidates.single().idle = false
        assertEquals(AccessFailureKind.WRITE_NOT_IDLE,
            assertThrows(RootExecutionFailure::class.java) { fixture.executor.assertIdleForRecovery() }.kind)
        fixture.executor.close()
        assertThrows(RootExecutionFailure::class.java) { fixture.executor.execute(RootCommandRequest("id", stage = "权限身份检查")) }
    }

    @Test fun sourceReadPinsOrdinaryViewBeforeAnotherPackageCanRequestFallback() {
        for (source in listOf(request("读取存档内容", sensitive = true), request("检查 XML 搜索特征"),
            request("读取完整性校验"), request("事务文件替换", writing = true))) {
            val fixture = Fixture(ordinaryReply = { command -> when {
                command.command == "id" -> ok("uid=0(root) gid=0(root)")
                command.stage == "普通 su 挂载视图目录检查" && command.packageName == pkg -> ok("directory")
                command.stage == "普通 su 挂载视图目录检查" -> absent("/data/user/10/${command.packageName}")
                else -> ok("ordinary")
            } })
            fixture.identity()
            fixture.executor.execute(source)
            fixture.executor.execute(request(packageName = "com.other.game"))
            assertTrue(fixture.candidates.isEmpty())
            assertEquals(1, fixture.ordinary.calls.count { it.stage == "普通 su 挂载视图目录检查" })
        }
    }

    @Test fun discoveryMayChangeViewBeforeAnySourceReadAndCloseRestartsOrdinaryFirst() {
        val fixture = Fixture(masterReply = { command -> when {
            command.command == "id" -> ok("uid=0(root) gid=0(root)")
            command.packageName == "com.unrelated.app" -> absent("/data/user/10/com.unrelated.app")
            command.stage == "Magisk 全局挂载视图目录检查" -> ok("directory")
            command.command.startsWith("readlink") -> ok(root)
            else -> ok("master")
        } })
        fixture.identity()
        assertEquals("ordinary", fixture.executor.execute(request(packageName = "com.unrelated.app")).mergedOutput)
        assertEquals("master", fixture.executor.execute(request()).mergedOutput)
        assertEquals(1, fixture.candidates.first().closes)
        fixture.executor.close()
        assertEquals("", fixture.executor.diagnosticSuffix)
        fixture.identity()
        fixture.executor.execute(request())
        assertEquals(2, fixture.ordinary.calls.count { it.stage == "识别 Magisk su" })
        assertEquals(3, fixture.candidates.size)
    }

    @Test fun rootStoragePreservesOldStatFallbackAndOrdinaryDiagnostics() {
        val fixture = Fixture(ordinaryReply = { command -> when {
            command.command == "id" -> ok("uid=0(root) gid=0(root)")
            command.command.startsWith("LC_ALL=C stat -L") -> RootCommandResult(1, "stat: Unknown option L", 1)
            command.command.startsWith("LC_ALL=C stat -c") -> ok("directory")
            command.command.startsWith("readlink") -> ok(root)
            else -> error("Unexpected command ${command.stage}")
        } })
        val storage = RootStorage(fixture.executor)
        assertTrue(storage.exists(root))
        assertEquals("uid=0 gid=0", storage.latestDiagnostic!!.identity)
        assertTrue(storage.latestDiagnostic!!.stage.contains("旧版 stat 不支持 -L"))
        assertTrue(fixture.candidates.isEmpty())
        assertTrue(fixture.ordinary.calls.none { it.stage == "识别 Magisk su" })
    }

    @Test fun rootStorageRetainsLegacyHashSelfTestWithoutTryingMountFallbackOrWriting() {
        val path = "$root/files/game.data"
        val fixture = Fixture(ordinaryReply = { command -> when {
            command.command == "id" -> ok("uid=0(root) gid=0(root)")
            command.stage == "普通 su 挂载视图目录检查" -> RootCommandResult(1, "stat: Unknown option L", 1)
            command.command.startsWith("readlink") -> ok(path)
            command.command == "sha256sum /dev/null" -> RootCommandResult(127, "sha256sum: not found", 1)
            command.command.endsWith(" --self-test") -> ok("0".repeat(64))
            else -> error("Unexpected command ${command.stage}")
        } })
        val storage = RootStorage(fixture.executor, "/data/app/com.test.editor/base.apk")
        assertThrows(IllegalArgumentException::class.java) {
            storage.replace(SaveFile(path, byteArrayOf(1), "10010:10010", "600", ""), byteArrayOf(2))
        }
        assertEquals(1, fixture.ordinary.calls.count { it.command.endsWith(" --self-test") })
        assertTrue(fixture.ordinary.calls.none { it.writing || it.stage == "识别 Magisk su" })
        assertTrue(fixture.candidates.isEmpty())
    }

    @Test fun directReadAndReplaceSelectGlobalBeforeTheirFirstFileCommand() {
        for (replace in listOf(false, true)) {
            val path = "$root/files/game.data"
            val fixture = Fixture(masterReply = { command -> when {
                command.command == "id" -> ok("uid=0(root) gid=0(root)")
                command.stage == "Magisk 全局挂载视图目录检查" -> ok("directory")
                command.stage == "Magisk 全局视图路径检查" -> ok(root)
                command.command.startsWith("readlink") -> ok(path)
                else -> RootCommandResult(1, "Permission denied", 1)
            } })
            val storage = RootStorage(fixture.executor)
            val error = assertThrows(AccessFailure::class.java) {
                if (replace) storage.replace(SaveFile(path, byteArrayOf(1), "10010:10010", "600", ""), byteArrayOf(2))
                else storage.read(path)
            }
            assertEquals(AccessFailureKind.TARGET_ACCESS_DENIED, error.kind)
            assertTrue(error.diagnostic!!.identity.contains("Magisk 全局挂载视图"))
            assertTrue(fixture.ordinary.calls.none { it.stage == "规范化路径检查" || it.stage == "读取文件元数据" || it.writing })
            assertTrue(fixture.candidates.single().calls.any { it.stage == "规范化路径检查" })
        }
    }
}
