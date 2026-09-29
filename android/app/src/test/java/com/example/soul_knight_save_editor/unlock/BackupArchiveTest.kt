package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(path: String, bytes: ByteArray = byteArrayOf(0, 1, 2, -1)) =
        SaveFile("/data/user/0/com.test.game/$path", bytes, "10001:10001", "600", "u:object_r:app_data_file:s0")
    private fun payload(vararg files: SaveFile) = BackupPayload("com.test.game", 0, files.toList())
    private fun rewrite(source: File, transform: (String, ByteArray) -> ByteArray?): File {
        val output = temp.newFile()
        ZipFile(source).use { input -> ZipOutputStream(output.outputStream()).use { zip ->
            input.entries().asSequence().forEach { entry ->
                transform(entry.name, input.getInputStream(entry).use { it.readBytes() })?.let { bytes ->
                    zip.putNextEntry(ZipEntry(entry.name)); zip.write(bytes); zip.closeEntry()
                }
            }
        } }
        return output
    }
    @Test fun roundTripKeepsOpaqueLocalBytesAndMetadata() {
        val sources = listOf(file("files/game.data"), file("files/123/statistics.data"), file("shared_prefs/player.xml"))
        val info = BackupArchive.create(temp.newFolder(), BackupPayload("com.test.game", 0, sources))
        assertEquals(info, BackupArchive.verify(info.file))
        ZipFile(info.file).use { zip -> sources.forEach { source ->
            val relative = source.path.substringAfter("com.test.game/")
            assertArrayEquals(source.bytes, zip.getInputStream(zip.getEntry("payload/$relative")).use { it.readBytes() })
        } }
    }
    @Test fun xmlOnlyAndDataAliasAreSupported() {
        val source = file("shared_prefs/player.xml").copy(path = "/data/data/com.test.game/shared_prefs/player.xml")
        assertEquals(1, BackupArchive.create(temp.newFolder(), payload(source)).count)
    }
    @Test fun cloudFilesAndUnrelatedPathsAreRefused() {
        listOf("files/game.data.new", "files/session.json", "files/../secret.data", "files/a/b/game.data").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { BackupArchive.create(temp.newFolder(), payload(file(path))) }
        }
    }
    @Test fun foreignAccountSandboxIsRefused() {
        val source = file("files/game.data").copy(path = "/data/user/0/com.other.game/files/game.data")
        assertThrows(IllegalStateException::class.java) { BackupArchive.create(temp.newFolder(), payload(source)) }
    }
    @Test fun duplicateSourcesAreRefused() {
        val source = file("files/game.data")
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.create(temp.newFolder(), payload(source, source)) }
    }
    @Test fun tamperedBytesAreRefused() {
        val info = BackupArchive.create(temp.newFolder(), payload(file("files/game.data")))
        val corrupt = rewrite(info.file) { name, bytes -> if (name.startsWith("payload/")) byteArrayOf(9) else bytes }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.verify(corrupt) }
    }
    @Test fun missingPayloadAndManifestAreRefused() {
        val info = BackupArchive.create(temp.newFolder(), payload(file("files/game.data")))
        listOf("manifest.json", "payload/files/game.data").forEach { missing ->
            val corrupt = rewrite(info.file) { name, bytes -> if (name == missing) null else bytes }
            assertThrows(IllegalArgumentException::class.java) { BackupArchive.verify(corrupt) }
        }
    }
    @Test fun originalBackupsAreClearlyDistinguishedAndNamesNeverOverwrite() {
        val directory = temp.newFolder()
        val original = payload(file("shared_prefs/player.xml")).copy(kind = "pre-change")
        val first = BackupArchive.create(directory, original)
        val second = BackupArchive.create(directory, original)
        assertNotEquals(first.file.name, second.file.name)
        assertEquals("pre-change", BackupArchive.verify(first.file).kind)
        assertEquals(2, BackupArchive.list(directory).size)
    }
    @Test fun oversizedIndividualFileDoesNotBecomeCompletedBackup() {
        val directory = temp.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.create(directory, payload(file("files/game.data", ByteArray(UnlockEngine.MAX_BYTES + 1))))
        }
        assertTrue(BackupArchive.list(directory).isEmpty())
    }
}
