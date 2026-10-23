package com.example.soul_knight_save_editor.unlock

/** Native Root remains the default and retains one su shell per user operation. */
class RootStorage(executor: RootCommandExecutor = ProcessRootCommandExecutor(listOf("su")), helperApk: String? = null) :
    ShellSaveAccess(executor, AccessBackend.NATIVE_ROOT, helperApk)
