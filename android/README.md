# Android 工程

这里是“骑士档案馆”的 Android 源码。当前正式版 **2.0.5-rc04 / versionCode 29**，需要 Android 7.0 及以上和 Root；原生 Root 默认，Shizuku Root 可选。

- 直接使用：[下载 APK](https://github.com/Show-o4210/Soul-Knight-Save-Editor/releases/download/v2.0.5-rc04/SoulKnightSaveEditor-2.0.5-rc04.apk) · [图文教学](../docs/help/getting-started.md)
- 开发：[实现与构建](../docs/development/android-implementation.md) · [开发文档索引](../docs/development/README.md)
- 版本与边界：[2.0.5-rc04 发布说明](../docs/releases/2.0.5-rc04.md) · [开发状态](../docs/development-status.md)

本版增加 `.data`／`.data.new` 格式切换，新格式保留读取开关，还原时可选择开关处理方式。

在本目录使用 JDK 21：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Debug 包与正式 APK 签名不同，安装迁移前先保存外部备份，见[备份与更新](../docs/help/backup-and-update.md)。正式版沿用原签名，可覆盖更新。
