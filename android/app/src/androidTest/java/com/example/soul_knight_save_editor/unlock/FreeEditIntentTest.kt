package com.example.soul_knight_save_editor.unlock

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Test

/** No applications are opened and no game or storage files are touched. */
class FreeEditIntentTest {
    @Suppress("DEPRECATION")
    @Test fun directoryAlwaysUsesChooserWithReadWriteAndPrefixGrants() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Atest/document/primary%3Atest%2Fsession")
        val child = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Atest/document/primary%3Atest%2Fsession%2Fgame.json")
        val chooser = FreeEditDocuments.chooser(uri, true, documents = listOf(child))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, target.type)
        assertEquals(uri, target.data)
        assertEquals(uri, target.clipData!!.getItemAt(0).uri)
        assertEquals(child, target.clipData!!.getItemAt(1).uri)
        assertEquals(child, chooser.clipData!!.getItemAt(1).uri)
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(target.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(target.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
    }
    @Suppress("DEPRECATION")
    @Test fun fileUsesEditChooserWithAlternativeViewAndExplicitRawMimeType() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Atest%2Ffile")
        val chooser = FreeEditDocuments.chooser(uri, false)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_EDIT, target.action)
        assertEquals("text/plain", target.type)
        assertTrue(target.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val alternate = chooser.getParcelableArrayExtra(Intent.EXTRA_ALTERNATE_INTENTS)!!.single() as Intent
        assertEquals(Intent.ACTION_VIEW, alternate.action)
        val raw = FreeEditDocuments.chooser(uri, false, "application/octet-stream").getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("application/octet-stream", raw.type)
    }
}
