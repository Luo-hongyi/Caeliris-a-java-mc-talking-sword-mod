# Collector 开发指南

## 概述

`collector` 包存放所有 **上下文采集器（Context Collector）** 的实现。  
每个采集器负责采集一类游戏信息，写入 `AgentContext.Builder` 的 `extras` 字段和/或 `TaggedContextEntry` 标签条目。

如果要追踪“某个标签是怎么判出来的”“阈值写在哪”“当前有哪些已知误判”，优先看 [DETECTION_LOGIC.md](DETECTION_LOGIC.md)。

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
   - `timestamp`：精确物理时间戳（用于日志和短时排序，不参与长期记忆衰退）

### Prompt 可见性

- Collector 可以放心收集 `native_generic:*`，系统会将其写入 Context / STM / 检索层。
- 但在 Prompt 合成阶段，`native_generic:*` 默认不会直接喂给 LLM。
- 因此：
  - `associativeOnly` 负责控制“整条条目是否可见”
  - `native_generic` 隐藏负责控制“条目内哪些 tag 对 LLM 可见”

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

在新架构中，我们去除了历史上的物理语义（如 `entity`, `item` 等），完全采用以下四种结构化命名空间前缀：

| 常数 | 含义 | 示例 |
|---|---|---|
| `DetailTag.NATIVE_SPECIFIC` | (`native_specific:`) 游戏原生单点数据 | `native_specific:minecraft:zombie`, `native_specific:minecraft:diamond_sword` |
| `DetailTag.NATIVE_GENERIC` | (`native_generic:`) 游戏原生概括数据 (DataPack Tags)；可进系统检索层，但默认不进 Prompt | `native_generic:minecraft:undead` |
| `DetailTag.CUSTOM_SPECIFIC` | (`custom_specific:`) 指向一个确切、底层的状态或对象 | `custom_specific:某个家的路标家`, `custom_specific:health_critical`, `custom_specific:exposed` |
| `DetailTag.CUSTOM_GENERIC` | (`custom_generic:`) 泛指一类特征或抽象状态集合 | `custom_generic:combat_intense`, `custom_generic:undead`, `custom_generic:monster` |

## 现有采集器

| 类名 | 采集内容 | 产出标签示例 (DetailTag) |
|---|---|---|
| `BaseContextCollector` | HP、饥饿值、坐标、群系、天气、游戏时间 | `native_specific:*`, `custom_specific:*` |
| `ScreenshotCollector` | 当前画面截图（Base64） | _(无标签)_ |
| `NearbyEntitiesCollector`| 16 格内实体，按类型分组计数 | `native_specific:*`, `native_generic:*`, `custom_generic:monster`, `custom_generic:undead`, `custom_generic:combat_intense` |
| `PlayerEnvironmentCollector`| 维度、光照、持有物品、盔甲值、经验等级 | `native_specific:*`, `custom_specific:*` |
| `SurroundingBlocksCollector`| 3D探测玩家空间视野内的方块与地形 | `native_specific:*`, `custom_specific:*` |
| `NearbyStructureCollector` | 探测玩家周边的群系结构 | `native_specific:*` |
| `NearbyPoiCollector` | 探测周围的村庄职业工作台与床铺门户 | `custom_specific:*` |
| `LookingAtCollector` | 玩家视线正中锁定的方块或实体 | `native_specific:*` |

补充说明：
- `BaseContextCollector` 现在会额外尝试把玩家当前位置与 `location_registry.json` 做近邻匹配。
- 匹配范围不再是全局常量，而是由每个地点自己的 `horizontalRadius / verticalRadius` 条目决定；旧条目缺省时会自动回落到 `24 / 16`。
- 命中后会同时产出两层信息：
  - `extras.registered_location_prompt`：作为常驻环境语义直接进入 Prompt。
  - `TaggedContextEntry(detail=custom_specific:<location_name>)`：作为普通上下文标签参与权重、Persona、LTM 与 relation 链路。

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
                .detail(DetailTag.nativeSpecific("minecraft:zombie")) // 原生细节标签
                .detail(DetailTag.nativeGeneric("minecraft:undead"))  // 原生概括标签
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
- **原生泛化标签**：`native_generic:*` 可以全量接收，不需要再做 namespace 白名单；真正的冗余隔断发生在 Prompt 合成阶段
- **importance 取值**：0.0（无关紧要）~ 1.0（生死攸关），参考现有采集器的取值

## 可扩展方向

- `InventoryCollector` — 背包物品摘要 (`native_specific:*`)
- `EffectsCollector` — 当前药水效果 (`custom_generic:*`)
- `RecentChatCollector` — 最近聊天消息
- `LocationCollector` — 自定义地标检测 (`custom_specific:*`)
