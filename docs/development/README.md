# 开发文档

[项目首页](../../README.md) · [用户帮助](../help/README.md) · [文档总索引](../README.md)

本目录及相邻文档记录当前实现、构建方式和历史技术决策。**当前发布基线是 Android 2.0.5-rc05 / versionCode 30**；旧 alpha、dev 和 rc 文档只描述当时状态，不作为当前实现依据。

## 当前维护入口

- [Android 当前实现与构建](android-implementation.md)
- [开发状态、验证证据与未验收范围](../development-status.md)
- [已知问题与待验证范围](../known-issues.md)
- [正式签名与证书](../android-signing.md)
- [2.0.5-rc05 发布说明](../releases/2.0.5-rc05.md)
- [文档维护规则](../documentation-policy.md)

## 当前数据规则与调查依据

| 主题 | 文档 |
| --- | --- |
| 武器进化与神话武器 | [目录、字段和等级依据](../weapon-upgrades-8.7.0.md) |
| 武器皮肤 | [皮肤目录、索引与编辑规则](../weapon-skins-8.7.0.md) |
| 角色重新锁定 | [原锁值来源与限制](../character-relocking.md) |
| vivo 账号格式 | [vivo 8.6.0 适配档案](../vivo-channel-2.0.1-rc01.md) |
| Root / Shizuku | [实现与专项验证记录](../root-shizuku-validation.md) |

## 历史实现与调查档案

以下文档保留早期设计依据。涉及当前行为时，先以当前实现说明和开发状态为准，再把历史文档作为背景材料。

- 核心 API 与业务草稿：[alpha12](../core-api-alpha12.md)
- 固定路径刷新与界面体验：[alpha13](../experience-alpha13.md)
- 标题与彩蛋：[alpha14](../header-easter-egg-alpha14.md)
- 渠道发现：[alpha07](../navigation-discovery-alpha07.md)
- 物品目录：[alpha06](../quick-items-alpha06.md)
- 宠物映射：[alpha08](../quick-pets-alpha08.md) · [8.6.0 映射](../pet-mapping-8.6.0.md)
- 武器获取次数：[alpha11 规则](../quick-weapons-alpha11.md) · [8.6.0 调查](../weapon-forge-investigation-8.6.0.md) · [高编号名称映射](../weapon-high-id-name-map-8.6.0.md)
- [Android 正式版 V1 历史计划](../formal-v1-development-plan.md)
- [2.0.0 发布准备历史记录](../release-2.0.0.md)

## 历史发布说明

[2.0.0](../releases/2.0.0.md) · [2.0.1-rc01](../releases/2.0.1-rc01.md) · [2.0.2](../releases/2.0.2.md) · [2.0.3](../releases/2.0.3.md) · [2.0.4-rc01](../releases/2.0.4-rc01.md) · [2.0.5-rc01](../releases/2.0.5-rc01.md) · [2.0.5-rc02](../releases/2.0.5-rc02.md) · [2.0.5-rc03](../releases/2.0.5-rc03.md) · [2.0.5-rc04](../releases/2.0.5-rc04.md) · [2.0.5-rc05](../releases/2.0.5-rc05.md)

测试结论必须结合对应文件注明的日期、设备、渠道、样本和操作范围理解。构建成功、合成文件测试或离线预览通过，不等于真实游戏效果已经验收。
