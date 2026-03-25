# Episode Current State

本文档记录 `2026-03-25` 代码回滚后的 **当前实际实现**。

它不是设计草案，而是按现有代码重新梳理出的运行现状。后续如果设计稿与实现不一致，请优先以本文和源码为准。

## 1. 当前总览

现在的 episode 链路已经有一条可运行的主干：

1. `TickEventWatcher` 监听 Forge 事件，产出 `ActionRecord`
2. `AgentMemory.recordObservedAction(...)` 同时把动作写入：
   - `ActionArchive`，用于正式 fixed segment / episode
   - `MovingActionBuffer`，用于 prompt 侧近期动作
3. `ActionArchive` 通过 `FixedActionSegmentBuffer` 产出 **sealed fixed segments**
4. 新 fixed segment 会立刻送入 `EpisodeTimelineStore`
5. `EpisodeTimelineStore` 调用 `EpisodeTracker`，维护：
   - `segments`
   - `pending marks`
   - `split points`
   - `regions`
6. `EpisodeSynthesisMaterial` / `EpisodeSynthesisPromptFormatter` 已经可以把一个 closed region 的材料拼成 prompt draft
7. timeline 第一段进入时会自动补一个 bootstrap split at `segmentIndex = 0`
8. 当前存在一个临时的“右键触发手动 episode synthesis debug task”链路，用来测试 prompt draft 生成是否会卡顿

但这条链路还没有完全闭环，当前仍缺少几段正式接线：

- `EpisodeTimelineStore.peekEpisodeSynthesisTriggerSignal()` 仍然返回空
- 但 `EpisodeTimelineStore.peekLatestClosedRegionDebugSignal()` 已可读取“最近一个已闭合 region”
- `EpisodeSynthesisTrigger` 已定义，但没有实际被 episode 自动触发
- Studio workspace 已有 `studio_runtime_episode_timeline.json`，但 Electron Studio 里目前没有 episode 页面
- `EpisodeSynthesisTask` 当前只会生成 preview + 日志，不会真的提交 LTM

## 2. 动作采集层

### 2.1 统一入口

玩家动作当前统一由 [TickEventWatcher.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/trigger/TickEventWatcher.java) 采集。

这个 watcher 做两类事情：

- 直接监听 Forge 事件并记录动作
- 在 `onClientTick` 中做低频轮询检测

已经接入的主要动作类型包括：

- 战斗：`ATTACK`, `KILL`, `HURT_BY`, `DEATH`
- 劳作：`MINE_BLOCK`, `PLACE_BLOCK`, `USE_GATHER_TOOL`, `ACQUIRE_ITEM`
- 生存：`EAT`, `USE_ITEM`
- 生产：`INTERACT_FUNCTION_BLOCK`, `CRAFT_ITEM`
- 探索：`OPEN_LOOT_CONTAINER`, `TRAVEL`, `IDLE`, `BIOME_CHANGE`, `DIM_CHANGE`
- 社交：`INTERACT_NPC`, `TRADE`

### 2.2 边界动作 watcher

除了常规动作，当前还存在两类 episode 候选边界动作 watcher：

- [RegisteredLocationBoundaryTracker.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/trigger/RegisteredLocationBoundaryTracker.java)
  - 产出 `ENTER_REGISTERED_LOCATION`
  - 产出 `LEAVE_REGISTERED_LOCATION`
- [MajorStructureBoundaryTracker.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/trigger/MajorStructureBoundaryTracker.java)
  - 产出 `ENTER_MAJOR_STRUCTURE`
  - 产出 `EXIT_MAJOR_STRUCTURE`

这些动作现在已经进入 `ActionArchive` / `FixedActionSegment`，也会进入 `observedActionTypes`，但 **当前没有任何 episode splitter 消费它们**。

### 2.3 动作进入内存

`TickEventWatcher.emitObservedAction(...)` 最终调用 [AgentMemory.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/AgentMemory.java) 的 `recordObservedAction(...)`：

- 先写入 `ActionArchive`
- 再把新产生的 fixed segment update 应用到 `EpisodeTimelineStore`
- 最后写入 `MovingActionBuffer`

