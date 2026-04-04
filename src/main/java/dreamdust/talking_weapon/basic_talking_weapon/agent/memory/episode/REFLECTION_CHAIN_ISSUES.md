# Reflection Chain Issues

截至 `2026-04-03`，当前新版 reflection 主线已经形成了可运行的闭环：

- `EpisodeTimelineStore.collectEpisodeSynthesisTriggerSignals()`
- `EpisodeSynthesisInternalTrigger`
- `EpisodeSynthesisReflectionTask`
- `owner-side snapshot / cleanup`

但从原材料导出到 commit / cleanup 收尾这一整条链路看，仍有少数边界问题需要继续清理。这里保留当前状态，避免后续设计和代码再错位。

## 1. 当前检查范围

本轮检查覆盖：

- `EpisodeSynthesisTriggerSignal`
- `ReflectionMaterialRequest`
- `DefaultReflectionMaterialExporter`
- `DefaultReflectionMaterialMerger`
- `DefaultReflectionStateSnapshotLoader`
- `DefaultReflectionPersonaLoader`
- `AbstractReflectionTask`
- `EpisodeSynthesisReflectionTask`
- `AgentMemory.snapshotSourceMaterial(...) / cleanupSourceMaterial(...)`

## 2. 当前状态

### 2.1 本轮已收口的问题

下面这些问题已经不再是当前主阻塞：

1. synthesized LTM 缺少正式游戏内时间坐标
2. consumed cleanup stale 后仍继续 resolved
3. STM local context 没有进入 reflection material
4. exporter 仍只使用 segment default snapshot
5. persona loader 对 material 的利用过窄
6. 时间窗使用闭区间末点 + `+1` 补丁，存在 off-by-one 风险
7. InteractionTurn 只带 visible contexts，不带 extras

当前代码已经落实为：

- `EpisodeSynthesisReflectionTask` 写入 `occurredGameTime / occurredDayIndex / occurredTimeOfDayTick`
- consumed cleanup 返回 `stale` 时，不再继续推进 region 到 `RESOLVED`
- `DefaultReflectionMaterialExporter` 会同时消费：
  - segment `effective` snapshot
  - `STMPool.TimeSlice.contextEntries`
  - `STMPool.TimeSlice.interactionTurns`
- `DefaultReflectionMaterialMerger` 会把单段材料统一整理为：
  - `SEGMENT_SNAPSHOT`
  - `LOCAL_STM_CONTEXT`
  - `PLAYER_ACTION`
  - `AGENT_INTERACTION`
- `DefaultReflectionPersonaLoader` 已不再只看 player action，也会利用：
  - segment snapshot
  - local STM context
  - interaction bound snapshot
- `EpisodeRegion / EpisodeSynthesisTriggerSignal / ReflectionMaterialRequest / MemorySourceMaterialWindow` 的正式时间窗
  现在统一固定为 `[start, end)` 右开区间，不再额外对 `endTimestamp` 做 `+1`
- `InteractionTurn` 现在会正式携带 `contextExtras`
- `DefaultReflectionMaterialExporter` 在构造 `ReflectionInteraction.boundSnapshot` 时
  已改为同时注入：
  - `visibleContexts`
  - `contextExtras`

### 2.2 当前剩余问题

当前这一轮冻结的问题已全部收口，暂时没有新的底层 blocker。

### 2.3 暂缓项

- reflection system prompt 当前仍未注入 `PromptRegistry.getFocusTagDescription()`
- 当前先维持现状，先观察 episode synthesis prompt 是否真的出现 focus tag 语义理解偏差
- 这一项先只在文档中登记，暂不接入
- 只有当后续 prompt 中出现明显的 focus tag 语义歧义时，再把它提升为正式改动

## 3. 建议修复顺序

推荐先按下面顺序处理：

1. 继续搭 episode synthesis 的更高层 module / prompt 组合

- 这时底层 material 边界会更稳定
- 可以减少后面反复返工 exporter 和 task input 的次数

## 4. 关于“时间锚点”的设计澄清

这里需要先拆开两种概念，不然后面讨论会混：

1. 正式游戏内时间坐标

- `occurredGameTime`
- `occurredDayIndex`
- `occurredTimeOfDayTick`

它们服务的是：

- recall 年龄衰减
- 时间排序
- 运行时正确性

2. 叙事时间锚点

- `semanticTimeTag`
- `semanticTimeLabel`

它们服务的是：

- 给 LLM / Prompt 一个语义上的“这是清晨 / 深夜 / 雨夜 / 某个阶段”
- 为没有正式时间坐标的记忆提供可读的叙事锚点

当前更合理的设计结论是：

