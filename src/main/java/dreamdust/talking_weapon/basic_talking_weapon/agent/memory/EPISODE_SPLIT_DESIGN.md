# Episode Split 设计草案

本文档用于定义 **LTM 生成前的 episode 分割层**。

它回答的问题是：

- 连续的动作与 STM 材料，应该在什么地方切成一段段 episode
- 哪些断点由 `player intent` 偏移触发
- 哪些断点由高显著事件直接触发

当前目标不是立刻实现完整生成器，而是先明确：

- **episode 的最小时间单位**
- **episode region 的状态结构**
- **intent 偏移的确认规则**
- **额外事件断点的设计边界**

---

## 1. 当前前提

### 1.1 最小单位是 1 分钟 segment

当前 `ActionArchiveCompactor` 的固定分段窗口为：

```java
DEFAULT_FIXED_SEGMENT_MS = 60_000L
```

因此当前 episode split 层的最小分析单位先固定为：

- **1 分钟**

这意味着：

- 不再尝试对 1 分钟内做更细粒度断点
- intent 偏移也先只在分钟边界上判断
- 后续即使 LLM 在总结时选择把叙事截断点前后微调，系统级断点仍先按分钟边界计算

### 1.2 一个时间区域允许 1~2 个玩家目的

episode region 不应被简化成“单一 dominant intent”。

原因是 Minecraft 里的真实行为经常是混合的，例如：

- `GATHER + TRAVEL`
- `BUILD + CRAFT`
- `SOCIAL + CRAFT`

因此一个 region 当前建议维护：

- `primaryIntent`
- `secondaryIntent`
- `aggregateScores`
- `segmentCount`

也就是说：

- 一个 region 内允许 **1~2 个 active intents**
- 只有当新的分钟段持续脱离当前 active intents 时，才认为发生了真正的 intent 偏移

---

## 2. episode split 的总体原则

### 2.1 主断点由 intent 偏移触发

episode split 的主逻辑仍然应建立在：

- 最近若干个 1 分钟 segment 的 intent 统计
- 当前 region 的主/副 intent 组合
- 新进入 segment 是否持续偏离该组合

也就是说，默认优先级是：

1. `intent drift`
2. `事件型强断点`
3. `长度兜底`

### 2.2 事件断点只作为辅助，不替代 intent 层

像死亡、进入家附近、设置重生点这类事件确实很适合作为 episode 边界提示，
但它们不应把 intent 层完全绕开。

更合理的定位是：

- 它们是 **额外的 split trigger**
- 用来在高显著时刻强制结束或强烈建议结束当前 region

### 2.3 split 点应尽量落在“语义转折的开始”

当系统通过连续 2~3 个分钟段确认“目的已经偏移”时，
真正的 split 点不应落在“确认完成”的那一分钟，
而应回溯到：

- **候选偏移开始的第一分钟**

这样旧 episode 不会被拖尾，
新 episode 也不会丢失开头。

---

## 3. region 模型

### 3.1 输入

当前 region builder 的输入建议为：

- 固定 1 分钟 `ActionCompactedSegment`
- 对应的 `PlayerIntentSegmentAnalysis`

每个分钟段至少提供：

- `scores[PlayerIntent]`
- `dominantIntent`
- `runnerUpIntent`
- `ambiguous`

### 3.2 region 运行时状态

建议维护一份 `EpisodeRegionState`：

- `startSegmentIndex`
- `endSegmentIndex`
- `aggregateScores`
- `primaryIntent`
- `secondaryIntent`
- `activeIntentSet`
- `segmentCount`
- `lastHardBoundaryReason`

其中：

- `primaryIntent` = 当前 region 聚合分数最高的 intent
- `secondaryIntent` 只有在满足阈值时才存在

建议阈值：

- `secondaryScore >= 0.60 * primaryScore`
- 且不是只在单分钟中短暂出现

### 3.3 active intent set

region 的核心不是单 intent，
而是：

- `activeIntentSet = {primaryIntent}` 或 `{primaryIntent, secondaryIntent}`

后续判断偏移时，不是问：

- dominant 有没有变

而是问：

- 新分钟段是否仍然属于当前 `activeIntentSet`

---

## 4. intent 偏移的确认

### 4.1 不用“单分钟 dominant 变化”直接切段

以下情况不应直接切段：

- 只出现 1 分钟的战斗插曲
- 旅行途中顺手采集
- 建造过程中临时加工一下材料

因为这些都可能只是当前主 episode 中的局部行为，
而不是真正的目的偏移。

### 4.2 偏移分两级

建议把 intent 偏移分成：

#### `soft shift`

特点：

- 新分钟段与当前 region 的 active intents 仍有部分重叠
- 但新的主 intent 已明显在变强

例子：

- `GATHER + TRAVEL -> TRAVEL + COMBAT`
- `BUILD + CRAFT -> CRAFT + SOCIAL`

