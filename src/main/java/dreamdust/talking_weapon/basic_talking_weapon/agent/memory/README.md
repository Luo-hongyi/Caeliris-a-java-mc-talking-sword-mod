# 记忆系统说明

## 当前状态

另见：

- [episode/EPISODE_CURRENT_STATE.md](episode/EPISODE_CURRENT_STATE.md) - episode 当前实际实现总览；从 action 采集、action archive、splitter signals、split、region 到 prompt synthesis 的最新扫描结果
- [episode/REFLECTION_MAINLINE_REFACTOR_PLAN.md](episode/REFLECTION_MAINLINE_REFACTOR_PLAN.md) - 当前 reflection 主线重构计划；记录 episode / interaction trace / material exporter 边界、gate、模块化输出与后续实现顺序
- [episode/REFLECTION_CHAIN_ISSUES.md](episode/REFLECTION_CHAIN_ISSUES.md) - 当前 reflection 主线从原材料到 commit / cleanup 的问题清单与修复顺序
- [SEGMENT_COORDINATE_REFACTOR_PLAN.md](SEGMENT_COORDINATE_REFACTOR_PLAN.md) - fixed segment 持久化坐标重构讨论；解决中间 region 物理删除、signal 稳定引用和 reflection task 入队后坐标漂移问题
- [PERFORMANCE_HOTSPOTS.md](PERFORMANCE_HOTSPOTS.md) - 当前 memory / studio runtime 持久化热点、已完成优化与后续性能债记录
- [RECALL_OVERVIEW.md](RECALL_OVERVIEW.md) - 当前 recall 子系统的设计与实现总览
- [RECALL_STRATEGIES.md](RECALL_STRATEGIES.md) - 独立记录唯一统一记忆模型，以及 `SEED_FIRST` / `FULL_VISIBILITY` / `HYBRID` 三种参数预设
- [RECALL_TUNING_GUIDE.md](RECALL_TUNING_GUIDE.md) - 按当前代码实现整理的调参指南、公式与阈值
- [MEMORY_RECALL_NEXT_STEPS.md](MEMORY_RECALL_NEXT_STEPS.md) - 当前记忆重构讨论的明日清单与续做顺序
- [playeraction/PLAYER_INTENTS.md](playeraction/PLAYER_INTENTS.md) - LTM 生成前的玩家意图层设计草案，用于 episode 分割与长期记忆主题约束
- [episode/splitter/EPISODE_SPLIT_DESIGN.md](episode/splitter/EPISODE_SPLIT_DESIGN.md) - episode split 的历史设计稿；若与当前代码不一致，请以 `episode/EPISODE_CURRENT_STATE.md` 为准
- [episode/EPISODE_MARK_REASON_RULES.md](episode/EPISODE_MARK_REASON_RULES.md) - 按 reason 拆开的 episode mark / confirm / cover 规则表；与当前实现的差异请结合 `episode/EPISODE_CURRENT_STATE.md` 一起看
- [playeraction/ACTION_SIGNAL_DESIGN.md](playeraction/ACTION_SIGNAL_DESIGN.md) - player intent / action signal 的历史设计稿；当前动作链路请以 `episode/EPISODE_CURRENT_STATE.md` 为准
- [playeraction/LLM_FACING_ACTION_ARCHIVE.md](playeraction/LLM_FACING_ACTION_ARCHIVE.md) - 面向 prompt / LTM 生成的动作档案整理层设计草案

截至 `2026-03-25`，`agent.memory` 子系统的状态可以概括为：