- `IMPLANTED` 记忆：
  - 可以没有正式游戏内时间坐标
  - 但更适合带叙事时间锚点
- `SYNTHESIZED` 记忆：
  - 必须有正式游戏内时间坐标
  - 叙事时间锚点可以是可选增强层，而不是硬要求

也就是说：

- synthesized memory **不一定需要 semantic time tag**
- 但 synthesized memory **仍然需要正式游戏内时间坐标**

这两件事不能混为一谈。

当前代码上的落地原则也已经同步为：

- synthesized LTM 在 reflection task 提交时写入 `occurredGameTime / occurredDayIndex / occurredTimeOfDayTick`
- synthesized LTM 的默认时间文案优先由正式游戏内时间坐标生成
- `semanticTimeTag` 仅作为可选语义增强层保留，不再承担默认时间文案兜底职责

## 5. 关于“重复观察 / 正式消费 / stale”的进一步澄清

在继续推进 reflection 主线之前，需要先把下面这组边界钉住。

### 5.1 同一段 material 可以被重复读取

这里应区分两种行为：

1. 重复观察 / 重复分析

- 同一段 `segment / region` 在 cleanup 之前，可以被多个只读任务重复读取
- 这些任务可以在不同时间、不同粒度上使用同一段 material
- 典型例子：
  - 更高频的 `emotion` 刷新
  - recap preview
  - 其他临时反思或只读型评估

2. 正式消费 / 生命周期推进

- 只有“正式生成长期记忆并消费源材料”的任务，才允许推进 `segment / region` 的正式生命周期
- 当前设计上，这个角色应明确收敛到 `episode synthesis / LTM synthesis` 任务
- 其他较小的 reflection task 应保持只读，不修改：
  - region lifecycle
  - segment discard state
  - source material cleanup state

因此，“重复观察”是允许的；“重复正式结算同一段 source material”则不应成为常规语义。

### 5.2 stale 的目标语义

在上述边界下，`stale` 不应被视为主流程的一部分，而应收敛为低频保护信号。

更合理的目标语义是：

- 只读任务可以重复读取同一段 material，不涉及 stale
- 只有消费型任务在 claim / cleanup 时才会触发 stale 判断
- 如果发生 stale，优先应理解为：
  - 旧任务拿着过期票据回来结算
  - 同一段材料已经被更早的正式消费路径处理

也就是说：

- 在目标设计里，stale 理论上不应频繁出现
- 如果 stale 经常出现，就更像是“claim 语义没立住”或“cleanup 生命周期没有闭合”

### 5.3 基于当前实现，stale 的含义已经收缩

在当前代码已经完成一轮 `timeline index -> absolute segmentId` 正式坐标清理之后，`stale` 不应再承担“坐标漂移兜底”的主职责。

当前已经成立的事实是：

- `EpisodeRegion`
  - 已不再持有 `startSegmentIndex / endSegmentIndex`
- `EpisodePendingMark`
  - 已不再持有 `*TimelineIndex`
- `EpisodeSplitPoint`
  - 已不再持有 `*TimelineIndex`
- `EpisodeTracker`
  - detect / confirm / cleanup / rebuild 的正式语义已切到绝对 `segmentId`
- reflection signal / material exporter
  - 也已不再跨异步边界泄露 timeline index

因此，当前更合理的 stale 来源只剩两类：

1. 真正的重复 cleanup / 旧任务回收
2. 生命周期实现错误
   - claim / consume / cleanup 顺序错误
   - owner-side cleanup plan 构造错误
   - 任务在 resolve / reject / cancel 之后仍继续 cleanup

所以，基于当前版本，`stale` 仍然可能发生，但它更应该被视为：

- 旧票据防重
- 或实现层的异常信号

而不应再被理解为“相对坐标不稳定导致的常规保护”。

## 6. 当前坐标体系中，哪些是“还不稳定的正式坐标”

这一节原本用于定位“哪些正式结构还混着 timeline index”。当前代码已经推进到下一阶段，所以这里需要更新成“哪些正式结构已经稳定，哪些仍只是局部实现细节”。

### 6.1 已完成稳定化的正式坐标

1. `EpisodeRegion`

- 正式边界已经是：
  - `startSegmentId`
  - `endSegmentId`
- `regionId` 也已经不再混入 index 推导

2. `EpisodePendingMark`

- 正式 detect / split / confirm 坐标已经是：
  - `detectedAtSegmentId`
  - `splitLeftSegmentId`
  - `splitRightSegmentId`
  - `confirmAtSegmentId`
- `delay` 和 `cover` 也已按绝对 `segmentId` 语义计算

