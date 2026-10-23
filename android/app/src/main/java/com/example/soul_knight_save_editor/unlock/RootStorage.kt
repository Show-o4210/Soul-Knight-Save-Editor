package com.example.soul_knight_save_editor.unlock

/** Native Root prefers ordinary su and retains the selected mount view per user operation. */
class RootStorage(executor: RootCommandExecutor = NativeRootCommandExecutor(), helperApk: String? = null) :
    ShellSaveAccess(executor, AccessBackend.NATIVE_ROOT, helperApk)
