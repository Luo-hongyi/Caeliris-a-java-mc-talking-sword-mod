# Collector Detection Logic

## 目的

这份文档专门记录 `agent.context.collector` 内各个 Collector 的检测逻辑、阈值来源、已知误判点与调参入口。

目标不是解释上下文系统整体架构，而是回答下面这些维护问题：

- 某个标签是怎么被判出来的
- 某个 Collector 依赖哪些原始输入
- 阈值写死在代码里还是来自配置
- 哪些误判是已知的，后续应该从哪里改

如果后续修改了 Collector 的启发式规则、阈值、配置来源，应该同步更新这份文档。

## 入口文件

- 总体说明: [README.md](README.md)
- Collector 接口: [IContextCollector.java](IContextCollector.java)
- 基础上下文说明: [../README.md](../README.md)

## Collector 一览

| Collector | 主要职责 | 主要输出 |
|---|---|---|
| [BaseContextCollector.java](BaseContextCollector.java) | 玩家基础状态、时间、天气、群系 | `health_*`, `hungry_*`, `weather_*`, `native_specific:<biome>` |
| [PlayerEnvironmentCollector.java](PlayerEnvironmentCollector.java) | 维度、环境光照、主手物品、经验等级 | `light_dark`, `light_bright`, `native_specific:<dimension/item>` |
| [SurroundingBlocksCollector.java](SurroundingBlocksCollector.java) | 周围方块统计与环境推理 | `exposed`, `indoors`, `deep_indoors`, `cave`, `lava_nearby` |
| [NearbyEntitiesCollector.java](NearbyEntitiesCollector.java) | 周围实体扫描与分组 | `native_specific:<entity>`, `native_generic:<entity_tag>`, `monster`, `undead`, `combat_intense` |
| [NearbyPoiCollector.java](NearbyPoiCollector.java) | 附近 POI 检测 | `custom_specific:<poi>` |
| [NearbyStructureCollector.java](NearbyStructureCollector.java) | 已加载结构检测 | `native_specific:<structure>` |
| [LookingAtCollector.java](LookingAtCollector.java) | 准心目标检测 | `native_specific:<entity/block/item>` with `COMMON` focus + `crosshairTarget=true` |
| [RecentActionCollector.java](RecentActionCollector.java) | 近期动作归纳 | 动作记录中自带的 `DetailTag` 汇总 |
| [ScreenshotCollector.java](ScreenshotCollector.java) | 截图采集 | 仅写入 `screenshotBase64`，无标签 |

## 1. BaseContextCollector

源文件: [BaseContextCollector.java](BaseContextCollector.java)

### 输入

- `Player.getHealth() / getMaxHealth()`
- `Player.getFoodData().getFoodLevel()`
- 玩家坐标
- `Level.getGameTime()`
- `Level.getDayTime()`
- `Level.isRaining() / isThundering()`
- `Level.getBiome(...)`

### 判定逻辑

- 生命值区间：
  - `< 20%` -> `custom_specific:health_critical`
  - `< 50%` -> `custom_specific:health_low`
  - `< 80%` -> `custom_specific:health_medium`
  - `>= 80%` -> `custom_specific:health_full`
- 饥饿值区间：
  - `< 6` -> `custom_specific:hungry_critical`
  - `< 15` -> `custom_specific:hungry_low`
- 时间：
  - 使用 `Level.getGameTime()` 作为单调绝对时钟，生成 `dayIndex`
  - 使用 `Level.getDayTime()` 读取当前世界时段，再交给 `WorldTimeSemantics` 映射成语义时段
  - 时间标签目前是 `associativeOnly = true`
- 天气：
  - 雷暴 -> `weather_thunder`
  - 下雨 -> `weather_rain`
  - 否则 -> `weather_clear`
- 群系：
  - 直接记录注册表 ID，如 `minecraft:forest`
  - 群系标签目前是 `associativeOnly = true`

### 额外说明

- `ground_level` 不是通过 Heightmap，而是从玩家脚下向下最多扫描 64 格，找到第一层非空气方块。
- 这里的“地面”只服务于文本补充，不参与当前洞穴判定。

### 已知限制