建议：

- 不立即切段
- 进入候选偏移状态

#### `hard shift`

特点：

- 新分钟段的主/副 intent 与当前 region 的 active intents 基本不重叠
- 或者出现明显的高显著结构切换

例子：

- `BUILD + CRAFT -> COMBAT`
- `TRAVEL + GATHER -> SOCIAL`

建议：

- 以更短的确认窗口切段

### 4.3 确认窗口

建议规则：

- `soft shift`
  - 需要连续 `2~3` 个分钟段维持新趋势
- `hard shift`
  - 连续 `2` 个分钟段即可确认

`no signal` / `weak signal` 分钟的处理建议：

- 不单独触发 split
- 只作为当前 region 的过渡尾部

### 4.4 split 点回溯

当一个候选偏移最终被确认时：

- split 点应回溯到候选偏移开始的第一分钟

例如：

- 第 10 分钟开始出现新主 intent
- 第 11 分钟确认偏移成立

则边界应切在：

- **第 10 分钟开始处**

---

## 5. 额外事件断点

除了 intent 偏移，当前还建议增加若干类辅助 episode 断点。

这些断点的共同原则是：

- 它们不替代 intent region
- 但它们可以强制结束当前 region，或强烈建议结束当前 region

### 5.1 进入已注册地点范围

典型例子：

- 回家
- 回主基地
- 进入常用工作区

这类事件适合作为 episode 边界的原因是：

- 它常常意味着一次旅行的结束
- 也常常意味着玩家接下来会切换到另一组目的，例如：
  - `TRAVEL -> BUILD`
  - `GATHER -> CRAFT`
  - `COMBAT -> RECOVERY / HOME RETURN`

#### 设计建议 A: 进入半径小于离开半径

建议不要只用一个半径同时做进入和离开判定。

建议改为：

- `enterRadius < exitRadius`

即：

- 只有玩家从外部进入更小的核心区域时，才算“进入地点”
- 玩家离开时，则要超出更大的外圈才算“离开地点”

这样做的目的：

- 避免玩家在边界附近来回移动时反复触发
- 为“地点内 / 地点外”状态增加稳定区

建议语义：

- `outside -> insideCore` 才触发 `enter`
- `inside -> outsideOuter` 才触发 `leave`

#### 设计建议 B: 增加断点触发冷却

除了双半径，还应增加一次断点冷却：

- `locationSplitCooldown`

作用：

- 同一地点在短时间内只允许触发一次 episode 边界
- 避免玩家刚进家门后在院子里来回走动，连续切出多个 episode

建议：

- 冷却按分钟或真实秒数都可以
- 第一版建议直接按分钟段计，例如 `3~5` 个 segment 冷却

#### 设计建议 C: 这是“软强断点”，不是绝对强断点

进入注册地点附近虽然很重要，
但不应永远无条件切段。

更合理的策略是：

- 若当前 region 已经持续了一定长度，并且最近不在地点内
  - 则进入地点时触发一次强候选断点
- 若玩家本来就在该地点附近持续活动
  - 则不应重复切段

因此它更像：

- **高优先级辅助断点**

而不是：

- 每次进入范围都硬切

#### 当前实现状态

当前代码已经有：

- `LocationRegistry`
- `BaseContextCollector` 中的“当前位于已登记地点”解析

但当前还没有：

- 面向 episode split 的“进入 / 离开状态机”
- 进入半径 / 离开半径双阈值
- location split cooldown

也就是说：

- **地点信息基础已存在**
- **边界检测逻辑仍需专门补一层 runtime tracker**

### 5.2 离开已注册地点范围

离开已注册地点同样值得作为 episode 边界候选。

典型例子：

- 离开家开始远行
- 离开基地去采集
- 离开村庄或前哨点去探索新区域

这类事件适合作为断点的原因是：

- 它经常意味着“稳定活动阶段结束”
- 也经常意味着接下来要转入：
  - `TRAVEL`
  - `GATHER + TRAVEL`
  - `COMBAT + TRAVEL`

#### 设计建议

离开已注册地点不建议单独再造一套状态机，
而应直接复用：

- `enterRadius < exitRadius`
- `locationSplitCooldown`

也就是说：

- 进入地点用 `insideCore`
- 离开地点用 `outsideOuter`

只有当玩家从：

- `inside -> outsideOuter`

时，才认为发生了真正的“离开”。

#### 建议定位

`LEAVE_REGISTERED_LOCATION` 更适合作为：

- **高优先级辅助断点**

它一般比“进入家”略弱一些，
因为玩家可能只是短暂出门、绕圈或者在外围活动。

因此建议：

- 只在当前 region 已在地点内稳定持续一段时间时，才允许触发
- 若刚刚经历过 `ENTER_REGISTERED_LOCATION`，则应受同一套冷却约束

