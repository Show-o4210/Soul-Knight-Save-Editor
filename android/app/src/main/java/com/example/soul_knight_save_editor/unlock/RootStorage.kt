package com.example.soul_knight_save_editor.unlock

import android.util.Base64
import java.io.BufferedReader
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No networking, hooks, cloud files, global filesystem scans or caller-provided shell commands. */
class RootStorage : DeviceStorage, Closeable {
    private val packagePattern = SaveLayout.packagePattern
    private val pathPattern = SaveLayout.editablePath
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    private var session: Process? = null
    private var reader: BufferedReader? = null
    private var cancelled: (() -> Boolean)? = null
    private fun checkCancellation() { if (cancelled?.invoke() == true) throw DiscoveryCancelled() }
    override fun close() {
        runCatching { session?.outputStream?.close() }
        session?.destroy()
        session = null
        reader = null
    }

    /** One short-lived root shell per user operation, not a new permission request per file command. */
    @Synchronized private fun run(command: String, input: ByteArray? = null): String {
        checkCancellation()
        if (session == null) {
            session = try { ProcessBuilder("su").redirectErrorStream(true).start() } catch (_: Exception) { error("未找到 Root，请开启 Root 并授权后重试") }
            reader = session!!.inputStream.bufferedReader(Charsets.UTF_8)
        }
        val process = session!!
        val token = "SK_" + UUID.randomUUID().toString().replace("-", "")
        val begin = "${token}_BEGIN"
        val end = "${token}_END:"
        val delimiter = "${token}_INPUT"
        // Input is an internally generated Base64 payload, never caller-controlled shell text.
        val payload = input?.toString(Charsets.US_ASCII).orEmpty()
        require(payload.all { it.isLetterOrDigit() || it in "+/=\r\n" })
        val script = "printf '%s\\n' '$begin'\n(\n$command\n) <<'$delimiter'\n$payload\n$delimiter\n" +
            "_sk_result=\$?\nprintf '\\n%s:%s\\n' '${token}_END' \"\$_sk_result\"\n"
        val executor = Executors.newSingleThreadExecutor()
        val output = executor.submit<Pair<Int, String>> {
            var started = false
            val result = StringBuilder()
            while (true) {
                val line = reader!!.readLine() ?: error("Root 会话已关闭，请确认授权后重试")
                if (line == begin) { started = true; continue }
                if (!started) continue
                if (line.startsWith(end)) return@submit (line.removePrefix(end).toInt() to result.toString())
                require(result.length + line.length <= UnlockEngine.MAX_BYTES * 2) { "Root 输出超出限制" }
                result.append(line).append('\n')
            }
            @Suppress("UNREACHABLE_CODE") error("Root 会话无返回")
        }
        try {
            process.outputStream.write(script.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            var completed: Pair<Int, String>? = null
            while (completed == null) {
                checkCancellation()
                if (System.nanoTime() >= deadline) throw java.util.concurrent.TimeoutException()
                try { completed = output.get(200, TimeUnit.MILLISECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { /* Poll cancellation without killing a normal write. */ }
            }
            val (exit, out) = completed
            require(exit == 0) { "Root ${command.substringBefore(' ')} 操作未成功（退出码 $exit），请确认授权和设备状态" }
            return out
        } catch (error: DiscoveryCancelled) {
            close()
            throw error
        } catch (error: java.util.concurrent.TimeoutException) {
            close()
            throw IllegalStateException("Root 操作超时；如已开始写回，请先检查恢复提示", error)
        } catch (error: java.util.concurrent.ExecutionException) {
            close()
            throw IllegalStateException(error.cause?.message ?: "Root 会话中断", error)
        } finally {
            executor.shutdownNow()
        }
    }
    private val backupPathPattern = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+/files/(?:[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+\\.data$")
    private fun checked(path: String, backupOnly: Boolean = false): String {
        require((pathPattern.matches(path) || backupOnly && backupPathPattern.matches(path)) && !path.contains("..") && !path.endsWith(".new")) { "路径不在本地存档范围内" }
        val canonical = run("readlink -f ${quote(path)}").trim()
        // /data/data may be an alias for /data/user/0, but no other escape is accepted.
        require(canonical == path || canonical.replaceFirst("/data/user/0/", "/data/data/") == path.replaceFirst("/data/user/0/", "/data/data/")) { "存档路径指向范围外位置，已停止" }
        return quote(path)
    }
    override fun stop(packageName: String, user: Int) {
        require(packagePattern.matches(packageName) && user >= 0)
        require(run("id").contains("uid=0")) { "请授予 Root 权限" }
        run("am force-stop --user $user ${quote(packageName)}")
    }
    override fun read(path: String): SaveFile = readFile(path, false)
    private fun readFile(path: String, backupOnly: Boolean): SaveFile {
        val q = checked(path, backupOnly)
        val metadata = run("stat -c '%u:%g %a %s' $q").trim().split(Regex("\\s+"))
        require(metadata.size == 3 && Regex("\\d+:\\d+").matches(metadata[0]) && Regex("[0-7]{3,4}").matches(metadata[1])) { "文件权限无法识别" }
        require(metadata[2].toLong() in 1..UnlockEngine.MAX_BYTES.toLong()) { "文件大小不在可识别范围内" }
        val context = run("ls -Zd $q").trim().split(Regex("\\s+")).firstOrNull { it.startsWith("u:object_r:") } ?: ""
        require(context.isEmpty() || Regex("[a-zA-Z0-9_:,.-]+").matches(context))
        val bytes = Base64.decode(run("base64 $q"), Base64.DEFAULT)
        require(bytes.size.toLong() == metadata[2].toLong()) { "读取期间文件发生变化，请重试" }
        val remoteHash = run("sha256sum $q").trim().substringBefore(' ')
        require(UnlockEngine.sha(bytes) == remoteHash) { "读取校验不一致，请重试" }
        return SaveFile(path, bytes, metadata[0], metadata[1], context)
    }
    override fun replace(file: SaveFile, bytes: ByteArray) {
        val target = checked(file.path)
        require(bytes.size in 1..UnlockEngine.MAX_BYTES)
        require(Regex("\\d+:\\d+").matches(file.owner) && Regex("[0-7]{3,4}").matches(file.mode))
        require(file.context.isEmpty() || Regex("[a-zA-Z0-9_:,.-]+").matches(file.context))
        val temp = quote(file.path + ".unlock-" + UUID.randomUUID() + ".tmp")
        val label = if (file.context.isNotEmpty()) "chcon ${quote(file.context)} $temp && " else ""
        val encoded = Base64.encode(bytes, Base64.NO_WRAP)
        // Each temp path is generated here, in the target directory; trap never targets user files.
        val command = "umask 077; test ! -e $temp || exit 1; trap ${quote("rm -f $temp")} EXIT; " +
            "base64 -d > $temp && chown ${file.owner} $temp && chmod ${file.mode} $temp && " + label +
            "test \"\$(sha256sum $temp | cut -d ' ' -f 1)\" = '${UnlockEngine.sha(bytes)}' && " +
            "sync && mv -f $temp $target && sync"
        run(command, encoded)
    }

    private fun scanAccess(): SaveScanAccess = object : SaveScanAccess {
        override fun stop(packageName: String, user: Int) = this@RootStorage.stop(packageName, user)
        override fun exists(directory: String) = run("test -d ${quote(directory)} && echo yes").trim() == "yes"
        override fun read(path: String) = this@RootStorage.read(path)
        override fun list(root: String, source: SaveSource): List<String> {
            val q = quote("$root/${source.directory}")
            return run("if test -d $q; then find $q -maxdepth ${source.depth} -type f -name ${quote(source.glob)}; fi")
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        }
    }
    fun scan(packageName: String, user: Int): SaveSnapshot = SaveScanner.scan(scanAccess(), packageName, user)
    fun refresh(target: PinnedSaveTarget): SaveSnapshot = SaveScanner.refresh(scanAccess(), target)

    /** Read-only fallback across installed third-party packages of this Android user. Never force-stops candidates. */
    fun discover(user: Int, preferred: String, ownPackage: String, isCancelled: () -> Boolean,
        progress: (DiscoveryProgress) -> Unit, found: (GameCandidate) -> Unit): DiscoveryProgress {
        require(user >= 0)
        cancelled = isCancelled
        try {
            require(run("id").contains("uid=0")) { "请授予 Root 权限后再搜索" }
            val packages = SaveDiscovery.packages(run("pm list packages -3 --user $user"), preferred, ownPackage)
            require(packages.size <= SaveDiscovery.MAX_PACKAGES) { "已安装应用超过搜索上限，请手动填写目标包名" }
            var state = DiscoveryProgress(total = packages.size)
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
            progress(state)
            for (pkg in packages) {
                checkCancellation()
                if (System.nanoTime() >= deadline) return state
                try {
                    val root = "/data/user/$user/$pkg"
                    val prefs = quote("$root/shared_prefs")
                    val files = quote("$root/files")
                    // grep only prefilters small XML files; a strict parser verifies the actual keys below.
                    val paths = run("if test -d $prefs; then find $prefs -maxdepth 1 -type f -name '*.xml' -size -1048576c -exec grep -l -E 'c[0-9]+_(unlock|skin[0-9]+)' {} \\; | head -n 13; fi; " +
                        "if test -d $files; then find $files -maxdepth 2 -type f \\( -name 'game.data' -o -name 'item_data*.data' \\) | head -n 13; fi")
                        .lineSequence().map(String::trim).filter { pathPattern.matches(it) && !it.contains("..") && it.startsWith("$root/") }.distinct().toList()
                    val evidence = linkedSetOf<String>()
                    var incomplete = paths.size > SaveDiscovery.MAX_PROBES
                    for (path in paths.take(SaveDiscovery.MAX_PROBES)) {
                        checkCancellation()
                        if (System.nanoTime() >= deadline) { incomplete = true; break }
                        try {
                            val name = path.substringAfterLast('/')
                            if (name.endsWith(".xml") && "XML 角色／皮肤特征" in evidence || name.startsWith("item_data") && "物品分片结构" in evidence) continue
                            val bytes = read(path).bytes
                            when {
                                name.endsWith(".xml") && SaveDiscovery.xml(bytes) -> evidence += "XML 角色／皮肤特征"
                                name == "game.data" && SaveDiscovery.game(bytes) -> evidence += "game 角色／皮肤结构"
                                EditEngine.itemAccount(path) != null && SaveDiscovery.item(bytes) -> evidence += "物品分片结构"
                            }
                        } catch (error: DiscoveryCancelled) { throw error }
                        catch (_: Exception) { incomplete = true }
                    }
                    if (evidence.isNotEmpty()) {
                        val version = runCatching { run("dumpsys package ${quote(pkg)} | grep -m 1 'versionName='").trim().substringAfter("versionName=", "未知版本").take(80) }.getOrDefault("未知版本")
                        checkCancellation()
                        found(GameCandidate(pkg, version = version, evidence = evidence.toList()))
                    }
                    if (incomplete) state = state.copy(skipped = state.skipped + 1)
                } catch (error: DiscoveryCancelled) { throw error }
                catch (_: Exception) { state = state.copy(skipped = state.skipped + 1) }
                state = state.copy(checked = state.checked + 1)
                progress(state)
            }
            return state
        } finally { cancelled = null }
    }

    /** Local .data files are copied as opaque bytes; this does not grant write access to them. */
    fun backup(packageName: String, user: Int): BackupPayload {
        val snapshot = scan(packageName, user)
        val root = "/data/user/$user/$packageName/files"
        val paths = run("if test -d ${quote(root)}; then find ${quote(root)} -maxdepth 2 -type f -name '*.data'; fi")
            .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()
        require(paths.size < 128) { "本地文件过多，暂不创建备份" }
        val files = listOfNotNull(snapshot.prefs).toMutableList()
        var total = files.sumOf { it.bytes.size.toLong() }
        paths.forEach { path ->
            val file = readFile(path, true)
            total += file.bytes.size
            require(total <= BackupArchive.MAX_TOTAL_BYTES) { "本地存档超过当前备份大小限制" }
            files += file
        }
        // Detect changes across the capture, not only corruption during one read.
        files.forEach { original -> require(readFile(original.path, true).bytes.contentEquals(original.bytes)) { "备份期间存档发生变化，请保持游戏关闭后重试" } }
        return BackupPayload(packageName, user, files)
    }
}
