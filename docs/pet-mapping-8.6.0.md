# 8.6.0 宠物映射调查

调查日期：2026-09-30。仅收集映射，不实现解锁，不修改游戏存档。

## 结论

- 当前样本 `game.data.petUnlock` 有 57 个内部 ID。旧 Python 表有 55 个，前 55 个顺序未变，末尾新增 `CatMecha`、`EscapeMonkey`。这里的“新增”仅指相对旧工具，并不证明首次加入的游戏版本。
- APK 简体中文资源有 56 个 `Pet_name_N` 名称，编号范围 0–56，缺少 17。`Pet_name_55` 为“机甲阿凉”，`Pet_name_56` 为“小疯猴”。按存档顺序，分别对应上述两个新增 ID。
- `Hide` 位于存档顺序 17，但资源中没有 `Pet_name_17`。旧工具称其为“隐身”，属于旧工具标签，不能认定为官方宠物名或正常可获取宠物。建议后续快速解锁暂时排除。
- 两组配对 game/XML 样本均覆盖 `p0`–`p56`，按上述顺序比较为 57/57 相符。多数布尔状态相同，所以值一致不能独立证明每个 ID 与编号一一对应。全部编号仍标为候选，尚无逐项游戏内验证。
- 本次名称以 8.6.0 APK 本地化资源为准。旧工具有“猫咪”“十折”“上海塔”等泛称或译名，不能继续直接作为官方中文名。
- 这 57 条是当前样本覆盖的 ID 集合，不能等同于 57 只当前可获得宠物，也不能据此断言其他版本、渠道或未来更新没有更多宠物。

## 字段观察与边界

- `game.data.petUnlock`：对象，键为内部 ID，样本值为 JSON 布尔值。
- XML：`<UID>_p<N>_unlock`，样本为 `<string>` 节点，内容见 `true`、`false`，也见首字母大写 `True`。比较时做了大小写归一化。表中的 `<UID>` 是占位符。
- 两组样本都包含未解锁的布尔项，因此“尚未获取”不必然意味着字段不存在。不过样本可能来自云下载或编辑器处理，不能据此规定新建存档的初始化方式。
- 宠物解锁不能直接等同于亲密度、技能状态、喂食记录或当前出战选择。本轮未建立这些业务的字段映射；XML 中出现的 `furniture_pet_food_level` 是另一字段，未纳入解锁表。
- 后续实现不应要求必须有 `game.data` 才能判断 XML 可编辑性，也不应把任意 JSON 对象的遍历顺序当作所有渠道都可靠的编号定义。需要依据已验证的版本映射和目标存档实际结构决策。

## 对应方法与证据

1. 从 8.6.0 `game.data.petUnlock` 提取内部 ID 与观察顺序。
2. 与旧工具 `desktop/core/pet_map.py` 的 `CANONICAL_PET_ORDER` 比较。0–54 全部同序，55–56 为增量。旧表自身不是新版游戏配置证明。
3. 从 APK `localization.ab` 中 `mSource.mTerms` 读取 `Pet_name_N`，语言列按 `zh-CN` 定位。名称与资源编号直接对应；内部 ID 与该编号依然依赖顺序推断。
4. 对 09-25 真机、09-27 模拟器的成对 game/XML 做逐编号状态核对，两组各 57/57 一致。两份 game.data 的 SHA-256 完全相同，XML 文件不同；不能视为两份独立的宠物状态样本，更不能视为两个独立的行为实验。
5. 已检查保存的热更新 `localize.csv`，未发现宠物名称覆盖项。APK 和热更新 Luban 二进制中未直接找到这些 ID 的明文，本轮没有继续解码；已保存 Lua 包内仅按文件名找到宠物召唤增益相关项，未取得宠物编号注册表。

## 完整映射

所有 XML 编号均为候选。中文名称直接来自同编号本地化键，ID 对应依据见上文。

