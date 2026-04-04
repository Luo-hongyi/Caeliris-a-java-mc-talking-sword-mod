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
6. 旧 `EpisodeSynthesisMaterial` / `EpisodeSynthesisPromptFormatter` 预览链路已经删除
7. timeline 第一段进入时会自动补一个 bootstrap split at `segmentIndex = 0`
8. 旧的“右键触发手动 episode synthesis debug task”链路已经删除

当前 episode / reflection 之间仍未形成新正式闭环，现状是：

- `EpisodeTimelineStore.collectEpisodeSynthesisTriggerSignals()` 已能产出 ready signals，并在发出时将对应 region 推进为 `PENDING`
- 旧的 debug signal / preview task 链路都已经清除
- Studio workspace 已有 `studio_runtime_episode_timeline.json`，但 Electron Studio 里目前没有 episode 页面
- `EpisodeRegion` 已经带有 `regionId / gateDecision / lifecycleState`
- `EpisodeTracker` 现在会按 `regionId` 延续 region runtime state；replace-all 时若内存里没有旧 region，会回退读取 `studio_runtime_episode_timeline.json` 中已持久化的 region 状态
- 新的 reflection task 主线骨架已经落地，并已通过 internal trigger 接回 episode signal 提交

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

这些动作现在已经进入 `ActionArchive` / `FixedActionSegment`，也会进入 `observedActionTypes`。
当前它们已经被正式接入 episode splitter：

- `ENTER_REGISTERED_LOCATION`
- `LEAVE_REGISTERED_LOCATION`
- `ENTER_MAJOR_STRUCTURE`
- `EXIT_MAJOR_STRUCTURE`

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
- `taggedEntries / visible contexts` 当前仍按 merged 优先使用
- `extras` 当前用于 supplementary/base environment info，采用 `default extras + merged extras overlay` 的方式读取，避免 merged STM 把默认环境信息抹掉
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
- 对外提供 `collectEpisodeSynthesisTriggerSignals()` 这个正式多 signal 输出接口

真正的分割调度在 [EpisodeTracker.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTracker.java)。

当前 tracker 还会在第一条 fixed segment 进入时自动写入一个 bootstrap split：

- `splitTimelineIndex = 0`
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

- `detectedAtSegmentId`
- `splitLeftSegmentId`
- `splitRightSegmentId`
- `splitTimestamp`
- `reason`
- `confirmAtSegmentId`
- `ownerSplitterId`
- `highPriority`
- `coverBeforeSegments`
- `coverAfterSegments`

当前实现遵守这条规则：

- pending mark 的锚点和最终 split point 完全一致
- 边界锚点统一通过 `splitAnchorSegmentId()` 表达
- 先 detect，再 delay confirm
- confirm 后会清理覆盖范围内的其他 pending marks
- `delay` 和 `cover` 都按绝对 `segmentId` 距离计算，而不是按 active timeline 的当前位置计算

### 6.4 splitters

当前注册到 `EpisodeTracker` 的 splitter 有七个：

- [IntentShiftSplitter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/IntentShiftSplitter.java)
- [DeathSplitter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/DeathSplitter.java)
- [ObservedActionTypeSplitter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/ObservedActionTypeSplitter.java)
  - `ENTER_REGISTERED_LOCATION`
  - `LEAVE_REGISTERED_LOCATION`
  - `ENTER_MAJOR_STRUCTURE`
  - `EXIT_MAJOR_STRUCTURE`
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

- 回看 `splitRightSegmentId .. latestSegmentId` 的 aggregate scores
- 再次判断 shift 是否成立
- hard shift 的 pending 如果最后只剩 soft，不会 confirm

#### DeathSplitter

消费：

- latest segment 的 `observedActionTypes`

检测逻辑：

- 只要 segment 中出现 `ActionType.DEATH`
- 就挂一个“边界在当前 segment 之后”的 pending mark
  - `splitLeftSegmentId = latestSegmentId`
  - `splitRightSegmentId = UNASSIGNED`

