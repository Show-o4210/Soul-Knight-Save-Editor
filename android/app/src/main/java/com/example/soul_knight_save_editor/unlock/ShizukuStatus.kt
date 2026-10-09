package com.example.soul_knight_save_editor.unlock

/** Pure status policy: ADB identity never leads to an authorization prompt for private files. */
data class ShizukuStatus(val running: Boolean, val version: Int, val uid: Int?, val granted: Boolean,
    val permanentlyDenied: Boolean, val connected: Boolean) {
    val text: String get() = when {
        !running -> "Shizuku 未运行；请安装并使用 Root 方式启动"
        version < 13 -> "需要 Shizuku 13 或更新版本"
        uid != 0 -> ADB_MESSAGE
        !granted && permanentlyDenied -> "Shizuku 授权被永久拒绝，请在 Shizuku 中打开本应用授权"
        !granted -> "Shizuku Root 尚未授权，请点击请求授权"
        connected -> "Shizuku UserService 实际 UID=0；游戏目录尚未检查"
        else -> "Shizuku 服务 UID=0 已授权；UserService 和游戏目录尚未检查"
    }
    val canRequestPermission get() = running && version >= 13 && uid == 0 && !granted && !permanentlyDenied
    companion object {
        const val ADB_MESSAGE = "当前 Shizuku 以 ADB 模式运行，不能用于本应用的游戏私有存档访问，请使用 Root 启动 Shizuku。"
    }
}
