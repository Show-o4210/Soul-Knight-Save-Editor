package com.example.soul_knight_save_editor.unlock

/** A single, wide process snapshot. Recent-task cards do not establish that an app is running. */
internal object GameProcessProbe {
    const val COMMAND = "LC_ALL=C /system/bin/ps -A -w -o UID,PID,ARGS"
    const val TIMEOUT_MILLIS = 8_000L
    private const val USER_RANGE = 100_000
    private const val MAX_OUTPUT_CHARS = 2 * 1024 * 1024
    private val columns = Regex("\\s+")
    private val row = Regex("^([0-9]+)\\s+([0-9]+)\\s+(.+)$")

    fun isRunning(output: String, packageName: String, user: Int): Boolean {
        require(SaveLayout.packagePattern.matches(packageName) && user >= 0) { "包名或 Android 用户编号无效" }
        require(output.length <= MAX_OUTPUT_CHARS) { "进程列表超过检查范围" }
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).iterator()
        require(lines.hasNext() && lines.next().split(columns) == listOf("UID", "PID", "ARGS")) { "进程列表格式无法识别" }
        val pids = HashSet<Int>()
        var hasInit = false
        var found = false
        while (lines.hasNext()) {
            val match = row.matchEntire(lines.next()) ?: throw IllegalArgumentException("进程列表不完整")
            val uid = match.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("进程 UID 无法识别")
            val pid = match.groupValues[2].toIntOrNull() ?: throw IllegalArgumentException("进程 PID 无法识别")
            require(pid > 0 && pids.add(pid)) { "进程列表重复或无效" }
            // An all-process snapshot must include init; a restricted/self-only result is unsafe.
            if (pid == 1 && uid == 0) hasInit = true
            val name = match.groupValues[3].substringBefore(' ').substringBefore('\t')
            if (uid / USER_RANGE == user && (name == packageName || name.startsWith("$packageName:"))) found = true
        }
        require(hasInit) { "无法确认进程检查覆盖完整设备" }
        return found
    }
}