确认逻辑：

- 当前基本不做额外验证
- 等边界右侧 segment materialize 之后，到期直接产出 split point

#### FallbackQuietSplitter

它不是信号型 splitter，而是状态型 splitter。

它消费：

- 本轮其他 splitter 有没有产出 mark
- 当前 pending queue 是否为空
- 距离上一次成功加入 pending queue 已过去多少个绝对 `segmentId`

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

补充说明：

- `EpisodeTracker` 当前正式状态已经不再持有 `timeline index` 作为业务语义
- region rebuild 现在按 split point 的 `splitRightSegmentId` 重建边界
- 运行时 list index 仍可作为局部算法实现细节存在，但不再持久化，也不再跨模块流转

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
- `regionId`
- `gateDecision`
- `lifecycleState`

region 的构造统一走 [EpisodeIntentMath.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/splitter/EpisodeIntentMath.java)：

- secondary intent 阈值当前是 `0.72`
- 没有 split 时，会有一个覆盖全部 segments 的 tail region
- 没有“登录时默认插入一个 split point”的现实现状

当前实际 runtime state 语义是：

- tail region 会被强制保持为 `UNSET + ACTIVE`
- closed region 当前默认是 `UNSET + HOLDING`
- `EpisodeTracker.rebuildRegions()` 会按 `regionId` 复用已有 region 的 runtime state
- 如果发生 replace-all 重建：
  - 优先复用内存里已有的 region state
  - 若当前内存为空，则回退读取 `studio_runtime_episode_timeline.json` 中持久化的 region state
- 读取持久化 region state 时，当前已经会做归一化：
  - tail region 强制回到 `UNSET + ACTIVE`
  - closed region 的 `PENDING -> HOLDING`
  - closed region 不允许继承旧 tail 的 `ACTIVE`

也就是说，region runtime state 已经开始进入正式实现，但 gate 判定与 holdback 放行仍未接回。

## 8. 旧 Episode synthesis material 链路

### 8.1 当前状态

旧 `EpisodeSynthesisMaterial` 链路已经删除。

当前不再保留：

- `buildSynthesisMaterial(...)`
- `EpisodeSynthesisMaterial`
- `EpisodeSynthesisPromptFormatter`
- Studio runtime 中的 synthesis preview 持久化字段

### 8.2 当前融合方式

旧融合实现已经删除，不再作为当前代码现状描述。

后续会改由新的：

- `ReflectionRawMaterial`
- `ReflectionMaterial`
- `ReflectionMaterialExporter`
- `ReflectionMaterialMerger`

主线重建。

### 8.3 当前 context 融合的关键细节

`STMPool` 当前保存两类材料：

- 全量 `memoryEligible` context entries
- `InteractionTurn`

其中 [LLMInteractionTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/action/task/LLMInteractionTask.java) 当前会：

- 在构造 prompt 前，把这轮 `AgentContext` 的所有 `memoryEligible` 条目写入 `STMPool`
- 在生成回复后，把带有 `visibleContexts` 的 `InteractionTurn` 写入 `STMPool`
- 不再处理高频 `favorability_update`；好感度更新当前只保留 reflection 侧模块语义

这意味着 synthesis 融合时：

- 普通 context 走的是 `memoryEligible` 过滤后的全量上下文
- interaction 走的是带 `visibleContexts` 绑定的回合对象

## 9. 旧 Episode prompt 合成现状

### 9.1 入口

旧 prompt draft 生成入口已经删除。

后续会改由新的 reflection prompt 组装链路替代，不再以 `EpisodeSynthesisPromptFormatter` 作为当前实现。

### 9.2 当前段内叙事形态

这一整段旧 formatter 逻辑已不再代表当前实现，只保留作历史说明。

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

当前代码里，它仍然主要是 runtime 快照 / 调试文件，但已经开始承接一部分正式 episode runtime state 语义：