### 5.3 进入 major structure

进入大型结构也适合作为 episode 边界候选。

典型例子：

- 进入村庄
- 进入要塞
- 进入远古城市
- 进入地牢、神殿、下界堡垒等强语义结构区

这类事件适合作为断点的原因是：

- 它常常标志着一段旅行的目标抵达
- 它会明显改变后续 intent 分布，例如：
  - `TRAVEL -> SOCIAL`
  - `TRAVEL -> COMBAT`
  - `TRAVEL -> LOOT / GATHER`

#### 设计建议 A: major structure 需要白名单

不是所有结构都值得作为 episode 边界。

因此这里应显式维护：

- `majorStructureWhitelist`

白名单的作用是：

- 只允许高叙事强度、强阶段语义的结构参与 episode split
- 避免路过一些低价值结构时频繁切段

#### 设计建议 B: 白名单直接使用现有标签系统

这里不建议再单独维护一套 “MajorStructureType” 枚举。

当前更合理的做法是：

- 直接使用我们已有的 `DetailTag` 系统
- 白名单项直接写成结构对应的 detail tag 字符串

当前结构采集层已经会产出：

- `DetailTag.nativeSpecific(structureId)`

也就是像：

- `native_specific:minecraft:village_plains`
- `native_specific:minecraft:ancient_city`
- `native_specific:minecraft:fortress`

这样的 tag。

因此 `majorStructureWhitelist` 建议直接存：

- `DetailTag.toString()` 结果

而不是：

- 额外再造一层结构枚举或硬编码 id 列表

这样做的好处是：

- 与当前 collector 输出直接对齐
- 可复用已有的 tag description / relation / workspace authored data 管线
- 后续若需要扩展到模组结构，只要它最终产出了同样的 structure detail tag，就能直接加入白名单

#### 设计建议 C: 进入 structure 也要防抖

`ENTER_MAJOR_STRUCTURE` 也不应只靠单帧命中就成立。

建议至少要求：

- 玩家连续命中该 structure tag 一小段时间，或
- 玩家在结构附近稳定停留到下一个分钟段

目的：

- 避免只是从结构边缘路过就硬切 episode

#### 建议定位

`ENTER_MAJOR_STRUCTURE` 建议作为：

- **高优先级辅助断点**

它通常弱于 `DEATH` 和 `SET_RESPAWN_POINT`，
但强于一般的 soft shift。

### 5.4 离开 major structure

离开大型结构同样值得记录为候选边界。

典型例子：

- 清完村庄交易后离开
- 探索完远古城市后撤出
- 打完要塞 / 神殿后重新回到旅行状态

这类事件适合作为断点的原因是：

- 它常常意味着一个“结构内目标”已经结束
- 后续 intent 往往会切回：
  - `TRAVEL`
  - `HOME RETURN`
  - `CRAFT / BUILD`

#### 设计建议

`EXIT_MAJOR_STRUCTURE` 也应基于一套简单状态机：

- `outsideStructure`
- `insideMajorStructure`

并且只对：

- 在白名单中的 structure tag

生效。

建议语义：

- 先确认玩家已经在某个 major structure 中稳定停留过
- 之后连续离开该 structure 感知范围时，再触发 `exit`

这样可以避免：

- 只是结构边缘抖动
- 两个相邻 structure 感知结果短时间交替

#### 建议定位

`EXIT_MAJOR_STRUCTURE` 更适合作为：

- **高优先级辅助断点**

但一般略弱于 `ENTER_MAJOR_STRUCTURE`，
因为“离开”更容易和普通 travel 融在一起。

### 5.5 死亡

死亡是非常强的 episode 断点。

原因：

- 它天然意味着一个阶段被中断或终结
- 死前与死后的玩家目的通常会明显不同
- 死亡本身也非常值得进入长期记忆主题

#### 建议定位

死亡应作为：

- **硬断点**

也就是说：

- 一旦确认玩家死亡，允许立即结束当前 episode
- 下一个 episode 从死亡后的恢复 / 重生阶段重新开始

#### 设计建议

- 若同一分钟内已有大量动作，则死亡应作为该分钟的尾部高显著结束事件
- 若连续短时间内多次死亡，应避免每次都产出极碎片的 episode

因此建议附带一个很短的死亡断点冷却，例如：

- 同一连续死亡链只切一次

但整体原则仍然是：

- `DEATH` 的优先级高于普通 intent 偏移确认

### 5.6 设置重生点

设置重生点也是值得考虑的 episode 断点。

原因：

- 它通常表示玩家把某地认定为新的落脚点
- 它经常伴随“旅行结束 / 前哨建立 / 家园建立 / 地下据点确立”
- 它在叙事上很像一次阶段性锚定

#### 建议定位

设置重生点不一定要像死亡那样绝对强硬，
但它至少应作为：