| 候选编号 | 中文名 | 内部 ID | XML 键模板 | 备注 |
|---:|---|---|---|---|
| 0 | 阿凉 | `Cat` | `<UID>_p0_unlock` | 旧表已有 |
| 1 | 旺财 | `Dog` | `<UID>_p1_unlock` | 旧表已有 |
| 2 | 火腿 | `Pig` | `<UID>_p2_unlock` | 旧表已有 |
| 3 | 蓝色史莱姆 | `Slime` | `<UID>_p3_unlock` | 旧表已有 |
| 4 | 萝卜卜 | `Robot` | `<UID>_p4_unlock` | 旧表已有 |
| 5 | 熊猫 | `Panda` | `<UID>_p5_unlock` | 旧表已有 |
| 6 | 蹦蹦 | `Rabbit` | `<UID>_p6_unlock` | 旧表已有 |
| 7 | 罐头猪 | `CanPig` | `<UID>_p7_unlock` | 旧表已有 |
| 8 | 嗒普 | `TapTap` | `<UID>_p8_unlock` | 旧表已有 |
| 9 | 豆娃 | `Pet4399` | `<UID>_p9_unlock` | 旧表已有 |
| 10 | 鸽子 | `Gugu` | `<UID>_p10_unlock` | 旧表已有 |
| 11 | 扑扑 | `Owl` | `<UID>_p11_unlock` | 旧表已有 |
| 12 | 海豹宝宝 | `Seal` | `<UID>_p12_unlock` | 旧表已有 |
| 13 | 祖传漏洞 | `Bug` | `<UID>_p13_unlock` | 旧表已有 |
| 14 | 来福 | `Bat` | `<UID>_p14_unlock` | 旧表已有 |
| 15 | 粉红小河马 | `Hippo` | `<UID>_p15_unlock` | 旧表已有 |
| 16 | 小天狗 | `Tengo` | `<UID>_p16_unlock` | 旧表已有 |
| 17 | 未找到名称 | `Hide` | `<UID>_p17_unlock` | 名称缺失，暂排除 |
| 18 | 阿巴 | `Pug` | `<UID>_p18_unlock` | 旧表已有 |
| 19 | 黑麦 | `Corgi` | `<UID>_p19_unlock` | 旧表已有 |
| 20 | 冷喵 | `Cat1` | `<UID>_p20_unlock` | 旧表已有 |
| 21 | 雪诺 | `Cat2` | `<UID>_p21_unlock` | 旧表已有 |
| 22 | 趴趴 | `Rabbit1` | `<UID>_p22_unlock` | 旧表已有 |
| 23 | 牛仔 | `Pig1` | `<UID>_p23_unlock` | 旧表已有 |
| 24 | 阿凉长官 | `LiangSir` | `<UID>_p24_unlock` | 旧表已有 |
| 25 | 布拉尼 | `Blagny` | `<UID>_p25_unlock` | 旧表已有 |
| 26 | 小兲 | `Tortoise` | `<UID>_p26_unlock` | 旧表已有 |
| 27 | 夜歌 | `Serenade` | `<UID>_p27_unlock` | 旧表已有 |
| 28 | 太郎 | `Dog1` | `<UID>_p28_unlock` | 旧表已有 |
| 29 | 布丁 | `Cat3` | `<UID>_p29_unlock` | 旧表已有 |
| 30 | 泡泡 | `Cat4` | `<UID>_p30_unlock` | 旧表已有 |
| 31 | 波奇 | `Dog2` | `<UID>_p31_unlock` | 旧表已有 |
| 32 | 栗子 | `Dog3` | `<UID>_p32_unlock` | 旧表已有 |
| 33 | 炎焱 | `dragon` | `<UID>_p33_unlock` | 旧表已有 |
| 34 | 胡椒 | `Cat5` | `<UID>_p34_unlock` | 旧表已有 |
| 35 | 迷迭猫 | `Cat6` | `<UID>_p35_unlock` | 旧表已有 |
| 36 | 九折 | `TenOff` | `<UID>_p36_unlock` | 旧表已有 |
| 37 | 冲鸭 | `RushDuck` | `<UID>_p37_unlock` | 旧表已有 |
| 38 | 萨卡萨卡 | `SacaSaca` | `<UID>_p38_unlock` | 旧表已有 |
| 39 | 招财 | `Cat7` | `<UID>_p39_unlock` | 旧表已有 |
| 40 | 小锤锤 | `Hammer` | `<UID>_p40_unlock` | 旧表已有 |
| 41 | 粽小子 | `Zongzi` | `<UID>_p41_unlock` | 旧表已有 |
| 42 | 胡椒导演 | `JiaoDirector` | `<UID>_p42_unlock` | 旧表已有 |
| 43 | 进击的漏洞 | `FeatureBug` | `<UID>_p43_unlock` | 旧表已有 |
| 44 | 财满满 | `CoinPig` | `<UID>_p44_unlock` | 旧表已有 |
| 45 | 火力喵 | `FireCat` | `<UID>_p45_unlock` | 旧表已有 |
| 46 | 小史莱姆 | `MiniSlime` | `<UID>_p46_unlock` | 旧表已有 |
| 47 | 粽大哥 | `ZongziBro` | `<UID>_p47_unlock` | 旧表已有 |
| 48 | 土嚎 | `Mahhmot` | `<UID>_p48_unlock` | 旧表已有 |
| 49 | 小豚豚 | `Capybara` | `<UID>_p49_unlock` | 旧表已有 |
| 50 | 珍珠防御塔 | `ShanghaiTower` | `<UID>_p50_unlock` | 旧表已有 |
| 51 | 小小教官 | `TrainerRobot` | `<UID>_p51_unlock` | 旧表已有 |
| 52 | 火力雪人王 | `FireSnowman` | `<UID>_p52_unlock` | 旧表已有 |
| 53 | 凉块 | `ChillyGo` | `<UID>_p53_unlock` | 旧表已有 |
| 54 | 来财 | `Nian` | `<UID>_p54_unlock` | 旧表已有 |
| 55 | 机甲阿凉 | `CatMecha` | `<UID>_p55_unlock` | 相对旧表新增 |
| 56 | 小疯猴 | `EscapeMonkey` | `<UID>_p56_unlock` | 相对旧表新增 |

