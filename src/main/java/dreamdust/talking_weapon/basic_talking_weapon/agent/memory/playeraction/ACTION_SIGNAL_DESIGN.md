# Action Signal 设计草案

> 注意：本文是设计稿与历史讨论，不保证和当前实现完全一致。
> 当前实际运行链路请优先看 [../episode/EPISODE_CURRENT_STATE.md](../episode/EPISODE_CURRENT_STATE.md)。

本文档用于补充 **player intent 分析所需的底层动作信号层**，并回顾当前 `RecentActionBuffer + ActionArchive` 的职责分工。

它回答两个问题：

- 当前动作层已经有什么，实际是怎么流转的
- 为了支撑 `player intent -> episode split -> LTM generation`，动作层还需要补什么

> 更新：当前右键 prompt 的近期动作入口已经切到 `MovingActionBuffer`。
> `RecentActionBuffer` 仍保留在内存层中，作为旧链路兼容层和部分 legacy trigger 的依赖，
> 但 `RecentActionCollector` 不再直接从它读取。

## 1. 当前动作链路回顾

当前动作链路是：

1. `TickEventWatcher`
2. `ActionRecord`
3. `ActionArchive`
4. `RecentActionBuffer`
5. `MovingActionBuffer`
6. `RecentActionCollector`
7. `TaggedContextEntry`
8. `LLMInteractionTask`
9. `STMPool`

也就是说，当前动作层已经拆成两层主链外加一个旧兼容层：

- `ActionArchive`
  - 接住 watcher 观察到的原始动作
  - 不做 TTL 淘汰
  - 不做同类压缩
  - 供 `player intent / episode split / LTM generation` 使用
- `MovingActionBuffer`
  - 从同一批动作事件投影出来的 prompt 侧近期摘要缓冲
  - 保留短窗口、压缩、选择性消费语义
  - 供 `RecentActionCollector` 生成“玩家刚刚做了什么”
- `RecentActionBuffer`
  - 旧 prompt 链路遗留的兼容层
  - 当前不再作为 `RecentActionCollector` 的直接输入

## 2. 当前动作层的真实语义

### 2.1 `MovingActionBuffer` 是当前 prompt 短窗口缓冲

`RecentActionCollector` 当前流程是：

1. 在 LLM 触发时查看 `MovingActionBuffer`
2. 读取一个以当前触发时刻为原点、向前看的 moving prompt window
3. 将 window 里的 action entry 拆成多条更自然的“刚刚经历了什么”描述
4. 每条描述只保留该 entry 自己的 `FocusTag` / `DetailTag`
5. 写入本轮 `TaggedContextEntry`
6. `ACQUIRE_ITEM` 这类弱语义信号会在 prompt 视图中被隐藏

这意味着：

- prompt 侧读取的是独立的 moving prompt window 视图，而不是旧的消费式缓冲
- watcher 记录到的原始动作，会先进入 `ActionArchive`
- `MovingActionBuffer` 只负责“近期可说出口的动作摘要”，不承担长期连续行为历史
- 当前代码虽然在注释层写了“STM Pool 接管归档”，但真正被写入 `STMPool` 的是后续生成的 `TaggedContextEntry`，不是原始 `ActionRecord`
- 因此 player intent / LTM 应读取 `ActionArchive`，而不是 prompt 摘要层

### 2.2 `MovingActionBuffer` 服务“即时感知”，`ActionArchive` 服务“阶段分析”

`MovingActionBuffer` 当前很适合做的事情：

- “玩家刚刚打了一架”
- “玩家刚刚挖了一些矿”
- “玩家刚刚移动了一大段距离”

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
- 移动：`TRAVEL`, `IDLE`
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
- `INTERACT_FUNCTION_BLOCK`
- `CRAFT_ITEM`
- `ACQUIRE_ITEM`
- `INTERACT_NPC`
- `TRADE`
- `EAT`
- `USE_ITEM`
- `TRAVEL`
- `IDLE`
- `BIOME_CHANGE`
- `DIM_CHANGE`