当前 episode 吃的是 `ActionArchive` 这条正式链，不是 moving prompt window。

## 3. Action Archive 与 Segment 现状

### 3.1 ActionArchive

[ActionArchive.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/ActionArchive.java) 保存的是原始动作流：

- 环形容量默认 `4096`
- 不做 TTL
- 不在原始层做 prompt 语义裁剪

它内部持有 [FixedActionSegmentBuffer.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/FixedActionSegmentBuffer.java)，负责把原始动作压成正式 fixed segment。

### 3.2 Fixed segment 的形成

当前 fixed segment 的正式时间窗来自 [ActionTimelineRuntimeSettings.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/ActionTimelineRuntimeSettings.java)：

- `fixedSegmentMs`
- 默认是 `60_000 ms`

fixed segment 的关键特征：

- 只把 **sealed segment** 送给 episode
- 当前仍在累加的 open segment 不会进入 episode timeline
- 段内只合并“同 `ActionType` + 同 `target`”的动作
- entry 会保留：
  - `count`
  - `firstTimestamp / lastTimestamp`
  - `rawStartIndex / rawEndIndex`
  - `detailTags`

静态重建时走 [ActionArchiveCompactor.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/ActionArchiveCompactor.java)，增量更新时走 `FixedActionSegmentBuffer.append(...)`。

### 3.3 默认 context 快照

fixed segment 在创建时会采一份默认环境快照，入口在 [FixedSegmentDefaultContextCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/FixedSegmentDefaultContextCollector.java)。

当前行为是：

- 复用标准交互 collector 组合
- 但明确关闭：
  - screenshot
  - recent actions
- 最终得到一份 `CollectedContextSnapshot`
- 并同时生成一份默认 `visible context` 视图

segment 上下文结构在 [FixedActionSegmentContext.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/FixedActionSegmentContext.java)：

- `defaultContextSnapshot`
- `mergedContextSnapshot`
- `interactionTurns`

其中：

- `defaultContextSnapshot` 是段开始时的兜底快照
- `mergedContextSnapshot` 是后续 STM slice 融合进来的正式上下文
- `interactionTurns` 是该段内归桶后的 LLM 交互回合

## 4. Prompt 侧 moving segment 现状

[MovingActionBuffer.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/moving_segment/MovingActionBuffer.java) 只服务 prompt 侧近期动作，不参与 episode 边界。

当前它会：

- 维护一个 moving prompt window
- 对块类动作做折叠
- 在 prompt 视图里隐藏 `ACQUIRE_ITEM`

[RecentActionCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/RecentActionCollector.java) 读取它，并生成 `memoryEligible=false` 的 prompt 动作描述。

这意味着当前有一个明确的不对称：

- 即时 prompt 侧会隐藏 `ACQUIRE_ITEM`
- episode synthesis prompt draft **不会**隐藏 `ACQUIRE_ITEM`

## 5. Episode splitter signal 现状

### 5.1 固定信号载体

每个 [FixedActionSegment.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/FixedActionSegment.java) 在构造时都会自动生成 [FixedSegmentSplitterSignals.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/FixedSegmentSplitterSignals.java)。

当前只有两类正式 signal：

- `intentSignals`
- `observedActionTypes`

### 5.2 intentSignals

`intentSignals` 来自 [PlayerIntentScorer.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/PlayerIntentScorer.java)，当前输出对象是 [SegmentIntentSignals.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/SegmentIntentSignals.java)。

当前 intent scorer 的结果包括：

- 每个 `PlayerIntent` 的 score
- dominant intent / runner-up intent
- ambiguous 标记
- 每个 intent 的理由列表

它当前按 **单个 fixed segment 独立打分**，不做跨段平滑。

### 5.3 observedActionTypes

`observedActionTypes` 来自 [ActionSegmentView.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/ActionSegmentView.java) 的 `collectObservedActionTypes()`：

- 它直接从 segment entry 的 `type` 收集
- 不依赖额外 watcher 状态
- 只要某个动作在 fixed segment entry 中出现，它就会进入这个集合

因此当前：