- `regions` 已经持久化 `regionId / gateDecision / lifecycleState`
- `EpisodeTimelineStore.replaceFixedSegments(...)` 在 replace-all 重建时，会把这里的 persisted regions 当作 runtime state seed 读回
- 读回时已经执行 region 级归一化：
  - tail region 强制为 `UNSET + ACTIVE`
  - closed region `PENDING -> HOLDING`
  - closed region 不继承 `ACTIVE`

但已经冻结的后续方向是：

- 不再另起第二份 episode state 真源文件
- `studio_runtime_episode_timeline.json` 直接升级为 episode runtime state 的唯一真源
- Studio 直接读取这份真源
- 其中 `timeline` 主体字段成为正式状态
- `generatedAt` / `lastUpdate diff` 一类字段只保留为辅助信息，不参与正式状态判定

当前 `regions` 已经直接带上正式状态字段：

- `regionId`
- `gateDecision`
  - `UNSET / PASS / REJECT`
- `lifecycleState`
  - `ACTIVE / HOLDING / PENDING / RESOLVED`

当前在 persisted region 读回时已经执行：

- `PENDING -> HOLDING`

因为重启后旧的后台 reflection task 已经不存在。

### 10.3 当前 Studio app 的状态

当前 `tools/talking-weapon-studio/src` 里没有 episode 页面实现。

也就是说：

- runtime json 已经在 workspace 落盘
- Electron Studio 里目前没有对应 UI 页面

## 11. 当前缺口与偏移

这部分是当前最容易误判的地方。

### 11.1 自动 episode synthesis trigger 还没接上

[EpisodeTimelineStore.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EpisodeTimelineStore.java) 的 `collectEpisodeSynthesisTriggerSignals()` 现在已经会产出正式 ready signals。

它当前只会放行：

- `gateDecision = PASS`
- `lifecycleState = HOLDING`
- 已越过 holdback 窗口

signal 发出后，对应 region 会立即推进到 `PENDING`，避免重复发出。

因此当前已经会因为“region 满足 ready 条件”而自动提交后台 synthesis task。

旧的 debug signal / preview task 已经全部删除；当前使用的是新的 `EpisodeSynthesisInternalTrigger`。

不过，region runtime state 这一层已经有一部分真实实现：

- `EpisodeRegion` 已经携带 `regionId / gateDecision / lifecycleState`
- closed region 当前构造为 `UNSET + HOLDING`
- tail region 当前强制保持 `UNSET + ACTIVE`
- `EpisodeTracker` 重建 regions 时，会按 `regionId` 复用已有 runtime state
- replace-all 重建时，如果当前内存为空，会从 `studio_runtime_episode_timeline.json` 回读 persisted region state
- persisted region 回读时，当前已经执行：
  - `PENDING -> HOLDING`
  - tail 强制 `ACTIVE`
  - closed region 不继承 `ACTIVE`
- `EpisodeSynthesisTriggerSignal` 已经带有 `regionId`
- `EpisodeTimelineStore` 已经补入 owner-side 回调骨架：
  - `markEpisodeSynthesisResolved(long regionId)`
  - `rollbackEpisodeSynthesisToHolding(long regionId)`

按当前已冻结设计，后续这里不应只产出一个 signal，而应：

- 按 closed region 输出多个 signal
- 当前默认 holdback 数量是 `2`
- ready signal 数量约等于“已通过 gate 且索引早于 `regions.size() - holdback` 的 region 数量”
- 默认要额外保留最近一部分 region / STM 不立即推出，以维持上下文连贯性
- 当前默认语义是：至少保留最后两个 region 窗口不直接发 signal
- 再交给对应 internal trigger 逐个提交后台任务

这条 holdback 规则现在已经进入 `EpisodeRuntimeSettings / episode_tuning.json`，但还没有接到 state screen UI。

同时，当前已经冻结的 region gate 状态机是：

- `gateDecision`
  - `PASS`
  - `REJECT`
- `lifecycleState`
  - `HOLDING`
  - `PENDING`
  - `RESOLVED`

目标流程是：