- 悬空建筑、空岛、巨大树冠下的 `ground_level` 只反映脚下最近固体方块，不一定等于地表。
- 时间和群系目前默认不直接进 Prompt，更偏记忆检索用途。

## 2. PlayerEnvironmentCollector

源文件: [PlayerEnvironmentCollector.java](PlayerEnvironmentCollector.java)

### 输入

- 当前维度 `level.dimension()`
- 玩家脚下 `BLOCK` 光照和 `SKY` 光照
- 主手物品
- 经验等级

### 判定逻辑

- 维度：
  - 直接写入 `native_specific:<dimension_id>`
  - 当前设置为 `associativeOnly = true`
- 环境明暗：
  - `effectiveLight = max(blockLight, skyLight)`
  - `< 7` -> `custom_specific:light_dark`
  - `>= 7` -> `custom_specific:light_bright`
- 主手物品：
  - 非空则记录为 `native_specific:<item_id>`
- 经验等级：
  - `> 0` 时生成 `custom_specific:xp_level`

### 已知限制

- 这里的 `light_dark` 是光照感知，不等于“洞穴”。
- 即使在大型建筑里，只要照明充足，也可能被判为 `light_bright`。

## 3. SurroundingBlocksCollector

源文件: [SurroundingBlocksCollector.java](SurroundingBlocksCollector.java)

这是当前最复杂、最容易出现误判的 Collector。

### 输入

- 以玩家头部附近为中心的三维方块扫描
- 默认扫描半径 `5`
- 脚下方块、头顶方块、附近方块计数
- `canSeeSky`
- `BLOCK / SKY` 光照
- `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES`

### 输出分两层

- 显式环境标签：
  - `custom_specific:exposed`
  - `custom_specific:indoors`
  - `custom_specific:deep_indoors`
  - `custom_specific:cave`
  - `custom_specific:lava_nearby`
  - `custom_specific:water_nearby`
  - `custom_specific:pitch_dark`
  - `custom_specific:cliff`
- 隐式环境锚点：
  - 周围所有非空气方块都会生成 `native_specific:<block_id>`
  - 这些条目默认 `associativeOnly = true`

### 3.1 周围方块统计

- 扫描立方体内所有非空气方块
- 记录：
  - 方块计数 `blockCounts`
  - 方块显示名 `blockLabels`
  - 水体数量
  - 岩浆数量
- 同时生成 `surrounding_blocks_summary`
  - 只展示加权排序前 5 个方块
  - 常见基础块如泥土、石头、圆石、深板岩等会被降低排序权重，避免长期霸榜

### 3.2 露天 / 室内 / 洞穴判定

当前规则不是单一厚度判断，而是分三层：

#### 第一层：露天

- 如果 `level.canSeeSky(playerPos.above()) == true`
- 直接输出 `custom_specific:exposed`

#### 第二层：向上扫描遮挡

如果当前位置不能直接见天：

- 从玩家上方继续向上最多扫描 64 格
- 如果中途重新能见天，则 `hitSky = true`
- 在这个过程中，只统计“有效遮挡方块”

以下类型会被忽略，不计入顶层厚度：

- 树叶
- 原木
- 藤蔓
- 草、蕨、灌木、花瓣
- 作物、树苗
- 其他名称上明显属于树冠/植物覆盖的方块

这样做的目的是避免森林树冠把玩家误判成洞穴。

#### 第三层：深层室内 vs 普通室内 vs 洞穴

规则如下：

- `!hitSky`
  - 直接输出 `custom_specific:deep_indoors`
  - 含义是“上方 64 格内完全不见天日”
  - 这更像极深建筑内部、地堡、地下城，不强行断言为天然洞穴
- `coverBlocksAbove <= 4 && depthBelowSurface <= 4`
  - 输出 `custom_specific:indoors`
  - 含义是浅层遮挡，通常是屋檐下、树冠下、房屋内部
- 否则，继续做洞穴判定

只有同时满足下面 5 个条件，才输出 `custom_specific:cave`：

1. `depthBelowSurface >= 6`
2. `naturalRoofBlocksAbove >= 5`
3. `naturalRoofBlocksAbove >= structuralRoofBlocksAbove`
4. `naturalNearbyBlocks >= max(16, structuralNearbyBlocks)`
5. `skyLight <= 2`