- `DEATH` splitter 实际消费的是 `observedActionTypes`
- 结构 / 地点边界动作虽然已经能进入 `observedActionTypes`
- 但 episode 还没有对应 splitter

## 6. Episode 分割现状

### 6.1 总控

[EpisodeTimelineStore.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTimelineStore.java) 是 episode 的正式入口。

它当前负责：

- 接收新 fixed segment
- 持有最新 `EpisodeTimeline`
- 在需要时构造 `EpisodeSynthesisMaterial`

真正的分割调度在 [EpisodeTracker.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTracker.java)。

当前 tracker 还会在第一条 fixed segment 进入时自动写入一个 bootstrap split：

- `splitSegmentIndex = 0`
- `reason = null`
- 只作为左边界锚点使用
- 不属于任何 splitter，也不进入 tuning 配置

### 6.2 Timeline 结构

[EpisodeTimeline.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTimeline.java) 当前只维护四类正式材料：

- `segments`
- `regions`
- `pendingMarks`
- `splitPoints`

当前没有单独持久化的 mark 位置索引层，也没有 studio app 里的专用页面。

### 6.3 pending mark 模型

[EpisodePendingMark.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodePendingMark.java) 当前包含：

- `detectedAtSegmentIndex`
- `splitSegmentIndex`
- `splitTimestamp`
- `reason`
- `confirmAtSegmentIndex`
- `ownerSplitterId`
- `highPriority`
- `coverBeforeSegments`
- `coverAfterSegments`

当前实现遵守这条规则：

- pending mark 的锚点和最终 split point 完全一致
- 先 detect，再 delay confirm
- confirm 后会清理覆盖范围内的其他 pending marks

### 6.4 splitters

当前注册到 `EpisodeTracker` 的 splitter 只有三个：

- [IntentShiftSplitter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/IntentShiftSplitter.java)
- [DeathSplitter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/DeathSplitter.java)
- [FallbackQuietSplitter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/FallbackQuietSplitter.java)

#### IntentShiftSplitter

消费：

- 当前 latest region
- latest fixed segment 的 `intentSignals`

检测逻辑：

- 当前 region 至少要有 `2` 个 segment
- latest segment 要有有效 intent signal
- 新 profile 如果脱离当前 region 的 active intents，则生成：
  - `INTENT_HARD_SHIFT`
  - 或 `INTENT_SOFT_SHIFT`

确认逻辑：

- 回看 `splitSegmentIndex .. latestSegmentIndex` 的 aggregate scores
- 再次判断 shift 是否成立
- hard shift 的 pending 如果最后只剩 soft，不会 confirm

#### DeathSplitter

消费：

- latest segment 的 `observedActionTypes`

检测逻辑：

- 只要 segment 中出现 `ActionType.DEATH`
- 就挂一个 `splitSegmentIndex = latestSegmentIndex + 1` 的 pending mark

确认逻辑：

- 当前基本不做额外验证
- 到期后直接产出 split point

#### FallbackQuietSplitter

它不是信号型 splitter，而是状态型 splitter。

它消费：

- 本轮其他 splitter 有没有产出 mark
- 当前 pending queue 是否为空
- 距离上一次成功加入 pending queue 已经过了多少段

当前默认 quiet window 在 [EpisodeRuntimeSettings.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeRuntimeSettings.java) 里是 `8`

### 6.5 confirm / cleanup / region rebuild

`EpisodeTracker` 当前的工作顺序是：

1. 先基于旧 timeline + 最新 segment 做 detect
2. 再把新 segment 放进 `segments`
3. 把 detect 到的 pending marks 加入队列
4. 先做一次 high-priority pending 清理
5. 重建 regions
6. 收集已到 confirm 时机的 pending
7. 调各自 splitter 的 `confirm(...)`
8. split point 入列
9. 清理覆盖范围内的 pending
10. 再次重建 regions

### 6.6 当前 split reason 与默认参数

当前 formal split reason 只有：

- `DEATH`
- `INTENT_HARD_SHIFT`
- `INTENT_SOFT_SHIFT`
- `FALLBACK_QUIET_GAP`

默认运行时参数来自 [EpisodeRuntimeSettings.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeRuntimeSettings.java)：