1. region 一旦闭合，立刻做 gate 判定
2. gate 结果缓存到 region 自身
3. region 先进入 `HOLDING`
4. 越过 holdback 后：
   - `PASS + HOLDING` -> 发 signal -> `PENDING`
   - `REJECT + HOLDING` -> owner 直接清理 / 截断 -> `RESOLVED`
5. `PENDING` task 成功后 -> `RESOLVED`
6. `PENDING` task 失败、取消或重启后 -> 回滚到 `HOLDING`

并且最新冻结设计再补两点：

- gate 会改成独立结构，不把具体判定逻辑硬写死在 `EpisodeTimelineStore` 里
- gate 的输入必须同时包含：
  - region 内的 fixed segments 基础信息
  - region 时间窗内的 `STMPool` 记录

也就是说，后续 episode synthesis gate 不是纯 timeline 内部逻辑，而是跨 `episode + STM` 的判定结构。

reject 路径也不只是“写一个 `REJECT + RESOLVED`”：

- owner 还要同步抛出该 region 的 episode segments
- 还要同步截断同时间窗内的 `STMPool`
- 当前第一阶段已经正式接回 reject cleanup：
  - closed region 会先经过独立 `EpisodeSynthesisGate`
  - 若 region 时间窗内有 STM 记录，则直接放行
  - 若 STM slice 为空，则按可调 reject 概率判定
  - `REJECT` 当前会立即回到 owner-side cleanup，统一抛出 segment + 局部 STM
- consumed cleanup 目前还只停在 API 已提供，等待后续 synthesis task 主链接起后再正式调用

这意味着：

- 不再保留 `UNPROCESSED` 这一层
- gate fail 不会单独生成 reflection task
- 真正需要保留的是“已判定但暂不放行”的 `HOLDING`

### 11.2 当前 reflection 接线状态

当前状态需要拆成两层看：

- 新 reflection 主线骨架已经存在：
  - `AbstractReflectionTask`
- `EpisodeSynthesisReflectionTask`
- `ReflectionMaterialExporter / Merger`
- `ReflectionStateSnapshotLoader`
- `ReflectionPersonaLoader`
- 固定 modules：
  - `memory_synthesis`
  - `player_profile_update`
  - `recap_update`
  - `favorability_update`
  - `emotion_update`
- 当前这五个就是 reflection 包里全部现有 modules；`EpisodeSynthesisReflectionTask` 现阶段按“全量总任务”定位，直接装载完整模块集合，而不是只挑其中一部分。
- 当前骨架已经能完成：
  - material export
  - raw -> merged material
  - state snapshot load
  - relevant persona load
  - prompt draft 生成
  - module instruction prompt 拼装
  - episode ready signal 的后台 task 提交
  - 单次 LLM 调用
  - module output parse / validate 后的正式 commit
  - episode / STM 的消费与截断联动
- 其中 `recap_update` 现在已经写回 `AgentState.recap`，语义上作为低频阶段性前情提要，而不是即时状态。

也就是说，当前已经有正式的 episode -> reflection 后台执行闭环；剩余问题主要落在输出质量和后续重试 / 收紧策略上。

### 11.3 当前 reflection 后台队列状态

当前没有“reflection 专属后台队列”。

实际运行时结构是：

- `AgentRuntime` 只有一条共享后台队列 `bgQueue`
- 类型是 `PriorityBlockingQueue<AgentTask>`
- 由单独的 `bgWorker` 串行消费
- `AbstractReflectionTask` 作为 `AgentTask` 子类，本应进入这条队列
- 当前 reflection task 的默认后台优先级固定为 `40`

但当前代码里还没有任何地方调用：

- 对应 internal trigger 去逐个调用 `AgentRuntime.submitBackground(new EpisodeSynthesisReflectionTask(signal))`

所以准确地说：

- reflection 的后台执行容器已经存在
- reflection 的 task 骨架也已经存在
- 但 reflection 还没有真正入队运行

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
9. [StudioRuntimeEpisodeTimelineFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioRuntimeEpisodeTimelineFile.java)
