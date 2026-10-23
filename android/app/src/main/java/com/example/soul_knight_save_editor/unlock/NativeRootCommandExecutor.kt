package com.example.soul_knight_save_editor.unlock

import java.io.File
import java.util.concurrent.TimeUnit

/** The mount view is selected before source bytes are read, and retained until close(). */
internal class NativeRootCommandExecutor(
    private val suExecutable: String = findSuExecutable(),
    private val executorFactory: (List<String>) -> RootCommandExecutor = { ProcessRootCommandExecutor(it) }
) : RootCommandExecutor {
    private val ordinary by lazy { executorFactory(listOf(suExecutable)) }
    private var master: RootCommandExecutor? = null
    private var usingMaster = false
    private var ordinaryUidZero = false
    private var pinned = false
    private var fallbackDisabled = false
    private var magisk: Boolean? = null
    private val checkedRoots = mutableSetOf<String>()
    val diagnosticSuffix: String get() = if (usingMaster) "（Magisk 全局挂载视图）" else ""

    @Synchronized override fun close() {
        ordinary.close()
        master?.close()
        usingMaster = false
        ordinaryUidZero = false
        pinned = false
        fallbackDisabled = false
        magisk = null
        checkedRoots.clear()
    }

    @Synchronized override fun assertIdleForRecovery() {
        ordinary.assertIdleForRecovery()
        master?.assertIdleForRecovery()
    }

    @Synchronized override fun execute(request: RootCommandRequest): RootCommandResult {
        assertIdleForRecovery()
        val startedAt = System.nanoTime()
        val budget = request.timeoutMillis ?: 45_000L
        require(budget > 0)
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        fun bounded(source: RootCommandRequest, maximum: Long = budget): RootCommandRequest {
            if (source.isCancelled()) throw DiscoveryCancelled()
            val remaining = budget - elapsed()
            if (remaining <= 0) throw RootExecutionFailure(AccessFailureKind.TIMEOUT, elapsed())
            return source.copy(timeoutMillis = minOf(remaining, maximum))
        }
        fun probe(executor: RootCommandExecutor, command: String, stage: String): RootCommandResult =
            executor.execute(bounded(request.copy(command = command, inputBase64 = null,
                stage = stage, writing = false, sensitiveOutput = false), 3_000))

        try {
            // ShellSaveAccess normally supplied this identity already. Never infer it from a
            // process name or from a successful directory command.
            if (request.command != "id" && !usingMaster && !pinned && !fallbackDisabled &&
                request.packageName != null && request.user != null) {
                val root = SaveLayout.root(request.packageName, request.user)
                if (checkedRoots.add(root)) {
                    if (!ordinaryUidZero) {
                        ordinaryUidZero = uidZero(probe(ordinary, "id", "备用前权限身份检查"))
                        if (!ordinaryUidZero) fallbackDisabled = true
                    }
                    if (ordinaryUidZero && !fallbackDisabled) {
                        val command = "LC_ALL=C stat -L -c '%F' ${quote(root)}"
                        val visible = probe(ordinary, command, "普通 su 挂载视图目录检查")
                        // Unknown failures (including old stat's unsupported -L), denied access
                        // and non-directory operands stay on the existing ordinary-su path.
                        if (missing(visible, root) && suExecutable.startsWith('/')) {
                            if (magisk == null) {
                                val version = probe(ordinary, "${quote(suExecutable)} -v", "识别 Magisk su")
                                magisk = version.exitCode == 0 && magiskVersion.matches(version.mergedOutput.trim())
                                if (magisk != true) fallbackDisabled = true
                            }
                            if (magisk == true) {
                                val candidate = executorFactory(listOf(suExecutable, "--mount-master"))
                                var accepted = false
                                try {
                                    if (uidZero(probe(candidate, "id", "Magisk 全局视图权限身份检查"))) {
                                        val directory = probe(candidate, command, "Magisk 全局挂载视图目录检查")
                                        if (directory.exitCode == 0 && directory.mergedOutput.trim() == "directory") {
                                            val actual = probe(candidate, "readlink -f ${quote(root)}", "Magisk 全局视图路径检查")
                                            if (actual.exitCode == 0) {
                                                require(sameRoot(root, actual.mergedOutput.trim())) { "全局挂载视图的目标目录指向范围外位置，已停止" }
                                                master?.close()
                                                master = candidate
                                                usingMaster = true
                                                accepted = true
                                                ordinary.close()
                                            }
                                        }
                                    }
                                } finally {
                                    if (!accepted) candidate.close()
                                }
                            }
                        }
                    }
                }
            }
            // A failed/uncertain write is never replayed in a different shell. Discovery grep
            // and hashes also pin the view before reading any actual source bytes.
            if (request.writing || request.inputBase64 != null ||
                request.sensitiveOutput && request.stage != "检查目标游戏运行状态" ||
                request.stage in sourceReadStages) pinned = true
            val delegate = if (usingMaster) checkNotNull(master) else ordinary
            val output = delegate.execute(bounded(request))
            if (request.command == "id" && !usingMaster) {
                ordinaryUidZero = uidZero(output)
                if (!ordinaryUidZero) fallbackDisabled = true
            }
            return output.copy(elapsedMillis = elapsed())
        } catch (error: Exception) {
            // The caller can diagnose/recover, but must end this operation before trying
            // another view, even if a read or the capability probe failed.
            pinned = true
            throw error
        }
    }

    companion object {
        private val magiskVersion = Regex("[0-9]+(?:\\.[0-9]+)*(?:[-+][A-Za-z0-9._+-]+)?:MAGISKSU")
        private val sourceReadStages = setOf("读取存档内容", "读取完整性校验", "兼容读取完整性校验",
            "检查 XML 搜索特征", "写回 SHA-256 只读自检", "旧版 SHA-256 兼容自检")
        private fun uidZero(result: RootCommandResult): Boolean = result.exitCode == 0 &&
            Regex("(?:^|\\s)uid=([0-9]+)(?:\\([^)]*\\))?(?:\\s|$)")
                .find(result.mergedOutput.trim())?.groupValues?.get(1)?.toIntOrNull() == 0
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        private fun sameRoot(expected: String, actual: String): Boolean = actual == expected ||
            expected.startsWith("/data/user/0/") && actual == expected.replaceFirst("/data/user/0/", "/data/data/")
        private fun missing(result: RootCommandResult, path: String): Boolean {
            if (result.exitCode != 1) return false
            val line = result.mergedOutput.trim()
            val reason = listOf("No such file or directory", "Not a directory")
                .firstOrNull { line.endsWith(": $it") } ?: return false
            return line.removeSuffix(": $reason") in setOf("stat: $path", "stat: '$path'",
                "stat: cannot stat '$path'", "stat: cannot statx '$path'")
        }
        private fun findSuExecutable(): String = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .asSequence().filter { it.startsWith('/') }.map { File(it, "su") }
            .firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: "su"
    }
}
