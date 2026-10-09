package com.example.soul_knight_save_editor.unlock

import android.util.Base64
import java.util.UUID

/** Fixed privileged file operations, shared by both backends; no public command execution. */
open class ShellSaveAccess(private val executor: RootCommandExecutor, backend: AccessBackend) : BaseSaveAccess(backend) {
    private val pathPattern = SaveLayout.editablePath
    private val backupPattern = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+/files/(?:[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+\\.data$")
    private val directoryPattern = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+(?:/(?:files(?:/[a-zA-Z0-9_.-]+)?|shared_prefs))?$")
    private var identity = "未检查"
    private var ready = false
    final override var latestDiagnostic: AccessDiagnostic? = null
        private set
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
    override fun close() { ready = false; executor.close() }

    private fun context(path: String): Pair<String, Int> {
        val match = Regex("^/data/(?:user/([0-9]+)|data)/([^/]+)(?:/|$)").find(path)
            ?: throw IllegalArgumentException("路径不在本地存档范围内")
        val pkg = match.groupValues[2]
        val user = if (match.groupValues[1].isEmpty()) 0 else match.groupValues[1].toIntOrNull()
        require(SaveLayout.packagePattern.matches(pkg) && user != null && user >= 0) { "包名或 Android 用户编号无效" }
        return pkg to user
    }
    private fun diagnostic(request: RootCommandRequest, exit: Int?, elapsed: Long, output: String): AccessDiagnostic {
        val safe = if (request.sensitiveOutput) "包含文件内容的合并输出已省略" else sanitize(output)
        return AccessDiagnostic(backend, request.stage, request.packageName, request.user, identity, exit, elapsed, safe)
            .also { latestDiagnostic = it }
    }
    private fun sanitize(output: String): String {
        // Only fixed errno/command failure text is shown. Even short account IDs can occur in an operand.
        val reasons = listOf("Permission denied", "Operation not permitted", "No such file or directory", "Not a directory",
            "Input/output error", "Read-only file system", "No space left on device", "Invalid argument", "invalid option",
            "inaccessible or not found", "not found", "Broken pipe", "Operation timed out", "Too many open files")
        val safe = output.take(4096).lineSequence().mapNotNull { line -> reasons.firstOrNull { line.contains(it, true) } }
            .distinct().take(16).joinToString("\n")
        return if (safe.isNotBlank()) safe.take(1536) else if (output.isBlank()) "无合并错误输出" else "不可安全显示的合并输出已省略"
    }

    private fun result(command: String, stage: String, packageName: String? = null, user: Int? = null,
        input: ByteArray? = null, writing: Boolean = false, sensitive: Boolean = false): Pair<RootCommandRequest, RootCommandResult> {
        checkCancellation()
        val request = RootCommandRequest(command, input, stage, packageName, user, writing, sensitive) { cancellation?.invoke() == true }
        val output = try { executor.execute(request) } catch (error: RootExecutionFailure) {
            if (error.kind in setOf(AccessFailureKind.IO, AccessFailureKind.SESSION_DISCONNECTED, AccessFailureKind.TIMEOUT)) ready = false
            val detail = diagnostic(request, null, error.elapsedMillis, error.mergedOutput)
            val message = when (error.kind) {
                AccessFailureKind.TIMEOUT -> "${backend.label}操作超时；如已开始写回，请先检查恢复提示"
                AccessFailureKind.SESSION_DISCONNECTED -> "${backend.label}会话中断，请重新连接后重试"
                AccessFailureKind.WRITE_NOT_IDLE -> "仍有结果未确认的写任务，已停止后续写入和恢复"
                AccessFailureKind.OUTPUT_LIMIT -> "操作输出超出限制，已停止"
                else -> "${backend.label}通道 I/O 失败，请检查服务和设备状态"
            }
            throw AccessFailure(error.kind, message, detail, error.uncertainWrite, error)
        }
        return request to output
    }
    private fun successful(request: RootCommandRequest, output: RootCommandResult): String {
        if (output.exitCode != 0) {
            val denied = output.mergedOutput.contains("Permission denied", true) || output.mergedOutput.contains("Operation not permitted", true)
            val kind = if (request.stage == "权限身份检查") AccessFailureKind.ROOT_IDENTITY
                else if (denied) AccessFailureKind.TARGET_ACCESS_DENIED else AccessFailureKind.COMMAND_FAILED
            val detail = diagnostic(request, output.exitCode, output.elapsedMillis, output.mergedOutput)
            val message = if (kind == AccessFailureKind.ROOT_IDENTITY) "无法检查 Root 身份，请检查所选通道授权"
                else if (denied) "目标存档访问被拒绝；Root 身份与文件访问是不同检查阶段"
                else "${request.stage}未成功（退出码 ${output.exitCode}），请查看访问诊断"
            throw AccessFailure(kind, message, detail)
        }
        diagnostic(request, output.exitCode, output.elapsedMillis, "操作完成，输出已省略")
        return output.mergedOutput
    }
    private fun run(command: String, stage: String, packageName: String? = null, user: Int? = null,
        input: ByteArray? = null, writing: Boolean = false, sensitive: Boolean = false): String {
        val (request, output) = result(command, stage, packageName, user, input, writing, sensitive)
        return successful(request, output)
    }
    final override fun ensureReady() = ensureRoot(null, null)
    private fun ensureRoot(packageName: String?, user: Int?) {
        if (ready) return
        val (request, output) = result("id", "权限身份检查", packageName, user)
        val text = successful(request, output).trim()
        val uid = Regex("(?:^|\\s)uid=([0-9]+)(?:\\([^)]*\\))?(?:\\s|$)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val gid = Regex("(?:^|\\s)gid=([0-9]+)(?:\\([^)]*\\))?(?:\\s|$)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        identity = "uid=${uid ?: "未知"} gid=${gid ?: "未知"}"
        if (uid != 0) {
            val detail = diagnostic(request, output.exitCode, output.elapsedMillis, "实际执行身份不是 UID 0")
            throw AccessFailure(AccessFailureKind.ROOT_IDENTITY, "所选通道的实际执行身份不是 Root（UID ${uid ?: "未知"}）", detail)
        }
        ready = true
        diagnostic(request, output.exitCode, output.elapsedMillis, "Root 身份检查通过")
    }
    final override fun assertIdleForRecovery() {
        try { executor.assertIdleForRecovery() } catch (error: RootExecutionFailure) {
            throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE, "仍有结果未确认的写任务，暂不能恢复", latestDiagnostic, true, error)
        }
    }
    private fun canonical(path: String, pkg: String, user: Int): String {
        val actual = run("readlink -f ${quote(path)}", "规范化路径检查", pkg, user).trim()
        require(actual == path || actual.replaceFirst("/data/user/0/", "/data/data/") == path.replaceFirst("/data/user/0/", "/data/data/")) { "存档路径指向范围外位置，已停止" }
        return quote(path)
    }
    private fun checked(path: String, backupOnly: Boolean = false): String {
        require((pathPattern.matches(path) || backupOnly && backupPattern.matches(path)) && !path.contains("..") && !path.endsWith(".new")) { "路径不在本地存档范围内" }
        val (pkg, user) = context(path)
        ensureRoot(pkg, user)
        return canonical(path, pkg, user)
    }
    final override fun stop(packageName: String, user: Int) {
        SaveLayout.root(packageName, user)
        ensureRoot(packageName, user)
        run("am force-stop --user $user ${quote(packageName)}", "停止目标游戏", packageName, user)
    }
    final override fun exists(directory: String): Boolean {
        require(directoryPattern.matches(directory) && !directory.contains("..")) { "目录不在本地存档范围内" }
        val (pkg, user) = context(directory)
        ensureRoot(pkg, user)
        // C-locale stat reports platform errno; only exact ENOENT/ENOTDIR diagnostics mean absent.
        val (request, output) = result("LC_ALL=C stat -L -c '%F' ${quote(directory)}", "目标目录检查", pkg, user)
        if (output.exitCode != 0 && absentDirectory(output, directory)) {
            diagnostic(request, output.exitCode, output.elapsedMillis, "目标目录明确不存在或路径组件不是目录")
            return false
        }
        val type = successful(request, output).trim()
        require(type in setOf("directory", "regular file", "regular empty file", "symbolic link", "fifo", "socket", "character special file", "block special file")) { "无法识别目标目录类型" }
        if (type != "directory") return false
        canonical(directory, pkg, user)
        return true
    }
    private fun absentDirectory(output: RootCommandResult, path: String): Boolean {
        if (output.exitCode != 1) return false
        val line = output.mergedOutput.trim()
        val suffix = listOf("No such file or directory", "Not a directory").firstOrNull { line.endsWith(": $it") } ?: return false
        val prefix = line.removeSuffix(": $suffix")
        return prefix in setOf("stat: $path", "stat: '$path'", "stat: cannot stat '$path'", "stat: cannot statx '$path'")
    }
    final override fun read(path: String) = readFile(path, false)
    final override fun readBackup(path: String) = readFile(path, true)
    private fun readFile(path: String, backupOnly: Boolean): SaveFile {
        val q = checked(path, backupOnly)
        val (pkg, user) = context(path)
        val metadata = run("stat -c '%u:%g %a %s' $q", "读取文件元数据", pkg, user).trim().split(Regex("\\s+"))
        require(metadata.size == 3 && Regex("\\d+:\\d+").matches(metadata[0]) && Regex("[0-7]{3,4}").matches(metadata[1])) { "文件权限无法识别" }
        require(metadata[2].toLong() in 1..UnlockEngine.MAX_BYTES.toLong()) { "文件大小不在可识别范围内" }
        val label = run("ls -Zd $q", "读取 SELinux context", pkg, user).trim().split(Regex("\\s+")).firstOrNull { it.startsWith("u:object_r:") } ?: ""
        require(label.isEmpty() || Regex("[a-zA-Z0-9_:,.-]+").matches(label))
        val bytes = Base64.decode(run("base64 $q", "读取存档内容", pkg, user, sensitive = true), Base64.DEFAULT)
        require(bytes.size.toLong() == metadata[2].toLong()) { "读取期间文件发生变化，请重试" }
        val remoteHash = run("sha256sum $q", "读取完整性校验", pkg, user).trim().substringBefore(' ')
        require(UnlockEngine.sha(bytes) == remoteHash) { "读取校验不一致，请重试" }
        return SaveFile(path, bytes, metadata[0], metadata[1], label)
    }
    final override fun replace(file: SaveFile, bytes: ByteArray) {
        val target = checked(file.path)
        val (pkg, user) = context(file.path)
        require(bytes.size in 1..UnlockEngine.MAX_BYTES)
        require(Regex("\\d+:\\d+").matches(file.owner) && Regex("[0-7]{3,4}").matches(file.mode))
        require(file.context.isEmpty() || Regex("[a-zA-Z0-9_:,.-]+").matches(file.context))
        val temp = quote(file.path + ".unlock-" + UUID.randomUUID() + ".tmp")
        val label = if (file.context.isNotEmpty()) "chcon ${quote(file.context)} $temp && " else ""
        val encoded = Base64.encode(bytes, Base64.NO_WRAP)
        val command = "umask 077; test ! -e $temp || exit 1; trap ${quote("rm -f $temp")} EXIT; " +
            "base64 -d > $temp && chown ${file.owner} $temp && chmod ${file.mode} $temp && " + label +
            "test \"\$(sha256sum $temp | cut -d ' ' -f 1)\" = '${UnlockEngine.sha(bytes)}' && sync && mv -f $temp $target && sync"
        run(command, "事务文件替换", pkg, user, encoded, writing = true, sensitive = true)
    }
    final override fun list(root: String, source: SaveSource): List<String> {
        val (pkg, user) = context(root)
        require(root == SaveLayout.root(pkg, user)) { "扫描根目录无效" }
        val directory = "$root/${source.directory}"
        if (!exists(directory)) return emptyList()
        val paths = run("find ${quote(directory)} -maxdepth ${source.depth} -type f -name ${quote(source.glob)}", "列举存档位置", pkg, user)
            .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(paths.size <= 128 && paths.distinct().size == paths.size) { "候选文件过多或重复，暂不自动选择" }
        paths.forEach { path -> require(path.startsWith("$directory/") && !path.contains("..") && path.removePrefix("$directory/").split('/').size <= source.depth) { "扫描路径超出目标应用范围" } }
        return paths
    }
    final override fun packages(user: Int): String {
        require(user >= 0)
        ensureRoot(null, user)
        return run("pm list packages -3 --user $user", "搜索已安装游戏版本", user = user)
    }
    final override fun probePaths(packageName: String, user: Int): List<String> {
        val root = SaveLayout.root(packageName, user)
        val prefs = "$root/shared_prefs"
        val files = "$root/files"
        val paths = mutableListOf<String>()
        // No pipelines: find/grep errors cannot inherit head's successful exit.
        if (exists(prefs)) {
            val xmls = run("find ${quote(prefs)} -maxdepth 1 -type f -name '*.xml' -size -1048576c", "搜索 XML 位置", packageName, user)
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(xmls.size <= 128) { "候选 XML 过多，暂不自动搜索" }
            for (path in xmls) {
                require(path.startsWith("$prefs/") && !path.contains("..") && path.removePrefix("$prefs/").split('/').size == 1) { "搜索路径超出目标应用范围" }
                if (!pathPattern.matches(path)) continue
                val q = checked(path)
                val (request, output) = result("grep -l -E 'c[0-9]+_(unlock|skin[0-9]+)' $q", "检查 XML 搜索特征", packageName, user)
                if (output.exitCode == 1 && output.mergedOutput.isBlank()) continue
                successful(request, output)
                paths += path
                if (paths.size >= 13) break
            }
        }
        if (exists(files)) {
            val found = run("find ${quote(files)} -maxdepth 2 -type f \\( -name 'game.data' -o -name 'item_data*.data' \\)", "搜索数据存档位置", packageName, user)
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(found.size <= 128) { "候选数据文件过多，暂不自动搜索" }
            found.forEach { path -> require(path.startsWith("$files/") && !path.contains("..") && path.removePrefix("$files/").split('/').size <= 2) { "搜索路径超出目标应用范围" } }
            paths += found.filter(pathPattern::matches).take(13)
        }
        return paths.distinct()
    }
    final override fun version(packageName: String): String {
        require(SaveLayout.packagePattern.matches(packageName))
        val user = latestDiagnostic?.takeIf { it.packageName == packageName }?.user
        ensureRoot(packageName, user)
        return run("dumpsys package ${quote(packageName)}", "读取目标版本", packageName, user).lineSequence()
            .firstOrNull { it.trim().startsWith("versionName=") }?.trim()?.substringAfter("versionName=")?.take(80) ?: "未知版本"
    }
    final override fun backupPaths(packageName: String, user: Int): List<String> {
        val root = "${SaveLayout.root(packageName, user)}/files"
        if (!exists(root)) return emptyList()
        val paths = run("find ${quote(root)} -maxdepth 2 -type f -name '*.data'", "列举备份源文件", packageName, user)
            .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()
        require(paths.size < 128) { "本地文件过多，暂不创建备份" }
        paths.forEach { require(it.startsWith("$root/") && !it.contains("..") && backupPattern.matches(it)) { "备份路径超出目标应用范围" } }
        return paths
    }
}
