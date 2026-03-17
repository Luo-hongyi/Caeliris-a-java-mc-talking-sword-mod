# Trigger 开发指南

## 概述

`trigger` 包存放所有 **触发器（Agent Trigger）** 的实现。  
触发器定义：**什么游戏事件** → **需要哪些采集器** → **产生什么任务**。

## 现有触发器

### LLM 交互触发器（已实现）

| 类名 | 触发方式 | 采集器 | 产生任务 | Top-K 上限配置 |
|---|---|---|---|---|
| `RightClickTrigger` | 玩家右键使用武器 | Base + 截图 + 实体 + 环境... | `LLMInteractionTask` | `TOP_K_RIGHT_CLICK` |
| `BiomeChangeTrigger` | 进入新群系（由 TickEventWatcher 检测） | Base + 按配置动态组合 | `LLMInteractionTask` | `TOP_K_BIOME_CHANGE` |

### 记忆/反思触发器（过渡骨架，尚未完成当前路线接线）

| 类名 | 触发条件 | 用途 |
|---|---|---|
| `ReflectionTrigger` | 计划中的低频反思时机 | 旧版 LTM 合成入口骨架，尚未接入当前自动调度链 |
| `CombatRecapTrigger` | ActionBuffer 中 KILL 计数 ≥ 阈值 | 连杀/恶战回顾骨架 |
| `BossKillTrigger` | 击杀 Boss 级实体 | 重大战斗记忆骨架 |

### 事件感知器

| 类名 | 职责 |
|---|---|
| `TickEventWatcher` | 统一事件感知器：监听 Forge 事件，写入 ActionBuffer，检测群系变化和旅行 |

## TickEventWatcher 事件监听

`TickEventWatcher` 已扩展为统一事件感知器，监听以下 Forge 事件并写入 `ActionBuffer`：

| @SubscribeEvent | 产生的 ActionRecord |
|---|---|
| `LivingHurtEvent` (玩家攻击/被攻击) | `ATTACK` / `HURT_BY` |
| `LivingDeathEvent` (击杀/死亡) | `KILL` / `DEATH` |
| `BlockEvent.BreakEvent` | `MINE_BLOCK` |
| `LivingEntityUseItemEvent.Finish` | `EAT` / `USE_ITEM` |
| Tick 采样：锚点位移 | `TRAVEL`（距锚点 >200 格） |
| Tick 采样：群系差分 | `BIOME_CHANGE` + fire BiomeChangeTrigger |

> **重要**：所有新增的事件监听方法仅向 ActionBuffer 写入记录，不直接触发 LLM。

> **当前状态**：`TickEventWatcher` 目前没有自动派发 `ReflectionTrigger`。因此，长期记忆生成虽然保留了旧版 `ReflectionTask` 代码，但并未形成一条已经投入当前设计使用的稳定触发链。

## 如何新增触发器

### 1. 实现接口

创建一个新类实现 `IAgentTrigger`：

```java
public class MyNewTrigger implements IAgentTrigger {
    @Override public String getSourceName() { return "my_event"; }
    @Override public boolean requiresDebounce() { return true; }
    @Override public List<IContextCollector> getCollectors() { return List.of(new BaseContextCollector()); }
    @Override public AgentTask createTask(AgentContext context) { return new LLMInteractionTask(context); }
}
```

### 2. 调用触发器

```java
// 被动触发（由玩家操作驱动）
AgentSensor.getInstance().fireTrigger(new MyNewTrigger(), player);

// 主动触发（在 TickEventWatcher 的 tick 循环中检测条件）
if (shouldTrigger()) {
    AgentSensor.getInstance().fireTrigger(new MyNewTrigger(), player);
}
```

### 3. 注意事项

- **防抖**：`requiresDebounce() = true` 的触发器受 `AgentSensor` 冷却限制（默认 2 秒）
- **忙检查**：`AgentSensor.fireTrigger()` 自动检查 Runtime 是否空闲
- **主动触发器**应在 `fireTrigger` 前自行检查 `isRuntimeBusy()`
- **配置开关**：建议在 `TalkingWeaponConfig` 中为新触发器添加启用/禁用开关
- **注意力配额**：触发器通过 `getSourceName()` 定义了 `triggerSource`，它直接决定了环境数据塞给大模型时的 Top-K 截断上限值。如果在未显式匹配的情境下调用大模型聊天，系统会自动回落到通用的配置上限。

## 可扩展方向

- `LowHealthTrigger` — 血量低于阈值时主动提醒
- `TimeCycleTrigger` — 日夜交替时触发（如天黑时警告）
- `IdleTrigger` — 玩家长时间无操作时主动搭话
