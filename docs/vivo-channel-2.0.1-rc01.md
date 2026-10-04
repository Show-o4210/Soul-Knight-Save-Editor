# vivo 渠道适配：2.0.1-rc01

日期：2026-10-04。状态：**用户已实测 vivo 角色和皮肤解锁成功，[v2.0.1-rc01](https://github.com/Show-o4210/Soul-Knight-Save-Editor/releases/tag/v2.0.1-rc01) 已从预发布转为最新正式发布；其余 vivo 功能仍待游戏内验收。** 转正保留已实测 APK、标签和版本字符串，不重新打包；GitHub `v1` 的 `2.0.0` 附件保留，不包含此修复。

## 原因与样本范围

用户报告 `com.liangwu.yuanqiqishi.vivo` 能被发现，却无法编辑。Root 只读检查确认该 vivo 8.6.0 样本使用 16 位小写十六进制账号；旧版扫描与 XML 识别只接受数字账号，因此漏掉真正的账号文件。

- 角色 XML 位于 `shared_prefs/com.liangwu.yuanqiqishi.vivo.v2.playerprefs.xml`，角色、皮肤、等级与宠物键均有相同账号前缀。
- 实际账号物品文件为 `item_data_<账号>_.data`，`AppVersion=80600`；武器统计为同账号 `statistic_<账号>_.data`。
- 同目录的默认 `item_data.data` 是 `AppVersion=0` 的初始化文件，不能代替实际账号分片；旧扫描只找到默认文件，随后被版本检查拒绝。
- 游戏 Android 包的 versionCode 为 80601，存档内部 AppVersion 为 80600。物品／武器目录继续按存档内部版本核对。
- `game.data`、账号 XML、物品与统计分片均能通过现有解码和结构检查，无需变更编码算法。

私有样本、真实账号 ID 和助手安装包检查副本均保存在仓库外；公开测试只使用虚构账号。

## 共用账号 API

`SaveAccountId.TOKEN_PATTERN` 统一允许：原有数字账号、此次已确认的 16 位小写十六进制账号。账号始终保留为字符串，不转换为数字，不去掉前导零，不改变大小写。

`SaveAccountId.roleUnlock/skinUnlock` 供 `SaveDiscovery` 与 `UnlockEngine` 共用；`SaveSource.ITEM/STATISTIC` 使用同一账号模式。`SaveLayout` 由来源注册表生成可编辑路径规则，`RootStorage` 因而可以校验这些账号文件，固定路径刷新也沿用同一规则。后续若社区提供新的账号格式，应在此入口补充已确认规则及配对测试。

- XML、物品、武器按完整账号精确配对；存在命名账号时不以默认文件补缺。
- 多账号、同账号重复分片、缺少配对文件、角色镜像不一致时继续按原规则拒绝相应操作。
- `.data.new` 仍不参与编辑；路径限定于所选包名与 Android 用户，符号链接和越界检查继续执行。
- 快速与专家模式共用这些底层规则，无须分别实现渠道判断；物品／武器仍要求 `AppVersion=80600`。

本轮兼容记录限定于上述 vivo 8.6.0 样本。其他渠道是否使用相同格式和镜像结构，继续等待社区反馈。

## 验证记录

- 核心 JVM：123 项，119 通过、0 失败／错误、4 项旧可选私有样本未提供而跳过。
- 新增 vivo 回归 5 项全部通过：账号格式、发现与正式扫描、固定路径刷新、多账号隔离与默认文件排除、重复分片及版本／镜像保护、私有样本组合修改。
- 私有样本中角色、等级技能、宠物、物品与武器能力报告均通过；在内存中计算所选角色、宠物、材料 +1000、单武器 +8，并重新解码核对结果。原始样本字节未变，没有写真实游戏存档。
- `assembleRelease` 通过，包含 R8 与 lintVital；APK v2 签名有效、不可调试，minSdk 24 / targetSdk 36。本轮未重跑完整 lintRelease。
- 正式证书 SHA-256：`CE1FF5A2C745CAFDF385DDA40B21840625B4C909BB9BB289B89CB01F0230D87B`，与虚拟机现有正式助手相同。
- 候选 `2.0.1-rc01` / versionCode 17 已覆盖安装到 `emulator-5554` 并启动，保留助手数据；APK SHA-256：`E0D2F7EC7EF53E5DCD8BE91276615FAD61D6A2AAE555116962F0FEEA92B9C176`。
- 用户随后对 vivo 渠道进行了角色及皮肤解锁的简单游戏内测试，反馈成功。该结果只登记为已测样本有效，不扩大为等级、技能、宠物、物品、武器或其他渠道全部验收。

可在 Android 项目目录执行以下只读样本回归；签名构建另见[签名说明](android-signing.md)：

```powershell
.\gradlew.bat --no-configuration-cache testDebugUnitTest '-PvivoBaseline=<仓库外私有样本目录>'
```

目录需包含同账号 `game.data`、PlayerPrefs XML、item 与 statistic 分片；不提供参数时私有 vivo 测试按设计跳过。

## 后续用户二测

1. 更新后选择 vivo 版本，重新读取存档，确认角色／物品／武器页可加载。
2. 在设置中导出原始备份，任选少量修改，核对预览和写回反馈，再进入游戏检查效果。
3. 可再做一次增量编辑或恢复，确认固定路径读取最新内容。

测试反馈请注明游戏版本、模式、功能及报错；离线通过仅说明这份样本的识别和修改计算有效，不代表游戏内全部条目已验收。
