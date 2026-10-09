package com.example.soul_knight_save_editor.unlock

/** A timeout does not release this gate. Only the actual worker's completion does. */
class ShizukuOperationGate {
    private val lock = Object()
    private var active = false
    private var stopping = false
    fun begin() = synchronized(lock) {
        check(!stopping) { "Shizuku 服务正在退出，请稍后重试" }
        check(!active) { "Shizuku 上一次操作尚未结束，请等待后恢复" }
        active = true
    }
    fun end() = synchronized(lock) { active = false; lock.notifyAll() }
    fun assertIdle() = synchronized(lock) {
        check(!active && !stopping) { "Shizuku 上一次操作尚未结束，当前不能恢复" }
    }
    fun stopAndAwait() = synchronized(lock) {
        stopping = true
        while (active) lock.wait()
    }
}
