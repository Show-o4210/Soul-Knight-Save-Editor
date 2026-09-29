# Android 本地助手（2.0.0-alpha05）

这是需要 Root 的本地存档开发预览版。打开本目录作为 Android Studio 项目，或在本目录使用 JDK 21 运行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。该 APK 使用调试签名；预发布附件适合隔离测试，不保证能直接覆盖旧版安装。

## 当前编辑范围

| 数据 | 快捷模式 | 详细模式 |
| --- | --- | --- |
| 现有角色与皮肤 | 分别一键解锁，默认关闭 | 逐项选择 |
| 现有角色等级 | 低于 7 级时提升至 7 级，默认关闭 | 暂无 |
| 已有技能条目 | 解锁未解锁条目，默认关闭 | 暂无 |

编辑目标为识别出的 `game.data` 和 PlayerPrefs XML。XML 单文件模式仅支持角色、皮肤。其他 `.data` 可进入只读备份，但尚不能编辑。预览、写前漂移检查、原件备份、写后复读、失败恢复均在应用内实现；ZIP 仅能导出，不能导入或整包恢复。

首次写入前需确认个人本地存档用途。扫描会关闭所指定的游戏进程。应用不登录账号、不访问云端，也没有联网权限。

`alpha05` 的等级与技能尚待设备写回及游戏内验收。请先备份自己的原始文件，再在专用测试设备上验证。详见[开发状态](../docs/development-status.md)。