- `DEATH`: `highPriority=true`, `delay=1`, `coverBefore=1`, `coverAfter=1`
- 其他三类：`highPriority=false`, `delay=1`, `coverBefore=1`, `coverAfter=1`

## 7. Region 现状

[EpisodeRegion.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeRegion.java) 当前保存：

- `startSegmentIndex / endSegmentIndex`
- `startTimestamp / endTimestamp`
- `aggregateIntentScores`
- `primaryIntent / secondaryIntent`

region 的构造统一走 [EpisodeIntentMath.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/EpisodeIntentMath.java)：

- secondary intent 阈值当前是 `0.72`
- 没有 split 时，会有一个覆盖全部 segments 的 tail region
- 没有“登录时默认插入一个 split point”的现实现状

## 8. STM 融合与 Episode synthesis material

### 8.1 目前是否已经能拼 material

能。

[EpisodeTimelineStore.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTimelineStore.java) 的 `buildSynthesisMaterial(...)` 已经可用，但它依赖外部显式传入 `EpisodeSynthesisTriggerSignal`。

### 8.2 当前融合方式

传入 signal 后，store 会：

1. 先从 timeline 里切出 region 对应的 base fixed segments
2. 再从 `STMPool.snapshotBefore(signal.stmSliceExclusiveTimestamp())` 取时间切片
3. 按时间戳把：
   - `TaggedContextEntry`
   - `InteractionTurn`
   分配到各自 segment bucket
4. 生成新的 `FixedActionSegmentContext`
5. 返回 [EpisodeSynthesisMaterial.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeSynthesisMaterial.java)

### 8.3 当前 context 融合的关键细节

`STMPool` 当前保存两类材料：

- 全量 `memoryEligible` context entries
- `InteractionTurn`

其中 [LLMInteractionTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/task/LLMInteractionTask.java) 当前会：

- 在构造 prompt 前，把这轮 `AgentContext` 的所有 `memoryEligible` 条目写入 `STMPool`
- 在生成回复后，把带有 `visibleContexts` 的 `InteractionTurn` 写入 `STMPool`

这意味着 synthesis 融合时：

- 普通 context 走的是 `memoryEligible` 过滤后的全量上下文
- interaction 走的是带 `visibleContexts` 绑定的回合对象

## 9. Episode prompt 合成现状

### 9.1 入口

prompt draft 生成入口在 [EpisodeSynthesisPromptFormatter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeSynthesisPromptFormatter.java)。

当前只生成 **一个 region 级 prompt draft 字符串**，没有 segment draft 对象层。

### 9.2 当前段内叙事形态

当前 formatter 已经实现两种 segment 形态：

#### 没有 interaction turn

输出顺序是：

1. `周围 context`
2. `玩家动作列表`

这里使用的是 `segment.getContext().getEffectiveVisibleContextEntries()`。

#### 有 interaction turn

输出顺序是时间流：

- 玩家动作
- interaction
- interaction 绑定的 visible context
- 玩家动作
- interaction

交互描述当前形态是：

- 有 `userPrompt` 时：`玩家发出指令/事件「...」，随后剑说/剑心声...`
- 没有 `userPrompt` 时：`触发源 xxx`

### 9.3 interaction 与 context 的绑定

当前绑定优先级是：

1. `InteractionTurn` 自己携带的 `visibleContexts`
2. 如果 turn 自己没有，则回退到 segment 的 `effectiveVisibleContextEntries`

### 9.4 当前 prompt draft 的一个重要现状

当前 formatter **不会过滤 `ACQUIRE_ITEM`**。

也就是说：

- recent actions prompt 里看不到 `ACQUIRE_ITEM`
- 但 episode synthesis prompt draft 里可能会出现 `ACQUIRE_ITEM`

这是当前实现状态，不是文档笔误。

## 10. Studio / workspace 现状

### 10.1 已接上的 workspace 文件

当前 episode 相关的 workspace 文件有：

- [StudioEpisodeTuningFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioEpisodeTuningFile.java)
  - `episode_tuning.json`
- [StudioRuntimeEpisodeTimelineFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioRuntimeEpisodeTimelineFile.java)
  - `studio_runtime_episode_timeline.json`

