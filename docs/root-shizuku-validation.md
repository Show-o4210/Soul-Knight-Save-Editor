# Root / Shizuku Root 实现与验证记录

适用正式源码：`2.0.2` / versionCode 18，基于 `2.0.1-rc01` / 17 增量开发。文件修改测试时间：**2026-10-09（北京时间）**。下面的功能验证使用本地 `2.0.2-root01` Debug 候选，在助手自建的合成文件上进行；正式版沿用该实现并使用正式签名构建，发布前按用户要求停止进一步功能测试。正式发布不代表真实游戏内效果或反馈设备兼容性已经验证，不能保证实际可用。[正式版与下载说明](releases/2.0.2.md)。Issue #4 保持开启，等待反馈者下载新版后复测。

## 问题核对

[Issue #4](https://github.com/Show-o4210/Soul-Knight-Save-Editor/issues/4) 反馈版本是 `2.0.1-rc01(17)`，设备为 OnePlus Ace 6T / ColorOS 16 / APatch + FolkPatch。发布标签到当前基线 `02f5251` 只存在文档差异，相关 Android 代码一致。

- 已确认：旧 `RootStorage` 的目录检查使用 `test -d … && echo yes`，目录不存在会返回非零；命令执行器将非零退出统一包装为 Root 操作失败。这会把正常的“目录不存在”错误归因到 Root。
- 已修复：目录检查按明确的 ENOENT / ENOTDIR 与文件类型返回 false；权限拒绝、命令失败、会话断开、超时、I/O 与身份失败分别报告。文件操作失败不改变已确认的 Root 身份。
- 仍待反馈设备验证：su 启动方式、Root 管理器、SELinux、渠道路径或设备会话差异是否与 Issue 其他症状有关。本次 Pixel 6 测试不能证明 OnePlus / APatch 环境已修复。
- 反馈者的 MT `su -c` 测试与 Termux 对照仅是线索。应用使用 `ProcessBuilder("su")` 建立 stdin 会话，没有据此认定相同根因。

## 接入与文件职责

以下 Kotlin 文件均位于 `android/app/src/main/java/com/example/soul_knight_save_editor/unlock/`：

| 文件 | 必要性 |
| --- | --- |
| `RootStorage.kt`、`RootCommandExecutor.kt`、`ShellSaveAccess.kt` | 保留原生 su 默认通道；固定命令、身份与退出码分类、目录 errno、取消/超时/输出限制、路径与元数据检查共用。 |
| `SaveAccess.kt` | 覆盖扫描、刷新、渠道发现、备份源读取及事务文件访问；共享原识别逻辑，避免扫描与保存走不同后端。 |
| `ShizukuSaveAccess.kt`、`ShizukuSaveService.kt`、`ShizukuWire.kt` | 官方 UserService 绑定、权限、UID、连接生命周期；仅开放有限操作，服务端再次校验调用 UID、路径与参数；服务内使用 `/system/bin/sh`，不调用 su。 |
| `ShizukuOnlyProvider.kt` | 使用官方 Provider，关闭自动 Sui 初始化，不增加独立适配项目。 |
| `ShizukuOperationGate.kt`、`ShizukuStatus.kt`、`ShizukuWriterFence.kt`、`StreamProtocol.kt` | 控制服务串行执行、状态分类与同启动周期的旧写任务保护；可靠管道传输大小、长度、SHA-256 校验，避免大存档进入 Binder parcel。 |
| `NativeWriteGuard.kt` | 未确认原生写任务在应用重启后仍保留保护，避免将重建客户端误当成旧任务结束。 |
| `SaveRepository.kt` | 未确定的写结果保留事务、停止后续写入；记录可选后端字段，schema 仍为 1，旧事务默认原生 Root；中断手动恢复仍属于待恢复事务。 |
| `AssistantModel.kt`、`AssistantUi.kt` | 原设置页增加显式后端选择、检查/授权与本地诊断显示/复制；操作中与未完成事务时禁止切换，切换清除旧快照与预览。 |

`android/app/build.gradle.kts` 固定 Shizuku API / Provider **13.1.5**、启用 AIDL 并登记候选版本；`AndroidManifest.xml` 增加官方权限及受保护 Provider；`proguard-rules.pro` 保留反射创建的 Provider、UserService 构造器及 AIDL Binder。没有新增 INTERNET 权限，也没有更换签名配置或升级现有工具链。

新增 `android/app/src/main/aidl/com/example/soul_knight_save_editor/unlock/IShizukuSaveService.aidl`：有限操作与小元数据 IPC，固定方法编号，`destroy` 使用官方 UserService 约定的事务编号。

`android/README.md` 增加本地候选说明与本验证文档入口，保留上一正式版本记录；本文登记实现范围、测试结果和只读验证步骤。

SDK 选择与生命周期按 [Shizuku 官方 API](https://github.com/RikkaApps/Shizuku-API) 核对；大内容使用文件描述符管道以避开 [Binder 事务容量限制](https://developer.android.com/reference/android/os/TransactionTooLargeException)。

## 行为与限制

原生 Root 为默认。旧用户不需要安装 Shizuku，也不会自动请求其权限。用户选择 Shizuku Root 后才注册客户端监听并按明确按钮请求授权；服务运行、权限授权、服务 UID、UserService UID 与目标目录读取分别检查。UID 2000 明确拒绝游戏私有存档访问，既不回退原生 su，也不新增 ADB 免 Root 功能。

存档格式、编辑规则、目录/深度/数量/大小上限、`.data.new` 排除、预览、应用侧原件备份、漂移检查、临时替换、owner/mode/SELinux context 复读和 SAF 导出沿用原业务。存档字节经受控管道传输，真实存档的可写文件描述符不会交给客户端；备份与事务业务没有搬进高权限服务。

写入结果未知时不会自动重发或回滚。相同服务可确认旧写任务结束后，用户再显式恢复；若旧服务/原生进程消失且无法证明写任务结束，需要重启设备后先恢复事务。状态标记在调用前同步持久化，重启应用不会绕过保护。正常目录/权限检查与诊断不会执行写入或恢复。

## 验证记录

基线命令 `./gradlew.bat testDebugUnitTest`：123 项，118 通过、5 跳过、0 失败/错误。跳过的是未注入的 Item / Pet / Unlock / Vivo / Weapon 私有样本，每类一项。

最终 `testDebugUnitTest`：167 项，162 通过、5 个相同私有样本测试跳过、0 失败/错误；较基线新增 44 项。`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 均通过；lint 为 0 errors / 48 warnings。运行命令：

```powershell
# 工作目录 publication-worktree/android；沿用 Gradle 9.5.0 / AGP 9.3.0 / daemon JDK 21
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-configuration-cache
# 最后一处启动状态文字修正后
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-configuration-cache
```

Gradle 用户缓存锁文件首次被沙箱拒绝，授权后使用原缓存构建，没有换工具链。集成过程中出现过 AIDL 方法编号、诊断辅助函数编译错误，以及新测试中的合成管道未 flush 和 Windows manifest 替换失败；均已修复，最终回归通过。后端变化改用已有追加式事务日志记录，避免重写旧 manifest。

设备验证使用 Pixel 6、Android 16、Magisk；已只读确认 su 为 UID 0。设备游戏为默认渠道 8.6.0，本次不自动修改其真实存档。官方 Shizuku 13.6.0 / 1086 已安装并使用 Root 启动，服务进程 UID 0。

新增回归包括 `RootStorageTest`、`AccessSelectionTest`、`NativeWriteGuardTest`、`BackendSafetyTest`、`RoutingTest` 与 Shizuku 状态/管道/执行门测试；`ShizukuFixtureTest` 必须显式启用，只在助手自身 UUID 合成目录验证超过 1 MiB 的传输及事务。

| 新增 JVM 文件（位于 android/app/src/test/java/com/example/soul_knight_save_editor/unlock/） | 项数 | 覆盖 |
| --- | --- | --- |
| `RootStorageTest.kt` | 13 | 目录存在/缺失/非目录、拒绝、命令失败、身份、断开/超时、路径越界、诊断脱敏、关闭重开、未确认写 END。 |
| `ShizukuTransportTest.kt` | 13 | 服务与权限状态、UID 2000 / 0、版本、写保护、执行门、超 Binder 容量传输、大小/截断/哈希/协议拒绝、服务参数验证。 |
| `BackendSafetyTest.kt` | 8 | 未确定写入不重试/回滚、idle检查、后端固定、旧 schema、手动恢复中断及恢复期间第三方漂移。 |
| `RoutingTest.kt` | 3 | 两后端扫描/刷新/发现/备份/写入/恢复共用路线，Android 用户 10 与禁止 fallback。 |
| `AccessSelectionTest.kt` | 4 | 原生默认、启动状态文字、切换使快照/预览失效、操作或待恢复事务阻止切换。 |
| `NativeWriteGuardTest.kt` | 3 | 未确认写状态跨应用进程保留、执行器确认为 idle 后释放、设备重启与进程死亡保护。 |

设备命令（ADB 序列号 `1B271FDF60B7UU`）：

```powershell
adb -s 1B271FDF60B7UU shell am instrument -w -r -e rootFixture true -e class com.example.soul_knight_save_editor.unlock.RootFixtureTest com.example.soul_knight_save_editor.test/androidx.test.runner.AndroidJUnitRunner
adb -s 1B271FDF60B7UU shell am instrument -w -r -e shizukuFixture true -e class com.example.soul_knight_save_editor.unlock.ShizukuFixtureTest com.example.soul_knight_save_editor.test/androidx.test.runner.AndroidJUnitRunner
```

原生 Root **2 / 2 通过**（10.959 秒），验证角色与武器合成事务、原件恢复及权限元数据。Shizuku Root **1 / 1 通过**（23.999 秒），实际传输 2 MiB + 137 字节合成文件，关闭后重新绑定，写回、原件备份、恢复及 owner/mode/context 复读通过；未因缺权限跳过。测试结束 `ps` 确认仅保留官方 `shizuku_server` / manager，助手 UserService 已退出。Root 第一次身份检查未获得授权，用户在 Magisk 授权后通过；Shizuku 通过官方权限弹窗授权，没有修改管理器数据库。

仪器测试 APK已构建；两种 Root fixture 使用助手自建 UUID 目录，结束清理自建内容。服务失败状态和大文件截断/哈希/超限等负向案例在注入执行器/假后端/JVM 协议测试中验证，未在真实游戏目录注入故障。没有执行真实游戏写入、游戏实载验收、OnePlus/APatch 复测或 ADB 模式设备实测。Release 的构建及签名核对单独记录在正式版说明中，不计入这里的设备功能测试。

最终 Debug APK 导出到工作区 `outputs/2.0.2-root01-debug/SoulKnightSaveEditor-2.0.2-root01-debug.apk`，同目录附 `SHA256SUMS.txt`。构建输出也保留在 `android/app/build/outputs/apk/debug/app-debug.apk`；已覆盖安装候选并核对 versionCode 18 / `2.0.2-root01`。

SHA-256：`a03dc53ba5a27cd43aa498751c9a2142f5572e88adc9a311068f42b28bedfc95`。设备仪器回归与最终包仅相差启动状态文字一处纯状态修正，最终包已重跑完整 JVM 回归、Debug 构建与 lint 并覆盖安装，仪器回归未为这处文字重复执行。

最终安装包设置页只读检查通过：Shizuku 显示 UserService 实际 UID=0 且明确目标目录尚未读取验证，原生 Root 显示执行身份 UID 0 已确认。没有借身份检查读取真实游戏文件；测试后设备选项留为原生 Root。

## 正式打包记录

2026-10-09 按用户要求停止进一步功能测试，正式版本改为 `2.0.2` / 18。使用原仓库外签名配置执行 `assembleRelease --no-configuration-cache`，R8、lintVital 与 APK 打包通过。`apksigner verify --verbose --print-certs` 通过，证书 SHA-256 与原正式版一致：`ce1ff5a2c745cafdf385dda40b21840625b4c909bb9bb289b89cb01f0230d87b`。`aapt dump badging` 确认版本 `2.0.2` / 18、minSdk 24、targetSdk 36；正式包没有 debuggable 标记。

正式 APK SHA-256：`2bbbad1bc7f7171537dfaa455c3b52d3decc27554776c24c0947ddd34760ab20`。正式包未继续设备安装或游戏验证，前面的功能记录属于 Debug 候选；发布分类不扩大测试结论。

## 首次用户验证

1. 从 [v2.0.2 发布页](https://github.com/Show-o4210/Soul-Knight-Save-Editor/releases/tag/v2.0.2) 下载正式签名 APK。已有正式版可覆盖升级；若装的是不同签名的 Debug 包，先导出其内部备份，再处理签名冲突。不要直接卸载含有唯一备份的助手。
2. 设置 → 存档访问方式，确认默认原生 Root；点击“检查／重试”，按 Root 管理器提示授权。此步骤只检查身份。
3. 按 [官方说明](https://shizuku.rikka.app/guide/setup/) 安装并通过 Root 启动 Shizuku。设置中选择 Shizuku Root，点击“授权 Shizuku”，再点击“检查／重试”。应分别看到授权、UID 0 和 UserService 检查状态。
4. 在原存档页选择正确包名，先只做读取/刷新。读取会按原流程关闭目标游戏。失败时到设置展开并复制本地诊断，附设备、Android、Root 工具、游戏与 APK 版本反馈；不需要先写入存档。
5. 检查切换访问方式后需要重新读取，旧预览不可继续保存。ADB 模式应显示明确的不支持提示。若存在未完成事务，先保持原后端并进入原恢复流程。

实际编辑仍需原预览与备份流程；本次合成文件测试不能保证真实存档可用，也不能证明全部 Root 管理器、渠道或后续游戏版本均已兼容。
