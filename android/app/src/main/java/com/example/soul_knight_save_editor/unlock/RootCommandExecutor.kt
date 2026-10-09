package com.example.soul_knight_save_editor.unlock

import java.io.BufferedReader
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class RootCommandRequest(val command: String, val inputBase64: ByteArray? = null,
    val stage: String, val packageName: String? = null, val user: Int? = null,
    val writing: Boolean = false, val sensitiveOutput: Boolean = false,
    val isCancelled: () -> Boolean = { false })
data class RootCommandResult(val exitCode: Int, val mergedOutput: String, val elapsedMillis: Long)

/** Internal only: Binder never accepts a command string. Nonzero exits remain operation results. */
interface RootCommandExecutor : AutoCloseable {
    fun execute(request: RootCommandRequest): RootCommandResult
    fun assertIdleForRecovery() {}
}

class RootExecutionFailure(val kind: AccessFailureKind, val elapsedMillis: Long,
    val mergedOutput: String = "", val uncertainWrite: Boolean = false, cause: Throwable? = null) : IOException(kind.name, cause)

/** A bounded framed shell. Shizuku constructs this with /system/bin/sh, never su. */
class ProcessRootCommandExecutor(private val shell: List<String>, private val timeoutMillis: Long = 45_000,
    private val processFactory: () -> Process = { ProcessBuilder(shell).redirectErrorStream(true).start() }) : RootCommandExecutor {
    private var session: Process? = null
    private var reader: BufferedReader? = null
    @Volatile private var closed = false
    private class Running(val writing: Boolean) {
        @Volatile var acknowledged = false
        @Volatile var output: Future<Pair<Int, String>>? = null
        @Volatile var input: Future<*>? = null
    }
    @Volatile private var running: Running? = null

    companion object {
        private val uncertain = java.util.Collections.synchronizedSet(mutableSetOf<ProcessRootCommandExecutor>())
        private fun checkUncertainWrites() {
            val snapshot = synchronized(uncertain) { uncertain.toList() }
            snapshot.forEach { executor -> if (executor.running?.acknowledged == true) uncertain.remove(executor) }
            if (uncertain.isNotEmpty()) throw RootExecutionFailure(AccessFailureKind.WRITE_NOT_IDLE, 0, uncertainWrite = true)
        }
    }
    @Synchronized override fun assertIdleForRecovery() {
        checkUncertainWrites()
        val active = running
        if (active != null && active.writing && !active.acknowledged)
            throw RootExecutionFailure(AccessFailureKind.WRITE_NOT_IDLE, 0, uncertainWrite = true)
    }
    @Synchronized override fun close() {
        closed = true
        val active = running
        // A timeout is not proof that a child performing mv has stopped. Keep its END reader alive.
        if (active != null && active.writing && !active.acknowledged) return
        disposeSession()
    }
    private fun disposeSession() {
        runCatching { session?.outputStream?.close() }
        session?.destroy()
        runCatching { reader?.close() }
        session = null
        reader = null
    }
    @Synchronized override fun execute(request: RootCommandRequest): RootCommandResult {
        checkUncertainWrites()
        // close() ends the user operation. Native Root reuses this object for the next operation.
        if (closed) { disposeSession(); closed = false }
        require(timeoutMillis > 0)
        if (request.isCancelled()) throw DiscoveryCancelled()
        val startedAt = System.nanoTime()
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        if (session == null) {
            session = try { processFactory() } catch (error: Exception) { throw RootExecutionFailure(AccessFailureKind.IO, elapsed(), cause = error) }
            reader = session!!.inputStream.bufferedReader(Charsets.UTF_8)
        }
        val process = session!!
        val commandReader = reader!!
        val token = "SK_" + UUID.randomUUID().toString().replace("-", "")
        val begin = "${token}_BEGIN"
        val end = "${token}_END:"
        val delimiter = "${token}_INPUT"
        val payload = request.inputBase64?.toString(Charsets.US_ASCII).orEmpty()
        require(payload.length <= UnlockEngine.MAX_BYTES * 2 && payload.all { it.isLetterOrDigit() || it in "+/=\r\n" })
        val script = "printf '%s\\n' '$begin'\n(\n${request.command}\n) <<'$delimiter'\n$payload\n$delimiter\n" +
            "_sk_result=\$?\nprintf '\\n%s:%s\\n' '${token}_END' \"\$_sk_result\"\n"
        val pool = Executors.newFixedThreadPool(2)
        val active = Running(request.writing)
        running = active
        active.output = pool.submit<Pair<Int, String>> {
            var begun = false
            val result = StringBuilder()
            try {
                while (true) {
                    val line = boundedLine(commandReader, UnlockEngine.MAX_BYTES * 2)
                        ?: throw RootExecutionFailure(AccessFailureKind.SESSION_DISCONNECTED, elapsed())
                    if (line == begin) { begun = true; continue }
                    if (!begun) continue
                    if (line.startsWith(end)) {
                        val exit = line.removePrefix(end).toIntOrNull()
                            ?: throw RootExecutionFailure(AccessFailureKind.IO, elapsed())
                        active.acknowledged = true
                        uncertain.remove(this)
                        return@submit exit to result.toString()
                    }
                    if (result.length + line.length + 1 > UnlockEngine.MAX_BYTES * 2)
                        throw RootExecutionFailure(AccessFailureKind.OUTPUT_LIMIT, elapsed())
                    result.append(line).append('\n')
                }
                @Suppress("UNREACHABLE_CODE") error("No command frame")
            } finally {
                pool.shutdown()
                if (closed && active.acknowledged) {
                    runCatching { process.outputStream.close() }
                    process.destroy()
                }
            }
        }
        active.input = pool.submit {
            process.outputStream.write(script.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
        }
        try {
            val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                if (request.isCancelled()) {
                    if (!request.writing) throw DiscoveryCancelled()
                    throw RootExecutionFailure(AccessFailureKind.SESSION_DISCONNECTED, elapsed(), uncertainWrite = true)
                }
                if (System.nanoTime() >= deadline) throw RootExecutionFailure(AccessFailureKind.TIMEOUT, elapsed(), uncertainWrite = request.writing)
                if (active.input!!.isDone) active.input!!.get()
                try {
                    val (exit, out) = active.output!!.get(minOf(200, timeoutMillis), TimeUnit.MILLISECONDS)
                    active.input!!.get()
                    return RootCommandResult(exit, out, elapsed())
                } catch (_: java.util.concurrent.TimeoutException) { /* Poll cancellation and the deadline. */ }
            }
        } catch (error: DiscoveryCancelled) {
            disposeSession(); pool.shutdownNow(); throw error
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            val failure = cause as? RootExecutionFailure
                ?: RootExecutionFailure(AccessFailureKind.IO, elapsed(), cause = cause)
            if (request.writing && !active.acknowledged) {
                uncertain.add(this)
                // Keep the reader until an END marker proves completion; never resend the write.
                throw RootExecutionFailure(failure.kind, elapsed(), failure.mergedOutput, true, failure)
            }
            disposeSession(); pool.shutdownNow(); throw failure
        }
    }
    private fun boundedLine(source: BufferedReader, maximum: Int): String? {
        val line = StringBuilder()
        while (true) {
            val char = source.read()
            if (char < 0) return if (line.isEmpty()) null else line.toString()
            if (char == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= maximum) throw RootExecutionFailure(AccessFailureKind.OUTPUT_LIMIT, 0)
            line.append(char.toChar())
        }
    }
}
