package com.example.soul_knight_save_editor.unlock

import android.util.Base64
import java.util.UUID

/** Fixed privileged file operations, shared by both backends; no public command execution. */
open class ShellSaveAccess(private val executor: RootCommandExecutor, backend: AccessBackend,
    private val helperApk: String? = null) : BaseSaveAccess(backend) {
    private val pathPattern = SaveLayout.restorablePath
    private val backupPattern = SaveLayout.backupDataPath
    private val directoryPattern = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+(?:/(?:files(?:/[a-zA-Z0-9_.-]+)?|shared_prefs))?$")
    private var identity = "未检查"
    private var ready = false
    private enum class DigestTool { SYSTEM, HELPER }
    private var digestTool: DigestTool? = null
    private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    final override var latestDiagnostic: AccessDiagnostic? = null
        private set
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
    override fun close() { ready = false; digestTool = null; executor.close() }

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
        if (unsupportedStatLinkOption(output)) return "旧版 stat 不支持 -L 参数"
        // Only fixed errno/command failure text is shown. Even short account IDs can occur in an operand.
        val reasons = listOf("Permission denied", "Operation not permitted", "No such file or directory", "Not a directory",
            "Input/output error", "Read-only file system", "No space left on device", "Invalid argument", "invalid option",
            "inaccessible or not found", "not found", "Broken pipe", "Operation timed out", "Too many open files")
        val safe = output.take(4096).lineSequence().mapNotNull { line -> reasons.firstOrNull { line.contains(it, true) } }
            .distinct().take(16).joinToString("\n")
        return if (safe.isNotBlank()) safe.take(1536) else if (output.isBlank()) "无合并错误输出" else "不可安全显示的合并输出已省略"
    }

    private fun result(command: String, stage: String, packageName: String? = null, user: Int? = null,
        input: ByteArray? = null, writing: Boolean = false, sensitive: Boolean = false,
        timeoutMillis: Long? = null): Pair<RootCommandRequest, RootCommandResult> {
        checkCancellation()
        val request = RootCommandRequest(command, input, stage, packageName, user, writing, sensitive,
            remainingReadMillis(timeoutMillis ?: 45_000)) { cancellation?.invoke() == true }
        val output = try { executor.execute(request) } catch (error: RootExecutionFailure) {
            if (error.kind in setOf(AccessFailureKind.IO, AccessFailureKind.SESSION_DISCONNECTED, AccessFailureKind.TIMEOUT)) {
                ready = false
                digestTool = null
            }
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
    private fun canonicalPath(path: String, pkg: String, user: Int, stage: String = "规范化路径检查"): String {
        val actual = run("readlink -f ${quote(path)}", stage, pkg, user).trim()
        require(actual == path || actual.replaceFirst("/data/user/0/", "/data/data/") == path.replaceFirst("/data/user/0/", "/data/data/")) { "存档路径指向范围外位置，已停止" }
        return actual
    }
    private fun canonical(path: String, pkg: String, user: Int): String {
        canonicalPath(path, pkg, user)
        return quote(path)
    }
    private fun checked(path: String, backupOnly: Boolean = false): String {
        require((pathPattern.matches(path) || backupOnly && backupPattern.matches(path)) && !path.contains("..")) { "路径不在本地存档范围内" }
        val (pkg, user) = context(path)
        ensureRoot(pkg, user)
        return canonical(path, pkg, user)
    }
    final override fun stop(packageName: String, user: Int) {
        SaveLayout.root(packageName, user)
        ensureRoot(packageName, user)
        val (request, output) = result(GameProcessProbe.COMMAND, "检查目标游戏运行状态", packageName, user,
            sensitive = true, timeoutMillis = GameProcessProbe.TIMEOUT_MILLIS)
        if (output.exitCode != 0) {
            val detail = diagnostic(request, output.exitCode, output.elapsedMillis, output.mergedOutput)
            throw AccessFailure(AccessFailureKind.PROCESS_CHECK_FAILED, "无法确认游戏已退出，已停止存档操作；请检查访问通道后重试", detail)
        }
        val running = try { GameProcessProbe.isRunning(output.mergedOutput, packageName, user) }
            catch (error: IllegalArgumentException) {
                val detail = diagnostic(request, output.exitCode, output.elapsedMillis, output.mergedOutput)
                throw AccessFailure(AccessFailureKind.PROCESS_CHECK_FAILED, "无法完整检查游戏进程，已停止存档操作；请检查访问通道后重试", detail, cause = error)
            }
        val detail = diagnostic(request, output.exitCode, output.elapsedMillis, output.mergedOutput)
        if (running) throw AccessFailure(AccessFailureKind.GAME_RUNNING, "检测到所选游戏仍在运行，请保存并彻底退出游戏后重试", detail)
    }
    final override fun exists(directory: String): Boolean {
        require(directoryPattern.matches(directory) && !directory.contains("..")) { "目录不在本地存档范围内" }
        val (pkg, user) = context(directory)
        ensureRoot(pkg, user)
        // C-locale stat reports platform errno; only exact ENOENT/ENOTDIR diagnostics mean absent.
        val (request, output) = result("LC_ALL=C stat -L -c '%F' ${quote(directory)}", "目标目录检查", pkg, user)
        if (output.exitCode in 1..2 && unsupportedStatLinkOption(output.mergedOutput)) {
            diagnostic(request, output.exitCode, output.elapsedMillis, output.mergedOutput)
            return legacyDirectoryExists(directory, pkg, user)
        }
        val type = directoryType(request, output, directory) ?: return false
        if (type != "directory") return false
        canonical(directory, pkg, user)
        return true
    }
    private fun legacyDirectoryExists(directory: String, pkg: String, user: Int): Boolean {
        val stage = "兼容目录检查（旧版 stat 不支持 -L）"
        val (request, output) = result("LC_ALL=C stat -c '%F' ${quote(directory)}", stage, pkg, user)
        val type = directoryType(request, output, directory) ?: return false
        if (type != "directory" && type != "symbolic link") return false
        // Old stat does not follow a final symlink. Resolve it, enforce the same boundary as
        // the primary probe, and inspect the resolved path instead of trusting the link type.
        val actual = canonicalPath(directory, pkg, user, "兼容路径检查（旧版 stat 不支持 -L）")
        if (type == "symbolic link") {
            val (resolvedRequest, resolvedOutput) = result("LC_ALL=C stat -c '%F' ${quote(actual)}", stage, pkg, user)
            return directoryType(resolvedRequest, resolvedOutput, actual) == "directory"
        }
        return true
    }
    private fun directoryType(request: RootCommandRequest, output: RootCommandResult, directory: String): String? {
        if (output.exitCode != 0 && absentDirectory(output, directory)) {
            diagnostic(request, output.exitCode, output.elapsedMillis, "目标目录明确不存在或路径组件不是目录")
            return null
        }
        val type = successful(request, output).trim()
        require(type in setOf("directory", "regular file", "regular empty file", "symbolic link", "fifo", "socket", "character special file", "block special file")) { "无法识别目标目录类型" }
        return type
    }
    private fun unsupportedStatLinkOption(output: String): Boolean {
        if (output.length > 16 * 1024) return false
        val lines = output.trim().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val errors = setOf("stat: Unknown option L", "stat: Unknown option 'L'", "stat: invalid option -- 'L'",
            "stat: invalid option -- L", "stat: unrecognized option '-L'")
        val help = setOf("See stat --help", "See stat --help for more information.",
            "Try 'stat --help' for more information.", "usage: stat [-f] [-c FORMAT] FILE...",
            "Display status of files or filesystems.", "-f display filesystem status instead of file status",
            "-c Output specified FORMAT string instead of default",
            "The valid format escape sequences for files:", "The valid format escape sequences for filesystems:")
        // Android 7 toybox emits its entire usage text before the final error. Only accept
        // that known help structure plus a single exact L-option error, never operand errors.
        return lines.count { it in errors } == 1 && lines.all { line ->
            line in errors || line in help || Regex("^%[a-zA-Z]\\s+[^\\r\\n]+$").matches(line)
        }
    }
    private fun absentDirectory(output: RootCommandResult, path: String): Boolean {
        if (output.exitCode != 1) return false
        val line = output.mergedOutput.trim()
        val suffix = listOf("No such file or directory", "Not a directory").firstOrNull { line.endsWith(": $it") } ?: return false
        val prefix = line.removeSuffix(": $suffix")
        return prefix in setOf("stat: $path", "stat: '$path'", "stat: cannot stat '$path'", "stat: cannot statx '$path'")
    }
    private fun missingSystemDigest(output: RootCommandResult): Boolean = output.exitCode == 127 &&
        Regex("^(?:(?:/system/bin/)?sh: (?:<stdin>\\[[0-9]+\\]: )?)?sha256sum: (?:not found|inaccessible or not found)$")
            .matches(output.mergedOutput.trim())
    private fun helperCommand(): String {
        val apk = requireNotNull(helperApk) { "设备缺少 sha256sum，且兼容校验入口不可用" }
        require(apk.startsWith('/') && apk.endsWith(".apk") && apk.length <= 4096 &&
            apk.none { it == '\u0000' || it == '\n' || it == '\r' }) { "兼容校验入口无效" }
        return "CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin com.example.soul_knight_save_editor.unlock.LegacySha256"
    }
    private fun digestCommand(tool: DigestTool, quotedPath: String): String = when (tool) {
        DigestTool.SYSTEM -> "sha256sum $quotedPath"
        DigestTool.HELPER -> "${helperCommand()} $quotedPath"
    }
    private fun digestValue(output: String): String {
        val value = output.trim().substringBefore(' ')
        require(Regex("[a-f0-9]{64}").matches(value)) { "SHA-256 校验输出无法识别" }
        return value
    }
    private fun enableHelperDigest(pkg: String, user: Int): DigestTool {
        val (request, output) = result("${helperCommand()} --self-test", "旧版 SHA-256 兼容自检", pkg, user)
        require(successful(request, output).trim() == emptySha256) { "兼容 SHA-256 自检失败，已停止存档操作" }
        return DigestTool.HELPER.also { digestTool = it }
    }
    private fun readDigest(path: String, pkg: String, user: Int): String {
        if (digestTool == DigestTool.HELPER)
            return digestValue(run(digestCommand(DigestTool.HELPER, quote(path)), "兼容读取完整性校验", pkg, user))
        val (request, output) = result(digestCommand(DigestTool.SYSTEM, quote(path)), "读取完整性校验", pkg, user)
        if (missingSystemDigest(output) && helperApk != null) {
            diagnostic(request, output.exitCode, output.elapsedMillis, output.mergedOutput)
            val tool = enableHelperDigest(pkg, user)
            return digestValue(run(digestCommand(tool, quote(path)), "兼容读取完整性校验", pkg, user))
        }
        return digestValue(successful(request, output))
    }
    private fun writeDigestTool(pkg: String, user: Int): DigestTool {
        digestTool?.let { return it }
        // Establish support before starting a write. Never replay a transaction to try another tool.
        val (request, output) = result("sha256sum /dev/null", "写回 SHA-256 只读自检", pkg, user)
        if (missingSystemDigest(output) && helperApk != null) {
            diagnostic(request, output.exitCode, output.elapsedMillis, output.mergedOutput)
            return enableHelperDigest(pkg, user)
        }
        require(digestValue(successful(request, output)) == emptySha256) { "系统 SHA-256 自检失败，已停止写回" }
        return DigestTool.SYSTEM.also { digestTool = it }
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
        if (bytes.size.toLong() != metadata[2].toLong())
            throw AccessFailure(AccessFailureKind.SOURCE_CHANGED, "读取期间存档发生变化，请保持游戏关闭后重新读取", latestDiagnostic)
        val remoteHash = readDigest(path, pkg, user)
        if (UnlockEngine.sha(bytes) != remoteHash)
            throw AccessFailure(AccessFailureKind.SOURCE_CHANGED, "读取期间存档发生变化，请保持游戏关闭后重新读取", latestDiagnostic)
        return SaveFile(path, bytes, metadata[0], metadata[1], label)
    }
    final override fun replace(file: SaveFile, bytes: ByteArray) {
        val target = checked(file.path)
        val (pkg, user) = context(file.path)
        require(bytes.size in 1..UnlockEngine.MAX_BYTES)
        require(Regex("\\d+:\\d+").matches(file.owner) && Regex("[0-7]{3,4}").matches(file.mode))
        require(file.context.isEmpty() || Regex("[a-zA-Z0-9_:,.-]+").matches(file.context))
        val hashTool = writeDigestTool(pkg, user)
        val temp = quote(file.path + ".unlock-" + UUID.randomUUID() + ".tmp")
        val label = if (file.context.isNotEmpty()) "chcon ${quote(file.context)} $temp && " else ""
        val encoded = Base64.encode(bytes, Base64.NO_WRAP)
        val command = "umask 077; test ! -e $temp || exit 1; trap ${quote("rm -f $temp")} EXIT; " +
            "base64 -d > $temp && chown ${file.owner} $temp && chmod ${file.mode} $temp && " + label +
            "_sk_hash=\$(${digestCommand(hashTool, temp)}) && " +
            "test \"\${_sk_hash%% *}\" = '${UnlockEngine.sha(bytes)}' && sync && mv -f $temp $target && sync"
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
            // Older Android toybox find rejects the byte suffix in -size ...c. Check bytes
            // with the same stat primitive used by verified reads, before inspecting content.
            val xmls = run("find ${quote(prefs)} -maxdepth 1 -type f -name '*.xml'", "搜索 XML 位置", packageName, user)
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(xmls.size <= 128) { "候选 XML 过多，暂不自动搜索" }
            for (path in xmls) {
                require(path.startsWith("$prefs/") && !path.contains("..") && path.removePrefix("$prefs/").split('/').size == 1) { "搜索路径超出目标应用范围" }
                if (!pathPattern.matches(path)) continue
                val q = checked(path)
                val size = run("stat -c '%s' $q", "检查搜索 XML 大小", packageName, user).trim().toLongOrNull()
                require(size != null && size >= 0) { "搜索 XML 文件大小无法识别" }
                if (size == 0L || size >= SaveDiscovery.MAX_XML_BYTES) continue
                val (request, output) = result("grep -l -E 'c[0-9]+_(unlock|skin[0-9]+)' $q", "检查 XML 搜索特征", packageName, user)
                if (output.exitCode == 1 && output.mergedOutput.isBlank()) continue
                successful(request, output)
                paths += path
                if (paths.size >= 13) break
            }
        }
        if (exists(files)) {
            val found = run("find ${quote(files)} -maxdepth 2 -type f \\( -name 'game.data' -o -name 'item_data*.data*' \\)", "搜索数据存档位置", packageName, user)
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(found.size <= 128) { "候选数据文件过多，暂不自动搜索" }
            found.forEach { path -> require(path.startsWith("$files/") && !path.contains("..") && path.removePrefix("$files/").split('/').size <= 2) { "搜索路径超出目标应用范围" } }
            paths += found.filter(SaveLayout.editablePath::matches).take(13)
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
        val paths = run("find ${quote(root)} -maxdepth 2 -type f \\( -name '*.data' -o -name '*.data.new' -o -name '*.data_temp' -o -name '*.data.new_temp' \\)", "列举备份源文件", packageName, user)
            .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()
        require(paths.size < 128) { "本地文件过多，暂不创建备份" }
        paths.forEach { require(it.startsWith("$root/") && !it.contains("..") && backupPattern.matches(it)) { "备份路径超出目标应用范围" } }
        return paths
    }
}