其中当前已经定义、但还没有完整接入 prompt 消费语义的主要包括：

- NPC / 村民交互

## 3.1 新增信号：`IDLE` / “发呆”

含义：

- 玩家在一段时间内没有产生任何值得记录的主动动作

用途：

- 给 episode / intent 层一个明确的“阶段空白”信号
- 避免我们只能通过“没有记录”去被动猜测玩家是否停下来了
- 也可以作为 prompt 里的轻量氛围信息，例如“玩家刚才发了一会儿呆”

当前实现：

- 已接入 `TickEventWatcher`
- 已进入动作档案
- 已进入 moving prompt 视图的人性化描述层

检测方式：

- 基于 watcher 的统一时钟做 idle 窗口检测
- 只有在 `120s` 内没有出现任何**非 `IDLE` 动作**时才补写一条 `IDLE`
- 若持续发呆，会按 `120s` 的节奏继续补写，而不是每个 tick 刷屏
- `TRAVEL` 检测先于 `IDLE` 检测执行，因此同一次检查中，只要达到了 travel 结算阈值，就会先落成 `TRAVEL`，避免被当作 idle

注意点：

- `IDLE` 不是“玩家没有移动”
- `IDLE` 也不是 AFK 检测
- 它表达的是“在当前动作档案语义里，这一小段时间没有值得记录的行为事件”

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
- 在同一次有效采集里优先于 `MINE_BLOCK`

说明：

- 这不是泛化的 `USE_ITEM`
- 它专门服务“采集性工具使用”
- 它的判定不应依赖手工维护一份“哪些物品算工具”的白名单
- 更合理的方向是基于目标相关的通用游戏信号，例如：
  - 当前目标是否是可采集对象
  - 当前手持物是否对该目标表现出明显更高的有效性
  - 该交互是否最终导向资源获取
- 这样更容易兼容其他模组，也能降低维护成本
- 在上层 intent 打分时，若同一次有效采集同时产出 `USE_GATHER_TOOL` 与 `MINE_BLOCK`，应以 `USE_GATHER_TOOL` 覆盖 `MINE_BLOCK`，避免双计分

#### `ACQUIRE_ITEM`

含义：

- 玩家拾取了掉落物

用途：

- 给 `GATHER` 提供结果证据
- 帮助区分“做了动作但没有产出掉落”与“真正把掉落物拿到手”

说明：

- `ACQUIRE_ITEM` 表达的是“获得了什么”
- 它当前只回答“玩家拾起了什么掉落物”
- 它不是“为什么能获得”或“工具是否正确”的替代信号
- 工具是否匹配、破坏效率是否更高，属于挖掘 / 使用行为侧的上下文
- 它也不是 `CRAFT_ITEM` 的替代信号，因为 `CRAFT_ITEM` 表达的是“这次结果来自生产链”
- 因此 `ACQUIRE_ITEM` 应保持为一个纯拾取结果信号，而不是混入 crafting / smelting 结果或 harvest 正确性判断

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
- `INNER_MONOLOGUE` 属于 interaction message，应留在 `InteractionMessage` / `InteractionTurn` 这条链路

## 7. 面向 player intent 的推荐分类

如果只看“给 player intent 用”的最小证据集，建议优先依赖：

### `GATHER`

- `USE_GATHER_TOOL`
- `MINE_BLOCK`
- `ACQUIRE_ITEM`

说明：

- 若同一次有效采集同时出现 `USE_GATHER_TOOL` 与 `MINE_BLOCK`，打分时应只按 `USE_GATHER_TOOL` 计主权重，`MINE_BLOCK` 不再重复加分

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

### `SOCIAL`

- `INTERACT_NPC`
- `TRADE`

## 8. 动作层和 player intent 的推荐关系

当前最重要的结论是：

- **player intent 可以使用动作层**
- 但 **不应该直接依赖会被 prompt 消费的 `RecentActionBuffer`**

更合理的关系是：

### 当前实现：`MovingActionBuffer` 服务 prompt，`ActionArchive` 保留原始动作历史

