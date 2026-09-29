package com.example.soul_knight_save_editor.unlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[AssistantModel::class.java]
        val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.data?.let(model::folder)
        }
        // Browse via the system directory UI; returning must never change the destination or import data.
        val folderBrowser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
        fun chooseFolder() {
            runCatching {
                folderPicker.launch(BackupDestination.pickerIntent(model.state.folder?.let(android.net.Uri::parse)))
            }.onFailure { model.notice("无法打开系统目录选择器，请确认设备已启用文件管理器。") }
        }
        fun openFolder() {
            val uri = model.state.folder?.let(android.net.Uri::parse)
            if (uri == null) { chooseFolder(); return }
            // Android 9 DocumentsUI can crash on ACTION_VIEW of a tree-document URI.
            // OPEN_DOCUMENT_TREE + INITIAL_URI uses its supported navigation path instead.
            runCatching { folderBrowser.launch(BackupDestination.pickerIntent(uri)) }
                .onFailure { model.notice("备份仍保留。无法自动打开文件夹，请在系统文件管理器中打开：" + BackupDestination.label(uri)) }
        }
        setContent {
            val state = model.state
            LaunchedEffect(state.openFolderRequest) {
                if (state.openFolderRequest != null) {
                    model.consumedFolderRequest()
                    openFolder()
                }
            }
            AssistantApp(model, ::chooseFolder, ::openFolder)
        }
    }
}