- **高显著结构性断点**

它的语义更像：

- “玩家刚刚把这里正式变成了自己的据点”

#### 为什么它比“进入已注册地点”更强

因为：

- 进入已注册地点只是“回到熟悉区域”
- 设置重生点则是“正式建立或更新一个新的归属锚点”

它更接近：

- 一次阶段目标完成
- 一次新阶段的开始

#### 当前缺口

这一条目前大概率需要：

- **新增动作检测**

因为现在动作层里还没有一个明确表达：

- `SET_RESPAWN_POINT`

因此建议后续补入：

- 一个专门的 watcher / action signal
- 再把它接入 episode split 层

在动作层尚未补好之前：

- 这条断点先只保留在设计文档中

---

## 6. 建议的断点优先级

当前建议把 episode 边界优先级定为：

1. `DEATH`
2. `SET_RESPAWN_POINT`
3. `ENTER_REGISTERED_LOCATION`
4. `LEAVE_REGISTERED_LOCATION`
5. `ENTER_MAJOR_STRUCTURE`
6. `EXIT_MAJOR_STRUCTURE`
7. `INTENT_HARD_SHIFT`
8. `INTENT_SOFT_SHIFT_CONFIRMED`
9. `STM / EPISODE_LENGTH_CAP`

说明：

- `DEATH` 是明确的硬断点
- `SET_RESPAWN_POINT` 是高显著结构断点
- `ENTER_REGISTERED_LOCATION` / `LEAVE_REGISTERED_LOCATION` 是地点边界辅助断点
- `ENTER_MAJOR_STRUCTURE` / `EXIT_MAJOR_STRUCTURE` 是结构边界辅助断点
- `intent shift` 仍然是常规主逻辑
- 长度上限只是兜底

---

## 7. 下一步实现建议

建议实现顺序：

1. 先实现 `EpisodeIntentTracker`
   - 输入：分钟级 `PlayerIntentSegmentAnalysis`
   - 输出：region、active intent set、candidate shift、confirmed split
2. 再补 `RegisteredLocationBoundaryTracker`
   - 增加 enter/exit 双半径
   - 增加 split cooldown
3. 再补 `MajorStructureBoundaryTracker`
   - 读取 structure detail tags
   - 基于 `majorStructureWhitelist` 做 enter/exit 状态判断
4. 接入 `DEATH` 的硬断点规则
5. 设计并补入 `SET_RESPAWN_POINT` 动作检测
6. 最后再把这些断点统一汇总到 LTM 生成调度器

### 7.1 接入 Studio workspace 的后续代办

在 episode 断点主逻辑完成后，
需要把下面这条链路整体接入 workspace，
作为后续调试、可视化和 authored data 校验的统一观察面：

- `ActionArchive raw actions`
- `fixed 1-minute segments`
- `PlayerIntentSegmentAnalysis`
- `Episode region state`
- `confirmed split points`
- `boundary reasons`

也就是说，后续需要新增一层运行时导出：

- `action -> intent -> episode split`

而不是只停留在当前已有的：

- `action -> intent`

这条代办建议在 episode 断点逻辑稳定后再做，
不要和第一版断点实现并行推进。

---

## 8. 当前结论

当前 episode split 的设计应当满足：

- **1 分钟是最小单位**
- **一个 region 允许 1~2 个 active intents**
- **主断点由 intent 偏移驱动**
- **注册地点进出 / major structure 进出 / 死亡 / 设置重生点 作为辅助高显著断点**

其中：

- 进入注册地点需要：
  - `enterRadius < exitRadius`
  - `locationSplitCooldown`
- major structure 需要：
  - 一份基于现有 `DetailTag` 系统的白名单
  - enter/exit 状态判断
  - 必要的停留确认 / 防抖
- 死亡应视为硬断点
- 设置重生点应保留为高显著断点，并在后续补 action detection

---

## 9. 当前临时测试功能

为了验证第一版 delayed split 的体感是否合理，
当前实现允许在 **新 episode 断点首次被确认时**，
立即通过和 `speech` 相同的 hotbar / actionbar 输出一条提示。

当前提示的语义是：

- 系统刚刚确认了一个新的 episode 断点
- 由于断点允许延迟确认，因此提示里会额外说明：
  - 这个断点实际回溯到大约多久之前

注意：

- 这是一条**临时测试功能**
- 目标只是帮助观察 delayed confirmation 的实际体验
- 它不应作为正式玩法反馈长期保留
- 在 episode 断点逻辑稳定后，这条即时 hotbar 提示应删除
- 当前测试里，intent shift 的确认窗口也被进一步收紧：
  - 在 region 已达到最小长度后
  - 若出现新的 shift 候选，当前实现允许首个候选段就直接确认 split
  - 这同样是为了观察切段体感而做的临时调参，不代表最终规则