做法：

- `TickEventWatcher` 对每条观察到的动作同时写入：
  - `ActionArchive`
  - `MovingActionBuffer`
  - `RecentActionBuffer`
- `RecentActionCollector` 读取 `MovingActionBuffer`
- player intent 分析读取 `ActionArchive`

优点：

- prompt 摘要和长期分析彻底解耦
- 新动作即使暂时不进 prompt，也不会丢失原始行为时间线
- 最适合后续做 episode 分割

这是当前已经落地的方向。

补充说明：

- 当前在 `right_click` fire 时，会额外写出一份独立的 `ActionArchive` dump 日志
- 该日志当前会同时输出：
  - 原始动作时间线
  - 固定时间窗 compaction 视图
- 这样可以在不改写 `ActionArchive` 原始序列的前提下，直接观察后续 intent / episode 会读取到的分段压缩结果

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

### 9.4 `ACQUIRE_ITEM` 的当前实现边界

`ACQUIRE_ITEM` 当前按“玩家真正拾取了地面掉落物”来记录。

当前实现边界：

- `PlayerEvent.ItemPickupEvent`
  - 记录地面物品被玩家真正拾取
  - 标签会补上：
    - `acquired_item`
    - `picked_up_item`

也就是说，当前第一版明确覆盖的是：

- 捡起地上的物品

当前还没有单独稳定覆盖的是：

- 桶装液体这类直接替换手持物的获得
- 交易结果进入玩家侧
- crafting / smelting 结果进入背包
- 其他没有统一结果事件的直接获得行为

说明：

- `ACQUIRE_ITEM` 不需要承担“工具是否正确”的职责
- `ACQUIRE_ITEM` 现在不再与 `CRAFT_ITEM` 刻意重叠
- 当前设计里：
  - `ACQUIRE_ITEM` 表达“玩家拾起了掉落物”
  - `CRAFT_ITEM` 表达“这个结果来自生产链”

### 9.5 `USE_GATHER_TOOL` 的当前实现边界

`USE_GATHER_TOOL` 仍然是一个容易做歪的动作，所以当前实现刻意只保留低维护、跨模组兼容性相对更好的判定。

当前第一版实现边界：

- `BlockEvent.BreakEvent`
  - 要求当前手持物对目标方块表现出明显有效性
  - 并要求按当前方块掉落规则计算出的 drops 非空，才记录 `USE_GATHER_TOOL`
- `FillBucketEvent`
- `ItemFishedEvent`

也就是说，当前先覆盖：

- 有产出掉落的有效挖掘
- 打水 / 取液体
- 钓鱼

暂不覆盖那些需要成功回执、但缺少稳定通用判定的实体右键采集行为。

打分层提醒：

- `BlockEvent.BreakEvent` 当前仍会独立产出 `MINE_BLOCK`
- 因此上层 intent scorer 需要把“同一次有效采集里的 `USE_GATHER_TOOL`”视为对 `MINE_BLOCK` 的覆盖，避免单次采集被双算

### 9.6 `INTERACT_FUNCTION_BLOCK` 的当前实现边界

`INTERACT_FUNCTION_BLOCK` 当前采用“两段式确认”，避免把“只是点到了功能方块”误记成真实生产行为。

当前实现边界：

- 第一步：`PlayerInteractEvent.RightClickBlock`
  - 仅主手
  - 仅服务端侧事件
  - 目标方块必须存在 `MenuProvider`
  - 目标方块会先排除明显的储物类方块：
    - `chest`
    - `barrel`
    - `shulker`
    - `hopper`
    - `dispenser`
    - `dropper`
    - 以及常见 `crate / drawer / cabinet / locker / bin` 命名
- 第二步：`PlayerContainerEvent.Open`
  - 只有在短窗口内真的打开了容器，才落一条 `INTERACT_FUNCTION_BLOCK`
  - 菜单类型会排除明显不属于生产交互的打开结果：
    - 储物类菜单
    - `merchant`
    - `inventory`

也就是说，当前第一版覆盖的是：