- `MovingActionBuffer`、`ActionArchive`、`STMPool`、`AgentMemory`、`LTMEntry`、`LTMStorage` 已经构成可运行的短期记忆 + 长期记忆存储骨架。
- `ActionArchive` 之上已经接入第一版 `PlayerIntentScorer`：它会对固定时间窗压缩结果做规则打分，输出每段的多意图分数、主导意图和理由，供 episode / LTM 设计阶段调试。
- 全局植入记忆 (`IMPLANTED`) 的加载、保存和运行时热重载链路可用。
- 长期记忆召回 (`LTMRecallEngine`) 已切换到最新方案：先由 `visibleContexts` 注入跨轮维护的 `tag_pool`，再从 `tag_pool` 派生首层 cue，对长期记忆做联想式召回。
- `LTMEntry` 已补入可持久化的正式游戏内时间坐标；持久层优先保存 `occurredDayIndex + occurredTimeOfDayTick`，运行时再重建内部绝对 `occurredGameTime` 用于衰退计算。
- `semantic_time_tag` 已作为独立时间语义层接入：它会参与记忆之间的联想，但不会混入普通 `detail_tags` 的 Prompt cue；对于 synthesized LTM，它现在只是可选增强层，不再作为默认时间文案或结构性必填字段。
- 当前仍未完成的关键环节，是和这套新 recall 架构一致的 **LTM 生成闭环**。
- 新 reflection 主线骨架已经存在：`AbstractReflectionTask`、`EpisodeSynthesisReflectionTask`、`ReflectionMaterialExporter / Merger`、`ReflectionStateSnapshotLoader`、`ReflectionPersonaLoader` 都已落地；`EpisodeSynthesisReflectionTask` 当前固定装载 `memory_synthesis`、`player_profile_update`、`recap_update`、`favorability_update`、`emotion_update` 五个 modules，这五个也就是当前 reflection 包里全部现有的 modules。当前设计上，episode synthesis 先作为“全量总任务”承载这一整组输出。
- `emotion_update` 现在已经接上 `PromptRegistry.getEmotionTypeDescription()`，因此情绪类型说明文本可以热更新；但真正允许的情绪类型集合仍然是 `AgentState.PrimaryEmotion` 这组静态枚举，当前不能由玩家动态修改。
- action 侧旧的高频 `favorability_update` 链已经拆除：`LLMInteractionTask` 不再暴露也不再执行这类动作，`STMPool` 也不再维护对应账本；当前好感度更新只保留 reflection 侧模块语义。
- 这条 reflection 主线已经正式接回 episode gate，并进入 `AgentRuntime` 的共享后台队列执行闭环。
- 继续推进这条主线前，当前先插入一个更底层的前置议题：fixed segment 持久化坐标。原因是 episode / reflection 现在仍把当前数组位置当作正式引用坐标，这会阻塞中间 region 物理删除和 targeted cleanup。
- action runtime 持久化这一轮已经做过一次性能收尾：sealed fixed segment payload 已拆出高频 timeline snapshot 主路径，当前先不继续围绕写盘做大改，主线重新回到 segment / episode / reflection 的结构调整。
- fixed segment discard window 也已经从 `studio_runtime_action_timeline.json` 拔出，改为独立的 `studio_runtime_action_segment_discards.jsonl` sidecar log。
- 当前 fixed segment runtime 恢复链路已经拆成三部分：
  - sealed segments 从 `studio_runtime_action_segments.*` 恢复
  - discard windows 从 `studio_runtime_action_segment_discards.jsonl` 恢复
  - open segment / nextSegmentId / sealed context 仍由 `studio_runtime_action_timeline.json` 承载

## 当前核心目标

结合当前 `episode` / `reflection` / `segment coordinate` 三份主文档，接下来最核心的目标可以收敛为四条：

1. fixed segment 的正式稳定 identity 要彻底钉住

- `segmentId` 作为唯一正式 identity
- 由 `FixedActionSegmentBuffer` 在 segment 第一次 sealed 时发号
- sealed segment 与 `segmentId` 的绑定关系要能跨重启、跨 rebuild 延续

2. episode 侧要把“当前顺序位置”和“正式引用坐标”彻底分开

- `timelineIndex` 只服务 tracker 内部局部算法
- region / split / signal / material request / exporter 的正式引用，后续都要迁到稳定坐标或 region identity
- 这样才能真正支持中间 region 删除、targeted cleanup 和异步 task 稳定引用

3. episode owner-side 生命周期要继续收紧为正式主线

- gate 作为独立结构存在
- `EpisodeRegion` 继续承载 `regionId / gateDecision / lifecycleState`
- holdback、多 signal、reject cleanup、STM truncate 都由 owner-side 正式推进
- `MemorySourceMaterialCleanupPlan` 作为 reject / synthesis consumed 共用的 cleanup payload 已冻结

4. reflection 主线只在坐标稳定后再重新接回 episode

