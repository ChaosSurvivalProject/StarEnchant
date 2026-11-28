> ## 星穹旅驿出品 · Chaos Survival Project
> 
> 本插件/模组由 **星穹旅驿（Chaos Survival Project）** 团队开发维护。
> 遇到问题或有功能建议？请到 **GitHub** 提交 Issue 反馈，我们会尽快处理。
> This plugin/mod is developed by **星穹旅驿 (Chaos Survival Project)**. Found a bug or have a suggestion? Please report it on [GitHub Issues](https://github.com/ChaosSurvivalProject/StarEnchant/issues).
> 
> 🔗 GitHub 仓库 / Repo: [https://github.com/ChaosSurvivalProject/StarEnchant](https://github.com/ChaosSurvivalProject/StarEnchant) ｜ 反馈 / Issues: [https://github.com/ChaosSurvivalProject/StarEnchant/issues](https://github.com/ChaosSurvivalProject/StarEnchant/issues)

---

# StarEnchant 星穹旅驿·扩展附魔插件 v1.0.0

<p align="center">
  <img src="assets/images/banner.png" alt="StarEnchant 宣传图" width="820">
</p>


Bukkit/Purpur 原生插件，**双层附魔体系**：

1. **第一层（等级突破）**：原版附魔等级上限解锁至自定义值（锋利最高可到 `vanilla-plus.level-caps.SHARPNESS` 配置值），通过 `ItemMeta#addEnchant(ench, lv, true)` 实现，武器三件套 / 保护套件 / 效率精准可分别开关互斥。附魔台产出经 `EnchantItemEvent` 概率升档。
2. **第二层（自定义附魔）**：**60 个自定义附魔、8 大类别**。数据全部存于物品 PDC（`starinn:ench`，单字符串序列化），效果由服务端事件驱动，**Java 与基岩（Geyser）两端真实生效**。

## 效果预览

<p align="center">
  <img src="assets/images/demo.png" alt="StarEnchant 演示图" width="720">
</p>


## 为什么用 lore 显示而不上附魔注册表 / 资源包 —— 设计声明（非缺陷）

- 基岩版玩家经 Geyser 进服时：自定义资源包里的字体/附魔表 UI 不可靠，datapack 的自定义附魔注册表（1.21 `/give enchant` 形式）不被 Geyser 翻译，**必须在客户端安装东西**。
- 本插件红线是**零客户端下发**（无资源包、无数据包、无 mod），因此显示层用原版 `§色码 + lore 行`（如 `§c燃焰 III`），效果层全部由服务端事件计算。
- 代价是 lore 可被改名污染：存储真源是 PDC，渲染时按「颜色码 + 显示名前缀 + 罗马数字尾缀」识别并重建自定义行，玩家自写的近似行会被保留、本插件行只增删自己管理的部分。

## 安装

- 服务端：**Purpur 1.21.11**（Paper 1.21.x 同源兼容；编译目标 paper-api 1.21.11-R0.1-SNAPSHOT，Purpur 运行时是其超集）
- Java 21
- 把 `StarEnchant-1.0.0.jar` 放入 `plugins/`，重启。首次启动自动落默认 `config.yml`。
- Velocity 跨服：附魔数据在物品 PDC、阵营在玩家 PDC，物品/玩家随转档自然携带，**无需任何跨服端插件**。

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/enchant list [类别] [页码]` | 附魔总览（all/weapon/armor/tool/bow/shield/fishing/legend/faction） | `star.enchant.use` |
| `/enchant info <id>` | 触发事件、每级数值、获取途径 | `star.enchant.use` |
| `/enchant guide` | 领取附魔指南书（原版成书，含每项附魔的介绍/适用物品/最大等级/获取方式） | `star.enchant.use` |
| `/enchant give <玩家> <id> <等级>` | 发放附魔书（管理员） | `star.enchant.give` |
| `/enchant shop` | 阵营商店（货币=经验等级，原生 API） | `star.enchant.shop` |
| `/enchant faction <玩家> <dawn|dusk|none>` | 设置阵营 | `star.enchant.setfaction` |
| `/enchant reload` | 热加载配置 + 重跑自检 | `star.enchant.reload` |

主命令别名：`/ench`、`/se`。

## 获取途径（config `enchants.<id>.sources` 白名单强制）

| 途径 | 机制 |
|---|---|
| 锻造台（核心） | `smithing.recipes`：原版模板槽+装备槽+材料槽三元匹配（**零数据包**，模板物品只是识别标记），结果替换为「PDC 保留 + 指定附魔升级」产物；取出时手动结算：材料按数量扣、**模板不消耗可复用**、装备位清空。 |
| 附魔台 | 只对第一层生效：`enchanting-table.upgrade-chance` 概率把成品附魔往 `level-caps` 拉升；`conflict-extra-chance` 按启用的互斥组追加同组附魔。自定义附魔不占附魔台。 |
| 阵营商店 | 6 个阵营专属（dawn_light/night_hunt/star_vein_dawn/star_vein_dusk/oath_guard_dawn/oath_guard_dusk），按玩家 PDC 阵营过滤货架，价格=经验等级。 |
| 战利品/怪物掉落 | `loot.pools`：加权池 + 怪物材料过滤；玩家击杀掉落 → 附魔书（经 `LootPool`，含 `rift` 裂缝世界过滤）。 |
| 钓鱼 | `angler` / `treasure_seeker` 唯一产出点：`fishing.treasure-book-chance` 概率附带掉落对应书。 |
| 活动发放 | API + 命令：`sources` 必须含 `event`；Java API： |

```java
StarEnchant.createEnchantBook("blaze", 3);              // 只造书，不校验途径
StarEnchant.grantEnchantBook(player, "oath_guard_dawn", 1); // 校验 sources 含 event
```

## 铁砧与传递

- **书→装备**：自定义附魔书无原版附魔，原版铁砧必判空 → `PrepareAnvilEvent` 接管：PDC `applyBook`（新附魔=书本等级，已存在=+1 封顶），费用 `anvil.book-cost-levels`。
- **装备→装备**：原版结果存在时把双方 PDC `max` 合并写回结果；原版因互斥拒绝且 `anvil.merge-unconflict` 开启时按 `conflict-groups` 放行合并，费用封顶 `max-merge-cost`。
- **重命名/维修**：结果也回填原装备 PDC（原版会洗掉）。
- **合成传递**：默认关闭（`craft-transfer.enabled: false`），开启后白名单材料合成保留自定义附魔。

## 基岩兼容说明（重要取舍）

| 附魔 | Java 语义 | 本插件两端一致的服务端等效实现 |
|---|---|---|
| 疾锋 sharp_edge | 攻击冷却 -x%（原版 1.9+ 冷却条客户端可见） | 服务端无法改基岩端冷却条 → 「迅捷连击」：命中后概率追加一次伤害（数值键 `extra-hit-chance-percent-per-level`） |
| 迅弓 quick_draw | 拉弓速度 | 弓拉满度事件基岩不可见 → 以投射物 force≥阈值判定「满弓结算」，伤害在射出时 baked |
| 碎盾 shield_break | 原版盾牌 cooldown | `LivingEntity#setCooldown` 已移除且挡格数学不可拦截 → 举盾命中检测（`isHandRaised`）+ **4 秒禁用地图**：禁用期内其成功挡格按惩罚伤害结算，两端同效 |
| 破土加速 | 挖掘速度 | 属性 `BLOCK_BREAK_SPEED` 对 Java 1.21.2+ 客户端直生效；Geyser 基岩端属性不全量同步 → `tool-speed.mode: haste` 切药水效果（HASTE）模式，两端一致；属性模式带自动 fallback |

其他：所有伤害/掉落/燃烧/缓速/经验操作走服务端事件，基岩端表现为原版动画，无需客户端配合。

## 性能设计

- PDC 读取：**单字符串序列化 + parse 缓存**（`ConcurrentHashMap`），每事件 O(1)；实体装备快照按 tick 缓存。
- 无异步主逻辑：全部主线程事件（避免 PDC/实体并发损坏）；仅连锁挖掘/追踪箭/勘探走**分帧任务**：
  - `performance.chain-per-tick`（每刻最多连锁几格）+ `chain-queue-limit`（队列硬上限）+ 距离 48 格守卫；
  - 追踪箭扫描 `homing-scan-ticks`，勘探逐 y 层扫描 `performance.scan-radius`；
- 掉落/挂饰等内存态地图在退出/死亡/停用三点清理（`clearAllState`）。

## 启动自检与降级

`onEnable` 逐项检查：PDC 可用性、config 核心段、60 附魔注册合法性（等级上限/颜色码/数值键/显示名唯一）、阵营世界存在、互斥对称（三对数值必须完全一致，否则成对禁用）、锻造配方/战利品池/商店货架引用合法性、附魔书材料。任何单项失败只**降级该功能**并记日志，绝不崩溃；结尾输出：

```
[星穹旅驿] 附魔插件自检完成：正常 X 项 / 降级 Y 项
```

`/enchant reload` 重跑全套自检并重建全部监听。

## 数值全在 config.yml

60 个附魔的**每一概率/倍率/时长/百分比/等级上限**都在 `enchants.<id>.values`（`*-per-level` 键按等级线性放大），类别/颜色/显示名/获取途径白名单同样可配。代码内仅有单位换算常数（tick/秒、百分比 100 等），无任何玩法数值硬编码。默认关闭项：`shield_break`、`spread`、`deep_dig`、`shard_affinity`、`fortune-silk-touch` 互斥组。

## 60 附魔总表（由 config 同源生成）

| 类别 | id | 显示名 | 等级 | 触发与效果 | 获取途径 |
|---|---|---|---|---|---|
| 武器类·剑斧 | `blaze` | 燃焰 | V | EntityDamageByEntityEvent 命中生物 — 目标燃烧(等级×2)秒；对怪物伤害+(8×等级)% | 锻造台 |
| 武器类·剑斧 | `rift` | 裂痕 | III | EntityDamageByEntityEvent 命中生物 + EntityDeathEvent 击杀 — 击退距离+(50×等级)%；击杀怪物(15×等级)%概率额外掉落1个该怪物掉落物 | 锻造台·裂缝战利品 |
| 武器类·剑斧 | `sharp_edge` | 疾锋 | III | EntityDamageByEntityEvent 命中生物(服务端等价实现) — 攻击冷却-(8×等级)%：以服务端等效的「迅捷连击」实现——命中后(8×等级)%概率追加一次额外攻击 | 锻造台 |
| 武器类·剑斧 | `echo` | 回响 | III | EntityDeathEvent 击杀怪物 — (20×等级)%概率恢复2饥饿值+1点生命 | 锻造台 |
| 武器类·剑斧 | `drain` | 汲取 | II | EntityDeathEvent 击杀怪物 — 怪物经验掉落+(25×等级)% | 锻造台 |
| 武器类·剑斧 | `shield_break` | 碎盾 | I | EntityDamageByEntityEvent 命中且目标正举盾(PVP) — 目标盾牌禁用4秒(禁用期内其挡格视为无效)(默认关闭) | 活动任务 |
| 武器类·剑斧 | `cleave` | 横扫 | III | EntityDamageByEntityEvent 命中生物 — 2格范围内其他生物受到本次伤害(20×等级)% | 锻造台 |
| 武器类·剑斧 | `bloodlust` | 嗜血 | III | 连续击杀(EntityDeathEvent 叠层, EntityDamageByEntityEvent 增伤) — 每层叠加+(5×等级)%伤害，上限5层，脱战10秒清零 | 锻造台·裂缝战利品 |
| 武器类·剑斧 | `fracture` | 碎骨 | III | EntityDamageByEntityEvent 命中生物 — 无视目标护甲值(10×等级)% | 锻造台 |
| 武器类·剑斧 | `night_bite` | 噬夜 | III | 玩家在渊域世界且黑夜时造成伤害 — 伤害+(7×等级)% | 裂缝战利品 |
| 武器类·剑斧 | `sun_brand` | 烙日 | III | 玩家在炽壤世界且白天时造成伤害 — 伤害+(7×等级)% | 裂缝战利品 |
| 武器类·剑斧 | `executioner` | 处决 | II | EntityDamageByEntityEvent 命中生命低于阈值的生物 — 目标生命低于20%时伤害+(30×等级)% | 锻造台 |
| 武器类·剑斧 | `chain` | 连锁 | II | EntityDeathEvent 击杀 → 窗口期内下次命中增伤 — 击杀后5秒内下次攻击伤害+(25×等级)% | 锻造台 |
| 武器类·剑斧 | `blood_pact` | 血契 | III | EntityDamageByEntityEvent 造成伤害 — 吸取本次伤害(5×等级)%转化为自身生命 | 锻造台·裂缝战利品 |
| 防具类·头盔胸甲护腿靴 | `tough` | 韧甲 | III | PlayerItemDamageEvent 装备耐久损耗 — 耐久损耗-(20×等级)% | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `quench` | 淬火 | I | EntityCombustEvent 点燃 + 燃烧期玩家装备免损 — 燃烧期间穿戴装备免疫耐久损耗；自身燃烧时间-50% | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `bedrock_stance` | 磐石 | II | EntityKnockbackEvent 被击退 — 被击退距离-(40×等级)% | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `revive` | 复苏 | II | 玩家生命低于阈值时(EntityDamageEvent 后检测) — 获得吸收I持续8秒，冷却60秒 | 活动任务 |
| 防具类·头盔胸甲护腿靴 | `shade` | 潜影 | I | 玩家潜行且在渊域世界且黑夜(定时检测) — 移速+15%；半径8格内怪物不优先锁定该玩家 | 裂缝战利品 |
| 防具类·头盔胸甲护腿靴 | `featherweight` | 轻羽 | III | 穿戴时(定时检测) — 移速+(4×等级)% | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `vitality` | 生机 | III | 穿戴时(属性修正) — 最大生命+(2×等级)点(每级+1颗心) | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `thorns_edge` | 棘刺 | III | 被近战命中(EntityDamageByEntityEvent) — 反弹(15×等级)%伤害给攻击者 | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `void_ward` | 渊护 | III | 玩家在渊域世界时受到伤害(EntityDamageEvent) — 受伤-(5×等级)% | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `sun_ward` | 日照 | III | 玩家在炽壤世界时受到伤害(EntityDamageEvent) — 受伤-(5×等级)% | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `deep_breath` | 深息 | II | 水下时(定时检测) — 氧气消耗-(40×等级)% | 锻造台 |
| 防具类·头盔胸甲护腿靴 | `steadfast` | 屹立 | II | 落地摔落(EntityDamageEvent CAUSE=FALL) — 摔落伤害-(30×等级)% | 锻造台 |
| 工具类·镐斧铲 | `shard_affinity` | 碎片亲和 | III | BlockBreakEvent 挖掘(提供倍率记录,实际掉落由服主现有系统读取) — 炽阳碎片掉率×(1.5×等级)倍；若服主未做碎片系统请保持关闭(默认关闭) | 锻造台 |
| 工具类·镐斧铲 | `rich_vein` | 富矿 | III | BlockBreakEvent 挖掘矿石类方块(材料表可配) — (25×等级)%概率额外掉落1个 | 锻造台 |
| 工具类·镐斧铲 | `smelt` | 熔炼 | I | BlockBreakEvent 挖掘矿石类方块(映射表可配) — 直接掉落烧炼后产物，经验减半 | 活动任务 |
| 工具类·镐斧铲 | `rock_break` | 断岩 | III | BlockBreakEvent 挖掘(属性修正,可切药水效果模式) — 挖掘速度+(15×等级)% | 附魔台·锻造台 |
| 工具类·镐斧铲 | `spread` | 蔓延 | II | 潜行挖掘方块(分帧处理) — 连锁挖掘相邻同类方块，上限(3×等级)个(默认关闭) | 活动任务 |
| 工具类·镐斧铲 | `timber` | 伐木 | III | 砍伐原木(相连树扫描,分帧处理) — 连锁砍伐整棵相连树木，上限(8×等级)格 | 锻造台 |
| 工具类·镐斧铲 | `deep_dig` | 深掘 | II | 向下挖掘(分帧处理) — 连锁向下挖掘(2×等级)格(默认关闭) | 活动任务 |
| 工具类·镐斧铲 | `magnet` | 磁引 | I | 挖掘产生掉落物 — 掉落物直接进入玩家背包(满则落脚下) | 锻造台·裂缝战利品 |
| 工具类·镐斧铲 | `prospector` | 勘探 | II | 挖掘方块(分帧扫描+粒子,仅触发者可见) — 显示周围(8×等级)格内矿石轮廓3秒 | 锻造台 |
| 工具类·镐斧铲 | `swift_repair` | 速修 | III | BlockBreakEvent 挖掘 — (10×等级)%概率消耗经验修复装备2点耐久 | 锻造台 |
| 工具类·镐斧铲 | `quarry` | 采石 | III | 挖掘石头类方块(材料表可配) — 对石头类方块速度+(25×等级)% | 附魔台·锻造台 |
| 工具类·镐斧铲 | `fine_gather` | 精收 | II | BlockBreakEvent 挖掘非矿石方块 — (20×等级)%概率掉落物×2 | 裂缝战利品 |
| 弓弩类·弓与弩 | `star_chase` | 追星 | III | EntityShootBowEvent + ProjectileHitEvent — 箭速+(20×等级)%；箭矢伤害+(8×等级)% | 锻造台 |
| 弓弩类·弓与弩 | `void_split` | 裂空 | II | ProjectileHitEvent 箭矢命中生物 — 1.5格范围内其他生物受到本次伤害(30×等级)% | 锻造台 |
| 弓弩类·弓与弩 | `pierce_shadow` | 贯影 | I | ProjectileHitEvent 箭矢命中生物 — 箭矢穿透1个生物继续飞行 | 裂缝战利品 |
| 弓弩类·弓与弩 | `split_arrow` | 裂矢 | III | EntityShootBowEvent 射箭 — 额外发射(等级)支箭(带散布角度) | 锻造台 |
| 弓弩类·弓与弩 | `homing` | 寻星 | II | 射箭后定时刻追踪最近生物(有时限) — 箭矢轻微追踪半径(4×等级)格内最近生物 | 活动任务 |
| 弓弩类·弓与弩 | `blast_tip` | 爆镞 | II | ProjectileHitEvent 箭矢命中 — 小范围爆炸(不破坏方块)，范围内伤害(40×等级)% | 锻造台·裂缝战利品 |
| 弓弩类·弓与弩 | `frost_bind` | 霜缚 | II | ProjectileHitEvent 箭矢命中生物 — 目标获得减速I持续(2×等级)秒 | 锻造台 |
| 弓弩类·弓与弩 | `quick_draw` | 速射 | III | EntityShootBowEvent 拉弓(服务端按力度判定) — 未拉满但达到(85×等级)%阈值时按满弓结算 | 锻造台 |
| 盾牌类 | `bulwark` | 壁垒 | III | 挡格成功(EntityDamageByEntityEvent,举盾判定) — 格挡减免额外+(8×等级)% | 锻造台 |
| 盾牌类 | `reflect` | 反射 | II | 挡格箭矢成功 — 反弹箭矢，对原射手造成(50×等级)%伤害 | 锻造台 |
| 盾牌类 | `bash` | 盾击 | II | 挡格成功后(EntityDamageByEntityEvent) — 成功格挡后3秒内下次近战伤害+(20×等级)% | 锻造台 |
| 钓鱼类·钓竿 | `angler` | 垂钓 | III | PlayerFishEvent 抛竿/收竿 — 咬钩等待时间-(15×等级)%；钓鱼稀有度+1每级(按 +10%×等级 宝藏概率实现) | 钓鱼 |
| 钓鱼类·钓竿 | `treasure_seeker` | 寻宝 | III | PlayerFishEvent 钓到物品 — 宝藏概率+(20×等级)% | 钓鱼 |
| 传说级·极稀有 | `endless` | 不灭 | I | 被动(PlayerItemDamageEvent) — 装备永不损耗耐久 | 活动任务 |
| 传说级·极稀有 | `soulbound` | 魂契 | I | 玩家死亡(PlayerDeathEvent) — 该装备不掉落，复活时归还 | 活动任务 |
| 传说级·极稀有 | `beacon_light` | 灯塔之辉 | III | 被动(定时检测) — 全伤害+(3×等级)%、受伤-(3×等级)%、挖掘速度+(5×等级)% | 活动任务 |
| 阵营专属 | `dawn_light` | 昼辉 | III | 玩家在炽壤世界且白天造成伤害 — 伤害+(6×等级)% | 阵营商店 |
| 阵营专属 | `night_hunt` | 夜猎 | III | 玩家在渊域世界且黑夜造成伤害 — 伤害+(6×等级)% | 阵营商店 |
| 阵营专属 | `star_vein_dawn` | 星脉·昼 | III | 炽壤白天挖掘(属性/效果修正) — 挖掘速度+(12×等级)% | 阵营商店 |
| 阵营专属 | `star_vein_dusk` | 星脉·夜 | III | 渊域黑夜挖掘(属性/效果修正) — 挖掘速度+(12×等级)% | 阵营商店 |
| 阵营专属 | `oath_guard_dawn` | 守誓·昼 | III | 炽壤白天受伤(EntityDamageEvent) — 受伤-(5×等级)% | 阵营商店 |
| 阵营专属 | `oath_guard_dusk` | 守誓·夜 | III | 渊域黑夜受伤(EntityDamageEvent) — 受伤-(5×等级)% | 阵营商店 |

## 交付自检报告（§11）

| # | 检查项 | 结果 |
|---|---|---|
| 1 | `gradle build` 编译（Java 21 + paper-api 1.21.11，Purpur 运行时兼容） | 通过（0 错误） |
| 2 | 60 附魔注册：8 类分布 14/12/12/8/3/2/3/6 | 通过 |
| 3 | 代码数值键 ↔ config.yml 全量对齐（缺失 0 / 孤儿 0） | 通过 |
| 4 | 阵营三对附魔数值完全对称（仅世界+时间不同） | 通过 |
| 5 | 红线：零第三方插件 import / 零资源包 / 零数据包 / 零网络 / 零数据库 | 通过 |
| 6 | 默认关闭项符合规格（shield_break/spread/deep_dig/shard_affinity/fortune+silk） | 通过 |
| 7 | plugin.yml：main/api-version/命令/权限声明齐全，jar 内资源完整（config.yml 36KB 落包） | 通过 |
| 8 | 存储键、阵营值全部读 config（`storage.*`/`faction.*`） | 通过 |
| 9 | 自检降级机制编译在案（模块 try/catch + markDegraded + 成对禁用） | 通过 |

**通过 9 / 降级 0 / 未通过 0**

## 实机测试报告（v1.0.0）

**测试栈**：Purpur 1.21.11 (build 2568, Java 21, 在线模式关, 超平坦测试世界) + Geyser-Standalone 2.12.0-b1269（UDP 19132 → 上游 127.0.0.1:25599）+ ViaVersion 5.12.2（仅作 Geyser↔1.21.11 的协议桥，见注意事项；**StarEnchant 本体零插件依赖不变**）。
**测试客户端**：Java 端 mineflayer 4.39 真机器人（1.21.11）；基岩端 bedrock-protocol 3.59 真握手（1.26.51 / 协议 2193，离线认证）。
**方式**：一次性脚本 起服→断言→停服（平台不允许常驻后台进程）。

**结果：通过 31 项 / 降级 0 项 / 未通过 0 项。**

| 组 | 断言（节选） | 实测证据 |
|---|---|---|
| S 服务端 (19) | 启动+自检；无插件异常；总数 60；8 类计数 14/12/12/8/3/2/3/6；info 数值页；默认关闭 4 项（碎盾/蔓延/深掘/碎片亲和）；reload 热加载 | `自检完成：正常 32 项 / 降级 0 项`；`共 60 项`；4 项 `[已关闭]`；`热加载：正常 32 项 / 降级 0 项` |
| A Java 客户端 (8) | 登录；附魔书 lore/PDC 到客户端；铁砧书→剑迁移；**燃焰命中点燃**；**熔炼挖矿直接烧炼**；**生机 +最大生命**；faction 命令 | lore `燃焰 III` + PDC `starinn:ench="blaze=3"` 在客户端包内可见；燃焰剑命中 → `Fire=99`（≈3 级×2 秒）；熔炼镐挖铁矿石 → 铁锭 0→1（粗铁 0→0）；生机 II 胸甲 → `generic.max_health=24`；`已设置 JavaBot 阵营为 dawn` |
| B 基岩客户端 (4) | Geyser 启动；基岩登录；中文消息转发；**物品 lore 基岩可见** | `BedBot (logged in as: BedBot) has connected to remote java server`；协议包捕获 `text: {"message":"§r§6[星穹旅驿] §r§e获得附魔书：§r§6燃焰 III"}`（UTF-8 原样到达基岩端） |

**实机发现并已修复的真实缺陷**（均为本轮测试暴露）：
1. 1.20.5+ 物品无 `max_damage` 组件时 `getMaxDamage()` 抛 `IllegalStateException` → `wearTool`/`swift_repair` 耐久计算改以 `Material#getMaxDurability()` 为准。
2. 燃焰点燃原先限定 `Monster` → 与规范「命中**生物**点燃；对**怪物**增伤」对齐：点燃放宽到任意 `Mob`，增伤仍只对 `Monster`。

**验收清单（§11）对照**：
- 实机通过：零客户端下发（B 组即基岩可见性直证）｜60 项与 8 类计数｜触发事件与数值一致（代表项 S5+A4/A5/A6，其余同构代码路径）｜铁砧 PDC 传递（A3）｜阵营命令（A7）｜基岩端效果/lore（B）｜默认关闭项｜颜色 lore（A2a `§c燃焰 III`）｜Purpur 1.21.11 + Java 21 编译｜自检降级机制（正常 32/降级 0）。
- 代码审查+自检覆盖、未单独实跑：原版等级突破/互斥解除/附魔台升档（第一层，unsafe enchant 纯机制扩展）｜Velocity 跨服携带（数据在 PDC，随 NBT 转档属 Bukkit 标准保证）｜商店 GUI 点击（GUI 打开路径与 list 同源）。

**注意事项（部署层，与本插件无关）**：
- Geyser 2.12 起提示：2026-10-27 NetherNet 上线后旧 RakNet 将停止服务，请按 Geyser 官方指引升级启用 NetherNet。
- 若 Geyser 主版本较新（如 2.12 支持到基岩 26.52）而游戏服务端仍为 1.21.11，Geyser 官方要求上游装 ViaVersion 做协议桥（本测试即此组合）。
- 基岩脚本化测试客户端离线登录需 Geyser 开 `java.auth-type: offline` 且 `advanced.bedrock.validate-bedrock-login: false`；真实基岩玩家不受此项影响。

## 目录

```
build.gradle.kts / settings.gradle.kts
src/main/java/com/starinn/enchant/StarEnchant.java   # 单主文件 4000 行，全部嵌套类
src/main/resources/plugin.yml / config.yml
tools/gen_meta.py                                    # 60 附魔元数据单一来源（重新生成 config/表/注册行）
dist/StarEnchant-1.0.0.jar                           # 交付物
```
