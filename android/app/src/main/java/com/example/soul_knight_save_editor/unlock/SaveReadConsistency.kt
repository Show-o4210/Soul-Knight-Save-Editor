package com.example.soul_knight_save_editor.unlock

class SaveSnapshotChanged(message: String) : IllegalArgumentException(message)

/** Recheck the complete snapshot after all shards have been captured. */
object SaveReadConsistency {
    fun same(expected: SaveFile, actual: SaveFile) = expected.path == actual.path &&
        expected.bytes.contentEquals(actual.bytes) && expected.owner == actual.owner &&
        expected.mode == actual.mode && expected.context == actual.context

    fun verify(packageName: String, user: Int, files: List<SaveFile>,
        stopped: (String, Int) -> Unit, read: (String) -> SaveFile) {
        stopped(packageName, user)
        files.forEach { original ->
            if (!same(original, read(original.path))) throw SaveSnapshotChanged("读取期间存档发生变化，请保持游戏关闭后重新读取")
        }
        stopped(packageName, user)
    }
}
