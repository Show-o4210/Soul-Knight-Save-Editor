package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RootStorageTest {
    private val root = "/data/user/10/com.test.game"
    private class Executor(val reply: (RootCommandRequest) -> RootCommandResult) : RootCommandExecutor {
        val calls = mutableListOf<RootCommandRequest>()
        var idle = true
        override fun execute(request: RootCommandRequest): RootCommandResult {
            calls += request
            return if (request.command == "id") RootCommandResult(0, "uid=0(root) gid=0(root) groups=0(root)\n", 2)
                else reply(request)
        }
        override fun assertIdleForRecovery() { if (!idle) throw RootExecutionFailure(AccessFailureKind.WRITE_NOT_IDLE, 0, uncertainWrite = true) }
        override fun close() {}
    }
    private fun access(probe: RootCommandResult): Pair<RootStorage, Executor> {
        val executor = Executor { request -> if (request.command.startsWith("readlink")) RootCommandResult(0, root, 1) else probe }
        return RootStorage(executor) to executor
    }
    @Test fun directoryAndNonDirectoryHaveNormalBooleanResults() {
        val (directory, executor) = access(RootCommandResult(0, "directory\n", 3))
        assertTrue(directory.exists(root))
        assertEquals(1, executor.calls.count { it.command == "id" })
        assertFalse(access(RootCommandResult(0, "regular file\n", 1)).first.exists(root))
        assertEquals("uid=0 gid=0", directory.latestDiagnostic!!.identity)
    }
    @Test fun onlyExactMissingOperandErrnoReturnsFalse() {
        for (message in listOf("stat: '$root': No such file or directory\n", "stat: $root: Not a directory\n",
            "stat: cannot stat '$root': No such file or directory\n")) {
            assertFalse(access(RootCommandResult(1, message, 4)).first.exists(root))
        }
        for ((exit, message) in listOf(127 to "stat: not found\n", 1 to "stat: /another/path: No such file or directory\n",
            2 to "stat: '$root': No such file or directory\n", 1 to "stat: '$root': Input/output error\n")) {
            val error = assertThrows(AccessFailure::class.java) { access(RootCommandResult(exit, message, 5)).first.exists(root) }
            assertEquals(AccessFailureKind.COMMAND_FAILED, error.kind)
            assertEquals(exit, error.diagnostic!!.exitCode)
            assertFalse(error.message!!.contains("未授权"))
        }
    }
    @Test fun deniedDirectoryDoesNotInvalidateVerifiedRootIdentity() {
        var denied = true
        val executor = Executor { request -> when {
            request.command.startsWith("readlink") -> RootCommandResult(0, root, 1)
            denied -> RootCommandResult(1, "stat: '$root': Permission denied\n", 6)
            else -> RootCommandResult(0, "directory\n", 1)
        } }
        val storage = RootStorage(executor)
        val error = assertThrows(AccessFailure::class.java) { storage.exists(root) }
        assertEquals(AccessFailureKind.TARGET_ACCESS_DENIED, error.kind)
        assertEquals("uid=0 gid=0", error.diagnostic!!.identity)
        assertEquals("com.test.game", error.diagnostic!!.packageName)
        assertEquals(10, error.diagnostic!!.user)
        denied = false
        assertTrue(storage.exists(root))
        assertEquals(1, executor.calls.count { it.command == "id" })
    }
    @Test fun connectionTimeoutAndIoAreNotMissingDirectories() {
        for (kind in listOf(AccessFailureKind.SESSION_DISCONNECTED, AccessFailureKind.TIMEOUT, AccessFailureKind.IO)) {
            val storage = RootStorage(Executor { throw RootExecutionFailure(kind, 45_000) })
            val error = assertThrows(AccessFailure::class.java) { storage.exists(root) }
            assertEquals(kind, error.kind)
            assertEquals(45_000, error.diagnostic!!.elapsedMillis)
            assertNull(error.diagnostic.exitCode)
            assertFalse(error.uncertainWrite)
        }
    }
    @Test fun identityCheckRequiresActualUidZeroNotSubstring() {
        for (identity in listOf("uid=2000(shell) gid=2000(shell) groups=0(root)", "uid=1000(system) note=uid=0")) {
            val executor = object : RootCommandExecutor {
                override fun execute(request: RootCommandRequest) = RootCommandResult(0, identity, 1)
                override fun close() {}
            }
            assertEquals(AccessFailureKind.ROOT_IDENTITY,
                assertThrows(AccessFailure::class.java) { RootStorage(executor).ensureReady() }.kind)
        }
    }
    @Test fun absentSourceDirectoryIsEmptyButPermissionErrorsPropagate() {
        val missing = Executor { RootCommandResult(1, "stat: '$root/files': No such file or directory", 2) }
        assertTrue(RootStorage(missing).list(root, SaveSource.ITEM).isEmpty())
        assertFalse(missing.calls.any { it.command.startsWith("find") })
        val denied = Executor { RootCommandResult(1, "stat: '$root/files': Permission denied", 2) }
        assertEquals(AccessFailureKind.TARGET_ACCESS_DENIED,
            assertThrows(AccessFailure::class.java) { RootStorage(denied).list(root, SaveSource.ITEM) }.kind)
    }
    @Test fun canonicalSymlinkEscapeAndCallerParametersAreRejectedBeforeAccess() {
        val executor = Executor { request -> if (request.command.startsWith("readlink")) RootCommandResult(0, "/data/user/0/com.other.game", 1) else RootCommandResult(0, "directory", 1) }
        val storage = RootStorage(executor)
        assertThrows(IllegalArgumentException::class.java) { storage.exists(root) }
        val count = executor.calls.size
        for (path in listOf("/etc", "$root/../com.other.game", "/data/user/10/com.test.game;echo/files")) {
            assertThrows(IllegalArgumentException::class.java) { storage.exists(path) }
        }
        assertThrows(IllegalArgumentException::class.java) { storage.read("$root/files/game.data.new") }
        assertThrows(IllegalArgumentException::class.java) { storage.backupPaths("com.test.game;echo", 10) }
        assertEquals(count, executor.calls.size)
    }
    @Test fun boundedMergedErrorDiagnosticRedactsPathsAccountIdsAndPayloads() {
        val path = "$root/files/item_data_123456789_.data"
        val token = "QWxhZGRpbjpvcGVuIHNlc2FtZQ=="
        val executor = Executor { request -> if (request.command.startsWith("readlink")) RootCommandResult(0, path, 1)
            else RootCommandResult(1, "stat: '$path': Permission denied\naccount 123456789 $token\n" + "x".repeat(3000), 7) }
        val error = assertThrows(AccessFailure::class.java) { RootStorage(executor).read(path) }
        val diagnostic = error.diagnostic!!
        assertTrue(diagnostic.mergedOutput.length <= 1536)
        assertTrue(diagnostic.mergedOutput.contains("Permission denied"))
        assertFalse(diagnostic.render().contains(path))
        assertFalse(diagnostic.render().contains("123456789"))
        assertFalse(diagnostic.render().contains(token))
        assertTrue(diagnostic.render().contains("合并输出"))
    }
    @Test fun sensitiveReadFailuresNeverIncludePartialFileBodiesInDiagnostics() {
        val path = "$root/files/game.data"
        val secret = "very private save contents"
        val executor = Executor { request -> when {
            request.command.startsWith("readlink") -> RootCommandResult(0, path, 1)
            request.command.startsWith("stat") -> RootCommandResult(0, "10010:10010 600 3", 1)
            request.command.startsWith("ls") -> RootCommandResult(0, "u:object_r:app_data_file:s0 $path", 1)
            else -> RootCommandResult(1, secret, 1)
        } }
        val error = assertThrows(AccessFailure::class.java) { RootStorage(executor).read(path) }
        assertFalse(error.diagnostic!!.render().contains(secret))
        assertTrue(error.diagnostic.mergedOutput.contains("已省略"))
    }
    @Test fun recoveryChecksTheExecutorInsteadOfAssumingAnIdleWriter() {
        val executor = Executor { RootCommandResult(0, "", 1) }.apply { idle = false }
        val error = assertThrows(AccessFailure::class.java) { RootStorage(executor).assertIdleForRecovery() }
        assertEquals(AccessFailureKind.WRITE_NOT_IDLE, error.kind)
        assertTrue(error.uncertainWrite)
    }

    /** Synthetic shell protocol, not an Android process or real privileged command. */
    private class FramedProcess(private val exit: Int = 0, private val wait: CountDownLatch? = null,
        private val disconnect: Boolean = false) : Process() {
        private val pipe = PipedInputStream(64 * 1024)
        private val sender = PipedOutputStream(pipe)
        val sent = CountDownLatch(1)
        var destroyed = false
        private val input = object : OutputStream() {
            override fun write(b: Int) { error("bulk writes expected") }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val script = String(b, off, len, Charsets.UTF_8)
                val token = Regex("SK_[a-f0-9]+_BEGIN").find(script)!!.value.removeSuffix("_BEGIN")
                sent.countDown()
                Thread {
                    runCatching {
                        wait?.await(2, TimeUnit.SECONDS)
                        if (!disconnect) {
                            sender.write("${token}_BEGIN\nresult\n${token}_END:$exit\n".toByteArray())
                            sender.flush()
                        }
                        if (disconnect) sender.close()
                    }
                }.start()
            }
        }
        override fun getOutputStream() = input
        override fun getInputStream() = pipe
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun destroy() { destroyed = true; sender.close() }
    }
    @Test fun commandExecutorPreservesNonzeroResultsAndReopensAfterClose() {
        val processes = mutableListOf<FramedProcess>()
        val executor = ProcessRootCommandExecutor(listOf("unused"), 1000) { FramedProcess(3).also(processes::add) }
        val command = RootCommandRequest("synthetic", stage = "fixture")
        assertEquals(3, executor.execute(command).exitCode)
        executor.close()
        assertEquals(3, executor.execute(command).exitCode)
        executor.close()
        assertEquals(2, processes.size)
        assertTrue(processes.all { it.destroyed })
    }
    @Test fun nonWritingTimeoutAndDisconnectReleaseTheirSession() {
        val gate = CountDownLatch(1)
        val process = FramedProcess(wait = gate)
        val executor = ProcessRootCommandExecutor(listOf("unused"), 30) { process }
        assertEquals(AccessFailureKind.TIMEOUT,
            assertThrows(RootExecutionFailure::class.java) { executor.execute(RootCommandRequest("synthetic", stage = "fixture")) }.kind)
        assertTrue(process.destroyed)
        gate.countDown()
        val disconnected = FramedProcess(disconnect = true)
        val reader = ProcessRootCommandExecutor(listOf("unused"), 1000) { disconnected }
        assertEquals(AccessFailureKind.SESSION_DISCONNECTED,
            assertThrows(RootExecutionFailure::class.java) { reader.execute(RootCommandRequest("synthetic", stage = "fixture")) }.kind)
        assertTrue(disconnected.destroyed)
    }
    @Test fun timedOutWriteCannotRecoverUntilTheOriginalEndFrameArrives() {
        val gate = CountDownLatch(1)
        val process = FramedProcess(wait = gate)
        val executor = ProcessRootCommandExecutor(listOf("unused"), 30) { process }
        val error = assertThrows(RootExecutionFailure::class.java) {
            executor.execute(RootCommandRequest("synthetic", stage = "fixture", writing = true))
        }
        assertTrue(error.uncertainWrite)
        assertFalse(process.destroyed)
        assertThrows(RootExecutionFailure::class.java) { executor.assertIdleForRecovery() }
        executor.close()
        assertFalse(process.destroyed)
        gate.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (runCatching { executor.assertIdleForRecovery() }.isFailure && System.nanoTime() < deadline) Thread.sleep(10)
        executor.assertIdleForRecovery()
    }
}