- `collectEpisodeSynthesisTriggerSignals()` 需要正式产出可提交的 signal
- internal trigger 只负责提交具体 task
- task 只负责 material / prompt / LLM / parse / validate / commit
- resolved / rollback 与 reject cleanup 仍由 owner-side 闭环处理

## 当前断点（2026-03-28）

这一轮工作结束后，当前实际断点可以收敛为下面两组。

### 已完成的收敛

- reflection 正式链路已经拔除了跨边界 `timelineIndex`
- `EpisodeSynthesisTriggerSignal -> ReflectionMaterialRequest -> exporter -> material` 现在只保留：
  - `regionId`
  - `segmentId`
  - boundary segment ids
  - region / STM 的时间窗
- `DefaultReflectionMaterialExporter` 已改成走 owner-side `snapshotSourceMaterial(...)`
- `STMPool` 已具备：
  - `snapshotBetween(...)`
  - `discardBetween(...)`
- owner-side 共用 cleanup payload 已冻结：
  - `MemorySourceMaterialCleanupPlan`
  - `MemorySourceMaterialWindow`
  - `MemorySourceMaterialCleanupReason`
- `AgentMemory.cleanupSourceMaterial(...)` 第一版 unified cleanup executor 已落地：
  - 吃统一 `MemorySourceMaterialCleanupPlan`
  - 解析 active segment window
  - 裁剪 raw action source
  - 登记 fixed segment discard window
  - 截断局部 STM
  - 回写 episode active view
- owner-side 便利入口已补入：
  - `cleanupEpisodeGateRejectedSourceMaterial(...)`
  - `cleanupEpisodeSynthesisConsumedSourceMaterial(...)`

### 当前最重要的未完成点

1. fixed segment 真源还没有正式 discard / tombstone 机制

- 当前即使 episode / STM 侧做了 cleanup
- 如果上游 raw action source 不同步处理
- 被删掉的 segment 仍可能在后续 rebuild 时重新长回来
- 当前新增的 discard sidecar log 只解决了“不要在高频 action timeline snapshot 中反复重写 discard state”
- 它还没有升级成真正的 tombstone / compaction policy

2. unified cleanup API 还没有正式接回 episode / reflection 调用面

- gate reject 已经接回 owner cleanup：
  - closed region 会先经过第一版 `EpisodeSynthesisGate`
  - 若 gate 判定 `REJECT`，当前会立刻走 owner-side cleanup
- synthesis consumed cleanup 目前仍只停在 API 已提供：
  - `cleanupEpisodeSynthesisConsumedSourceMaterial(...)`
  - 等 episode synthesis task 真正跑通后再正式接线

3. reflection 主线已经有骨架，但还没接成完整闭环

- `EpisodeSynthesisInternalTrigger` 已经会逐个消费 ready signals，并提交 `EpisodeSynthesisReflectionTask`
- `EpisodeSynthesisReflectionTask.executePreparedTask(...)` 已经接起第一版 task 主体：
  - 单次 LLM 调用
  - root JSON 提取
  - module parse / validate
  - task-owned commit
  - consumed cleanup + region resolved / rollback
- 当前剩余缺口主要是：
  - `recap_update` 已写回 `AgentState.recap`，语义上作为低频“前情提要”
  - 失败后的 LLM 重试链路还没接
  - synthesized LTM 的 tag / 时间锚点质量还只是第一版

### 下一阶段推荐顺序

1. 先给 fixed segment 真源补 discard / tombstone 机制
- 让 segment “被抛出后不会复活”

2. 再把 unified cleanup API 接回 episode / reflection 调用面
- 当前 reject 已接回
- 下一步只剩 consumed cleanup 在合成链路落地后正式接回

3. 接回 episode gate / holdback / ready signal
- 让 `EpisodeTimelineStore.collectEpisodeSynthesisTriggerSignals()` 真正产出 ready signals

4. 补齐单次 reflection task 的完整 LLM 闭环
- instruction
- root output contract
- parse / validate / cross-validate
- task-owned commit
- owner-side consumed cleanup


## 记忆分层

### 1. MovingActionBuffer

- 记录 prompt 侧近期动作摘要。
- 维护 moving segment 与 prompt window 的压缩视图。
- 仅服务 prompt / 近期感知，不参与 episode 断点与最终 LTM 切片。

