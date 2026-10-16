package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupCatalogTest {
    @get:Rule val temp = TemporaryFolder()
    private fun row(id: String, time: Long, pkg: String = "com.test.game") = BackupRecord(id, pkg, time, 1, "local-save")
    @Test fun numbersPersistAcrossRenamePruningAndRestart() {
        val file = File(temp.newFolder(), "labels")
        val first = row("archive:a", 10)
        val second = row("transaction:b", 20)
        val catalog = BackupCatalog(file)
        assertEquals(mapOf(first.key to "存档#0", second.key to "存档#1"), catalog.names(listOf(second, first)))
        catalog.rename(first.key, "通关之前")
        assertEquals("通关之前", BackupCatalog(file).names(listOf(first, second))[first.key])
        val third = row("archive:c", 30)
        assertEquals("存档#2", BackupCatalog(file).names(listOf(third))[third.key])
        assertEquals("存档#1", catalog.names(listOf(second))[second.key])
    }
    @Test fun incompleteAppendDoesNotLoseExistingNamesOrBlockFutureEntries() {
        val file = File(temp.newFolder(), "labels")
        val catalog = BackupCatalog(file)
        val first = row("a", 0)
        catalog.names(listOf(first))
        file.appendText("{\"key\":")
        val second = row("b", 1)
        assertEquals("存档#1", catalog.names(listOf(first, second))[second.key])
        assertEquals("存档#1", BackupCatalog(file).names(listOf(second))[second.key])
    }
    @Test fun limitIsPerGameCountsBothKindsAndKeepsNewest() {
        val old = row("archive:old", 0)
        val middle = row("transaction:middle", 1).copy(kind = "pre-change")
        val newest = row("archive:new", 2)
        val other = row("other", 0, "com.other.game")
        val rows = listOf(newest, other, old, middle)
        assertTrue(BackupCatalog.excess(rows, 0).isEmpty())
        assertEquals(listOf(old), BackupCatalog.excess(rows, 2))
        assertEquals(setOf(old, middle), BackupCatalog.excess(rows, 1).toSet())
        assertThrows(IllegalArgumentException::class.java) { BackupCatalog.excess(rows, -1) }
    }
    @Test fun invalidNamesNeverOverwritePreviousLabel() {
        val catalog = BackupCatalog(File(temp.newFolder(), "labels"))
        val row = row("a", 0)
        catalog.names(listOf(row))
        listOf("", "../escape", "line\nbreak", "x".repeat(81)).forEach {
            assertThrows(IllegalArgumentException::class.java) { catalog.rename(row.key, it) }
        }
        assertEquals("存档#0", catalog.names(listOf(row))[row.key])
    }
}
