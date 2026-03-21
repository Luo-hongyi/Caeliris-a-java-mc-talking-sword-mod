# Action Signal 设计草案

本文档用于补充 **player intent 分析所需的底层动作信号层**，并回顾当前 `RecentActionBuffer + ActionArchive` 的职责分工。

它回答两个问题：

- 当前动作层已经有什么，实际是怎么流转的
- 为了支撑 `player intent -> episode split -> LTM generation`，动作层还需要补什么

## 1. 当前动作链路回顾

当前动作链路是：

1. `TickEventWatcher`
2. `ActionRecord`
3. `ActionArchive`
4. `RecentActionBuffer`
5. `RecentActionCollector`
6. `TaggedContextEntry`
7. `LLMInteractionTask`
8. `STMPool`

也就是说，当前动作层已经拆成两层：

- `ActionArchive`
  - 接住 watcher 观察到的原始动作
  - 不做 TTL 淘汰
  - 不做同类压缩
  - 供 `player intent / episode split / LTM generation` 使用
- `RecentActionBuffer`
  - 从同一批动作事件投影出来的 prompt 侧近期摘要缓冲
  - 保留短窗口、压缩、选择性消费语义
  - 供 `RecentActionCollector` 生成“主人刚刚做了什么”

## 2. 当前动作层的真实语义

### 2.1 `RecentActionBuffer` 是短窗口 prompt 缓冲

`RecentActionCollector` 当前流程是：

1. 在 LLM 触发时查看 `RecentActionBuffer`
2. 只消费 prompt 已稳定接入的旧动作类型
3. 按 `PrimaryFocusTag` 分组
4. 生成自然语言动作摘要
5. 写入本轮 `TaggedContextEntry`
6. 未接入 prompt 的新动作不进入 prompt 消费链

这意味着：

- prompt 侧现在不会误消费新加入的 intent / LTM 动作信号
- watcher 记录到的原始动作，会先进入 `ActionArchive`
- `RecentActionBuffer` 只负责“近期可说出口的动作摘要”，不再承担长期连续行为历史
- 当前代码虽然在注释层写了“STM Pool 接管归档”，但真正被写入 `STMPool` 的是后续生成的 `TaggedContextEntry`，不是原始 `ActionRecord`
- 因此 player intent / LTM 应读取 `ActionArchive`，而不是 prompt 摘要层

### 2.2 `RecentActionBuffer` 服务“即时感知”，`ActionArchive` 服务“阶段分析”

`RecentActionBuffer` 当前很适合做的事情：

- “主人刚刚打了一架”
- “主人刚刚挖了一些矿”
- “主人刚刚移动了一大段距离”

它不适合直接承担的事情：

- 判断玩家最近三到十分钟的阶段性目标
- 观察主导行为是否发生偏移
- 给 LTM 生成器提供稳定 episode 边界

原因不是它记录得不够好，而是它故意只保留 prompt 近期语义。

相对地，`ActionArchive` 更适合承担：

- 判断玩家最近三到十分钟的阶段性目标
- 观察主导行为是否发生偏移
- 给 LTM 生成器提供稳定 episode 边界

## 3. 当前动作层已有信号

当前 `ActionType` 包含：

- 战斗：`ATTACK`, `KILL`, `HURT_BY`, `DEATH`
- 劳作：`MINE_BLOCK`, `PLACE_BLOCK`, `USE_GATHER_TOOL`, `ACQUIRE_ITEM`
- 生存：`EAT`, `USE_ITEM`
- 创造：`INTERACT_FUNCTION_BLOCK`, `CRAFT_ITEM`
- 移动：`TRAVEL`
- 环境变化：`BIOME_CHANGE`, `DIM_CHANGE`
- 社交：`TRADE`, `INTERACT_NPC`

其中真正已经由 `TickEventWatcher` 稳定写入的主要是：

- `ATTACK`
- `KILL`
- `HURT_BY`
- `DEATH`
- `MINE_BLOCK`
- `PLACE_BLOCK`
- `USE_GATHER_TOOL`
- `EAT`
- `USE_ITEM`
- `TRAVEL`
- `BIOME_CHANGE`
- `DIM_CHANGE`

其中当前已经定义、但还没有完整接入 watcher + prompt 语义闭环的包括：

- 获得资源物品
- 功能方块交互
- 合成行为
- NPC / 村民交互

## 4. 当前动作层的几个问题

### 4.1 `USE_ITEM` 过宽

现在它把大量“使用物品”行为揉在一起。

问题是：

- 对 prompt 来说，这还勉强可读
- 对 intent 来说，它太模糊，无法区分：
  - 普通使用
  - 采集性工具使用
  - 功能性交互前置动作

### 4.2 泛化 `INTERACT` 过宽

它无法区分：

- 与 NPC 的社交交互
- 与功能方块的生产交互

这会直接污染 `SOCIAL` 和 `CRAFT` 的边界。

### 4.3 `RecentActionBuffer` 会被即时消费

这是当前最关键的一点。

如果我们把 player intent 分析直接建立在 `RecentActionBuffer` 当前内容上，就会出现：

