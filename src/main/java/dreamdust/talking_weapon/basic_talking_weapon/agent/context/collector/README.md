# Collector 开发指南

## 概述

`collector` 包存放所有 **上下文采集器（Context Collector）** 的实现。  
每个采集器负责采集一类游戏信息，写入 `AgentContext.Builder` 的 `extras` 字段和/或 `TaggedContextEntry` 标签条目。

## 标签系统 (Tag System)

### 双轨标签体系

采集器同时产出两种数据：

1. **extras（扁平键值对）**：向后兼容的人类可读文本，如 `"held_item" → "钻石剑"`
2. **TaggedContextEntry（结构化标签条目）**：每条包含：
   - `focusTags`：注意力元标签（`FocusTag` 枚举），决定属于哪个注意力领域
   - `detailTags`：细节标签（`DetailTag`，`prefix:value` 格式），用于记忆检索
   - `description`：人类/LLM 可读的事件描述
   - `baseImportance`：事件客观重要度 (0.0~1.0)
   - `associativeOnly`：是否为隐式关联标签（若为 true，则在未命中记忆联想时对大模型不可见，用于海量方块扫描）
   - `timestamp`：精确物理时间戳

### FocusTag（注意力元标签，共 11 个）

| 领域 | 标签 | 含义 |
|---|---|---|
| 行动域 | `COMBAT` | 战斗 |
| | `MOVEMENT` | 移动/探索 |
| | `LABOR` | 劳作/生存 |
| | `CREATION` | 创造/合成 |
| | `SOCIAL` | 社交交互 |
| 信息域 | `LANDSCAPE` | 风景/环境 |
| | `HAZARD` | 危险/异状 |
| | `STATUS` | 自身状态 |
| 物品域 | `CONSUMABLE` | 消耗品/食物 |
| | `BLOCK` | 方块交互 |
| | `TOOL` | 工具/武器 |

### DetailTag 前缀

| 前缀 | 含义 | 示例 |
|---|---|---|
| `action:` | 行为动词 | `action:attack`, `action:eat` |
| `entity:` | 实体 Registry ID | `entity:minecraft:zombie` |
| `item:` | 物品 Registry ID | `item:minecraft:diamond_sword` |
| `block:` | 方块 Registry ID | `block:minecraft:obsidian` |
| `env:` | 抽象环境/地貌特征 | `env:exposed`, `env:cave`, `env:pitch_dark` |
| `struc:`| 结构特征 | `struc:minecraft:village` |
| `trait:` | 抽象特征 (Forge Tags) | `trait:undead`, `trait:boss` |
| `dim:` | 维度 ID | `dim:minecraft:the_nether` |
| `biome:` | 群系 ID | `biome:minecraft:plains` |
| `state:` | 量化状态分桶 | `state:health_critical`, `state:time_night` |
| `era:` | 纯叙事纪元 | `era:forged`, `era:current_journey` |
| `loc:` | 自定义叙事地标 | `loc:home`, `loc:trial_grounds` |
| `poi:` | 系统级功能 POI | `poi:nether_portal` |

## 现有采集器

| 类名 | 采集内容 | 产出标签 |
|---|---|---|
| `BaseContextCollector` | HP、饥饿值、坐标、群系、天气、游戏时间 | `state:health_*`, `state:time_*`, `state:weather_*`, `biome:*` |
| `ScreenshotCollector` | 当前画面截图（Base64） | _(无标签)_ |
| `NearbyEntitiesCollector`| 16 格内实体，按类型分组计数 | `entity:*`, `state:combat_intense` |
| `PlayerEnvironmentCollector`| 维度、光照、持有物品、盔甲值、经验等级 | `dim:*`, `state:light_*`, `item:*` |
| `SurroundingBlocksCollector`| 3D探测玩家空间视野内的方块与地形 | `env:*`, `block:* (隐式)` |
| `NearbyStructureCollector` | 探测玩家周边的群系结构 | `struc:*` |
| `NearbyPoiCollector` | 探测周围的村庄职业工作台与床铺门户 | `poi:*` |
| `LookingAtCollector` | 玩家视线正中锁定的方块或实体 | `entity:*`, `block:*`, `item:*` |

## 如何新增采集器

### 1. 实现接口

```java
public class MyNewCollector implements IContextCollector {

    @Override
    public String getName() {
        return "my_new_data";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        // ── 传统 extras 输出（向后兼容） ──
        builder.extra("my_key", "人类可读的描述");

        // ── 标签化输出（推荐） ──
        builder.addTaggedEntry(TaggedContextEntry.builder()
                .focus(FocusTag.COMBAT)              // 注意力元标签
                .detail(DetailTag.ENTITY, "minecraft:zombie") // 细节标签
                .detail(DetailTag.TRAIT, "undead")    // 可添加多个
                .description("附近发现一只僵尸")       // LLM 可读描述
                .importance(0.5f)                     // 客观重要度
                .build());
    }
}
```

### 2. 注册到触发器

在需要此数据的 Trigger 的 `getCollectors()` 中添加：

```java
@Override
public List<IContextCollector> getCollectors() {
    return List.of(
        new BaseContextCollector(),
        new ScreenshotCollector(),
        new MyNewCollector()     // ← 加在这里
    );
}
```

无需修改 `AgentSensor` 或 `AgentContext`。

### 3. 注意事项

- **主线程安全**：`collect()` 在 Minecraft 主线程上调用，可直接访问 `Level`、`Player` 等对象
- **容错**：单个采集器抛异常不会阻塞其他采集器（`AgentSensor` 会 catch）
- **性能**：避免在 `collect()` 中执行耗时操作，建议限制扫描半径
- **标签命名**：`DetailTag` 的值优先使用 Minecraft Registry ID（如 `minecraft:zombie`），确保跨模组兼容
- **importance 取值**：0.0（无关紧要）~ 1.0（生死攸关），参考现有采集器的取值

## 可扩展方向

- `InventoryCollector` — 背包物品摘要 (`item:*`)
- `EffectsCollector` — 当前药水效果 (`trait:*`)
- `RecentChatCollector` — 最近聊天消息
- `LocationCollector` — 自定义地标检测 (`loc:*`)
