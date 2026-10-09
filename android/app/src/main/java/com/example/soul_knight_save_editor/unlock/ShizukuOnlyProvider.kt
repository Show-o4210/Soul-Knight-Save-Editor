package com.example.soul_knight_save_editor.unlock

import rikka.shizuku.ShizukuProvider

/** This access choice means Shizuku only. Sui must not silently provide another backend. */
class ShizukuOnlyProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }
}
