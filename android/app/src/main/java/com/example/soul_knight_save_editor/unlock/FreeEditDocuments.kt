package com.example.soul_knight_save_editor.unlock

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

data class FreeDocumentRead(val documents: List<FreeDocument>, val ignored: List<String>)

object FreeEditDocuments {
    private class TooLarge : IllegalArgumentException("文件超过大小限制")
    fun createDirectory(context: Context, tree: Uri): Uri {
        BackupDestination.validate(tree)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) { "编辑目录授权已失效，请重新选择" }
        val name = "自由编辑-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(6)}"
        return DocumentsContract.createDocument(context.contentResolver, BackupDestination.document(tree), DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("无法创建自由编辑目录")
    }
    fun write(context: Context, directory: Uri, documents: List<FreeDocument>): List<Uri> = documents.map { document ->
        val uri = DocumentsContract.createDocument(context.contentResolver, directory,
            if (document.name.endsWith(".json")) "application/json" else "application/octet-stream", document.name)
            ?: error("无法创建 ${document.name}")
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(document.bytes) } ?: error("无法导出 ${document.name}")
        val check = context.contentResolver.openInputStream(uri)?.use { readBounded(it, UnlockEngine.MAX_BYTES) } ?: error("无法复读导出文件")
        require(UnlockEngine.sha(check) == document.hash) { "导出文件复读校验失败" }
        uri
    }
    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (output.size() + size > limit) throw TooLarge()
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
    private fun name(context: Context, uri: Uri): String = context.contentResolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()
    fun readUris(context: Context, uris: List<Uri>): FreeDocumentRead {
        require(uris.size <= 256) { "一次最多比较 256 个文件" }
        var total = 0L
        val ignored = mutableListOf<String>()
        val documents = uris.distinct().mapNotNull { uri ->
            val name = name(context, uri)
            val bytes = try { context.contentResolver.openInputStream(uri)?.use { readBounded(it, UnlockEngine.MAX_BYTES) }
                ?: error("无法读取 $name") } catch (_: TooLarge) { ignored += "$name（超过单文件大小限制）"; return@mapNotNull null }
            if (bytes.isEmpty()) { ignored += "$name（空文件）"; null }
            else {
                total += bytes.size
                require(total <= BackupArchive.MAX_TOTAL_BYTES) { "待比较文件总量超过 64 MiB" }
                FreeDocument(uri.toString(), name, bytes)
            }
        }
        return FreeDocumentRead(documents, ignored)
    }
    fun readDirectory(context: Context, directory: Uri): FreeDocumentRead {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory))
        val uris = mutableListOf<Uri>()
        val ignored = mutableListOf<String>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                require(uris.size + ignored.size < 256) { "目录中的文件过多，请改用“选择文件导入”" }
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) ignored += cursor.getString(1) + "（子目录）"
                else uris += DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0))
            }
        } ?: error("无法读取编辑目录，请重新授权或选择文件导入")
        val read = readUris(context, uris)
        return read.copy(ignored = ignored + read.ignored)
    }
    fun find(context: Context, directory: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name)
                return DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0))
        }
        error("原导出文件已被移动或改名，请打开目录查看，或选择文件导入")
    }
    fun children(context: Context, directory: Uri): List<Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory))
        val result = mutableListOf<Uri>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                require(result.size < 256) { "目录中的文件过多，请逐文件打开" }
                result += DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0))
            }
        } ?: error("无法读取编辑目录，请重新选择目录授权")
        return result
    }
    /** Always show a chooser, including when a default handler was previously selected. */
    fun chooser(uri: Uri, directory: Boolean, mime: String = "text/plain", documents: List<Uri> = emptyList()): Intent {
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val target = Intent(if (directory) Intent.ACTION_VIEW else Intent.ACTION_EDIT).apply {
            setDataAndType(uri, if (directory) DocumentsContract.Document.MIME_TYPE_DIR else mime)
            clipData = ClipData.newRawUri("自由编辑", uri)
            // Document IDs may encode slashes inside a single URI path segment. Directory
            // prefix grants alone therefore need not cover child document URIs. Grant the
            // exported children explicitly so the selected app can edit their contents.
            if (directory) documents.distinct().forEach { clipData!!.addItem(ClipData.Item(it)) }
            addFlags(grants or if (directory) Intent.FLAG_GRANT_PREFIX_URI_PERMISSION else 0)
        }
        return Intent.createChooser(target, if (directory) "选择打开目录的应用" else "选择编辑文件的应用").apply {
            if (!directory) putExtra(Intent.EXTRA_ALTERNATE_INTENTS, arrayOf(Intent(target).setAction(Intent.ACTION_VIEW)))
        }
    }
}
