# Collector 开发指南

## 概述

`collector` 包存放所有 **上下文采集器（Context Collector）** 的实现。  
每个采集器负责采集一类游戏信息，写入 `AgentContext.Builder` 的 `extras` 字段。

## 现有采集器

| 类名 | 采集内容 |
|---|---|
| `BaseContextCollector` | HP、饥饿值、坐标、群系、天气、游戏时间 |
| `ScreenshotCollector` | 当前画面截图（Base64），受 `ENABLE_SCREENSHOT` 配置控制 |
| `NearbyEntitiesCollector` | 16 格内实体，按类型分组计数 |
| `PlayerEnvironmentCollector` | 维度、光照、持有物品、盔甲值、经验等级、准星目标方块 |

## 如何新增采集器

### 1. 实现接口

创建一个新类实现 `IContextCollector`：

```java
package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

public class MyNewCollector implements IContextCollector {

    @Override
    public String getName() {
        return "my_new_data";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        // 通过原版 / Forge API 获取数据
        String data = ...;
        
        // 写入 extras（key 会出现在 LLM 的 [Extra Context] 段）
        builder.extra("my_key", data);
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
- **性能**：避免在 `collect()` 中执行耗时操作（如大范围方块扫描），建议限制扫描半径
- **命名**：`extra()` 的 key 使用 `snake_case`，值为人类可读的中/英文文本

## 可扩展方向

- `NearbyStructureCollector` — 附近结构（村庄、矿井、神殿等）
- `InventoryCollector` — 背包物品摘要
- `EffectsCollector` — 当前药水效果
- `RecentChatCollector` — 最近聊天消息
- `BlocksBelowCollector` — 脚下方块类型（判断是否在水上/岩浆旁）
