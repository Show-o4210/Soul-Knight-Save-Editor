# Android 2.0.0 正式发布准备

> 历史开发档案：以下状态、界面与测试对应标题所示版本。当前用户功能见[帮助中心](help/README.md)，当前发布与未验收范围见[开发状态](development-status.md)。

日期：2026-10-01。版本：`2.0.0` / versionCode 16。状态：**正式签名与发布材料就绪，GitHub 草稿准备；尚未执行公开发布。**

## 来源与范围

- 用户提出正式签名和 GitHub 正式发布准备，本次启动正式版 V1 计划的 P4。
- 已先 fetch 并 pull 最新 `origin/main`，合并截至 `16c4a0f` 的远端进度；本地 alpha06～14 实现完整保留。保留远端新增的 Root 真机游戏闪退反馈；当时本地读取说明以根目录文件为准；现已归入[用户帮助](help/local-data-reading.md)，根目录及旧路径均保留入口。
- 发布分支为 `release/android-2.0.0`，GitHub 正式标签拟为 `v2.0.0`，发布类别为正式版，草稿阶段不对外发布。
- 本次只打包 Android；Python 目录保持独立，不把 Android 的功能进度视作 Python 已同步完成。

这是首个 Android 正式发布里程碑，不定义为项目最终交付，后续继续完善功能。发布说明见 [2.0.0](releases/2.0.0.md)。

## 签名与安装策略

- applicationId 保持 `com.example.soul_knight_save_editor`，正式 Release 为不可调试 APK，沿用项目配置启用 R8 优化。
- 使用独立 PKCS12 密钥，RSA 3072 / SHA256withRSA；证书有效期 10000 天。
- 公开证书 SHA-256：`CE1FF5A2C745CAFDF385DDA40B21840625B4C909BB9BB289B89CB01F0230D87B`。
- `apksigner verify --verbose --print-certs` 校验通过，APK v2 签名有效，适用于 minSdk 24。
- 私钥及密码配置放在仓库外并限制 Windows 目录访问权限，未提交 Git。具体本地备份位置单独交付给维护者，公开文档只记录规则。
- 历史 alpha 是调试签名，无法直接覆盖。需先导出内部备份、处理未完成事务，然后卸载旧助手再安装正式版。此后使用同一正式密钥且增加 versionCode 发布更新。
- 保留现有虚拟机的 alpha14 和内部备份，没有为测试安装正式签名而卸载它；正式 APK 的实际安装体验由用户在完成备份后检查。

构建方式与后续密钥维护见[签名文档](android-signing.md)。

## 验证结果

- `testDebugUnitTest`：118 项中 114 通过，0 失败／错误，4 项可选私有样本测试未注入输入而跳过。本轮完整执行公开核心回归，历史已注入私有样本的通过记录继续保留。
- `assembleRelease`、`lintRelease` 通过；lint 0 errors / 43 warnings，未升级依赖和工具链。
- APK 签名、包名、versionName／versionCode、minSdk 24 和 targetSdk 36 已核对；包含 arm64-v8a、armeabi-v7a、x86、x86_64。
- 发布附件为 APK、`SHA256SUMS.txt` 和公开证书；实际 APK SHA-256 在附件及本地构建记录登记。
- 签名构建关闭 Gradle 配置缓存；密码不放在命令参数、Git 或公开附件中。

本轮没有直接写真实游戏存档，也没有把用户已测样本扩大为全部版本／渠道／目录条目验收。游戏内反馈、尚未验证的专家条目和新格式边界继续见[开发状态](development-status.md)。
