package com.example.soul_knight_save_editor.unlock

/** A new process in the same boot cannot prove that a dead service's child writer has ended. */
object ShizukuWriterFence {
    fun mayCheckIdle(previousInstance: String, currentInstance: String, previousBoot: Int, currentBoot: Int): Boolean =
        previousInstance == currentInstance || previousBoot >= 0 && currentBoot > previousBoot
}