action 侧 authored tuning 仍在：

- [StudioActionTimelineTuningFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioActionTimelineTuningFile.java)
  - `action_timeline_tuning.json`

### 10.2 当前 Studio runtime episode 文件包含什么

`studio_runtime_episode_timeline.json` 当前会写出：

- splitter tuning 快照
- timeline 中的 segments / pending marks / split points / regions
- 相对上一份快照的 diff
- 最近一次 synthesis preview 的 prompt draft

### 10.3 当前 Studio app 的状态

当前 `tools/talking-weapon-studio/src` 里没有 episode 页面实现。

也就是说：

- runtime json 已经在 workspace 落盘
- Electron Studio 里目前没有对应 UI 页面

## 11. 当前缺口与偏移

这部分是当前最容易误判的地方。

### 11.1 自动 episode synthesis trigger 还没接上

[EpisodeTimelineStore.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTimelineStore.java) 的 `peekEpisodeSynthesisTriggerSignal()` 现在仍直接返回空。

因此当前并不会因为“region 闭合”而自动发起后台 synthesis。

但当前为了测试 prompt draft：

- 已新增 `peekLatestClosedRegionDebugSignal()`
- 它会读取最近一个已闭合的 region
- 不读取当前未封口的 tail region

### 11.2 内部 trigger 只是骨架

- [EpisodeSynthesisTrigger.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/trigger/internal/EpisodeSynthesisTrigger.java)
- [IInternalAgentTrigger.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/trigger/internal/IInternalAgentTrigger.java)

这条线已经定义了，但没有看到实际调用方。

### 11.3 EpisodeSynthesisTask 目前只做到 preview

[EpisodeSynthesisTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/task/EpisodeSynthesisTask.java) 当前会：

- 读取 synthesis material
- 写入 Studio runtime preview
- 打印 prompt draft 日志

除此之外，当前还新增了一个纯调试后台任务：

- [DebugEpisodeSynthesisTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/task/DebugEpisodeSynthesisTask.java)
- 右键成功触发前台交互后，会额外提交一次后台调试任务
- 它只读取最近一个已闭合 region，构造 `EpisodeSynthesisMaterial`，生成 region prompt draft，并写出独立日志
- 它不会写 Studio runtime preview，也不会提交 LTM

但还没有：

- 真正调用 LLM 生成 LTM
- commit / discard STM slice
- 记忆价值判断

### 11.4 地点 / 结构边界动作已采集，但 episode 还没消费

当前 action 层已经有：

- `ENTER_REGISTERED_LOCATION`
- `LEAVE_REGISTERED_LOCATION`
- `ENTER_MAJOR_STRUCTURE`
- `EXIT_MAJOR_STRUCTURE`

但 splitter 侧没有任何对应消费者。

### 11.5 Action runtime settings 里还有一组旧 episode 长度参数未接入

[ActionTimelineRuntimeSettings.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/ActionTimelineRuntimeSettings.java) 里还有：

- `episodeLengthCapSegments`
- `episodeLengthCapLookbackSegments`

当前代码没有任何地方读取它们。

也就是说，当前 episode v2 实际跑的是：

- `FallbackQuietSplitter` 的 `fallbackQuietWindowSegments`

而不是旧设计稿里那套 action 侧长度兜底参数。

## 12. 推荐阅读顺序

如果要继续重构，建议按下面顺序看代码：

1. [TickEventWatcher.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/trigger/TickEventWatcher.java)
2. [AgentMemory.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/AgentMemory.java)
3. [ActionArchive.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/ActionArchive.java)
4. [FixedActionSegmentBuffer.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/FixedActionSegmentBuffer.java)
5. [FixedSegmentSplitterSignals.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/playeraction/fixed_segment/FixedSegmentSplitterSignals.java)
6. [EpisodeTimelineStore.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTimelineStore.java)
7. [EpisodeTracker.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTracker.java)
8. `episode/splitter/*`
9. [EpisodeSynthesisPromptFormatter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeSynthesisPromptFormatter.java)
10. [StudioRuntimeEpisodeTimelineFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioRuntimeEpisodeTimelineFile.java)
