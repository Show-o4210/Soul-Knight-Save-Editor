package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Deliberately uninterruptible pipes model a child that retained stdout after its shell exited. */
class RootCommandTimeoutTest {
    private class BlockedProcess(private val blockDestroy: Boolean = false) : Process() {
        val reading = CountDownLatch(1)
        val destroying = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val source = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                release.awaitUninterruptibly()
                return -1
            }
            override fun close() { cleaned.countDown() }
        }
        private val sink = object : OutputStream() { override fun write(b: Int) {} }
        override fun getInputStream() = source
        override fun getOutputStream() = sink
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = throw IllegalThreadStateException()
        override fun destroy() {
            destroying.countDown()
            if (blockDestroy) release.awaitUninterruptibly()
        }
        fun unblock() { release.countDown() }
    }

    @Test fun timeoutReturnsWhileReaderCloseOrProcessDestroyIsBlockedAndNextReadCanStart() {
        for (blockDestroy in listOf(false, true)) {
            val stuck = BlockedProcess(blockDestroy)
            val succeeding = EchoProcess()
            var starts = 0
            val executor = ProcessRootCommandExecutor(listOf("synthetic"), 2_000) {
                if (starts++ == 0) stuck else succeeding
            }
            val caller = daemonCaller()
            try {
                val result = caller.submit<Throwable?> {
                    runCatching { executor.execute(RootCommandRequest("read", stage = "fixture", timeoutMillis = 80)) }.exceptionOrNull()
                }
                assertTrue(stuck.reading.await(1, TimeUnit.SECONDS))
                val failure = result.get(1, TimeUnit.SECONDS) as RootExecutionFailure
                assertEquals(AccessFailureKind.TIMEOUT, failure.kind)
                assertFalse(failure.uncertainWrite)
                assertTrue(stuck.destroying.await(1, TimeUnit.SECONDS))
                // This would also wait on BufferedReader's read lock with synchronous cleanup.
                caller.submit { executor.close() }.get(1, TimeUnit.SECONDS)
                assertEquals(0, executor.execute(RootCommandRequest("read", stage = "next fixture")).exitCode)
                assertEquals(2, starts)
            } finally {
                stuck.unblock()
                executor.close()
                caller.shutdownNow()
                assertTrue(stuck.cleaned.await(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun cancellingReadReturnsWithoutWaitingForItsPipeToClose() {
        val process = BlockedProcess()
        val cancelled = AtomicBoolean()
        val executor = ProcessRootCommandExecutor(listOf("synthetic"), 2_000) { process }
        val caller = daemonCaller()
        try {
            val result = caller.submit<Throwable?> {
                runCatching {
                    executor.execute(RootCommandRequest("read", stage = "fixture") { cancelled.get() })
                }.exceptionOrNull()
            }
            assertTrue(process.reading.await(1, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue(result.get(1, TimeUnit.SECONDS) is DiscoveryCancelled)
            caller.submit { executor.close() }.get(1, TimeUnit.SECONDS)
        } finally {
            process.unblock()
            executor.close()
            caller.shutdownNow()
            assertTrue(process.cleaned.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun requestDeadlineCanShortenButCannotExtendExecutorDeadline() {
        val process = BlockedProcess()
        val executor = ProcessRootCommandExecutor(listOf("synthetic"), 80) { process }
        val caller = daemonCaller()
        try {
            val result = caller.submit<Throwable?> {
                runCatching { executor.execute(RootCommandRequest("read", stage = "fixture", timeoutMillis = 10_000)) }.exceptionOrNull()
            }
            assertEquals(AccessFailureKind.TIMEOUT, (result.get(1, TimeUnit.SECONDS) as RootExecutionFailure).kind)
        } finally {
            process.unblock()
            executor.close()
            caller.shutdownNow()
            assertTrue(process.cleaned.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun completedOutputDoesNotRemoveTheDeadlineFromAStuckInputFlush() {
        val release = CountDownLatch(1)
        val flushing = CountDownLatch(1)
        val process = EchoProcess(flush = {
            flushing.countDown()
            release.awaitUninterruptibly()
        })
        val executor = ProcessRootCommandExecutor(listOf("synthetic"), 80) { process }
        val caller = daemonCaller()
        try {
            val result = caller.submit<Throwable?> {
                runCatching { executor.execute(RootCommandRequest("read", stage = "fixture")) }.exceptionOrNull()
            }
            assertTrue(flushing.await(1, TimeUnit.SECONDS))
            assertEquals(AccessFailureKind.TIMEOUT, (result.get(1, TimeUnit.SECONDS) as RootExecutionFailure).kind)
        } finally {
            release.countDown()
            executor.close()
            caller.shutdownNow()
        }
    }

    @Test fun uncertainWriteStillBlocksOtherInstancesUntilItsOwnEndFrameArrives() {
        val release = CountDownLatch(1)
        val process = EchoProcess(beforeReply = { release.awaitUninterruptibly() })
        val executor = ProcessRootCommandExecutor(listOf("synthetic"), 80) { process }
        val other = ProcessRootCommandExecutor(listOf("synthetic"), 2_000) { EchoProcess() }
        try {
            val failure = assertThrows(RootExecutionFailure::class.java) {
                executor.execute(RootCommandRequest("write", stage = "fixture", writing = true))
            }
            assertTrue(failure.uncertainWrite)
            executor.close()
            assertFalse(process.destroyed.get())
            assertEquals(AccessFailureKind.WRITE_NOT_IDLE, assertThrows(RootExecutionFailure::class.java) {
                other.execute(RootCommandRequest("read", stage = "other fixture"))
            }.kind)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (runCatching { executor.assertIdleForRecovery() }.isFailure && System.nanoTime() < deadline) Thread.sleep(5)
            executor.assertIdleForRecovery()
            assertEquals(0, other.execute(RootCommandRequest("read", stage = "other fixture")).exitCode)
        } finally {
            release.countDown()
            executor.close()
            other.close()
        }
    }

    @Test fun repeatedUninterruptibleReadTimeoutsHaveABoundedSessionBudget() {
        val processes = mutableListOf<BlockedProcess>()
        val executors = mutableListOf<ProcessRootCommandExecutor>()
        val caller = daemonCaller()
        try {
            var exhausted = false
            repeat(12) {
                if (!exhausted) {
                    val executor = ProcessRootCommandExecutor(listOf("synthetic"), 30) {
                        BlockedProcess(true).also(processes::add)
                    }.also(executors::add)
                    val result = caller.submit<Throwable?> {
                        runCatching { executor.execute(RootCommandRequest("read", stage = "fixture")) }.exceptionOrNull()
                    }
                    val failure = result.get(1, TimeUnit.SECONDS) as RootExecutionFailure
                    if (failure.kind == AccessFailureKind.IO) exhausted = true
                    else assertEquals(AccessFailureKind.TIMEOUT, failure.kind)
                    executor.close()
                }
            }
            assertTrue(exhausted)
            assertTrue(processes.size in 1..8)
        } finally {
            processes.forEach { it.unblock() }
            executors.forEach { it.close() }
            caller.shutdownNow()
            processes.forEach { assertTrue(it.cleaned.await(1, TimeUnit.SECONDS)) }
        }
    }

    /** Emits a full valid frame synchronously while allowing stdin.flush or the reply to stall. */
    private class EchoProcess(private val flush: () -> Unit = {}, private val beforeReply: () -> Unit = {}) : Process() {
        val destroyed = AtomicBoolean()
        private val source = java.io.PipedInputStream(64 * 1024)
        private val sender = java.io.PipedOutputStream(source)
        private val sink = object : OutputStream() {
            override fun write(b: Int) { error("bulk write required") }
            override fun write(b: ByteArray, off: Int, len: Int) {
                val token = Regex("SK_[a-f0-9]+_BEGIN").find(String(b, off, len, Charsets.UTF_8))!!.value.removeSuffix("_BEGIN")
                beforeReply()
                sender.write("${token}_BEGIN\n${token}_END:0\n".toByteArray())
                sender.flush()
            }
            override fun flush() { flush.invoke() }
        }
        override fun getInputStream() = source
        override fun getOutputStream() = sink
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = throw IllegalThreadStateException()
        override fun destroy() { destroyed.set(true); sender.close() }
    }

    private fun daemonCaller() = Executors.newSingleThreadExecutor { task -> Thread(task, "fixture-caller").apply { isDaemon = true } }

    private companion object {
        fun CountDownLatch.awaitUninterruptibly() {
            var interrupted = false
            while (true) {
                try { await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
