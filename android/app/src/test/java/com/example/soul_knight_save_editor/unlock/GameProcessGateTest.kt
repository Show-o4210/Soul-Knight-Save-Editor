package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class GameProcessGateTest {
    private val pkg = "com.test.game"
    private class Executor(private val reply: (RootCommandRequest) -> RootCommandResult) : RootCommandExecutor {
        val calls = mutableListOf<RootCommandRequest>()
        override fun execute(request: RootCommandRequest): RootCommandResult {
            calls += request
            return if (request.command == "id") RootCommandResult(0, "uid=0(root) gid=0(root)", 1) else reply(request)
        }
        override fun close() {}
    }

    @Test fun bothBackendsRefuseRunningGamesWithOnlyReadOnlyBoundedCommands() {
        for (backend in AccessBackend.entries) {
            val executor = Executor { RootCommandResult(0,
                "UID PID ARGS\n0 1 init\n1010123 300 $pkg:worker\n10099 301 com.private.unrelated\n", 3) }
            val access = ShellSaveAccess(executor, backend)
            val error = assertThrows(AccessFailure::class.java) { access.stop(pkg, 10) }
            assertEquals(AccessFailureKind.GAME_RUNNING, error.kind)
            assertTrue(error.message!!.contains("保存并彻底退出"))
            assertFalse(error.uncertainWrite)
            assertEquals(listOf("id", GameProcessProbe.COMMAND), executor.calls.map { it.command })
            val probe = executor.calls.last()
            assertTrue(probe.command.contains("-w"))
            assertTrue(probe.sensitiveOutput)
            assertFalse(probe.writing)
            assertEquals(GameProcessProbe.TIMEOUT_MILLIS, probe.timeoutMillis)
            assertFalse(error.diagnostic!!.render().contains("com.private.unrelated"))
            assertFalse(error.diagnostic.render().contains("1010123"))
        }
    }

    @Test fun aClosedTargetSucceedsAndDoesNotForceStopAnyProcess() {
        val executor = Executor { RootCommandResult(0, "UID PID ARGS\n0 1 init\n1010123 300 $pkg\n", 1) }
        val access = RootStorage(executor)
        access.stop(pkg, 0)
        access.stop(pkg, 0)
        assertEquals(1, executor.calls.count { it.command == "id" })
        assertEquals(2, executor.calls.count { it.command == GameProcessProbe.COMMAND })
        assertFalse(executor.calls.any { "force-stop" in it.command || it.writing })
    }

    @Test fun aFailedOrMalformedProcessProbeNeverProceeds() {
        for (output in listOf(RootCommandResult(127, "ps: not found", 2),
            RootCommandResult(0, "UID PID ARGS\n10123 300 $pkg", 2),
            RootCommandResult(0, "UID PID ARGS\n0 1 init\nps: private diagnostic", 2))) {
            val executor = Executor { output }
            val error = assertThrows(AccessFailure::class.java) { RootStorage(executor).stop(pkg, 0) }
            assertEquals(AccessFailureKind.PROCESS_CHECK_FAILED, error.kind)
            assertFalse(error.uncertainWrite)
            assertEquals(2, executor.calls.size)
            assertFalse(error.diagnostic!!.render().contains(output.mergedOutput))
        }
    }

    @Test fun probeTimeoutRemainsAReadFailureAndAllowsAReconnectedRetry() {
        var timedOut = true
        val executor = Executor {
            if (timedOut) throw RootExecutionFailure(AccessFailureKind.TIMEOUT, 8_000, "private process output")
            RootCommandResult(0, "UID PID ARGS\n0 1 init", 1)
        }
        val access = RootStorage(executor)
        val error = assertThrows(AccessFailure::class.java) { access.stop(pkg, 0) }
        assertEquals(AccessFailureKind.TIMEOUT, error.kind)
        assertFalse(error.uncertainWrite)
        assertFalse(error.diagnostic!!.render().contains("private process output"))
        timedOut = false
        access.stop(pkg, 0)
        assertEquals(2, executor.calls.count { it.command == "id" })
    }

    @Test fun invalidTargetsAreRejectedBeforePrivilegedCommands() {
        val executor = Executor { throw AssertionError("No privileged command should run") }
        val access = RootStorage(executor)
        assertThrows(IllegalArgumentException::class.java) { access.stop("$pkg;bad", 0) }
        assertThrows(IllegalArgumentException::class.java) { access.stop(pkg, -1) }
        assertTrue(executor.calls.isEmpty())
    }
}
