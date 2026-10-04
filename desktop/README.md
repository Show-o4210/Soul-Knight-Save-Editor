# Python 桌面版

本目录是原有的 Python / PySide6 存档编辑器，和 `../android/` 是独立客户端。它通过本地 `输入/`、`参考/`、`输出/` 工作，不直接连接手机或修改游戏私有目录。

本 Python 客户端不再持续维护。来自 [PR #1](https://github.com/Show-o4210/Soul-Knight-Save-Editor/pull/1) 的 iOS/PList 实现已适配到本目录，作为未经验证的社区贡献保留，不代表已交付可用的 iOS 支持。它尚未通过真实 iOS 存档或设备验证，已知存在布尔值保存为整数 0、嵌套 data/date 解析失败、混合输入识别及部署路径不准确等问题；完整修复和设备验证暂缓。仅供参考实现思路，不建议用于重要存档的实际写回。

旧版“武器获取次数 +8”实际修改 `_weaponUsedTimes`。8.6.0 实测确认正确目标为统计文件顶层 `object2ObtainTime`；桌面代码尚未迁移这个新规则，不能用旧按钮判断锻造资格。Android alpha11 已接入正确字段，详见[武器调查](../docs/weapon-forge-investigation-8.6.0.md)和[新功能规则](../docs/quick-weapons-alpha11.md)。

## 启动

需要 Python 3.8+。在**本目录**执行：

```powershell
python -m pip install -r requirements.txt
python run.py
```

Windows 也可使用 `快速开始.bat`。桌面工具依赖 PySide6 和 pycryptodome。

1. 将自己从设备上取得的存档放入 `输入/`，不要混用不同账号的文件。
2. `参考/` 用于可选的增量合并。仓库中已有旧版公开参考样本；如使用自己的参考存档，请仅保留在本机。
3. 在界面中预览并输出后，从 `输出/` 核对修改结果，再自行部署到设备。原始输入不会被覆盖。

旧版的材料、花圃、武器记录等能力只属于此桌面客户端；不能据此推断 Android 预览版也支持这些项目。脚本和测试仍以本目录作为项目根目录。当前目录调整没有改变桌面版业务逻辑。