- 上一轮刚看过的动作已经被清掉
- 本轮只能看到尚未被 LLM“读过”的动作
- 行为窗口的连续性取决于 LLM 触发节奏，而不是玩家真实行为节奏

这对 episode 分割来说是不稳定的。

### 4.4 `INNER_MONOLOGUE` 不属于玩家行为层

它不应出现在 `ActionType` 里。

后续最好把它视为：

- Agent 输出层信号

而不是：

- 玩家动作层信号

## 5. 动作层设计原则

为了支撑 player intent，建议动作层遵守以下原则：

### 5.1 一个动作类型只表达一个清晰语义

理想状态下，每个 `ActionType` 都应该有明确的：

- 输入：什么事件会产生它
- 输出：它代表哪一种行为证据

不要让一个动作类型同时承担多个语义角色。

### 5.2 intent 读取的是“行为证据”，不是 prompt 文案

动作层要服务两个上层：

- prompt 近期动作摘要
- player intent 统计

两者都可以复用同一批底层动作事件，但不应共用同一个“消费语义”。

### 5.3 travel / gather / craft 的边界必须在动作层可见

否则后续 intent 层只能猜：

- `TRAVEL` 是否被少量垫脚放块误伤
- `GATHER` 是否被 `USE_ITEM` 稀释
- `CRAFT` 是否和 `SOCIAL` 混淆

## 6. 建议补充的 `ActionType`

下面是当前建议补充的一组动作信号。

### 6.1 保留现有核心动作

以下动作仍然有价值，建议保留：

- `ATTACK`
- `KILL`
- `HURT_BY`
- `DEATH`
- `MINE_BLOCK`
- `PLACE_BLOCK`
- `EAT`
- `USE_ITEM`
- `TRAVEL`
- `BIOME_CHANGE`
- `DIM_CHANGE`
- `TRADE`

### 6.2 建议新增的动作

#### `USE_GATHER_TOOL`

含义：

- 玩家有意识地使用了与资源获取直接相关的工具

用途：

- 作为 `GATHER` 的强证据
- 与 `MINE_BLOCK` 同等级重要

说明：

- 这不是泛化的 `USE_ITEM`
- 它专门服务“采集性工具使用”
- 它的判定不应依赖手工维护一份“哪些物品算工具”的白名单
- 更合理的方向是基于目标相关的通用游戏信号，例如：
  - 当前目标是否是可采集对象
  - 当前手持物是否对该目标表现出明显更高的有效性
  - 该交互是否最终导向资源获取
- 这样更容易兼容其他模组，也能降低维护成本

#### `ACQUIRE_ITEM`

含义：

- 玩家获得了物品，尤其是资源性或产出性物品

用途：

- 给 `GATHER` 和 `CRAFT` 提供结果证据
- 帮助区分“做了动作但没有产出”与“真正拿到了东西”

说明：

- `ACQUIRE_ITEM` 表达的是“获得了什么”
- 它不是“为什么能获得”或“工具是否正确”的替代信号
- 工具是否匹配、破坏效率是否更高，属于挖掘 / 使用行为侧的上下文
- 因此 `ACQUIRE_ITEM` 应保持为一个纯结果信号，而不是混入 harvest 正确性判断

#### `INTERACT_FUNCTION_BLOCK`

含义：

- 玩家与工作台、熔炉、铁砧等功能方块发生交互

用途：

- 作为 `CRAFT` 的强证据
- 避免把这类行为误写到 `SOCIAL`

#### `CRAFT_ITEM`

含义：

- 玩家完成了一次合成 / 制造产出

用途：

- 作为 `CRAFT` 的直接结果证据

#### `INTERACT_NPC`

含义：

- 玩家与村民或其他 NPC 发生明确交互

用途：

- 作为 `SOCIAL` 的直接证据
- 与 `INTERACT_FUNCTION_BLOCK` 清晰分界

## 6.3 当前清理边界

为了保持 `ActionType` 是纯玩家行为层，当前明确不再保留：

- 泛化的 `INTERACT`
- Agent 输出侧的 `INNER_MONOLOGUE`

原因是：

- `INTERACT` 会把 `SOCIAL` 和 `CRAFT` 的边界重新糊掉
- `INNER_MONOLOGUE` 属于 Agent 输出记录，应留在 `AgentOutputRecord` / `InteractionTurn` 这条链路

## 7. 面向 player intent 的推荐分类

如果只看“给 player intent 用”的最小证据集，建议优先依赖：

### `GATHER`

- `MINE_BLOCK`
- `USE_GATHER_TOOL`
- `ACQUIRE_ITEM`

### `TRAVEL`

- `TRAVEL`
- `BIOME_CHANGE`
- `DIM_CHANGE`

说明：

- `BIOME_CHANGE` / `DIM_CHANGE` 只增强旅行证据，不单独成立主 intent

### `COMBAT`

- `ATTACK`
- `KILL`
- `HURT_BY`
- `DEATH`

### `BUILD`

- `PLACE_BLOCK`

### `CRAFT`

