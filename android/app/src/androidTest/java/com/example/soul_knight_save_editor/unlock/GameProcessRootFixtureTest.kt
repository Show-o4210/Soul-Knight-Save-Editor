package com.example.soul_knight_save_editor.unlock

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in. A synthetic shell waits on its own FIFO; no real game is started or stopped. */
@RunWith(AndroidJUnit4::class)
class GameProcessRootFixtureTest {
    @Test fun nativeRootDetectsAnExactSyntheticChildProcessWithoutKillingIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.example.soul_knight_save_editor")
        val token = UUID.randomUUID().toString().replace("-", "")
        val packageName = "com.example.sk.gatefixture.$token"
        val processName = "$packageName:worker"
        val fifo = File(context.cacheDir, "game-process-fixture-$token.fifo")
        require(fifo.canonicalFile.parentFile == context.cacheDir.canonicalFile && !fifo.exists())
        val executor = ProcessRootCommandExecutor(listOf("su"))
        val access = RootStorage(executor)
        var pid: Int? = null
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun command(value: String): String {
            val result = executor.execute(RootCommandRequest(value, stage = "合成进程门禁验收", sensitiveOutput = true,
                timeoutMillis = 8_000))
            check(result.exitCode == 0) { "Synthetic fixture command failed: ${result.exitCode}" }
            return result.mergedOutput.trim()
        }
        fun ownPidAlive(): Boolean {
            val current = pid ?: return false
            val row = Regex("^0\\s+$current\\s+${Regex.escape(processName)}(?:\\s|$).*")
            return command(GameProcessProbe.COMMAND).lineSequence().map(String::trim).any(row::matches)
        }
        fun waitFor(expectedAlive: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (ownPidAlive() != expectedAlive && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals(expectedAlive, ownPidAlive())
        }
        fun cleanProcess() {
            val current = pid
            if (current != null && ownPidAlive()) {
                require(current > 1)
                command("kill $current")
                waitFor(false)
            }
        }
        try {
            // A FIFO opened read/write keeps read waiting without a child process or busy loop.
            val waiting = "exec 3<>\"\$1\"; read -t 60 _sk_fixture <&3"
            val start = "mkfifo ${quote(fifo.absolutePath)} || exit 1; " +
                "(exec -a ${quote(processName)} /system/bin/sh -c ${quote(waiting)} fixture ${quote(fifo.absolutePath)}) " +
                "</dev/null >/dev/null 2>&1 & echo \$!"
            pid = command(start).toInt()
            require(pid!! > 1)
            waitFor(true)

            val error = assertThrows(AccessFailure::class.java) { access.stop(packageName, 0) }
            assertEquals(AccessFailureKind.GAME_RUNNING, error.kind)
            assertTrue(ownPidAlive())
            // The same argv0 in Android user 0 cannot block user 10 or a similar package.
            access.stop(packageName, 10)
            access.stop("${packageName}other", 0)
            assertTrue(ownPidAlive())

            cleanProcess()
            access.stop(packageName, 0)
        } finally {
            try { cleanProcess() } finally {
                access.close()
                require(fifo.canonicalFile.parentFile == context.cacheDir.canonicalFile && fifo.name == "game-process-fixture-$token.fifo")
                check(fifo.delete() || !fifo.exists())
            }
        }
    }
}