未满足上述组合条件时，统一回退到 `custom_specific:indoors`。

### 3.3 天然块 / 建筑块分类

当前实现通过方块 ID 名称启发式分类：

- 天然洞穴候选块：
  - `stone`, `deepslate`, `andesite`, `diorite`, `granite`
  - `tuff`, `dripstone`, `calcite`
  - `basalt`, `blackstone`, `netherrack`, `end_stone`
  - `sandstone`, `gravel`, `dirt`, `mud`, `clay`, `terracotta`, `sand`
  - `snow_block`, `powder_snow`, `ice`, `magma`, `soul_sand`, `soul_soil`
  - 各类 `ore`
- 人造结构候选块：
  - `planks`, `brick`, `bricks`, `tiles`
  - `glass`, `pane`, `concrete`, `wool`, `carpet`
  - `door`, `trapdoor`, `fence`, `gate`, `wall`
  - `stairs`, `slab`, `pillar`
  - `lamp`, `lantern`, `torch`, `chain`, `scaffold`
  - `cobblestone`, `polished`, `cut_`, `chiseled`, `smooth_`, `stripped_`
  - `bed`, `bookshelf`

### 已知限制

- 模组建筑块如果命名不带上述关键词，可能被误认为天然块。
- 以天然石材大量搭建的基地，仍可能更接近 `cave`。
- `deep_indoors` 目前只看“64 格内不见天日”，没有进一步区分天然地下与人造地下。

### 后续建议

- 将“天然块关键词”“建筑块关键词”“深度阈值”“顶层厚度阈值”迁移到配置文件。
- 若后续要进一步稳定判定，可增加：
  - 周围人工光源密度
  - 门窗存在性
  - 方块实体/容器密度
  - 地下 Y 层区间偏置

## 4. NearbyEntitiesCollector

源文件: [NearbyEntitiesCollector.java](NearbyEntitiesCollector.java)

### 输入

- 玩家包围盒扩张半径内的实体
- 配置项来自 `TalkingWeaponConfig`：
  - `entity.scanRadiusBlocks`
  - `entity.onlyCreatures`
  - `entity.lineOfSight`
  - `entity.losMaxCheck`
  - `entity.fuzzyCount`

### 判定逻辑

- 扫描范围：
  - 使用 `player.getBoundingBox().inflate(radius)`
- 如果启用 LOS：
  - 只对最近的若干个生物做遮挡检测
  - 被方块遮挡的生物会被忽略
- 生物与非生物实体分开统计
- 敌对判断：
  - 使用 `EntityType.getCategory() == MobCategory.MONSTER`
- 附加语义标签：
  - 敌对生物 -> `custom_generic:monster`
  - `isInvertedHealAndHarm()` -> `custom_generic:undead`
  - 节肢类 -> `custom_generic:arthropod`
- 大量敌对生物：
  - 总敌对数量 `>= 5` -> `custom_generic:combat_intense`

### 输出特点

- 单个生物条目会挂：
  - `native_specific:<entity_id>`
  - 所有反向实体标签生成的 `native_generic:*`
  - 部分自定义抽象标签
- 描述文本可切换“精确数量”或“模糊数量”

### 已知限制

- LOS 只检查最近的一部分生物，不是全量精确可见性。
- 敌对性只按 `MobCategory.MONSTER`，对中立但可攻击实体不够细。

## 5. NearbyPoiCollector

源文件: [NearbyPoiCollector.java](NearbyPoiCollector.java)

### 输入

- 集成服务端 `PoiManager`
- 默认扫描半径 `5` 区块
- 默认最多返回 `8` 条

### 判定逻辑

- 只在单机 / 集成服务端有效
- 不再回退到方块扫描
- 找到附近全部 POI 后，按最近距离去重保留
- 焦点分类：
  - 含 `bed` -> `STATUS`
  - 含 `nether_portal` -> `MOVEMENT`
  - 命中工作站集合 -> `CREATION`
  - 否则 -> `BLOCK`

### 已知限制

