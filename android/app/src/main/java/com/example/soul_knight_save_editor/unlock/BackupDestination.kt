package com.example.soul_knight_save_editor.unlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest

/** Uses only a user-granted local SAF directory. No storage permission or cloud provider access. */
object BackupDestination {
    private val localAuthorities = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")
    fun validate(uri: Uri) {
        require(uri.scheme == "content" && uri.authority in localAuthorities && DocumentsContract.isTreeUri(uri)) {
            "请选择系统文件管理器中的本地存储文件夹，不使用云盘目录"
        }
    }
    fun remember(context: Context, uri: Uri) {
        validate(uri)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    fun document(uri: Uri): Uri { validate(uri); return DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) }
    fun label(uri: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(uri).replace("primary:", "内部存储 / ") }.getOrDefault("已选择本地目录")
    fun write(context: Context, tree: Uri, source: File): Uri {
        validate(tree)
        BackupArchive.verify(source)
        val resolver = context.contentResolver
        require(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) { "备份文件夹授权已失效，请重新选择；应用内副本仍保留" }
        val created = DocumentsContract.createDocument(resolver, document(tree), "application/zip", source.name)
            ?: error("无法创建备份文件，请重新选择可写文件夹")
        try {
            resolver.openOutputStream(created, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("无法写入备份目录")
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            resolver.openInputStream(created)?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n; require(count <= source.length())
                    digest.update(buffer, 0, n)
                }
            } ?: error("无法复读导出的备份")
            require(count == source.length() && digest.digest().joinToString("") { "%02x".format(it) } == UnlockEngine.sha(source.readBytes())) { "导出复读校验失败，应用内副本仍保留" }
            return created
        } catch (error: Exception) {
            // Only this newly-created output is removed, never a pre-existing user document.
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }
    fun pickerIntent(initial: Uri? = null) = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 26 && initial != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, document(initial))
    }
}