## 社区核对重点

1. 编号 55“机甲阿凉”和 56“小疯猴”是否能在目标版本界面中确认，实际获取后对应哪些 XML/存档变化？
2. `Hide` 是隐藏占位、特殊形态还是其他用途？在确认前保持排除。
3. 渠道专属、活动或联动宠物是否还依赖额外字段？建议核对嗒普、豆娃、夜歌、财满满、珍珠防御塔、机甲阿凉、小疯猴；此处是核对名单，不是已确认的额外依赖清单。
4. 选取正常获取前后的配对备份，先验证单只宠物，观察 XML 的 `pN` 与 `petUnlock` 哪个内部 ID 同步变化。特别需要没有 `game.data` 的渠道样本。无需分享完整存档、UID 或账号信息。

## 来源指纹

以下指纹用于日后确认调查材料版本。原始资源与玩家存档不随映射表分发。

- APK `localization.ab` SHA-256：`dfa5677a2f9504fcce93c8da5c5b20063282dfe31a90caae92828dba257d6722`。
- 提取的本地化 JSON SHA-256：`b2713549630a09bf1d4a057c0bcf8effc971b1c1b5d5948971bcf5e7aa91cc44`。
- 旧工具 `pet_map.py` SHA-256：`8c858a459d56d2b38b2774e7f82ea7baa04172b89fd266bf2d1dc3cd381ffbe7`。
- 09-25 真机云存档副本：57/57 顺序比对相符。
  - game.data SHA-256：`98584cd4b1e23ca4d056ecf9c93d624dea6c8ecacc2338ec2d1a3788ffe2f3fe`。
  - XML SHA-256：`02e63ffdee71830b71ae21e2f1c95482329820c8b5ac77f21002c4b38f924f44`。
- 09-27 模拟器修改前备份：57/57 顺序比对相符。
  - game.data SHA-256：`98584cd4b1e23ca4d056ecf9c93d624dea6c8ecacc2338ec2d1a3788ffe2f3fe`。
  - XML SHA-256：`a1f79a33e96ca53fa77481b92f7d4958d5f1aad95c2c8f4845a79072b4c4fa99`。
