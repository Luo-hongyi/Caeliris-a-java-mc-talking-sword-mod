# Trigger 开发指南

## 概述

`trigger` 包存放所有 **触发器（Agent Trigger）** 的实现。  
触发器定义：**什么游戏事件** → **需要哪些采集器** → **产生什么任务**。

## 现有触发器

| 类名 | 触发方式 | 采集器 | 产生任务 |
|---|---|---|---|
| `RightClickTrigger` | 玩家右键使用武器 | Base + 截图 + 实体 + 环境 | `LLMInteractionTask` |
| `BiomeChangeTrigger` | 进入新群系（由 TickEventWatcher 检测） | Base | `AgentStateUpdateTask` |
| `TickEventWatcher` | Forge `ClientTickEvent`（周期检测） | — （调度其他触发器） | — |

## 如何新增触发器

### 1. 实现接口

创建一个新类实现 `IAgentTrigger`：

```java
package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

public class MyNewTrigger implements IAgentTrigger {

    @Override
    public String getSourceName() {
        return "my_event";  // 会写入 AgentContext.triggerSource
    }

    @Override
    public boolean requiresDebounce() {
        return true;  // 交互式触发建议开启防抖
    }

    @Override
    public List<IContextCollector> getCollectors() {
        return List.of(
            new BaseContextCollector(),
            new ScreenshotCollector()
            // 按需组合采集器
        );
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        return new LLMInteractionTask(context);
        // 或 new AgentStateUpdateTask(context, "描述")
        // 或自定义 AgentTask 子类
    }
}
```

### 2. 调用触发器

#### 被动触发（由玩家操作驱动）

在事件处理器中调用：

```java
AgentSensor.getInstance().fireTrigger(new MyNewTrigger(), player);
```

#### 主动触发（由 TickEventWatcher 驱动）

在 `TickEventWatcher.onClientTick()` 中添加检测逻辑：

```java
// 在 onClientTick 的检测区域添加
if (shouldTriggerMyEvent(player, level)) {
    AgentSensor.getInstance().fireTrigger(new MyNewTrigger(), player);
}
```

### 3. 注册 Forge 事件（如需要）

如果触发器需要监听新的 Forge 事件，在 `basic_talking_weapon` 构造函数中注册：

```java
MinecraftForge.EVENT_BUS.register(new MyEventListener());
```

### 4. 注意事项

- **防抖**：`requiresDebounce() = true` 的触发器受 `AgentSensor` 的冷却时间限制（默认 2 秒）
- **忙检查**：`AgentSensor.fireTrigger()` 会自动检查 Runtime 是否空闲，忙时跳过
- **主动触发器**应在 `fireTrigger` 前自行检查 `AgentSensor.getInstance().isRuntimeBusy()`
- **配置开关**：建议在 `TalkingWeaponConfig` 中为新触发器添加独立的启用/禁用开关

## 可扩展方向

- `DamageTakenTrigger` — 受到伤害时触发（评论当前战况）
- `KillEntityTrigger` — 击杀生物时触发（庆祝或吐槽）
- `LowHealthTrigger` — 血量低于阈值时主动提醒
- `TimeCycleTrigger` — 日夜交替时触发（如天黑时警告）
- `IdleTrigger` — 玩家长时间无操作时主动搭话
