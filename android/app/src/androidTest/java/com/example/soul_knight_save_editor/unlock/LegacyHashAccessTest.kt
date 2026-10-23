package com.example.soul_knight_save_editor.unlock

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The executor is synthetic: these tests never execute a shell or touch a game file. */
@RunWith(AndroidJUnit4::class)
class LegacyHashAccessTest {
    private val path = "/data/user/10/com.test.game/files/game.data"
    private val apk = "/data/app/com.test.editor/base.apk"
    private val bytes = "abc".toByteArray()
    private val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val file get() = SaveFile(path, bytes, "10010:10010", "600", "")
    private class Executor(val reply: (RootCommandRequest) -> RootCommandResult) : RootCommandExecutor {
        val calls = mutableListOf<RootCommandRequest>()
        override fun execute(request: RootCommandRequest): RootCommandResult {
            calls += request
            return if (request.command == "id") RootCommandResult(0, "uid=0(root) gid=0(root)", 1) else reply(request)
        }
        override fun close() {}
    }
    private fun baseReply(request: RootCommandRequest): RootCommandResult? = when {
        request.command.startsWith("readlink") -> RootCommandResult(0, path, 1)
        request.command.startsWith("stat") -> RootCommandResult(0, "10010:10010 600 ${bytes.size}", 1)
        request.command.startsWith("ls") -> RootCommandResult(0, "", 1)
        request.command.startsWith("base64") -> RootCommandResult(0, Base64.encodeToString(bytes, Base64.NO_WRAP), 1)
        else -> null
    }
    @Test fun nativeReadKeepsDefaultDigestWithoutRunningCompatibilityCode() {
        val executor = Executor { request -> baseReply(request) ?: when {
            request.command.startsWith("sha256sum ") -> RootCommandResult(0, "${UnlockEngine.sha(bytes)}  $path", 1)
            else -> error("Unexpected compatibility invocation")
        } }
        val storage = RootStorage(executor, apk)
        assertArrayEquals(bytes, storage.read(path).bytes)
        assertArrayEquals(bytes, storage.read(path).bytes)
        assertEquals(2, executor.calls.count { it.command.startsWith("sha256sum ") })
        assertTrue(executor.calls.none { it.writing || "app_process" in it.command })
    }
    @Test fun missingDigestFallsBackForBothBackendsAndIsCachedUntilClose() {
        for (backend in AccessBackend.entries) {
            val executor = Executor { request -> baseReply(request) ?: when {
                request.command.startsWith("sha256sum ") -> RootCommandResult(127, "sh: <stdin>[19]: sha256sum: not found", 1)
                request.command.endsWith(" --self-test") -> RootCommandResult(0, empty, 1)
                "app_process" in request.command -> RootCommandResult(0, "${UnlockEngine.sha(bytes)}  $path", 1)
                else -> error("Unexpected command")
            } }
            val storage = ShellSaveAccess(executor, backend, apk)
            repeat(2) { assertArrayEquals(bytes, storage.read(path).bytes) }
            assertEquals(1, executor.calls.count { it.command.startsWith("sha256sum ") })
            assertEquals(1, executor.calls.count { it.command.endsWith(" --self-test") })
            storage.close()
            assertArrayEquals(bytes, storage.read(path).bytes)
            assertEquals(2, executor.calls.count { it.command.startsWith("sha256sum ") })
            assertEquals(2, executor.calls.count { it.command.endsWith(" --self-test") })
            assertTrue(executor.calls.none { it.writing })
        }
    }
    @Test fun compatibilityWriteChoosesHashBeforeExactlyOneTransactionAndPreservesExitCheck() {
        val executor = Executor { request -> baseReply(request) ?: when {
            request.command == "sha256sum /dev/null" -> RootCommandResult(127, "/system/bin/sh: sha256sum: not found", 1)
            request.command.endsWith(" --self-test") -> RootCommandResult(0, empty, 1)
            request.writing -> RootCommandResult(0, "", 1)
            else -> error("Unexpected command")
        } }
        RootStorage(executor, apk).replace(file, "changed".toByteArray())
        val writing = executor.calls.single { it.writing }
        assertTrue(writing.command.contains("_sk_hash=\$(CLASSPATH='$apk' /system/bin/app_process"))
        assertTrue(writing.command.contains(") && test \"\${_sk_hash%% *}\" = '"))
        assertFalse(writing.command.contains("| cut"))
        assertEquals(1, executor.calls.count { it.command.endsWith(" --self-test") })
        assertTrue(executor.calls.indexOfFirst { it.command.endsWith(" --self-test") } < executor.calls.indexOf(writing))
    }
    @Test fun nativeWriteUsesKnownEmptyHashProbeAndNeverRunsHelper() {
        val executor = Executor { request -> baseReply(request) ?: when {
            request.command == "sha256sum /dev/null" -> RootCommandResult(0, "$empty  /dev/null", 1)
            request.writing -> RootCommandResult(0, "", 1)
            else -> error("Unexpected command")
        } }
        RootStorage(executor, apk).replace(file, "changed".toByteArray())
        assertEquals(1, executor.calls.count { it.command == "sha256sum /dev/null" })
        assertTrue(executor.calls.single { it.writing }.command.contains("_sk_hash=\$(sha256sum "))
        assertTrue(executor.calls.none { "app_process" in it.command })
    }
    @Test fun failedTransactionIsNeverRetriedWithAnotherHashTool() {
        val executor = Executor { request -> baseReply(request) ?: when {
            request.command == "sha256sum /dev/null" -> RootCommandResult(127, "sha256sum: not found", 1)
            request.command.endsWith(" --self-test") -> RootCommandResult(0, empty, 1)
            request.writing -> RootCommandResult(1, "sha256: read error", 1)
            else -> error("Unexpected command")
        } }
        assertThrows(AccessFailure::class.java) { RootStorage(executor, apk).replace(file, "changed".toByteArray()) }
        assertEquals(1, executor.calls.count { it.writing })
    }
    @Test fun sessionDisconnectionInvalidatesTheCompatibilityCache() {
        var disconnect = false
        val executor = Executor { request ->
            if (disconnect) { disconnect = false; throw RootExecutionFailure(AccessFailureKind.SESSION_DISCONNECTED, 1) }
            baseReply(request) ?: when {
                request.command.startsWith("sha256sum ") -> RootCommandResult(127, "sha256sum: not found", 1)
                request.command.endsWith(" --self-test") -> RootCommandResult(0, empty, 1)
                "app_process" in request.command -> RootCommandResult(0, "${UnlockEngine.sha(bytes)}  $path", 1)
                else -> error("Unexpected command")
            }
        }
        val storage = RootStorage(executor, apk)
        storage.read(path)
        disconnect = true
        assertThrows(AccessFailure::class.java) { storage.read(path) }
        storage.read(path)
        assertEquals(2, executor.calls.count { it.command.startsWith("sha256sum ") })
        assertEquals(2, executor.calls.count { it.command.endsWith(" --self-test") })
        assertEquals(2, executor.calls.count { it.command == "id" })
    }
}
