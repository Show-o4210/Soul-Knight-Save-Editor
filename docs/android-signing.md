# Android 正式签名与构建

正式包使用独立 PKCS12 密钥，RSA 3072 / SHA256withRSA。私钥和签名密码必须存放在仓库外；不要放入 Git、Release 附件或工单。

## 私有签名配置

仓库外的 `signing.properties` 需包含 `storeFile`、`storeType`、`keyAlias`、`storePassword`、`keyPassword`。`storeFile` 可以是相对配置文件的路径，也可以是绝对路径。当前格式为 PKCS12，密钥别名固定为 `soul-knight-editor-release`。

PowerShell 构建时仅设置本进程变量；密码不放进命令行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:SK_SIGNING_PROPERTIES = '仓库外的绝对路径\signing.properties'
.\gradlew.bat --no-configuration-cache assembleRelease lintRelease
```

从 `android/` 执行。没有私有配置时可构建 Debug；Release 会拒绝生成未签名的交付包。签名配置和密钥位于仓库内时也会拒绝构建。带签名配置的构建必须关闭 Gradle 配置缓存，避免密码被序列化进缓存。

构建结果为 `app/build/outputs/apk/release/app-release.apk`。使用当前 SDK 的 `apksigner verify --verbose --print-certs` 检查签名；在发布前核对 versionCode、versionName 和 SHA-256。

## 密钥保管与更新

- 备份整个私有签名目录，包括密钥文件、密码配置和保管说明；只备份 `.p12` 而丢失密码无法继续签名。
- 建议备份到自己控制的加密存储。密钥或密码丢失后，无法用另一把新密钥直接更新已安装的正式应用。
- 后续正式更新继续使用同一密钥、同一 applicationId，并增加 versionCode。
- 首次正式版与历史 alpha 调试包签名不同，安装迁移步骤见[2.0.0 发布说明](releases/2.0.0.md)。

仓库 `.gitignore` 排除常见密钥与签名配置格式。这是误提交保护；实际保管位置仍必须在仓库外。公开证书不包含私钥，可以作为发布附件。