3. `EpisodeSplitPoint`

- 正式 split / confirm 坐标已经是：
  - `splitLeftSegmentId`
  - `splitRightSegmentId`
  - `confirmationSegmentId`

4. `EpisodeTimelineStore` 的 signal / gate 边界推导

- 外部 signal 已切到 `segmentId + timestamp`
- 内部正式边界推导也已经以 `segmentId` 为主
- 这里剩下的主要风险已不再是坐标漂移，而是 cleanup lifecycle 是否闭合

### 6.2 目前可以接受的局部 index

下面这类 index 当前不是主要问题：

- `ReflectionMaterial` / `ReflectionSegmentMaterial` 里的 `localSegmentIndex`
- `memory_synthesis.selected_segment_indices`

原因是：

- 它们只在单次 task 内部使用
- 不被持久化
- 不跨 task / 跨模块拿去做 owner-side cleanup

所以它们属于：

- task-local 引用编号
- 不是正式持久化坐标

## 7. 将正式坐标体系统一切到“单调递增绝对 segment id”的可行性评估

结论先写在前面：

- **可行**
- **而且对跨模块 / 异步 / 持久化边界来说，这是正确方向**
- 当前这条路线已经进入“正式结构基本完成”的阶段
- 但**不应该理解成“系统里所有 index 都要消失”**

更准确的目标应是：

- 所有正式引用统一到单调递增的绝对 `segmentId`
- 所有只服务局部算法的当前位置值，降级为临时内部变量，不再进入正式状态和持久化结构

### 7.1 为什么这条路可行

当前 fixed segment 层已经具备关键前提：

- `FixedActionSegmentBuffer` 已维护单调递增的 `nextSegmentId`
- 新 segment seal 时会分配新的 `segmentId`
- runtime state 也已经持久化 `nextSegmentId`
- discard / compact 不会重写已有 active segment 的 `segmentId`

也就是说，底层 segment identity 已经开始具备“绝对稳定坐标”的雏形。

### 7.2 这条路已经直接解决了什么

现在已经直接改善了：

1. 中间 region 删除不会导致后续结构整体重编号
2. signal / cleanup plan / persisted state 不再依赖当前数组位置
3. stale 的来源已经明显收缩，更接近真正的“重复 cleanup / 旧票据”语义
4. region / split / pending mark 的跨重启恢复会更稳定

### 7.3 但“一个绝对 segment id”还不足以表达全部边界语义

需要注意：

- region 是一个范围，不是一个点
- split 是一条边界，不是一个 segment

因此不能简单把所有结构都替换成单个 `long segmentId`。

更合理的正式坐标模型应是：

1. segment 范围

- `startSegmentId`
- `endSegmentId`

2. split 边界

- `splitLeftSegmentId`
- `splitRightSegmentId`
- 必要时保留 `splitTimestamp`

3. confirm / detect 锚点

- `detectedAtSegmentId`
- `confirmationSegmentId`

也就是说：

- 正式体系应统一到“绝对 segment id 家族”
- 但具体结构仍需按“点 / 范围 / 边界”来表达

### 7.4 剩余阻力已经不在正式字段，而在算法与生命周期语义

真正还需要盯住的点，不再是“字段怎么改成 long”，而是下面这些逻辑是否始终坚持绝对语义：

- `delay by N segments`
- `coverBeforeSegments / coverAfterSegments`
- split boundary 的左右邻接关系
- cleanup plan 的 owner-side 生命周期

这些逻辑本质上依赖的是：

- 顺序
- 邻接关系
- 相对距离

绝对 `segmentId` 可以稳定表达顺序，因此正式语义应落在它上面；局部 list index 只应继续服务临时运行时访问。

当前代码已经基本落在这个形态上：

- 正式状态与持久化结构：统一用绝对 `segmentId`
- tracker / splitter 运行时局部算法：允许继续临时计算当前位置或局部顺序
- 但这些局部 index 不再持久化，也不再跨边界泄露

### 7.5 对当前系统的具体判断

所以，当前系统的判断应更新为：

1. 正式坐标重构这一阶段已经基本完成

- `EpisodeRegion`
- `EpisodePendingMark`
- `EpisodeSplitPoint`
- `EpisodeTracker`
- runtime persistence
- reflection signal / exporter

2. 接下来的重点不再是“继续拔字段”，而是“收紧 stale 和 cleanup lifecycle”

- 让 stale 真正退回“旧票据 / 重复 cleanup 保护”
- 把 claim / consume / cleanup / cancel 的 owner-side 语义补齐

因此，坐标问题已经从主问题降级；接下来更值得聚焦的是生命周期闭环本身。
