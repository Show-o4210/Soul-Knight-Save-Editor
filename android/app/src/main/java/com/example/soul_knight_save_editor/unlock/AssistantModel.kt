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

enum class AssistantMode(val label: String) { QUICK("快速模式"), EXPERT("专家模式"), FREE("自由编辑") }
enum class AssistantPage { WORKSPACE, BACKUPS, SETTINGS }
data class ModeChoices(val quickRoles: Boolean = false, val quickSkins: Boolean = false,
    val quickLevels: Boolean = false, val quickSkills: Boolean = false, val quickPets: Boolean = false,
    val quickWeapons: Boolean = false,
    val expert: UnlockSelection = UnlockSelection()) {
    fun weapons(mode: AssistantMode) = mode == AssistantMode.QUICK && quickWeapons
    fun selection(mode: AssistantMode, catalog: Catalog): UnlockSelection = when (mode) {
        AssistantMode.FREE -> UnlockSelection()
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
    val packageName: String = GameVersions.ORIGINAL,
    val knownGames: List<GameCandidate> = GameVersions.restore(null),
    val accessBackend: AccessBackend = AccessBackend.NATIVE_ROOT,
    val accessStatus: String = "${accessBackend.label}：尚未检查权限身份及目标目录。",
    val accessDiagnostic: String = "",
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
    val weaponSkinInventory: WeaponSkinInventory? = null,
    val weaponSkinMessage: String = "请先读取武器皮肤存档",
    val quickWeaponSkins: WeaponSkinSelection = WeaponSkinSelection(),
    val expertWeaponSkins: WeaponSkinSelection = WeaponSkinSelection(),
    val weaponEvolutionInventory: WeaponEvolutionInventory? = null,
    val weaponEvolutionMessage: String = "请先读取武器进化存档",
    val quickWeaponEvolution: WeaponEvolutionSelection = WeaponEvolutionSelection(),
    val expertWeaponEvolution: WeaponEvolutionSelection = WeaponEvolutionSelection(),
    val mythicInventory: MythicInventory? = null,
    val mythicMessage: String = "请先读取神话武器存档",
    val quickMythic: MythicSelection = MythicSelection(),
    val expertMythic: MythicSelection = MythicSelection(),
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
    val backupRecords: List<BackupRecord> = emptyList(),
    val backupNames: Map<String, String> = emptyMap(),
    val backupLimit: Int = 0,
    val folder: String? = null,
    val autoOpenFolder: Boolean = true,
    val openFolderRequest: String? = null,
    val freeFolder: String? = null,
    val freeSnapshot: SaveSnapshot? = null,
    val freeSources: List<FreeSource> = emptyList(),
    val freeSelected: Set<String> = emptySet(),
    val freeAccounts: List<String> = emptyList(),
    val freeAccount: String? = null,
    val freeSession: FreeEditSession? = null,
    val freeReview: FreeImportReview? = null,
    val freeOpenRequest: String? = null,
    val freeOpenDirectory: Boolean = true,
    val freeOpenMime: String = "text/plain",
    val freeOpenDocuments: List<String> = emptyList()
)

fun AssistantState.navigate(target: AssistantPage): AssistantState = copy(
    page = target,
    settingsReturnPage = if (target in setOf(AssistantPage.SETTINGS, AssistantPage.BACKUPS) && page == AssistantPage.WORKSPACE) page else settingsReturnPage,
    preview = null,
    previewMode = null
)

fun AssistantState.selectAccessBackend(backend: AccessBackend): AssistantState {
    require(!busy) { "操作进行中，请等待完成再切换存档访问方式" }
    require(pending.isEmpty()) { "请先使用原访问方式恢复未完成事务" }
    if (backend == accessBackend) return this
    return clearLoaded("已切换为${backend.label}，请重新读取存档。", clearTarget = true).copy(
        accessBackend = backend, accessStatus = "${backend.label}：尚未检查权限身份及目标目录。",
        accessDiagnostic = "", candidates = emptyList(), discoveryProgress = DiscoveryProgress(), applyOutcome = null)
}

class AssistantModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.getSharedPreferences("assistant-ui", 0)
    private val journalDirectory = File(application.filesDir, "unlock-backups-v1")
    private var device: SaveAccess = createAccess(AccessBackend.entries.firstOrNull {
        it.id == settings.getString("saveAccessBackend", null)
    } ?: AccessBackend.NATIVE_ROOT)
    private var repository = SaveRepository(journalDirectory, device)
    private fun createAccess(backend: AccessBackend): SaveAccess = when (backend) {
        AccessBackend.NATIVE_ROOT -> GuardedNativeSaveAccess(RootStorage(), NativeWriteGuard(
            { android.provider.Settings.Global.getInt(getApplication<Application>().contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) },
            { settings.getInt("nativeWriteBoot", -1) },
            { boot ->
                val edit = settings.edit()
                if (boot == null) edit.remove("nativeWriteBoot") else edit.putInt("nativeWriteBoot", boot)
                check(edit.commit()) { "无法保存原生 Root 写任务状态，已停止写入" }
            }))
        AccessBackend.SHIZUKU_ROOT -> ShizukuSaveAccess(getApplication()) {
            viewModelScope.launch {
                val shizuku = device as? ShizukuSaveAccess ?: return@launch
                if (!shizuku.connected) state = state.clearLoaded("Shizuku 访问上下文已变化，请检查状态并重新读取。", clearTarget = true)
                state = state.copy(accessStatus = shizuku.statusString, preview = null, previewMode = null)
            }
        }
    }
    private val archiveDirectory = File(application.filesDir, "export-backups-v1")
    private val backupCatalog = BackupCatalog(File(application.filesDir, "backup-labels-v1.jsonl"))
    private val freeStore = FreeEditStore(File(application.filesDir, "free-edit-sessions-v1"))
    private val searchCancelled = AtomicBoolean(false)
    var state by mutableStateOf(AssistantState(
        accessBackend = device.backend,
        packageName = settings.getString("package", null)?.takeIf(SaveLayout.packagePattern::matches) ?: GameVersions.ORIGINAL,
        knownGames = GameVersions.restore(settings.getString("knownGameVersions", null), settings.getString("package", null)),
        mode = settings.getString("mode", null)?.let { runCatching { AssistantMode.valueOf(it) }.getOrNull() },
        rememberMode = settings.getBoolean("rememberMode", true),
        personalUseAccepted = settings.getBoolean("personalUseAcceptedV1", false),
        consentPromptVisible = !settings.getBoolean("personalUseAcceptedV1", false),
        folder = settings.getString("folder", null), autoOpenFolder = settings.getBoolean("autoOpenFolder", true),
        backupLimit = settings.getInt("backupLimit", 0).coerceAtLeast(0),
        freeFolder = settings.getString("freeFolder", null),
        pending = repository.pending(), pendingPackages = repository.pending().associateWith(repository::packageName),
        backups = repository.completed(settings.getString("package", "com.ChillyRoom.DungeonShooter")),
        originalBackups = repository.exportable(settings.getString("package", "com.ChillyRoom.DungeonShooter"))))
        private set
    init { work {
        refreshBackups()
        if (state.mode == AssistantMode.FREE) state = state.copy(workspaceTab = WorkspaceTabs.free.id)
        if (state.mode == AssistantMode.FREE) settings.getString("freeSession", null)?.let { id ->
            val session = withContext(Dispatchers.IO) { runCatching { freeStore.load(id) } }
            if (session.isSuccess && session.getOrThrow().snapshot.packageName == state.packageName)
                state = state.copy(freeSession = session.getOrThrow())
            else if (session.isFailure) state = state.copy(message = "上次自由编辑记录无法读取：${session.exceptionOrNull()?.message}")
        }
    } }

    private suspend fun refreshBackups(prune: Boolean = false) {
        val result = withContext(Dispatchers.IO) {
            fun collectRecords(archives: List<BackupInfo>) = repository.history() + archives.map {
                BackupRecord("archive:${it.file.name}", it.packageName, it.createdAt, it.count, it.kind)
            }
            var archives = BackupArchive.list(archiveDirectory)
            var records = collectRecords(archives)
            // Allocate labels before pruning so the sequence never moves backwards.
            backupCatalog.names(records)
            if (prune && repository.pending().isEmpty()) {
                BackupCatalog.excess(records, state.backupLimit).forEach { record ->
                    if (record.key.startsWith("transaction:")) repository.delete(record.key.removePrefix("transaction:"))
                    else {
                        val file = archives.single { "archive:${it.file.name}" == record.key }.file
                        require(file.canonicalFile.parentFile == archiveDirectory.canonicalFile)
                        check(file.delete()) { "无法删除旧备份" }
                    }
                }
                archives = BackupArchive.list(archiveDirectory)
                records = collectRecords(archives)
            }
            Triple(archives, records.sortedByDescending { it.createdAt }, backupCatalog.names(records))
        }
        state = state.copy(archives = result.first, backupRecords = result.second, backupNames = result.third)
    }

    private fun work(onFailure: ((Exception) -> Unit)? = null, block: suspend () -> Unit) {
        if (state.busy) return
        val operationDevice = device
        val operationRepository = repository
        var failureDiagnostic: String? = null
        state = state.copy(busy = true, preview = null, previewMode = null)
        viewModelScope.launch {
            var succeeded = false
            try { block(); succeeded = true } catch (error: Exception) {
                failureDiagnostic = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<AccessFailure>().firstOrNull { it.diagnostic != null }?.diagnostic?.let {
                        it.copy(packageName = it.packageName ?: state.packageName,
                            user = it.user ?: android.os.Process.myUid() / 100000).render()
                    }
                state = state.copy(message = error.message ?: "操作未完成，已保存的备份仍保留", preview = null, previewMode = null)
                if (operationDevice.backend == AccessBackend.NATIVE_ROOT && generateSequence<Throwable>(error) { it.cause }
                        .any { it is AccessFailure && it.kind == AccessFailureKind.ROOT_IDENTITY }) {
                    state = state.copy(accessStatus = "原生 Root：本次权限身份检查失败，请检查授权后重试。")
                }
                onFailure?.invoke(error)
            } finally {
                val diagnostic = failureDiagnostic ?: operationDevice.latestDiagnostic?.render().orEmpty()
                val accessStatus = if (operationDevice is ShizukuSaveAccess) {
                    operationDevice.statusString + if (state.snapshot != null && operationDevice.connected) "；当前目标目录及存档读取通过。" else "；目标目录尚未通过本次读取验证。"
                } else state.accessStatus
                withContext(Dispatchers.IO) { operationDevice.close() }
                try { refreshBackups(prune = succeeded) }
                catch (error: Exception) { state = state.copy(message = state.message + "\n备份列表／保留上限处理未完成：${error.message}") }
                state = state.copy(busy = false, discovering = false, pending = operationRepository.pending(),
                    accessStatus = accessStatus, accessDiagnostic = diagnostic.ifEmpty { state.accessDiagnostic },
                    pendingPackages = operationRepository.pending().associateWith(operationRepository::packageName),
                    backups = operationRepository.completed(state.packageName), originalBackups = operationRepository.exportable(state.packageName))
            }
        }
    }
    fun accessBackend(backend: AccessBackend) {
        if (state.busy || state.pending.isNotEmpty()) {
            notice(if (state.busy) "操作进行中，请等待完成再切换存档访问方式" else "请先使用原访问方式恢复未完成事务")
            return
        }
        if (backend == device.backend) return
        val old = device
        state = state.selectAccessBackend(backend).copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (old is ShizukuSaveAccess) old.dispose() else old.close() }
                device = createAccess(backend)
                repository = SaveRepository(journalDirectory, device)
                settings.edit().putString("saveAccessBackend", backend.id).apply()
            } catch (error: Exception) {
                device = old
                state = state.copy(accessBackend = old.backend, message = error.message ?: "访问方式切换失败，请重试")
            } finally { state = state.copy(busy = false) }
        }
    }
    fun checkAccess(requestPermission: Boolean = false) = work(onFailure = { error ->
        if (device.backend == AccessBackend.NATIVE_ROOT) state = state.copy(
            accessStatus = "原生 Root：检查未完成；${error.message ?: "请检查通道状态后重试"}")
    }) {
        val selected = device
        val status = withContext(Dispatchers.IO) {
            if (selected is ShizukuSaveAccess) {
                if (requestPermission) selected.requestPermission()
                if (!requestPermission) selected.ensureReady()
                selected.checkStatus()
            } else {
                selected.ensureReady()
                "原生 Root：执行身份 UID 0 已确认；目标目录尚未检查。"
            }
        }
        state = state.copy(accessStatus = status, message = status)
    }
    fun hasDiscoveredCheer() = settings.getBoolean("showcheerDiscoveredV1", false)
    fun markCheerDiscovered() { settings.edit().putBoolean("showcheerDiscoveredV1", true).apply() }
    fun chooseMode(mode: AssistantMode) {
        if (state.busy) return
        settings.edit().apply { if (state.rememberMode) putString("mode", mode.name) else remove("mode") }.apply()
        state = state.copy(mode = mode, page = AssistantPage.WORKSPACE, settingsReturnPage = AssistantPage.WORKSPACE,
            workspaceTab = if (mode == AssistantMode.FREE) WorkspaceTabs.free.id else if (state.snapshot == null || state.workspaceTab == WorkspaceTabs.free.id) WorkspaceTabs.saves.id else state.workspaceTab,
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
            require(SaveLayout.packagePattern.matches(target)) { "游戏包名无效" }
            if (target == state.packageName) return
            if (state.pending.isNotEmpty()) { notice("请先恢复未完成事务，再切换游戏版本。"); return }
            val games = GameVersions.add(state.knownGames, GameCandidate(target, evidence = emptyList()))
            settings.edit().putString("package", target).putString("knownGameVersions", GameVersions.encode(games)).apply()
            state = state.selectPackage(target).copy(knownGames = games,
                backups = repository.completed(target), originalBackups = repository.exportable(target))
        }
    }
    private fun rememberGame(candidate: GameCandidate) {
        val games = GameVersions.add(state.knownGames, candidate)
        settings.edit().putString("knownGameVersions", GameVersions.encode(games)).apply()
        state = state.copy(knownGames = games)
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
                            viewModelScope.launch {
                                val found = candidate.copy(label = label)
                                rememberGame(found)
                                state = state.copy(candidates = state.candidates.filterNot { it.packageName == found.packageName } + found)
                            }
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
        val selected = device
        // Closing a shell or IPC connection may block; the lifecycle callback stays on main.
        java.util.concurrent.Executors.newSingleThreadExecutor().let { executor ->
            executor.submit { if (selected is ShizukuSaveAccess) selected.dispose() else selected.close() }
            executor.shutdown()
        }
        super.onCleared()
    }
    fun scan() = work {
        require(state.pending.isEmpty()) { "请先在备份页恢复未完成的写回" }
        state = state.clearLoaded("正在读取存档…", clearTarget = true)
        val snapshot = withContext(Dispatchers.IO) { device.scan(state.packageName.trim(), android.os.Process.myUid() / 100000) }
        val accounts = withContext(Dispatchers.Default) { EditEngine.accounts(snapshot) }
        state = state.copy(snapshot = snapshot, accounts = accounts,
            accessStatus = "${device.backend.label}：执行身份 UID 0 已确认，当前目标目录及存档读取通过。")
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
        val weaponSkins = report.weaponSkins
        val evolution = report.weaponEvolution
        val mythic = report.mythic
        val catalog = characters.getOrNull() ?: Catalog(state.accounts, account, emptyList(), true)
        state = state.copy(pinnedTarget = PinnedSaveTarget.from(snapshot, account), progression = report.progression.getOrNull().orEmpty(), weaponStates = report.weapons.getOrNull().orEmpty(), expertItems = if (clearDrafts) ItemChoices() else state.expertItems, expertWeapons = if (clearDrafts) WeaponChoices() else state.expertWeapons, catalog = catalog, petStates = pets.getOrNull().orEmpty(),
            petMessage = if (pets.isFailure) pets.exceptionOrNull()?.message ?: "宠物读取失败"
                else if (pets.getOrThrow().isEmpty()) "这个存档暂时没有可修改的宠物。" else "可修改 ${pets.getOrThrow().size} 只宠物。",
            choices = if (clearDrafts) ModeChoices() else state.choices, itemRoot = items.getOrNull(), itemChoices = if (clearDrafts) ItemChoices() else state.itemChoices,
            itemMessage = if (items.isSuccess) "物品已就绪。" else items.exceptionOrNull()?.message ?: "物品读取失败",
            weaponInfo = weapons.getOrNull(),
            weaponMessage = weapons.fold({ "可修改 ${it.total} 把武器的获取次数。" }, { it.message ?: "武器获取次数读取失败" }),
            weaponSkinInventory = weaponSkins.getOrNull(),
            weaponSkinMessage = weaponSkins.fold({ "已核对 ${it.states.sumOf { state -> state.skins.size }} 个皮肤 · ${it.states.size} 把武器。" },
                { it.message ?: "武器皮肤读取失败" }),
            quickWeaponSkins = if (clearDrafts) WeaponSkinSelection() else state.quickWeaponSkins,
            expertWeaponSkins = if (clearDrafts) WeaponSkinSelection() else state.expertWeaponSkins,
            weaponEvolutionInventory = evolution.getOrNull(),
            weaponEvolutionMessage = evolution.fold({ "已核对 ${it.states.size} 把可进化武器。" }, { it.message ?: "武器进化读取失败" }),
            quickWeaponEvolution = if (clearDrafts) WeaponEvolutionSelection() else state.quickWeaponEvolution,
            expertWeaponEvolution = if (clearDrafts) WeaponEvolutionSelection() else state.expertWeaponEvolution,
            mythicInventory = mythic.getOrNull(),
            mythicMessage = mythic.fold({ "已核对 ${it.states.size} 把神话武器。" }, { it.message ?: "神话武器读取失败" }),
            quickMythic = if (clearDrafts) MythicSelection() else state.quickMythic,
            expertMythic = if (clearDrafts) MythicSelection() else state.expertMythic,
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
    fun weaponSkins(selection: WeaponSkinSelection) {
        if (state.busy || state.weaponSkinInventory == null) return
        state = when (state.mode) {
            AssistantMode.QUICK -> state.copy(quickWeaponSkins = selection, preview = null, previewMode = null)
            AssistantMode.EXPERT -> state.copy(expertWeaponSkins = selection, preview = null, previewMode = null)
            else -> return
        }
    }
    fun weaponEvolution(selection: WeaponEvolutionSelection) {
        if (state.busy || state.weaponEvolutionInventory == null) return
        state = when (state.mode) {
            AssistantMode.QUICK -> state.copy(quickWeaponEvolution = selection, preview = null, previewMode = null)
            AssistantMode.EXPERT -> state.copy(expertWeaponEvolution = selection, preview = null, previewMode = null)
            else -> return
        }
    }
    fun mythic(selection: MythicSelection) {
        if (state.busy || state.mythicInventory == null) return
        state = when (state.mode) {
            AssistantMode.QUICK -> state.copy(quickMythic = selection, preview = null, previewMode = null)
            AssistantMode.EXPERT -> state.copy(expertMythic = selection, preview = null, previewMode = null)
            else -> return
        }
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
        val patch = withContext(Dispatchers.Default) { EditEngine.preview(snapshot, catalog.account, selection, items, state.weaponsFor(mode), state.weaponSkinsFor(mode),
            state.weaponEvolutionFor(mode), state.mythicFor(mode)) }
        if (patch.outputs.isEmpty()) state = state.copy(message = "所选内容没有变化，无需写入。")
        else state = state.copy(preview = patch, previewMode = mode)
    }
    fun dismiss() { if (!state.busy) state = state.copy(preview = null, previewMode = null) }
    fun dismissApplyOutcome() { if (!state.busy) state = state.copy(applyOutcome = null) }
    fun apply() {
        if (!state.personalUseAccepted) { state = state.copy(preview = null, previewMode = null, message = "请先确认仅用于自己的本地存档"); return }
        val patch = state.preview ?: return
        val snapshot = (if (state.previewMode == AssistantMode.FREE) state.freeSession?.snapshot else state.snapshot) ?: return
        if (state.previewMode != state.mode) { dismiss(); return }
        work(onFailure = { error ->
            state = state.copy(applyOutcome = ApplyOutcome(false, snapshot.packageName, 0,
                emptyList(), listOf(error.message ?: "写回未完成；请查看备份状态")))
        }) {
            withContext(Dispatchers.IO) { repository.apply(snapshot, patch) }
            if (state.previewMode == AssistantMode.FREE || state.mode == AssistantMode.FREE) {
                settings.edit().remove("freeSession").apply()
                state = state.clearLoaded("自由编辑已写回。继续修改时请重新读取并导出。", clearTarget = true).copy(
                    freeSession = null, freeReview = null, freeSnapshot = null, freeSources = emptyList(), freeSelected = emptySet(),
                    applyOutcome = ApplyOutcome(true, snapshot.packageName, patch.outputs.size, patch.changes, emptyList()))
                return@work
            }
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
    fun restoreBackup(key: String) = work {
        val record = state.backupRecords.singleOrNull { it.key == key } ?: error("备份不存在，请刷新列表")
        require(record.packageName == state.packageName) { "请先选择备份对应的游戏版本" }
        withContext(Dispatchers.IO) {
            val user = android.os.Process.myUid() / 100000
            if (key.startsWith("transaction:")) {
                val id = key.removePrefix("transaction:")
                require(repository.originals(id).user == user) { "备份属于其他 Android 用户，不能直接还原" }
                repository.restore(id)
            } else {
                val info = state.archives.single { "archive:${it.file.name}" == key }
                require(info.file.canonicalFile.parentFile == archiveDirectory.canonicalFile)
                val payload = BackupArchive.read(info.file)
                require(payload.packageName == state.packageName && payload.user == user) { "备份与当前游戏或 Android 用户不一致" }
                val fallback = if (payload.files.none { it.path.contains("/shared_prefs/") }) device.scan(payload.packageName, payload.user).prefs?.path else null
                repository.restore(payload, fallback)
            }
        }
        state = state.clearLoaded("存档已还原，已有读取开关已设为 0。请重新读取存档。", clearTarget = true)
    }
    fun renameBackup(key: String, name: String) = work {
        require(state.backupRecords.any { it.key == key }) { "备份不存在" }
        withContext(Dispatchers.IO) { backupCatalog.rename(key, name) }
        state = state.copy(message = "备份已重命名。")
    }
    fun backupLimit(value: String) = work {
        val limit = value.trim().ifEmpty { "0" }.toIntOrNull()
        require(limit != null && limit >= 0) { "请输入大于 0 的保留数量；0 或留空表示不限" }
        check(settings.edit().putInt("backupLimit", limit).commit()) { "无法保存保留上限" }
        state = state.copy(backupLimit = limit, message = if (limit == 0) "备份保留数量不限。" else "每个游戏最多保留 $limit 份备份，超出的最旧备份将删除。")
    }
    fun importBackup(uri: Uri) = work {
        require(state.pending.isEmpty()) { "请先恢复未完成事务" }
        val temporary = File(getApplication<Application>().cacheDir, "backup-import-${java.util.UUID.randomUUID()}.zip")
        try {
            val payload = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= BackupArchive.MAX_TOTAL_BYTES + 1024 * 1024) { "备份包过大" }
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: error("无法读取所选备份包")
                BackupArchive.read(temporary).also { BackupArchive.create(archiveDirectory, it) }
            }
            state = state.copy(message = "备份包已导入（${payload.packageName}）。选择对应游戏后，可在列表中还原。")
        } finally { withContext(Dispatchers.IO) { temporary.delete() } }
    }
    fun folder(uri: Uri) {
        runCatching { BackupDestination.remember(getApplication(), uri) }.onSuccess {
            settings.edit().putString("folder", uri.toString()).apply()
            state = state.copy(folder = uri.toString(), message = "备份目录已记住，可以开始备份。")
        }.onFailure { notice(it.message ?: "无法保存目录授权，请重新选择本地文件夹") }
    }
    fun autoOpen(value: Boolean) { settings.edit().putBoolean("autoOpenFolder", value).apply(); state = state.copy(autoOpenFolder = value) }
    fun freeFolder(uri: Uri) {
        if (state.busy) return
        runCatching { BackupDestination.remember(getApplication(), uri) }.onSuccess {
            settings.edit().putString("freeFolder", uri.toString()).apply()
            state = state.copy(freeFolder = uri.toString(), message = "自由编辑导出目录已保存。")
        }.onFailure { notice(it.message ?: "目录授权失败") }
    }
    fun freeScan() = work {
        require(state.pending.isEmpty()) { "请先恢复未完成事务" }
        val snapshot = withContext(Dispatchers.IO) { device.freeSnapshot(state.packageName, android.os.Process.myUid() / 100000) }
        val sources = withContext(Dispatchers.Default) { snapshot.files.filter(FreeEditEngine::exportable).map { FreeSource(it.path, FreeEditEngine.detect(it)) } }
        val accounts = withContext(Dispatchers.Default) { FreeEditEngine.accounts(snapshot) }
        state = state.copy(freeSnapshot = snapshot, freeSources = sources,
            freeSelected = sources.filter { it.codec == FreeCodec.PREFS_XML }.map { it.path }.toSet(), freeAccounts = accounts,
            freeAccount = accounts.singleOrNull(), freeReview = null,
            accessStatus = "${device.backend.label}：自由编辑文件读取通过。",
            message = "已找到 ${sources.count { it.codec != FreeCodec.PREFS_XML }} 个 .data 与 ${sources.count { it.codec == FreeCodec.PREFS_XML }} 个 XML 文件；XML 默认随选中的存档一同导出，也可单独编辑。")
    }
    fun freeSelect(path: String, selected: Boolean) {
        if (!state.busy && state.freeSources.any { it.path == path }) state = state.copy(
            freeSelected = if (selected) state.freeSelected + path else state.freeSelected - path, preview = null, previewMode = null)
    }
    fun freeAccount(account: String) {
        if (!state.busy && account in state.freeAccounts) state = state.copy(freeAccount = account)
    }
    fun freeExport() = work {
        require(state.pending.isEmpty()) { "请先恢复未完成事务" }
        val folder = requireNotNull(state.freeFolder) { "请先选择自由编辑的导出目录" }
        val selected = state.freeSelected.toSet()
        require(selected.isNotEmpty()) { "请先选择 .data 或 XML 文件" }
        val exported = withContext(Dispatchers.IO) {
            val snapshot = device.freeSnapshot(state.packageName, android.os.Process.myUid() / 100000)
            val directory = FreeEditDocuments.createDirectory(getApplication(), Uri.parse(folder))
            val exported = FreeEditEngine.create(snapshot, selected, state.freeAccount, directory.toString())
            val documents = FreeEditDocuments.write(getApplication(), directory, exported.second)
            freeStore.save(exported.first)
            check(settings.edit().putString("freeSession", exported.first.id).commit()) { "无法保存导出记录" }
            exported.first to documents
        }
        val session = exported.first
        state = state.copy(freeSession = session, freeReview = null, freeOpenRequest = session.directory, freeOpenDirectory = true,
            freeOpenDocuments = exported.second.map(Uri::toString),
            message = "已导出 ${session.entries.size} 个文件，请选择应用打开目录并编辑；完成后返回导入。")
    }
    fun freeImport(uris: List<Uri>? = null) = work {
        val session = requireNotNull(state.freeSession) { "请先导出解码文件" }
        require(session.snapshot.packageName == state.packageName && session.snapshot.user == android.os.Process.myUid() / 100000) { "导出记录与当前游戏或 Android 用户不一致" }
        val review = withContext(Dispatchers.IO) {
            val read = if (uris == null) FreeEditDocuments.readDirectory(getApplication(), Uri.parse(session.directory))
                else FreeEditDocuments.readUris(getApplication(), uris)
            FreeEditEngine.match(session, read.documents).let { it.copy(ignored = read.ignored + it.ignored) }
        }
        state = state.copy(freeReview = review, message = "导入比较完成：${review.changed} 份内容变化，${review.unresolved} 份待选择，${review.ignored.size} 个多余文件已忽略。")
    }
    fun freeChoose(path: String, id: String?) {
        if (!state.busy) state = state.copy(freeReview = state.freeReview?.select(path, id), preview = null, previewMode = null)
    }
    fun freePreview() = work {
        require(state.mode == AssistantMode.FREE && state.personalUseAccepted) { "请先确认个人使用约定" }
        require(state.pending.isEmpty()) { "请先恢复未完成事务" }
        val session = requireNotNull(state.freeSession)
        require(session.snapshot.packageName == state.packageName) { "请切换到导出记录对应的游戏" }
        val plan = withContext(Dispatchers.Default) { FreeEditEngine.plan(session, requireNotNull(state.freeReview) { "请先导入修改后的文件" }) }
        if (plan.outputs.isEmpty()) state = state.copy(message = "没有需要重新打包写回的内容变化。")
        else state = state.copy(preview = plan, previewMode = AssistantMode.FREE)
    }
    fun freeOpenDirectory() = work {
        val session = requireNotNull(state.freeSession)
        val documents = withContext(Dispatchers.IO) { FreeEditDocuments.children(getApplication(), Uri.parse(session.directory)) }
        state = state.copy(freeOpenRequest = session.directory, freeOpenDirectory = true, freeOpenDocuments = documents.map(Uri::toString))
    }
    fun freeOpenFile(entry: FreeEditEntry) = work {
        val session = requireNotNull(state.freeSession)
        require(entry in session.entries)
        val uri = withContext(Dispatchers.IO) { FreeEditDocuments.find(getApplication(), Uri.parse(session.directory), entry.name) }
        state = state.copy(freeOpenRequest = uri.toString(), freeOpenDirectory = false, freeOpenDocuments = emptyList(),
            freeOpenMime = if (entry.codec == FreeCodec.RAW) "application/octet-stream" else "text/plain")
    }
    fun consumedFreeOpen() { state = state.copy(freeOpenRequest = null, freeOpenDocuments = emptyList()) }
    fun canResumeFree() = settings.contains("freeSession")
    fun freeResume() = work {
        val id = requireNotNull(settings.getString("freeSession", null)) { "没有可继续的导出记录" }
        val session = withContext(Dispatchers.IO) { freeStore.load(id) }
        require(session.snapshot.packageName == state.packageName) { "上次导出的游戏为 ${session.snapshot.packageName}，请先选择该游戏" }
        state = state.copy(freeSession = session, freeReview = null, message = "已载入上次导出记录，可打开目录或导入修改。")
    }
    fun consumedFolderRequest() { state = state.copy(openFolderRequest = null) }
    fun backupNow() = work {
        require(state.pending.isEmpty()) { "请先恢复未完成事务，再备份当前存档" }
        state = state.copy(message = "正在关闭游戏并备份本地 .data 与 XML；不会修改存档…")
        val info = withContext(Dispatchers.IO) { BackupArchive.create(archiveDirectory, device.backup(state.packageName.trim(), android.os.Process.myUid() / 100000)) }
        refreshBackups()
        val tree = state.folder
        if (tree != null) exportToFolder(info, tree)
        else state = state.copy(message = "${state.backupNames["archive:${info.file.name}"]} 已保存到应用内；可选择文件夹导出。")
    }
    fun exportOriginal(id: String) = work {
        val tree = requireNotNull(state.folder) { "请先选择本地备份文件夹" }
        val info = withContext(Dispatchers.IO) { BackupArchive.create(File(getApplication<Application>().cacheDir, "backup-exports"), repository.originals(id)) }
        try { exportToFolder(info, tree, state.backupNames["transaction:$id"]) }
        finally { withContext(Dispatchers.IO) { info.file.delete() } }
    }
    fun exportArchive(info: BackupInfo) = work {
        val tree = requireNotNull(state.folder) { "请先选择本地备份文件夹" }
        require(info.file.canonicalFile.parentFile == archiveDirectory.canonicalFile)
        exportToFolder(info, tree)
    }
    private suspend fun exportToFolder(info: BackupInfo, tree: String, label: String? = state.backupNames["archive:${info.file.name}"]) {
        val name = "${label ?: info.file.nameWithoutExtension}.zip"
        withContext(Dispatchers.IO) { BackupDestination.write(getApplication(), Uri.parse(tree), info.file, name) }
        state = state.copy(message = "备份已导出并校验：${info.count} 个文件。$name", openFolderRequest = if (state.autoOpenFolder) tree else null)
    }
}
