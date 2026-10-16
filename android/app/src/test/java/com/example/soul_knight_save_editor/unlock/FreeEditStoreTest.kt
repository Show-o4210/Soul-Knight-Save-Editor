package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FreeEditStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun session(): FreeEditSession {
        val source = SaveFile("/data/user/10/com.test.game/files/game.data", UnlockEngine.xor("""{"unlisted":true}""".toByteArray()), "1010100:1010100", "600", "label")
        return FreeEditEngine.create(SaveSnapshot("com.test.game", 10, null, null, listOf(source)), setOf(source.path), null, "content://export/session").first
    }
    @Test fun immutableSourceBindingAndOriginalsSurviveRestart() {
        val directory = temp.newFolder()
        val session = session()
        FreeEditStore(directory).save(session)
        val loaded = FreeEditStore(directory).load(session.id)
        assertEquals(session.id, loaded.id)
        assertEquals(session.directory, loaded.directory)
        assertEquals(session.entries, loaded.entries)
        assertEquals(10, loaded.snapshot.user)
        assertArrayEquals(session.snapshot.files.single().bytes, loaded.snapshot.files.single().bytes)
        assertEquals(session.snapshot.files.single().owner, loaded.snapshot.files.single().owner)
    }
    @Test fun corruptOriginalAndOutOfScopeRecordAreRefused() {
        val directory = temp.newFolder()
        val session = session()
        val store = FreeEditStore(directory)
        store.save(session)
        File(directory, "${session.id}/0.original").writeText("corrupted")
        assertThrows(IllegalArgumentException::class.java) { store.load(session.id) }
        assertThrows(IllegalArgumentException::class.java) { store.load("../../outside") }
        val foreign = session.snapshot.files.single().copy(path = "/data/user/10/com.other.game/files/game.data")
        assertThrows(IllegalStateException::class.java) { FreeEditStore(temp.newFolder()).save(session.copy(snapshot = session.snapshot.copy(items = listOf(foreign)))) }
    }
}