### 2. ActionArchive

- 记录 watcher 观察到的原始动作事件流。
- 不做 TTL 淘汰，不做同类压缩。
- 为 player intent、episode 分割和后续 LTM 生成提供连续行为时间线。
- 当前在其上方额外挂了一层**派生压缩视图**：
  - 先按固定时间窗切成时间段
  - 再在段内仅合并同类型且同目标的动作
- 当前也已经有一层**派生 intent 分析视图**：
  - 读取固定时间窗 compaction 结果
  - 对每个时间段独立计算 `GATHER / TRAVEL / EXPLORE / COMBAT / BUILD / CRAFT / SOCIAL`
  - 输出主导 intent、次强 intent 和理由
- 当前在 `right_click` fire 时会额外写出一份独立的 `ActionArchive` dump 日志：
  - 同时包含原始时间线
  - 固定时间窗 compaction 结果
  - 以及首版 intent 打分结果

### 2.1 Episode v2 runtime 观察

- 当前正式 episode 链路只维护：
  - `segments`
  - `pending marks`
  - `split points`
  - `regions`
- `pending mark` 与最终 `split point` 的位置保持一致。
- splitters 当前使用统一的 `mark -> delay confirm -> confirm 后清理周围 pending` 模型。
- 当前可在 Studio workspace 中查看：
  - `studio_runtime_episode_timeline.json`
  - `episode_tuning.json`
- 旧的 episode hotbar 调试提示已经不再作为运行时设计的一部分；若文档或旧讨论里仍提到它，应视为历史方案。

### 3. STMPool

- 聚合近期 `InteractionTurn` 与上下文条目。
- 为后续的记忆总结、人格清算、LTM 生成提供原始材料。
- 当前系统中，LTM 的“召回起点”来自 `LLMInteractionTask` 中已经进入 `visibleContexts` 的上下文，而不是直接从隐藏上下文或原始动作记录起跳。

### 4. LTMRecords

- `AgentMemory` 中持有的长期记忆集合。
- 包含两类来源：`IMPLANTED` 与 `SYNTHESIZED`。
- `IMPLANTED`：外部植入的人设 / 背景记忆。
- `SYNTHESIZED`：运行时从经验中沉淀出的长期记忆。
- 每条 `LTMEntry` 现在除了描述、focus 和 detail tags 以外，还会持有轻量的世界语义锚点，例如：发生时的游戏内时间描述、维度、群系和是否下雨。

## 长期记忆召回

当前的 `LTMRecallEngine` 采用的是“联想式召回”，而不是旧式的全量 deterministic top-k：