- `INTERACT_FUNCTION_BLOCK`
- `CRAFT_ITEM`
- `ACQUIRE_ITEM`

### `SOCIAL`

- `INTERACT_NPC`
- `TRADE`

## 8. 动作层和 player intent 的推荐关系

当前最重要的结论是：

- **player intent 可以使用动作层**
- 但 **不应该直接依赖会被 prompt 消费的 `RecentActionBuffer`**

更合理的关系是：

### 当前实现：`RecentActionBuffer` 只服务 prompt，`ActionArchive` 保留原始动作历史

做法：

- `TickEventWatcher` 对每条观察到的动作同时写入：
  - `ActionArchive`
  - `RecentActionBuffer`
- `RecentActionCollector` 只消费 `RecentActionBuffer`
- player intent 分析读取 `ActionArchive`

优点：

- prompt 摘要和长期分析彻底解耦
- 新动作即使暂时不进 prompt，也不会丢失原始行为时间线
- 最适合后续做 episode 分割

这是当前已经落地的方向。

补充说明：

- 当前为了测试 `ActionArchive` 是否正确积累原始动作时间线，
  会在 `right_click` fire 时额外写出一份 `ActionArchive` dump 日志
- 这个日志写出仅用于当前调试验证，不属于正式设计的一部分
- 后续在 `ActionArchive -> intent / episode / LTM` 链路稳定后，应删除这个测试钩子

## 9. watcher 设计备注

下面补充当前已经讨论过、并且后续实现应遵守的 watcher 判定边界。

### 9.1 `KILL` 不应只看死亡瞬间伤害来源

如果只在死亡事件里检查“最后一击是不是玩家”，会漏掉很多仍应归因给玩家的击杀：

- 击退后坠落
- 点火后烧死
- 持续伤害收尾

因此更合理的实现是：

- 在目标受伤时记录“最近曾被玩家有效伤害”
- 在目标死亡时按一个短时间窗口做归因

当前实现约定：

- 维护一张 `targetUuid -> lastPlayerDamageTime` 的短期归因表
- 默认时间窗口为 `15s`

也就是说，`KILL` 更接近：

- “这个死亡是否最近由玩家主导造成”

而不是：

- “死亡包里最后一跳的 source 是否仍然直接指向玩家”

### 9.2 行为感知不应依赖剑是否拿在手上

长期设计上，剑对玩家行为的感知应默认覆盖：

- 剑在主手
- 剑在副手
- 剑只是在背包中

其中只有一件事需要“手持”作为条件：

- 输入拦截

也就是说：

- 正常的玩家行为 watcher 不应因为剑没拿在手上就失效
- 只有把玩家的右键改写成 `RightClickTrigger` 这类输入级拦截时，才需要检查是否手持

### 9.3 右键交互拦截与行为感知是两回事

当前设计中，我们需要把下面两件事明确拆开：

- 是否要阻止原版 / 方块 / 实体先吃掉右键
- 是否要记录一次玩家发生了某种交互行为

后者不应绑定在前者上。

理想语义是：

- 玩家只要发生了真实交互，就可以被 watcher 感知
- 只有在“手里正拿着剑，并且我们希望剑优先发言”的场景下，才拦截原本的右键处理

### 9.4 `ACQUIRE_ITEM` 的建议来源

`ACQUIRE_ITEM` 后续更适合作为一个汇总型结果信号，来源可以包括：

- 捡起地上的物品
- 合成产物进入玩家侧
- 烧炼产物进入玩家侧
- 其他明确的产出型获得行为

它不需要承担“工具是否正确”的职责。

### 9.5 `USE_GATHER_TOOL` 的当前实现边界

`USE_GATHER_TOOL` 仍然是一个容易做歪的动作，所以当前实现刻意只保留低维护、跨模组兼容性相对更好的判定。

当前第一版实现边界：

- `LeftClickBlock.START`，并要求当前手持物对目标方块表现出明显有效性
  - 同一维度、同一方块坐标、同一手持物的重复 `START` 会走一个短窗口防抖
- `FillBucketEvent`
- `ItemFishedEvent`

也就是说，当前先覆盖：

- 有效挖掘
- 打水 / 取液体
- 钓鱼

暂不覆盖那些需要成功回执、但缺少稳定通用判定的实体右键采集行为。

## 9. 当前建议

如果我们接下来开始落代码，建议顺序是：

1. 补充 `ActionType`
2. 让 watcher 能产出最关键的新动作：
   - `USE_GATHER_TOOL`
   - `INTERACT_FUNCTION_BLOCK`
   - `INTERACT_NPC`
   - `CRAFT_ITEM`
   - `ACQUIRE_ITEM`
3. 不直接把 player intent 建在 `RecentActionBuffer` 上
4. 优先在 `ActionArchive` 上做 intent 统计
5. 再基于这份原始动作历史做 episode 分割

一句话总结：

- `RecentActionBuffer` 适合“让剑知道刚刚发生了什么”
- `ActionArchive` 才适合“让系统知道最近这一阶段玩家在干什么”

这两者相关，但不应是同一个消费层。
