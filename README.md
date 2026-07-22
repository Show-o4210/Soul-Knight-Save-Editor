# 元气骑士存档编辑器 (Soul Knight Save Editor)

这是一个专门针对《元气骑士》本地离线存档的图形化修改与合并工具。支持游戏主存档、PlayerPrefs、材料及武器进化数据的深度编辑与智能增量合并。

> [!WARNING]
> 修改存档存在一定风险，请在操作前务必**手动备份您的原始存档文件**！

---

## 🚀 快速开始

### 1. 安装依赖环境
确保已安装 Python 3.8+，然后在项目根目录下执行以下命令安装运行所需的第三方库：
```bash
pip install -r requirements.txt
```

依赖仅需 **PySide6** 与 **pycryptodome**。加解密已内置于 `core/crypto.py`（XOR + DES），**不依赖** `soul-knight-data-processing` / UnityPy，便于 PyInstaller 等打包。

### 2. 运行图形界面 (GUI)
运行主入口脚本启动编辑器：
```bash
python run.py
```

### 3. 执行回归测试 (可选)
如果需要对修改功能进行回归验收，可运行测试脚本：
```bash
python -Xutf8 tests/accept_crypto_parity.py   # 去 UnityPy/SKD 验收门禁（含与 SKD 字节对照）
python -Xutf8 tests/test_crypto_pure.py
python -Xutf8 tests/test_v2.py
python -Xutf8 tests/test_v3.py
```

---

## 📁 目录结构说明

整理后的目录非常清晰，分离了核心逻辑、用户数据、测试与文档：

```
存档编辑器/
├── run.py                 # 独立环境 GUI 启动入口
├── requirements.txt       # 项目依赖定义文件
├── 输入/                  # 待修改的存档源文件目录（只读源）
├── 参考/                  # 全满参考存档目录（仅增量合并 union 时使用）
├── 输出/                  # 修改应用后的结果目录（每次保存时自动清空重写）
├── core/                  # 核心业务层（加解密、数据结构 Store、合并策略）
├── engine/                # 补丁执行引擎（PatchRunner / PatchPlan 逻辑）
├── gui/                   # PySide6 表现层界面与本地化 strings.json
├── scripts/               # 辅助开发工具（精简输入、收集实体键名等）
├── tests/                 # 集中回归与验收测试脚本
└── docs/                  # 开发与设计文档（包含详细的《结构.md说明》）
```

---

## 📖 使用指南

### 0. 手机上的存档在哪里？（最重要）

从 Android 设备取出存档时，路径是固定的（需 root / 备份工具 / 可访问应用私有目录的文件管理器）：

| 类型 | 手机路径 | 常见文件 |
|------|----------|----------|
| **所有 `.data` 分片** | `/data/data/com.ChillyRoom.DungeonShooter/files/` | `game.data`、`item_data_{UID}_.data`、`statistic_{UID}_.data`、`weapon_evolution_data_{UID}_.data`、`setting_{UID}_.data` 等 |
| **PlayerPrefs XML** | `/data/data/com.ChillyRoom.DungeonShooter/shared_prefs/` | 通常为 `com.ChillyRoom.DungeonShooter.v2.playerprefs.xml`（版本不同时也可能是**名字类似**的 `*playerprefs*.xml`） |

> 工具在 GUI「设置」页、工作台提示、以及「输入不完整」弹窗里，都会**写明缺哪个文件、在手机哪个路径**。缺文件时请对照提示复制到本地 `输入/` 后再点「刷新载入」。

### 1. 输入目录 (`输入/`) 放置规则

把上表中从手机复制出的文件，按修改目标放入 `输入/` 目录：

* **必备文件（始终需要）**：
  * `game.data` ← 手机 `files/game.data`
  * `com.ChillyRoom.DungeonShooter.v2.playerprefs.xml` ← 手机 `shared_prefs/`（名字类似也可）
* **按需放置（根据需要修改的项目；均在手机 `files/`）**：
  * `item_data_{UID}_.data` — 涉及花圃、材料、种子、蓝图、神话武器修改时必须放入
  * `setting_{UID}_.data` — 改动物品时，若需同步 Legacy 开关则放入
  * `weapon_evolution_data_{UID}_.data` — 涉及武器进化等级合并时放入
  * `statistic_{UID}_.data` — 涉及修改地牢武器获取次数 / 常规武器锻造 +8 时放入
* **不要放置**：
  * 未修改的分片文件（如 `bp`、`task`、`season` 等）
  * 与存档无关的日志文件（如 `bugly`、`ace_shell` 等）
  * 无 UID 区分的冗余副本（如 `item_data.data`）

### 2. 参考目录 (`参考/`) 放置规则
仅在需要进行 **增量参考合并 (Union Merge)** 时使用。在该文件夹下放置全满号的 `playerprefs.xml`（同样来自参考号手机的 `shared_prefs/`）以及对应 UID 的分片文件（来自参考号手机的 `files/`，例如 `item_data_{UID}_.data` 等）。

### 3. 输出目录 (`输出/`) 部署方法
每次在 GUI 界面点击“应用并输出”后：
1. `输出/` 文件夹会被**清空并重新生成**。
2. 仅会输出本次修改/合并**触及的加密分片文件**，未被修改的文件不会在输出目录中产生。
3. 输出目录中会伴随生成 `部署说明.json` 和 `changes_preview.json`，提供详细的部署步骤清单。
4. **写回手机时按原路径覆盖**：
   * `.data` → `/data/data/com.ChillyRoom.DungeonShooter/files/`
   * XML → `/data/data/com.ChillyRoom.DungeonShooter/shared_prefs/`

---

## 🛠️ 核心修改原则与安全设计

1. **增量合并，拒绝覆盖**：参考号合并时只追加您的账号缺失的项目，不会覆盖您原有的存档进度。
2. **输入输出物理隔离**：程序仅对 `输入/` 目录进行只读读取，一切修改成果均写入 `输出/` 目录，杜绝污染原始文件的隐患。
3. **Legacy 开关强制同步**：在修改物品分片时，程序会自动设置 `OpenRijTest=0` 并同步相应开关，确保修改后的数据能够顺利被游戏客户端读取加载。
4. **布尔类型强制标准化**：自动在 PlayerPrefs 中将角色和宠物解锁的布尔值统一同步为首字母大写格式（`True`/`False`），完美契合 Unity PlayerPrefs 的底层存储格式。
