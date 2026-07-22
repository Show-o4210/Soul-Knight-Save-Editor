# 骑士档案馆 (Soul Knight Save Editor)

<div align="center">

> **一款专为《元气骑士》打造的高安全性、完全离线、支持多端（Python / Android）的图形化存档修改与智能合并工具。**

[![Support](https://img.shields.io/badge/Support-赞赏支持-FF69B4.svg)](#-赞赏支持-support)
[![Python](https://img.shields.io/badge/Python-3.8+-3776AB.svg?logo=python&logoColor=white)](https://www.python.org/)
[![Android](https://img.shields.io/badge/Android-Kotlin_&_Compose-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](#-开源许可-license)

---

### ❤️ 赞赏支持 (Support)

如果您觉得本项目对您有所帮助，欢迎打赏支持作者的持续开发与维护！

<img src="support.png" alt="赞赏码 support.png" width="260" />

*(请将 `support.png` 放置在仓库根目录)*

---

</div>

> [!WARNING]
> 修改存档存在一定风险，请在操作前务必**手动备份您的原始存档文件**！

---

## 📖 项目简介 (Overview)

**骑士档案馆 (Soul Knight Save Editor)** 旨在解决传统修改器或手动替换存档时容易导致的**存档损坏、进度覆盖失误、云同步失效（`.data.new` 死锁）**等痛点。通过双端（Python 桌面版 / Kotlin Android 原生版）全套编解码引擎与严密的安全控制流，为玩家与开发者提供可视化、零风险的存档管理体验。

### 核心功能一览

1. **多分片存档解包与编解码 (Multi-Shard Codec)**
   - 自动识别并解密《元气骑士》复杂的存档分片结构，包含主存档 (`game.data`)、物品分片 (`item_data`)、统计分片 (`statistic`)、武器进化分片 (`weapon_evolution_data`) 及系统配置 (`PlayerPrefs XML`) 等。
   - 完美适配多种加密与编码方案（XOR、DES-CBC 变体及 Plain Text）。
2. **高颗粒度可视化编辑 (Comprehensive Save Editing)**
   - **角色与技能**：一键全角色/全技能解锁、角色等级精准调节（1~15 级）。
   - **皮肤与宠物**：全皮肤解锁（同步更新 `game.data` 与 `PlayerPrefs`）、全宠物解锁与独立选择。
   - **物品与花圃**：材料/种子数量自定义、蓝图获取、特定物品解锁、花圃槽位与植物生长状态（0~20 级）精准配置。
   - **客厅与武器**：客厅设施等级配置、秘密钥匙追加、常规武器锻造解锁（获取次数统一 +8）与单武器使用次数调整。
3. **智能增量合并 (Union Reference Sync)**
   - 支持将“参考存档”或他人满资源存档作为参考源，采用 **Union 合并策略**（只补缺少项目，绝不覆盖已有进度），一键无损补全当前账号缺少的蓝图、材料或武器记录。
4. **云同步死锁修复 (Legacy Cloud Reset)**
   - 针对游戏使用云存档后将新进度写入 `*.data.new` 导致本地原 `*.data` 停更的常见问题，提供一键安全备份并清除临时分片与缓存、强制同步 `OpenRijTest=0` 引导重启重新重建云同步。
5. **版本化备份与安全导出/写回 (Backup & Export/Deploy)**
   - **本地版本库**：支持创建不可变的本地存档快照，包含 SHA-256 完整性清单，随时可一键恢复至新工作区。
   - **免 Root 导出**：导出包含标准 SHA-256 校验清单与部署说明的 ZIP 压缩包，方便手动导入。
   - **Root 直写部署**：拥有 Root 权限时，支持自动化原子级部署写回游戏私有目录。

---

## 🛠️ 核心架构与技术亮点 (Technical Highlights)

### 1. 工业级安全设计与纵深防御 (Security & Robustness)
- **完全离线保障 (Zero Network Risk)**  
  应用未申请 `INTERNET` 联网权限，所有存档解析与修改纯在本地进行，零数据上报风险。
- **物理隔离与只读保护**  
  程序仅对输入源目录进行只读读取，一切修改成果均写入独立的输出目录或临时内存区，杜绝污染原始文件。
- **Root 部署原子性与自动回滚 (Atomic Write & Transaction Rollback)**  
  在 Root 直写模式下执行严密事务控制：强制停止游戏进程并抓取 live 目标存档创建不可变备份 $\rightarrow$ 临时文件写入与元数据恢复（`chown` / `chmod` / SELinux `restorecon`） $\rightarrow$ `mv -f` 原子替换与二次复读校验 $\rightarrow$ 遇异常逆序自动回滚。
- **防御性 ZIP 解压与 DOM 解析**  
  内置 `SafeZipExtractor` 防范 **Zip Slip 路径穿越**、文件名注入及 Zip Bomb；XML 解析显式禁用 DTD 与外部实体，彻底防范 **XXE 漏洞**。

### 2. 强一致性编解码与镜像引擎 (Codec & Dual-Mirror Engine)
- **精准算法映射 (`SaveCodec` & `ShardRegistry`)**  
  - `game.data` $\rightarrow$ XOR 算法  
  - `statistic` $\rightarrow$ DES (CRST1 密钥)  
  - `item_data` 等分片 $\rightarrow$ DES (IAMBO 密钥)  
  - `PlayerPrefs` $\rightarrow$ XML DOM Codec  
- **双向镜像同步机制 (`patchGameItemMirror` / `syncGameItemMirror`)**  
  维护 `item_data` 分片与 `game.data` 内部 `itemData` 镜像字段的**强一致同步更新**，防止因分片与主存档镜像冲突导致游戏写回覆盖或数据不同步。
- **格式标准化**  
  在 PlayerPrefs 中自动将角色与宠物解锁布尔值标准化为 Unity 存储格式（`True`/`False`）。

### 3. 可预测的修改校验与预览 (Differential Patch Preview)
- **内存级预编码校验**：在真正写回或导出前，修改引擎先在内存中构建目标 Bundle 并执行完整解密/解析复读测试（Verify Encoded）。
- **透明 Byte & SHA-256 差分**：提供“校验并生成预览”功能，清晰对比变动文件名、文件大小及 SHA-256 哈希值变化。

---

## 💻 快速开始与多开发环境指南 (Getting Started)

本仓库提供**环境一：Python 桌面 GUI/命令行工具** 与 **环境二：Android Native 原生 App** 两种开发/运行形态。

---

### 环境一：Python 桌面端 / 脚本开发环境

适合在 PC 端进行离线存档解析、快速测试、批量修改与 PySide6 GUI 交互。

#### 1. 安装依赖环境
环境要求 Python 3.8+。核心加解密逻辑已内置于 `core/crypto.py`（纯 Python 实现 XOR + DES），不依赖 UnityPy 或 soul-knight-data-processing，极易打包部署。
```bash
pip install -r requirements.txt
```
*(依赖库仅包含 `PySide6` 与 `pycryptodome`)*

#### 2. 运行 PySide6 图形界面 (GUI)
运行主入口脚本启动桌面编辑器：
```bash
python run.py
```

#### 3. 执行回归与验收测试
项目内置完整的测试套件，可直接运行：
```bash
python -Xutf8 tests/accept_crypto_parity.py   # 去 UnityPy/SKD 验收门禁（含与 SKD 字节对照）
python -Xutf8 tests/test_crypto_pure.py       # 纯算法加解密测试
python -Xutf8 tests/test_v2.py                # V2 版本逻辑测试
python -Xutf8 tests/test_v3.py                # V3 版本逻辑测试
```

---

### 环境二：Android 原生 App 开发环境

适合构建运行于 Android 设备的“骑士档案馆”App，提供 Jetpack Compose 现代化 UI、免 Root / Root 原子部署与版本库管理。

#### 1. 技术栈说明
- **语言**: Kotlin (1.9+)
- **界面**: Jetpack Compose, Material 3, Edge-to-Edge 沉浸式设计
- **并发与序列化**: Kotlin Coroutines, Kotlinx Serialization
- **安全与工具**: Root 事务 Shell 脚本引擎、SafeZipExtractor、DOM XML Codec

#### 2. 交互模式与 UI
- **通用模式**：面向普通玩家，提供开箱即用的开关与数值调节。
- **专家模式**：面向高级用户与开发者，展示会话 ID、UID 校验、分片 SHA-256 哈希与元数据诊断信息。

#### 3. Android 单元与破坏性测试套件
```kotlin
SaveCodecTest              // 加解密正确性与各分片算法映射测试
PatchEngineTest            // 修改逻辑与修改范围校验测试
ReferenceMergeEngineTest   // 参考同步与 Union 合并算法测试
ImportSecurityTest         // 解压与导入安全屏障测试
RootGatewayDestructiveTest // Root 部署失败时的破坏性回滚验证测试
```

---

## 📂 项目目录结构说明 (Directory Structure)

```
SoulKnightSaveEditor/
├── run.py                 # Python 桌面 GUI 启动入口
├── requirements.txt       # Python 第三方依赖定义
├── 输入/                  # 待修改的存档源文件目录（只读源）
├── 参考/                  # 全满参考存档目录（仅增量合并 union 时使用）
├── 输出/                  # 修改应用后的结果目录（自动生成/清空重写）
├── core/                  # 核心业务层（加解密、SaveCodec、Store 数据结构、Union 合并策略）
├── engine/                # 补丁执行引擎（PatchRunner / PatchPlan / 镜像同步）
├── gui/                   # PySide6 桌面端界面与本地化资源 (strings.json)
├── scripts/               # 辅助开发工具（精简输入、实体键名提取等）
├── tests/                 # Python 端集中回归与验收测试脚本
├── docs/                  # 开发与设计文档（包含详细结构说明）
└── android/               # Android Native 原生客户端源码 (Kotlin / Compose)
```

---

## 📱 手机存档路径与文件放置指南

### 0. 手机上的存档路径（最重要）

从 Android 设备取出存档时，路径是固定的（需 Root / 备份工具 / 可访问私有目录的文件管理器）：

| 文件类型 | 手机所在路径 | 常见文件示例 |
|:---|:---|:---|
| **所有 `.data` 分片** | `/data/data/com.ChillyRoom.DungeonShooter/files/` | `game.data`、`item_data_{UID}_.data`、`statistic_{UID}_.data`、`weapon_evolution_data_{UID}_.data`、`setting_{UID}_.data` 等 |
| **PlayerPrefs XML** | `/data/data/com.ChillyRoom.DungeonShooter/shared_prefs/` | `com.ChillyRoom.DungeonShooter.v2.playerprefs.xml`（不同版本名称可能略有差异） |

> **提示**：软件界面（GUI / App）、工作台与弹窗中均会明确提示缺少哪个文件及对应的手机路径。缺少文件时请对照提示复制到本地 `输入/` 目录中。

---

### 1. `输入/` 目录放置规则

将从手机提取的文件按修改需求放入 `输入/` 目录：

* **必备文件（始终需要）**：
  * `game.data` $\leftarrow$ 手机 `files/game.data`
  * `com.ChillyRoom.DungeonShooter.v2.playerprefs.xml` $\leftarrow$ 手机 `shared_prefs/`
* **按需放置（根据修改项目选择）**：
  * `item_data_{UID}_.data` — 修改花圃、材料、种子、蓝图、神话武器时必须放入
  * `setting_{UID}_.data` — 改动物品且需同步 Legacy 开关时放入
  * `weapon_evolution_data_{UID}_.data` — 修改/合并武器进化等级时放入
  * `statistic_{UID}_.data` — 修改地牢武器获取次数 / 常规武器锻造 (+8) 时放入
* **切勿放置**：
  * 未修改的分片文件（如 `bp`、`task`、`season` 等）
  * 日志或临时文件（如 `bugly`、`ace_shell` 等）
  * 无 UID 的冗余副本（如 `item_data.data`）

---

### 2. `参考/` 目录放置规则 (增量合并)

仅在执行 **增量参考合并 (Union Merge)** 时使用：
* 在 `参考/` 目录下放置参考号（全满号）的 `playerprefs.xml`（来自参考号 `shared_prefs/`）。
* 放置对应 UID 的分片文件（来自参考号 `files/`，如 `item_data_{UID}_.data`）。

---

### 3. `输出/` 目录部署方法

点击“应用并输出”后：
1. `输出/` 目录会被**自动清空并重新生成**。
2. 仅输出本次修改触及的加密分片文件，未被修改的文件不会在输出目录中产生。
3. 伴随生成 `部署说明.json` 与 `changes_preview.json`。
4. **覆盖写回手机**：
   * `.data` 文件覆盖到 `/data/data/com.ChillyRoom.DungeonShooter/files/`
   * XML 文件覆盖到 `/data/data/com.ChillyRoom.DungeonShooter/shared_prefs/`

---

## 📄 开源许可 (License)

本项目采用 [MIT License](LICENSE) 许可协议。