- 起点是当前 `visibleContexts`。
- 首层召回不再先抽少量 seed entries / seed tags，而是直接将当前 `visibleContexts` 中全部 detail tags 聚合为一个去重后的初始 cue 池，再由这些 cue 对长期记忆做统一评分。
- recall 内部当前维护的是“活跃 cue 输入批次”：第 0 层来自 `tag_pool` 中当前活跃的 detail tags，sandbox 阶段则来自表层记忆回灌出的 associative tags。
- 主召回候选会做 semantic match、focus 对齐和时间衰退加权；旧发酵记忆的续存则主要依赖发酵分数自身的半衰期衰减与本轮 cue 回灌。
- 时间衰退现在只根据游戏内时间推进，不再回退到现实时钟。
- 当前实现会把“衰退时钟”和“叙事时钟”分开：前者依赖单调的 `gameTime`，后者依赖长期保存的 `dayIndex + timeOfDayTick`。
- 不同 seed 可以共同支持同一条记忆，证据会在 memory layer 聚合。
- `native_generic` 可以参与内部检索，但不会直接进入 Prompt。
- `semantic_time_tag` 会加入记忆的 associative tag 视图，因此可以成为记忆到记忆的联想边；但它仍然保持为独立时间语义，不会被塞进普通 detail cue 列表。
- `wanderingMind` 当前不再表示“随机塞一条无关记忆”，而是采样温度：值越低越稳定地偏向高分候选，值越高越容易从次高分候选中抽到意外联想。
- 植入记忆 (`IMPLANTED`) 的 `ageFactor` 固定为 `1.0`，不参与任何时间衰退。
- 合成记忆的时间衰退半衰期现在由 `TalkingWeaponConfig.LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE` 控制，单位固定为游戏内天数。
- 当前 recall 要求 `SYNTHESIZED` 记忆和本轮运行时都带有有效的游戏内时间；若缺失会直接报错，不再回退到现实小时衰退。
- `nostalgia` 目前保留在 `AgentState` 中，但暂未接入 recall 公式。
- “植入记忆保底”现在由 `agent_state.json.state.implanted_memory_safety_net` 单独控制，不再和 `nostalgia` 绑定。
- 这个“植入记忆保底”不会重跑整套 recall；它只复用本轮已经产生的总 cue 池，遍历所有 `SourceType.IMPLANTED && occurredGameTime < 0` 的记忆，并仅按 cue 与记忆 associative tags 的语义命中强度做一次加权抽取，最终最多补入 1 条记忆；补入后的 selection 分数由 `TalkingWeaponConfig.LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE` 单独控制。
- 对最近刚显性回忆过的记忆，系统现在维护的是一张 `memoryId -> remainingCooldown` 的显性回忆抑制表，而不是固定长度 queue；同一条记忆不会因为后来又想起了别的记忆就提前失去抑制。
- `LTM_RECALL_RECENT_WINDOW` 现在表示“显性回忆抑制持续多少轮 recall”，不是“队列最多记多少条”。
- retained fermentation 和 conscious memories 现在是两个独立层：前者表示“还在发热”，后者表示“这一轮真的浮到表层”。
- `recent recall penalty` 现在落在独立的 conscious 选择层；越靠近最近一次召回，显性浮现抑制越强，冷却结束后恢复为 `1.0`；该抑制不直接作用于旧发酵层的续存。

## 当前缺口：LTM 生成

旧版 `ReflectionTask` 和 `ReflectionTrigger` 已经删除；当前 reflection 方向已经有新的 episode 驱动骨架，但它仍不是当前设计意义上的完整实现。原因包括：

- `EpisodeTimelineStore.collectEpisodeSynthesisTriggerSignals()` 已经能产出 `PASS + HOLDING` 且越过 holdback 的多 signal，并在发出时将 region 推进到 `PENDING`。
- `EpisodeSynthesisInternalTrigger` 已经会正式调用 `AgentRuntime.submitBackground(new EpisodeSynthesisReflectionTask(signal))`，所以 reflection task 已经开始进入共享后台队列。
- `EpisodeSynthesisReflectionTask.executePreparedTask(...)` 现在已经能完成单次 LLM 调用、root output 解析、module validate、task-owned commit，以及 consumed cleanup + region resolved。
- `recap_update` 现在已经写回 `AgentState.recap`；这里的语义不是“即时当前状态”，而是基于最近一段 episode 产出的阶段性前情提要。
- 它还没有和最新的 `specific` / `generic` 语义完全统一。
- 它还没有和当前的 `visibleContexts`、`associativeOnly`、`native_generic hidden` 策略形成闭环。
- 它还没有明确的新一代结算时机和筛选规则。
- 例如：什么时候从 STM 结算到 LTM。
- 例如：如何在全噪声情况下保底生成可用记忆。
- 例如：如何限制 detail tags 的质量，避免把脏标签直接写入长期记忆。
- 例如：如何与 favorability / persona 的低频更新合并或解耦。

因此，当前路线下最重要的未完成组件是：

- 新版 LTM 生成器
- 对应的触发 / 调度时机
- 与 persona / favorability 的低频结算协议
- 一层真正面向 LLM 的动作档案整理层：
  - 在 `ActionArchive` debug compaction 之上继续压缩
  - 按重要度过滤低价值动作 / 片段
  - 增加“这一段是否值得生成记忆”的 generation gate
  - 并与后续 importance 重构统一设计，而不是额外再造一套孤立规则

## 维护原则

- Prompt 是否可见，与 tag 是否参与内部检索，是两个不同层次的问题。
- `native_generic` 可以进入系统，但应被视为内部冗余语义层；真正喂给 LLM 的是 `native_specific` 和 `custom_*`。
- 记忆召回不应该回忆“所有相关记忆”，而应该按当前注意力与随机温度抽取少量联想结果。
