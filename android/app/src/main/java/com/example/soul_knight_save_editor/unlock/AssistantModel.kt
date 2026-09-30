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
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class AssistantMode { QUICK, EXPERT }
enum class AssistantPage { WORKSPACE, BACKUPS, SETTINGS }
data class ModeChoices(val quickRoles: Boolean = false, val quickSkins: Boolean = false,
    val quickLevels: Boolean = false, val quickSkills: Boolean = false, val quickPets: Boolean = false,
    val quickWeapons: Boolean = false,
    val expert: UnlockSelection = UnlockSelection()) {
    fun weapons(mode: AssistantMode) = mode == AssistantMode.QUICK && quickWeapons
    fun selection(mode: AssistantMode, catalog: Catalog): UnlockSelection = when (mode) {
        AssistantMode.EXPERT -> expert
        AssistantMode.QUICK -> UnlockSelection(
            if (quickRoles) catalog.heroes.filter { it.unlocked != null }.map { it.index }.toSet() else emptySet(),
            if (quickSkins) catalog.heroes.flatMap { hero -> hero.skins.keys.map { SkinId(hero.index, it) } }.toSet() else emptySet(),
            quickLevels, quickSkills, quickPets)
    }
}
/** A completed transaction has only successes; a rejected/rolled-back transaction has only a failure reason. */
data class ApplyOutcome(
    val succeeded: Boolean,
    val packageName: String,
    val fileCount: Int,
    val successful: List<String>,
    val failed: List<String>
)
data class AssistantState(
    val packageName: String = "com.ChillyRoom.DungeonShooter",
    val mode: AssistantMode? = null,
    val page: AssistantPage = AssistantPage.WORKSPACE,
    val workspaceTab: String = WorkspaceTabs.saves.id,
    val settingsReturnPage: AssistantPage = AssistantPage.WORKSPACE,
    val rememberMode: Boolean = true,
    val personalUseAccepted: Boolean = false,
    val consentPromptVisible: Boolean = true,
    val busy: Boolean = false,
    val discovering: Boolean = false,
    val discoveryProgress: DiscoveryProgress = DiscoveryProgress(),
    val candidates: List<GameCandidate> = emptyList(),
    val discoveryMessage: String = "安装了其他版本？点击扫描查找。",
    val message: String = "选择游戏，然后读取存档。",
    val snapshot: SaveSnapshot? = null,
    val pinnedTarget: PinnedSaveTarget? = null,
    val accounts: List<String> = emptyList(),
    val catalog: Catalog? = null,
    val petStates: List<PetState> = emptyList(),
    val petMessage: String = "请先扫描宠物存档",
    val choices: ModeChoices = ModeChoices(),
    val progression: List<HeroProgress> = emptyList(),
    val expertItems: ItemChoices = ItemChoices(),
    val expertWeapons: WeaponChoices = WeaponChoices(),
    val weaponStates: List<WeaponState> = emptyList(),
    val itemRoot: JsonObject? = null,
    val itemChoices: ItemChoices = ItemChoices(),
    val itemMessage: String = "请先扫描物品存档",
    val weaponInfo: WeaponInfo? = null,
    val weaponMessage: String = "请先扫描武器存档",
    val preview: SavePlan? = null,
    val previewMode: AssistantMode? = null,
    val applyOutcome: ApplyOutcome? = null,
    val pending: List<String> = emptyList(),
    val pendingPackages: Map<String, String> = emptyMap(),
    val backups: List<String> = emptyList(),
    val originalBackups: List<String> = emptyList(),
    val archives: List<BackupInfo> = emptyList(),
    val folder: String? = null,
    val autoOpenFolder: Boolean = true,
    val openFolderRequest: String? = null
)

fun AssistantState.navigate(target: AssistantPage): AssistantState = copy(
    page = target,
    settingsReturnPage = if (target in setOf(AssistantPage.SETTINGS, AssistantPage.BACKUPS) && page == AssistantPage.WORKSPACE) page else settingsReturnPage,
    preview = null,
    previewMode = null
)

class AssistantModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.getSharedPreferences("assistant-ui", 0)
    private val device = RootStorage()
    private val repository = SaveRepository(File(application.filesDir, "unlock-backups-v1"), device)
    private val archiveDirectory = File(application.filesDir, "export-backups-v1")
    private val searchCancelled = AtomicBoolean(false)
    var state by mutableStateOf(AssistantState(
        packageName = settings.getString("package", "com.ChillyRoom.DungeonShooter")!!,
        mode = settings.getString("mode", null)?.let { runCatching { AssistantMode.valueOf(it) }.getOrNull() },
        rememberMode = settings.getBoolean("rememberMode", true),
        personalUseAccepted = settings.getBoolean("personalUseAcceptedV1", false),
        consentPromptVisible = !settings.getBoolean("personalUseAcceptedV1", false),
        folder = settings.getString("folder", null), autoOpenFolder = settings.getBoolean("autoOpenFolder", true),
        pending = repository.pending(), pendingPackages = repository.pending().associateWith(repository::packageName),
        backups = repository.completed(settings.getString("package", "com.ChillyRoom.DungeonShooter")),
        originalBackups = repository.exportable(settings.getString("package", "com.ChillyRoom.DungeonShooter"))))
        private set
    init { viewModelScope.launch { state = state.copy(archives = withContext(Dispatchers.IO) { BackupArchive.list(archiveDirectory) }) } }

    private fun work(onFailure: ((Exception) -> Unit)? = null, block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, preview = null, previewMode = null)
        viewModelScope.launch {
            try { block() } catch (error: Exception) {
                state = state.copy(message = error.message ?: "操作未完成，已保存的备份仍保留", preview = null, previewMode = null)
                onFailure?.invoke(error)
            } finally {
                withContext(Dispatchers.IO) { device.close() }
                val archives = withContext(Dispatchers.IO) { BackupArchive.list(archiveDirectory) }
                state = state.copy(busy = false, discovering = false, pending = repository.pending(),
                    pendingPackages = repository.pending().associateWith(repository::packageName),
                    backups = repository.completed(state.packageName), originalBackups = repository.exportable(state.packageName), archives = archives)
            }
        }
    }
    fun hasDiscoveredCheer() = settings.getBoolean("showcheerDiscoveredV1", false)
    fun markCheerDiscovered() { settings.edit().putBoolean("showcheerDiscoveredV1", true).apply() }
    fun chooseMode(mode: AssistantMode) {
        if (state.busy) return
        settings.edit().apply { if (state.rememberMode) putString("mode", mode.name) else remove("mode") }.apply()
        state = state.copy(mode = mode, page = AssistantPage.WORKSPACE, settingsReturnPage = AssistantPage.WORKSPACE,
            workspaceTab = if (state.snapshot == null) WorkspaceTabs.saves.id else state.workspaceTab,
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
    fun tab(tab: WorkspaceTab) { if (!state.busy) state = state.selectTab(tab) }
    fun back() {
        if (state.busy) return
        when (state.page) {
            AssistantPage.BACKUPS -> page(AssistantPage.SETTINGS)
            AssistantPage.SETTINGS -> page(state.settingsReturnPage)
            AssistantPage.WORKSPACE -> guide()
        }
    }
    fun notice(message: String) { state = state.copy(message = message) }
    fun packageName(value: String) {
        if (!state.busy) {
            val target = value.trim()
            if (target == state.packageName) return
            settings.edit().putString("package", target).apply()
            state = state.selectPackage(target).copy(backups = repository.completed(target), originalBackups = repository.exportable(target))
        }
    }
    fun discover() {
        if (state.busy) return
        searchCancelled.set(false)
        work {
            state = state.copy(discovering = true, candidates = emptyList(), discoveryProgress = DiscoveryProgress(),
                discoveryMessage = "正在扫描…")
            try {
                val progress = withContext(Dispatchers.IO) {
                    device.discover(android.os.Process.myUid() / 100000, state.packageName, getApplication<Application>().packageName,
                        searchCancelled::get,
                        { update -> viewModelScope.launch { state = state.copy(discoveryProgress = update) } },
                        { candidate ->
                            val label = runCatching {
                                val manager = getApplication<Application>().packageManager
                                manager.getApplicationLabel(manager.getApplicationInfo(candidate.packageName, 0)).toString()
                            }.getOrDefault(candidate.packageName)
                            viewModelScope.launch { state = state.copy(candidates = state.candidates + candidate.copy(label = label)) }
                        })
                }
                state = state.copy(discoveryProgress = progress, discoveryMessage = (if (progress.checked < progress.total) "搜索达到时间上限，已检查 ${progress.checked}/${progress.total} 个应用。" else "搜索完成。") +
                    "请选择你要修改的版本。" +
                    if (progress.skipped > 0) " ${progress.skipped} 个应用有未完整检查的文件，可手动填写包名扫描。" else "")
            } catch (_: DiscoveryCancelled) {
                state = state.copy(discoveryMessage = "搜索已取消，已找到的候选仍可选择。")
            } catch (error: Exception) {
                state = state.copy(discoveryMessage = error.message ?: "搜索未完成，请检查 Root 权限或手动填写包名。")
            }
        }
    }
    fun cancelDiscovery() { if (state.discovering) searchCancelled.set(true) }
    override fun onCleared() {
        searchCancelled.set(true)
        super.onCleared()
    }
    fun scan() = work {
        require(state.pending.isEmpty()) { "请先在备份页恢复未完成的写回" }
        state = state.clearLoaded("正在读取存档…", clearTarget = true)
        val snapshot = withContext(Dispatchers.IO) { device.scan(state.packageName.trim(), android.os.Process.myUid() / 100000) }
        val accounts = withContext(Dispatchers.Default) { EditEngine.accounts(snapshot) }
        state = state.copy(snapshot = snapshot, accounts = accounts)
        if (accounts.size == 1) loadAccount(accounts.single()) else state = state.copy(message = "请选择要修改的账号。")
    }
    fun refresh() = work {
        require(state.pending.isEmpty()) { "请先恢复未完成的修改" }
        try { rereadTarget(clearDrafts = true); state = state.copy(message = "存档已刷新。") }
        catch (error: Exception) { state = state.clearLoaded("刷新失败：${error.message}。可重试刷新，或重新查找存档。"); throw IllegalStateException(state.message, error) }
    }
    private suspend fun rereadTarget(clearDrafts: Boolean) {
        val target = requireNotNull(state.pinnedTarget) { "请先读取存档并选择账号" }
        val snapshot = withContext(Dispatchers.IO) { device.refresh(target) }
        state = state.copy(snapshot = snapshot, accounts = withContext(Dispatchers.Default) { EditEngine.accounts(snapshot) })
        loadAccount(target.account, clearDrafts)
    }
    private suspend fun refreshAfterWrite(): Boolean = try {
        rereadTarget(clearDrafts = true)
        true
    } catch (error: Exception) {
        state = state.clearLoaded("修改完成，但刷新失败：${error.message}。请刷新或重新查找存档。")
        false
    }
    private suspend fun loadAccount(account: String, clearDrafts: Boolean = true) {
        val snapshot = requireNotNull(state.snapshot)
        require(account in state.accounts)
        val report = withContext(Dispatchers.Default) { SaveInspector.inspect(snapshot, account) }
        val characters = report.characters
        val items = report.items
        val pets = report.pets
        val weapons = report.weapons.map { states -> WeaponInfo(states.size, states.count { it.missing }) }
        val catalog = characters.getOrNull() ?: Catalog(state.accounts, account, emptyList(), true)
        state = state.copy(pinnedTarget = PinnedSaveTarget.from(snapshot, account), progression = report.progression.getOrNull().orEmpty(), weaponStates = report.weapons.getOrNull().orEmpty(), expertItems = if (clearDrafts) ItemChoices() else state.expertItems, expertWeapons = if (clearDrafts) WeaponChoices() else state.expertWeapons, catalog = catalog, petStates = pets.getOrNull().orEmpty(),
            petMessage = if (pets.isFailure) pets.exceptionOrNull()?.message ?: "宠物读取失败"
                else if (pets.getOrThrow().isEmpty()) "这个存档暂时没有可修改的宠物。" else "可修改 ${pets.getOrThrow().size} 只宠物。",
            choices = if (clearDrafts) ModeChoices() else state.choices, itemRoot = items.getOrNull(), itemChoices = if (clearDrafts) ItemChoices() else state.itemChoices,
            itemMessage = if (items.isSuccess) "物品已就绪。" else items.exceptionOrNull()?.message ?: "物品读取失败",
            weaponInfo = weapons.getOrNull(),
            weaponMessage = weapons.fold({ "可修改 ${it.total} 把武器的获取次数。" }, { it.message ?: "武器获取次数读取失败" }),
            preview = null, previewMode = null,
            message = if (characters.isFailure) "角色暂不可用：${characters.exceptionOrNull()?.message}"
                else "存档已就绪，可以开始编辑。")
    }
    fun account(account: String) = work { loadAccount(account) }
    fun select(selection: UnlockSelection) { if (!state.busy && state.mode == AssistantMode.EXPERT) state = state.copy(choices = state.choices.copy(expert = selection), preview = null, previewMode = null) }
    fun quick(roles: Boolean = state.choices.quickRoles, skins: Boolean = state.choices.quickSkins,
        levels: Boolean = state.choices.quickLevels, skills: Boolean = state.choices.quickSkills,
        pets: Boolean = state.choices.quickPets) {
        if (!state.busy && state.mode == AssistantMode.QUICK) state = state.copy(choices = state.choices.copy(quickRoles = roles, quickSkins = skins,
            quickLevels = levels, quickSkills = skills, quickPets = pets), preview = null, previewMode = null)
    }
    fun items(choices: ItemChoices) {
        if (!state.busy && state.mode == AssistantMode.QUICK && state.itemRoot != null)
            state = state.copy(itemChoices = choices, preview = null, previewMode = null)
    }
    fun expertItems(choices: ItemChoices) {
        if (!state.busy && state.mode == AssistantMode.EXPERT && state.itemRoot != null)
            state = state.copy(expertItems = choices, preview = null, previewMode = null)
    }
    fun expertWeapons(choices: WeaponChoices) {
        if (!state.busy && state.mode == AssistantMode.EXPERT && state.weaponInfo != null)
            state = state.copy(expertWeapons = choices, preview = null, previewMode = null)
    }
    fun itemGroup(group: String, undo: Boolean = false) {
        val root = state.itemRoot ?: return
        if (state.busy || state.mode != AssistantMode.QUICK) return
        runCatching { ItemDrafts.step(root, state.itemChoices, group, undo) }.onSuccess(::items)
            .onFailure { notice(it.message ?: "分类增量未加入") }
    }
    fun weapons(value: Boolean) {
        if (!state.busy && state.mode == AssistantMode.QUICK && state.weaponInfo != null)
            state = state.copy(choices = state.choices.copy(quickWeapons = value), preview = null, previewMode = null)
    }
    fun addItem(key: ItemKey) {
        val root = state.itemRoot ?: return
        if (state.busy || state.mode != AssistantMode.QUICK) return
        runCatching {
            require(ItemCatalog.byKey[key]?.kind == ItemKind.QUANTITY)
            val actions = state.itemChoices.actions
            val delta = (actions.increments[key] ?: 0).toLong() + 1000
            require(ItemEngine.quantity(root, key).toLong() + delta <= Int.MAX_VALUE) { "继续添加会超出整数范围" }
            items(state.itemChoices.copy(actions = actions.copy(increments = actions.increments + (key to delta.toInt()))))
        }.onFailure { notice(it.message ?: "此物品数量格式不支持") }
    }
    fun clearItem(key: ItemKey) {
        items(state.itemChoices.copy(actions = state.itemChoices.actions.copy(increments = state.itemChoices.actions.increments - key)))
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
        require(state.pending.isEmpty()) { "请先恢复未完成的修改" }
        try { rereadTarget(clearDrafts = false) }
        catch (error: Exception) {
            state = state.clearLoaded("读取当前存档失败：${error.message}。请刷新或重新查找。")
            throw IllegalStateException(state.message, error)
        }
        val snapshot = requireNotNull(state.snapshot)
        val catalog = requireNotNull(state.catalog)
        val mode = requireNotNull(state.mode)
        val selection = state.choices.selection(mode, catalog)
        val items = state.itemsFor(mode).selection()
        val patch = withContext(Dispatchers.Default) { EditEngine.preview(snapshot, catalog.account, selection, items, state.weaponsFor(mode)) }
        if (patch.outputs.isEmpty()) state = state.copy(message = "所选内容没有变化，无需写入。")
        else state = state.copy(preview = patch, previewMode = mode)
    }
    fun dismiss() { if (!state.busy) state = state.copy(preview = null, previewMode = null) }
    fun dismissApplyOutcome() { if (!state.busy) state = state.copy(applyOutcome = null) }
    fun apply() {
        if (!state.personalUseAccepted) { state = state.copy(preview = null, previewMode = null, message = "请先确认仅用于自己的本地存档"); return }
        val patch = state.preview ?: return
        val snapshot = state.snapshot ?: return
        if (state.previewMode != state.mode) { dismiss(); return }
        work(onFailure = { error ->
            state = state.copy(applyOutcome = ApplyOutcome(false, snapshot.packageName, 0,
                emptyList(), listOf(error.message ?: "写回未完成；请查看备份状态")))
        }) {
            withContext(Dispatchers.IO) { repository.apply(snapshot, patch) }
            val refreshed = refreshAfterWrite()
            state = state.copy(page = AssistantPage.WORKSPACE,
                applyOutcome = ApplyOutcome(true, snapshot.packageName, patch.outputs.size, patch.changes.toList(), emptyList()),
                message = if (refreshed) "修改完成，存档已刷新，可以继续编辑。" else state.message)

        }
    }
    fun restore(id: String, recovery: Boolean) = work {
        withContext(Dispatchers.IO) { if (recovery) repository.recover(id) else repository.restore(id) }
        if (state.pinnedTarget?.packageName == repository.packageName(id)) {
            val refreshed = refreshAfterWrite()
            if (refreshed) state = state.copy(message = "已恢复，存档已刷新，可以继续编辑。")
        } else state = state.clearLoaded("已恢复原存档。")
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
