package com.example.soul_knight_save_editor.unlock

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AssistantMode { QUICK, EXPERT }
enum class AssistantPage { WORKSPACE, BACKUPS, SETTINGS }
data class ModeChoices(val quickRoles: Boolean = false, val quickSkins: Boolean = false,
    val quickLevels: Boolean = false, val quickSkills: Boolean = false, val expert: UnlockSelection = UnlockSelection()) {
    fun selection(mode: AssistantMode, catalog: Catalog): UnlockSelection = when (mode) {
        AssistantMode.EXPERT -> expert
        AssistantMode.QUICK -> UnlockSelection(
            if (quickRoles) catalog.heroes.filter { it.unlocked != null }.map { it.index }.toSet() else emptySet(),
            if (quickSkins) catalog.heroes.flatMap { hero -> hero.skins.keys.map { SkinId(hero.index, it) } }.toSet() else emptySet(),
            quickLevels, quickSkills)
    }
}
data class AssistantState(
    val packageName: String = "com.ChillyRoom.DungeonShooter",
    val mode: AssistantMode? = null,
    val page: AssistantPage = AssistantPage.WORKSPACE,
    val settingsReturnPage: AssistantPage = AssistantPage.WORKSPACE,
    val rememberMode: Boolean = true,
    val personalUseAccepted: Boolean = false,
    val consentPromptVisible: Boolean = true,
    val busy: Boolean = false,
    val message: String = "准备好自己的本地存档后，开始扫描。",
    val snapshot: SaveSnapshot? = null,
    val accounts: List<String> = emptyList(),
    val catalog: Catalog? = null,
    val choices: ModeChoices = ModeChoices(),
    val preview: UnlockPatch? = null,
    val previewMode: AssistantMode? = null,
    val pending: List<String> = emptyList(),
    val backups: List<String> = emptyList(),
    val originalBackups: List<String> = emptyList(),
    val archives: List<BackupInfo> = emptyList(),
    val folder: String? = null,
    val autoOpenFolder: Boolean = true,
    val openFolderRequest: String? = null
)

fun AssistantState.navigate(target: AssistantPage): AssistantState = copy(
    page = target,
    settingsReturnPage = if (target == AssistantPage.SETTINGS && page != AssistantPage.SETTINGS) page else settingsReturnPage,
    preview = null,
    previewMode = null
)

class AssistantModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.getSharedPreferences("assistant-ui", 0)
    private val device = RootStorage()
    private val repository = SaveRepository(File(application.filesDir, "unlock-backups-v1"), device)
    private val archiveDirectory = File(application.filesDir, "export-backups-v1")
    var state by mutableStateOf(AssistantState(
        packageName = settings.getString("package", "com.ChillyRoom.DungeonShooter")!!,
        mode = settings.getString("mode", null)?.let { runCatching { AssistantMode.valueOf(it) }.getOrNull() },
        rememberMode = settings.getBoolean("rememberMode", true),
        personalUseAccepted = settings.getBoolean("personalUseAcceptedV1", false),
        consentPromptVisible = !settings.getBoolean("personalUseAcceptedV1", false),
        folder = settings.getString("folder", null), autoOpenFolder = settings.getBoolean("autoOpenFolder", true),
        pending = repository.pending(), backups = repository.completed(), originalBackups = repository.exportable()))
        private set
    init { viewModelScope.launch { state = state.copy(archives = withContext(Dispatchers.IO) { BackupArchive.list(archiveDirectory) }) } }

    private fun work(block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, preview = null, previewMode = null)
        viewModelScope.launch {
            try { block() } catch (error: Exception) {
                state = state.copy(message = error.message ?: "操作未完成，已保存的备份仍保留", preview = null, previewMode = null)
            } finally {
                withContext(Dispatchers.IO) { device.close() }
                val archives = withContext(Dispatchers.IO) { BackupArchive.list(archiveDirectory) }
                state = state.copy(busy = false, pending = repository.pending(), backups = repository.completed(), originalBackups = repository.exportable(), archives = archives)
            }
        }
    }
    fun chooseMode(mode: AssistantMode) {
        if (state.busy) return
        settings.edit().apply { if (state.rememberMode) putString("mode", mode.name) else remove("mode") }.apply()
        state = state.copy(mode = mode, page = AssistantPage.WORKSPACE, settingsReturnPage = AssistantPage.WORKSPACE,
            preview = null, previewMode = null)
    }
    fun rememberMode(value: Boolean) {
        settings.edit().putBoolean("rememberMode", value).apply {
            if (!value) remove("mode") else state.mode?.let { putString("mode", it.name) }
        }.apply()
        state = state.copy(rememberMode = value)
    }
    fun guide() { if (!state.busy) state = state.copy(mode = null, page = AssistantPage.WORKSPACE,
        settingsReturnPage = AssistantPage.WORKSPACE, preview = null, previewMode = null) }
    fun page(page: AssistantPage) {
        if (!state.busy) state = state.navigate(page)
    }
    fun notice(message: String) { state = state.copy(message = message) }
    fun packageName(value: String) {
        if (!state.busy) {
            settings.edit().putString("package", value).apply()
            state = state.copy(packageName = value, snapshot = null, catalog = null, preview = null, previewMode = null, accounts = emptyList(), choices = ModeChoices())
        }
    }
    fun scan() = work {
        require(state.pending.isEmpty()) { "请先在备份页恢复未完成的写回" }
        state = state.copy(snapshot = null, catalog = null, accounts = emptyList(), choices = ModeChoices(), message = "正在关闭游戏并识别本地存档…")
        val snapshot = withContext(Dispatchers.IO) { device.scan(state.packageName.trim(), android.os.Process.myUid() / 100000) }
        val accounts = withContext(Dispatchers.Default) { UnlockEngine.accounts(snapshot.prefs.bytes) }
        state = state.copy(snapshot = snapshot, accounts = accounts)
        if (accounts.size == 1) loadAccount(accounts.single()) else state = state.copy(message = "发现多个账号键，请在详细选择中确认账号。")
    }
    private suspend fun loadAccount(account: String) {
        val snapshot = requireNotNull(state.snapshot)
        val catalog = withContext(Dispatchers.Default) { UnlockEngine.catalog(snapshot.game?.bytes, snapshot.prefs.bytes, account) }
        state = state.copy(catalog = catalog, choices = state.choices.copy(expert = UnlockSelection(),
            quickLevels = state.choices.quickLevels && !catalog.xmlOnly, quickSkills = state.choices.quickSkills && !catalog.xmlOnly),
            preview = null, previewMode = null,
            message = if (catalog.xmlOnly) "已识别 XML，使用单文件模式；部分渠道采用这种方式。" else "已识别本地存档，game.data 与 XML 将同步处理。")
    }
    fun account(account: String) = work { loadAccount(account) }
    fun select(selection: UnlockSelection) { if (!state.busy) state = state.copy(choices = state.choices.copy(expert = selection), preview = null, previewMode = null) }
    fun quick(roles: Boolean = state.choices.quickRoles, skins: Boolean = state.choices.quickSkins,
        levels: Boolean = state.choices.quickLevels, skills: Boolean = state.choices.quickSkills) {
        if (!state.busy) state = state.copy(choices = state.choices.copy(quickRoles = roles, quickSkins = skins,
            quickLevels = levels, quickSkills = skills), preview = null, previewMode = null)
    }
    fun acceptPersonalUse() {
        if (settings.edit().putBoolean("personalUseAcceptedV1", true).commit())
            state = state.copy(personalUseAccepted = true, consentPromptVisible = false)
        else state = state.copy(message = "无法保存个人使用确认，请检查应用存储。")
    }
    fun declinePersonalUse() { state = state.copy(consentPromptVisible = false, preview = null, previewMode = null) }
    fun requestConsent() { state = state.copy(consentPromptVisible = true) }
    fun preview() = work {
        require(state.personalUseAccepted) { "请先确认仅用于自己的本地存档" }
        val snapshot = requireNotNull(state.snapshot)
        val catalog = requireNotNull(state.catalog)
        val mode = requireNotNull(state.mode)
        val selection = state.choices.selection(mode, catalog)
        val patch = withContext(Dispatchers.Default) { UnlockEngine.unlock(snapshot.game?.bytes, snapshot.prefs.bytes, catalog.account, selection) }
        if (patch.changes.isEmpty()) state = state.copy(message = "所选内容已经解锁，无需写入。")
        else state = state.copy(preview = patch, previewMode = mode)
    }
    fun dismiss() { if (!state.busy) state = state.copy(preview = null, previewMode = null) }
    fun apply() {
        if (!state.personalUseAccepted) { state = state.copy(preview = null, previewMode = null, message = "请先确认仅用于自己的本地存档"); return }
        val patch = state.preview ?: return
        val snapshot = state.snapshot ?: return
        if (state.previewMode != state.mode) { dismiss(); return }
        work {
            withContext(Dispatchers.IO) { repository.apply(snapshot, patch) }
            state = state.copy(snapshot = null, catalog = null, accounts = emptyList(), choices = ModeChoices(),
                message = "已备份并写回，校验通过。修改前原件可在备份页导出。请自行进入游戏检查。")
        }
    }
    fun restore(id: String, recovery: Boolean) = work {
        withContext(Dispatchers.IO) { if (recovery) repository.recover(id) else repository.restore(id) }
        state = state.copy(snapshot = null, catalog = null, accounts = emptyList(), message = "原文件已恢复并校验，请重新扫描。")
    }
    fun folder(uri: Uri) {
        runCatching { BackupDestination.remember(getApplication(), uri) }.onSuccess {
            settings.edit().putString("folder", uri.toString()).apply()
            state = state.copy(folder = uri.toString(), message = "备份目录已记住，可以开始备份。")
        }.onFailure { notice(it.message ?: "无法保存目录授权，请重新选择本地文件夹") }
    }
    fun autoOpen(value: Boolean) { settings.edit().putBoolean("autoOpenFolder", value).apply(); state = state.copy(autoOpenFolder = value) }
    fun consumedFolderRequest() { state = state.copy(openFolderRequest = null) }
    fun backupNow() = work {
        require(state.pending.isEmpty()) { "请先恢复未完成事务，再备份当前存档" }
        val tree = requireNotNull(state.folder) { "请先选择一个本地备份文件夹" }
        state = state.copy(message = "正在关闭游戏并备份本地 .data 与 XML；不会修改存档…")
        val info = withContext(Dispatchers.IO) { BackupArchive.create(archiveDirectory, device.backup(state.packageName.trim(), android.os.Process.myUid() / 100000)) }
        exportToFolder(info, tree)
    }
    fun exportOriginal(id: String) = work {
        val tree = requireNotNull(state.folder) { "请先选择本地备份文件夹" }
        val info = withContext(Dispatchers.IO) { BackupArchive.create(archiveDirectory, repository.originals(id)) }
        exportToFolder(info, tree)
    }
    fun exportArchive(info: BackupInfo) = work {
        val tree = requireNotNull(state.folder) { "请先选择本地备份文件夹" }
        require(info.file.canonicalFile.parentFile == archiveDirectory.canonicalFile)
        exportToFolder(info, tree)
    }
    private suspend fun exportToFolder(info: BackupInfo, tree: String) {
        withContext(Dispatchers.IO) { BackupDestination.write(getApplication(), Uri.parse(tree), info.file) }
        state = state.copy(message = "备份已导出并校验：${info.count} 个文件。${info.file.name}", openFolderRequest = if (state.autoOpenFolder) tree else null)
    }
}