- 工作台
- 熔炉 / 高炉 / 烟熏炉
- 铁砧
- 砂轮
- 织布机
- 锻造台
- 制图台
- 酿造台
- 石切机
- 以及大多数会打开自定义菜单、且不像储物箱的模组功能方块

而不是：

- 单纯点到功能方块但没有成功打开界面
- 箱子 / 桶 / 潜影盒等储物交互
- 村民交易这类社交交互

### 9.7 `CRAFT_ITEM` 的当前实现边界

`CRAFT_ITEM` 当前先按“玩家已经拿到了生产产物”来记录，不在这一层提前做 recipe 推断。

当前实现边界：

- `PlayerEvent.ItemCraftedEvent`
  - 记录普通 crafting 产出
  - 标签会补上：
    - `crafted_output`
    - `crafting_recipe`
- `PlayerEvent.ItemSmeltedEvent`
  - 记录 furnace-family 产出
  - 第一版统一覆盖：
    - `smelting`
    - `blasting`
    - `smoking`
  - 标签会补上：
    - `crafted_output`
    - `smelted_output`

也就是说，当前第一版明确覆盖的是：

- 背包 2x2 / 工作台 3x3 crafting 的产出
- 熔炉 / 高炉 / 烟熏炉取出成品

当前还没有单独稳定覆盖的是：

- 石切机产出
- 锻造台产出
- 铁砧重命名 / 合并一类的产出

原因不是这些行为不重要，而是 Forge 当前给到的通用结果事件并没有把它们和普通 crafting 一起稳定兜住。

因此目前 `CRAFT_ITEM` 第一版先保证：

- 信号来源清晰
- 真正有结果才记录
- 不把 `INTERACT_FUNCTION_BLOCK` 这种“打开了工作台”与“真的做出了产物”混在一起

补充说明：

- 当前 `CRAFT_ITEM` 还没有覆盖附魔、铁砧重命名 / 合并、以及其他并非标准 crafting / smelting 结果事件的生产行为
- 这在第一版里是可以接受的
- 原因是我们在判断 `CRAFT` / `创造` 倾向时，并不是只看 `CRAFT_ITEM`
- 还会同时参考：
  - `INTERACT_FUNCTION_BLOCK`
- 因此像“玩家打开了附魔台，并且随后出现了相关物品结果”这类情况，仍然可以由上层统计组合出较强的创造 / 生产证据
- 也就是说：
  - `CRAFT_ITEM` 不需要在第一版吞下所有“加工”语义
  - 它只需要先稳定表达“这次产物明确来自 crafting / smelting 结果事件”

### 9.8 `INTERACT_NPC` 的当前实现边界

`INTERACT_NPC` 当前按“玩家和一个 NPC 发生了明确右键交互”来记录。

当前实现边界：

- `PlayerInteractEvent.EntityInteractSpecific`
- `PlayerInteractEvent.EntityInteract`

判定规则：

- 仅服务端侧事件
- 仅主手
- 目标实体必须实现 `Npc`
- 同一个 NPC 在短窗口内会走一次防重，避免同一次右键因为 `EntityInteractSpecific` 和 `EntityInteract` 双重入档

标签会补上：

- `npc_interaction`
- `npc`
- 若目标是 `AbstractVillager`
  - `merchant_npc`
- 若目标是 `Villager`
  - villager profession 的自定义 specific tag

也就是说，当前第一版表达的是：

- “玩家和这个 NPC 发生了明确交互”

而不是：

- “玩家已经完成了一笔交易”

后者由 `TRADE` 单独承担。

### 9.9 `TRADE` 的当前实现边界

`TRADE` 当前按“玩家已经和村民完成了一笔实际交易”来记录。

当前实现边界：

- `TradeWithVillagerEvent`

标签会补上：

- `trade_completed`
- `merchant_trade`
- villager 本体标签
- 本次交易结果物品标签

也就是说：

- `INTERACT_NPC` 表达的是“发生了社交交互”
- `TRADE` 表达的是“这次社交交互已经落实成一次成交”

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