- 联机客户端通常拿不到这部分数据，因为不做方块扫描回退。
- 输出 `detail` 是 `custom_specific:<poi_name>`，不是原生 `native_specific:*`。

## 6. NearbyStructureCollector

源文件: [NearbyStructureCollector.java](NearbyStructureCollector.java)

### 输入

- 已加载区块中的 `StructureStart`
- 默认扫描半径 `5` 区块
- 默认最多返回 `8` 条

### 判定逻辑

- 只扫描已加载区块，不触发新区块加载
- 每个结构使用包围盒中心点和玩家距离作为近似距离
- 同一结构 ID 只保留最近一个
- 重要度：
  - `< 32` 格 -> `0.8`
  - 否则 -> `0.5`
- 客户端世界为空时，会在单机模式下尝试从集成服务端补扫

### 已知限制

- 不是“最近结构定位器”，只能看当前已加载区域。
- 距离按结构包围盒中心估算，大型结构会有偏差。

## 7. LookingAtCollector

源文件: [LookingAtCollector.java](LookingAtCollector.java)

### 输入

- `Minecraft.getInstance().crosshairPickEntity`
- `Minecraft.getInstance().hitResult`

### 判定逻辑

- 优先处理准心实体
- 若准心指向 `ItemFrame`：
  - 优先取展示框内物品 ID
- 若没有实体，则处理准心方块
- 统一使用 `FocusTag.COMMON`
- 同时写入 `crosshairTarget = true`
  - 是否施加额外加分，改由 `LOOKING_AT_ATTENTION_BONUS_ENABLED` 控制
  - 加分大小由 `LOOKING_AT_ATTENTION_BONUS_VALUE` 控制，且现在是绝对值加分，不再是倍率
  - 这部分已经和 `COMMON` 的普通权重配置解耦

### 已知限制

- 完全依赖客户端准心结果
- “正在看天空/远方”只写 `extras`，不会生成结构化标签

## 8. RecentActionCollector

源文件: [RecentActionCollector.java](RecentActionCollector.java)

### 输入

- `AgentRuntime.getInstance().getMemory().getActionBuffer()`

### 判定逻辑

- 如果 `ActionBuffer` 为空，则不输出
- 每次读取后会 `clear()`
  - 即“阅后即焚”，避免同一批动作被重复感知
- 先按 Primary Focus 分组，再对每组生成一条总结
- 重要度按 Focus 分类：
  - `COMBAT / HAZARD` -> combat importance
  - `LABOR` -> labor importance
  - `STATUS / CONSUMABLE` -> status importance
  - `MOVEMENT` -> movement importance
  - `SOCIAL` -> social importance
  - 其他 -> default importance
- 每组会合并：
  - 全部 FocusTag
  - 全部 DetailTag

### 已知限制

- 这是摘要器，不是原始动作日志。
- 组内动作很多时，细节会被 `createSummaryForGroup()` 压缩。

## 9. ScreenshotCollector

源文件: [ScreenshotCollector.java](ScreenshotCollector.java)

### 输入

- 当前帧截图
- 配置项：
  - `enableScreenshot`
  - `screenshotWidth`
  - `screenshotHeight`

### 判定逻辑

- 如果 `enableScreenshot = false`，直接空操作
- 否则抓图并转 Base64
- 任何异常都吞掉，不阻断整条 Collector 管线

### 已知限制

- 本身不生成标签
- 只适合作为多模态输入补充，不适合做规则判定追踪

## 维护约定

后续只要出现下面任一改动，就更新这份文档：

- 新增或删除 Collector
- 改变判定阈值
- 改变 `DetailTag` / `FocusTag` 输出语义
- 某个误判被修复或确认是设计如此
- 某个阈值从硬编码迁移到配置

建议在每次大改后，至少补 3 项：

- 改了什么规则
- 为什么改
- 新的已知边界在哪里

## 最近变更记录

- `2026-03-18`
  - `SurroundingBlocksCollector` 的洞穴判定从“头顶厚度”升级为“树冠过滤 + 地表深度 + 天然/建筑块分类 + 低天光”的组合判定。
  - 新增 `custom_specific:indoors`，用于承接大型建筑内部和非洞穴型遮挡环境。
