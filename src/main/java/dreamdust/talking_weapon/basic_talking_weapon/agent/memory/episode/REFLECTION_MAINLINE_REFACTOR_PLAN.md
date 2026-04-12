# Reflection Mainline Refactor Plan

> 本文是当前 `reflection` 主线重构的唯一设计基线。
> 后续代码实现、包结构、类命名、注释、README、设计说明文档，都必须按本文同步修改。
> 与本文冲突的旧注释、旧 README、旧设计口径、旧反思链路，直接删除或改写，不保留兼容层，不保留双轨并存。
>
> 截至 `2026-03-27`，本文不仅记录目标设计，也记录当前已经落地的骨架实现。
> 若文档中的“下一步”与代码现状冲突，以这里补充的“当前实现状态”小节为准，并继续同步修正文档。

## 当前冻结状态

截至 `2026-04-09`，正在进行中的 `reflection` 构建先临时冻结。

当前冻结原因不是 `reflection` 自身设计变动，而是底层 `context` 语义正在做一次正式重构：

- 给所有 `TaggedContextEntry` 增加正式 `source`
- 引入 `PLAYER / AGENT / ENVIRONMENT` 主体语义
- 清理旧的“context 中所有行动默认都属于玩家”这一前提

对应重构计划见：

- [TAGGED_CONTEXT_SOURCE_REFACTOR_PLAN.md](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/TAGGED_CONTEXT_SOURCE_REFACTOR_PLAN.md)

在这轮 source 重构完成之前，`reflection` 主线当前约定为：

- 暂不继续向前推进新的 prompt / module / task 业务构建
- 暂不继续扩展基于旧 context 语义的 reflection 细化实现
- 只允许做与 source 重构直接相关的同步调整或文档修正

原因很简单：

- `reflection` 现在大量消费 `TaggedContextEntry`
- 如果继续沿旧前提推进 prompt / material / cognition 设计，后面一定还要整体返工
- 因此先冻结上层，优先把底层主体语义立住，才是更干净的做法

截至 `2026-04-10`，这轮底层语义重构已经基本收口，当前可视为进入“恢复 reflection 主线构建”的阶段。

当前恢复点记录如下：

- `TaggedContextEntry.source` 已正式贯通到 reflection formatter
- `InteractionTurn.messages` 已正式切到 `InteractionMessage`
- `InteractionMessage` 当前采用：
  - `source = PLAYER / AGENT / ENVIRONMENT`
  - `kind = TEXT / INNER_MONOLOGUE / REACTION_CUE`
- reflection prompt preview 已能稳定显示：
  - tagged context 的 source
  - interaction message 的 `source + kind`

当前仍暂缓的点：

- interaction 的 `initiator / target` 还没有进入正式数据模型
- 这一点已经记入 source / interaction 计划文档，暂不阻塞 reflection 主线继续推进

因此从现在开始，reflection 链路可以重新继续向前推进；
但推进时应默认使用当前这版 `source + kind` 口径，而不是旧的 `userPrompt + outputs` 结构。


## 1. 重构范围

这次不是单独补一个 `episode synthesis` 功能，而是重构整条 `reflection` 主线。

当前确认的总目标：

- 将 `action` 和 `reflection` 拆成两条平行链路
- 将 `episode` 和 `STMPool` 明确成两条平行素材源
- 将旧的 `episode` 老链路、旧 `reflection` 残留、旧好感度链路直接清除
- 以新的 task-centric 结构重建 reflection

当前工程基线：

- `action`
  - 面向运行时即时行为
  - 主要信息来源是 collector / context
- `reflection`
  - 面向低频后台反思
  - 主要信息来源是 `episode + STMPool + agent state + relevant persona`

这里要明确一条语义：

- `episode` 主要描述玩家行为时间线
- `STMPool` 主要描述 Agent interaction trace
- 两者是平行素材源
- 只是当前 LTM 生成点挂在玩家行为侧，所以 `episode` 暂时作为默认主时间轴

## 2. 已冻结的设计决策

### 2.1 Episode 侧先做 gate

不是每个 closed region 都值得消耗一次 LLM 调用。

因此正式链路固定为：

1. region 一旦闭合，`EpisodeTimelineStore` 就立刻做 gate 判定
2. gate 结果要缓存到 region 自身，而不是每次重新判
3. 只有越过 holdback 窗口的 region 才真正进入后续 signal / cleanup 流程
4. reflection task 只处理已经通过 gate 且已被放行的材料

结论：

- “要不要记忆”是 `episode` 侧决策
- 不是 reflection task 运行时再偷偷做一遍判断

这里补一条边界修正：

- gate 本身应是一个独立结构
- 它不是 `EpisodeTimelineStore` 内部揉在一起的 if-else
- `EpisodeTimelineStore` 负责保存 gate 结果与推进 region state
- 但“如何判 PASS / REJECT”应由独立 `EpisodeSynthesisGate` 结构完成

### 2.1.1 Episode 应发出多个 signal

episode 不应对一整段历史只发一个总 signal。

当前固定方向是：

- episode 应按 closed region 发出多个 `EpisodeSynthesisTriggerSignal`
- 当前 region 模型下，signal 数量默认等于 `regions.size() - 2`
- 这个 holdback 数量后续应可调，而不是写死
- 默认要额外保留最近一部分 region / STM 不立即推出，以维持上下文连贯性
- 当前默认语义是：至少保留最后两个 region 窗口不直接发 signal
- 每个 signal 后续交给对应 internal trigger，逐个提交后台 task

这么做的原因是：

- 避免某个高优先级后台任务暂时阻塞时，把整批 reflection 素材都卡在一个总任务后面
- 避免某个记忆生成任务跑到一半失败时，整批 closed regions 都绑定在同一次提交里
- 让补提、重试、跳过都能在 region 粒度处理

当前这套多 signal + gateDecision/lifecycle/holdback 管理机制还没有正式实现，但这是已经冻结的方向。

并且这条可调 holdback 规则与 episode gate 一起实现，不提前在当前骨架里硬编码。

### 2.1.2 Region gate 状态机

为了避免重复发 signal，同时又允许 backlog 恢复、失败回滚和上下文 holdback，region 侧状态机固定为两层：

- `gateDecision`
  - `PASS`
  - `REJECT`
- `lifecycleState`
  - `HOLDING`
  - `PENDING`
  - `RESOLVED`

固定流程：

1. region 一旦闭合，立刻做 gate 判定
2. 判定结果写入 `gateDecision`
3. 初始生命周期不是 `PENDING`，而是先进入 `HOLDING`
4. 当 region 越过 holdback 窗口后：
   - `PASS + HOLDING` -> 发 signal -> `PENDING`
   - `REJECT + HOLDING` -> owner 直接清理 / 截断 -> `RESOLVED`
5. `PENDING` 的 reflection task 成功后 -> `RESOLVED`
6. `PENDING` 的 reflection task 失败、取消、或游戏重启后 -> 回滚到 `HOLDING`

这里明确不再使用：

- `UNPROCESSED`
- “闭合后等着以后再第一次做 gate”的模型

原因：

- region 闭合时就已经拿到了最稳定的 gate 判定时机
- 没必要让多个未判定 region 在队列里堆积
- 真正需要保留的是“已判定但暂不放行”的 `HOLDING` 状态

并且 gate 的正式输入不只包含 region 自身：

- 必须看到 region 内的 fixed segments 基础信息
- 必须看到同时间窗内的 `STMPool` 记录
- 也就是说，gate 不是纯 episode timeline 内部函数，而是一个跨 `episode + STM` 的判定结构

### 2.1.3 Gate fail 不走 reflection task

`gateDecision = REJECT` 的 region，不应专门生成 reflection task。

固定方向：

- gate fail 后由 owner 直接做清理 / 截断
- 这不是 LLM 任务
- 也不是 reflection 业务
- 不应占用后台 reflection 队列

这里再补充 reject cleanup 的固定语义：

- reject 不只是把 region 标成 `RESOLVED`
- owner 还需要同步抛出该 region 对应的 episode segments
- 同时还要截断该 region 对应时间窗内的 STM pool 记录
- 也就是说，reject cleanup 是 `episode + STM` 的双侧清理

### 2.1.4 Episode 持久化直接落在 `studio_runtime_episode_timeline.json`

当前正式方向固定为：

- 不再额外新建第二份 episode state 真源文件
- `studio_runtime_episode_timeline.json` 直接升级为 episode runtime state 的唯一真源
- Studio 直接消费这份真源文件

这么做的原因是：

- 避免 episode runtime state 出现两套真源
- 与项目中其他 workspace / runtime JSON 的持久化方式保持一致
- Studio 不需要再额外读取一份 projection 文件

这里要明确：

- 这不表示“什么调试字段都能变成正式状态”
- 而是表示该文件中的 `timeline` 主体字段升级为正式真源
- 其中附带的 diff / generatedAt 一类字段只能作为辅助信息，不能反向决定 episode 正式状态

当前实现状态：

- `studio_runtime_episode_timeline.json` 仍未成为完整 episode timeline 的反向加载真源
- 但 `regions` 已经开始承担 runtime state 恢复职责
- `EpisodeTimelineStore.replaceFixedSegments(...)` 在 replace-all 重建时，会优先复用内存中的 region state；若当前内存为空，则回退读取这个文件中持久化的 regions 作为 state seed

### 2.1.5 `EpisodeRegion` 新增正式状态字段

为了让 region 状态直接跟随 timeline 真源持久化，`EpisodeRegion` 应直接新增正式字段：

- `regionId`
  - region 的稳定身份
- `gateDecision`
  - `UNSET`
  - `PASS`
  - `REJECT`
- `lifecycleState`
  - `ACTIVE`
  - `HOLDING`
  - `PENDING`
  - `RESOLVED`

语义约束：

- `ACTIVE`
  - 表示当前 tail region，尚未闭合
  - 此时 `gateDecision` 应为 `UNSET`
- region 一旦闭合
  - 立刻做 gate
  - `gateDecision` 进入 `PASS / REJECT`
  - 生命周期先进入 `HOLDING`
- 越过 holdback 后
  - `PASS + HOLDING` -> `PENDING`
  - `REJECT + HOLDING` -> `RESOLVED`

当前实现状态：

- `EpisodeRegion` 已经实际新增 `regionId / gateDecision / lifecycleState`
- `EpisodeTracker.rebuildRegions()` 已经开始按 `regionId` 延续 runtime state
- tail region 当前会被强制写回 `UNSET + ACTIVE`
- closed region 当前默认是 `UNSET + HOLDING`
- 若 closed region 与旧 tail 共享同一个 `regionId`，重建时不会继承旧的 `ACTIVE`

### 2.1.6 重启恢复规则

因为 `studio_runtime_episode_timeline.json` 会作为 episode runtime state 真源，
所以加载时必须有归一化规则：

- `PENDING -> HOLDING`

原因：

- 游戏重启后，后台队列中的实际 reflection task 已经不存在
- 不能把“上次曾经发出过 signal”误当成“这次启动后仍然在执行”

当前实现状态：

- persisted region state 读回时，这条归一化已经开始落地
- 当前除了 `PENDING -> HOLDING` 外，也会保证 tail region 强制恢复为 `UNSET + ACTIVE`

### 2.1.7 Owner / Trigger / Task 边界

这一层职责固定如下：

- `EpisodeTimelineStore`
  - 是 region runtime state 的唯一 owner
  - 负责 gate 判定
  - 负责 holdback 放行
  - 负责 signal 产出时将 region 置为 `PENDING`
  - 负责 task 成功后的 `RESOLVED`
  - 负责 task 失败 / 取消 / 重启后的 `HOLDING` 回滚
- internal trigger
  - 只负责轮询 `collectEpisodeSynthesisTriggerSignals()`
  - 只负责把 signal 逐个提交成后台 reflection task
  - 不负责 gate，不负责 holdback，不负责 region 状态判断
- reflection task
  - 只负责 material / prompt / LLM / parse / validate / commit
  - 不直接改 episode region 状态
  - 完成后只通过 owner API 回报 `resolved` 或 `rollback`

当前实现状态：

- `EpisodeSynthesisTriggerSignal` 已经切到稳定字段：
  - `regionId`
  - `regionStartSegmentId / regionEndSegmentId`
  - boundary segment ids
  - `stmSliceStartTimestamp / stmSliceExclusiveTimestamp`
- `EpisodeTimelineStore` 已经补入 owner-side 回调骨架：
  - `markEpisodeSynthesisResolved(long regionId)`
  - `rollbackEpisodeSynthesisToHolding(long regionId)`
- `EpisodeTimelineStore.collectEpisodeSynthesisTriggerSignals()` 已接回第一版 ready signal 产出：
  - 只放行 `PASS + HOLDING` 且已越过 holdback 的 region
  - signal 发出后会立即将 region 推进为 `PENDING`
  - holdback 数量当前已进入 `EpisodeRuntimeSettings / episode_tuning.json`，默认值为 `2`
- `EpisodeSynthesisInternalTrigger` 已接回第一版后台提交：
  - 在 `TickEventWatcher` 的低频轮询里拉取 ready signals
  - 按顺序逐个调用 `AgentRuntime.submitBackground(new EpisodeSynthesisReflectionTask(signal))`
  - 若提交失败，会立即把对应 region 回滚到 `HOLDING`
- `EpisodeSynthesisReflectionTask` 当前已经把 `regionId` 写入 task context extras
- `EpisodeSynthesisGate` 独立骨架已落地
- `DefaultEpisodeSynthesisGate` 第一版已落地：
  - region 时间窗内只要存在 STM 记录则直接 `PASS`
  - 若 STM slice 为空，则按可调 reject 概率判定
  - 当前 reject 概率已挂到 state screen，并持久化进 `episode_tuning.json`
- `STMPool` 已补出 `snapshotBetween(...)`，为 gate 提供 region 内 STM 切片能力
- `STMPool` 已补出 `discardBetween(...)`，为后续 owner-side cleanup 提供 STM 截断入口
- `MemorySourceMaterialCleanupPlan` 已落地：
  - `forEpisodeGateReject(...)`
  - `forEpisodeSynthesisConsumed(...)`
  - 后续 reject cleanup 与 synthesis consumed cleanup 共用同一套 plan 负载
- reject cleanup 第一阶段已接回：
  - `EpisodeTimelineStore` 现在会先做 closed region gate 判定
  - `REJECT` 会立即回到 owner-side cleanup
  - `PASS` 当前会先缓存 gate 结果；越过 holdback 后由 `collectEpisodeSynthesisTriggerSignals()` 正式放行

这里要明确一个当前未完成点：

- consumed cleanup 目前还没有正式接线
- `cleanupEpisodeSynthesisConsumedSourceMaterial(...)` 已经提供，但要等合成 task 主链真正跑通后再接

### 2.1.8 当前断点：先暂停 reflection 主线推进

到目前为止，reflection 主线在“owner-side targeted cleanup / 中间 region 物理删除”这里遇到一个更底层的问题：

- 当前系统把“当前 timeline 数组位置”同时当成了：
  - tracker 内部计算坐标
  - region / split / signal / reflection material 的正式引用坐标
- 一旦允许中间 region 物理删除，后面的 index 就会整体漂移
- 这会直接冲击：
  - episode runtime state
  - 已发出的 signal
  - 已入队但尚未执行的 reflection task
  - material exporter 的定位逻辑

因此这里先明确打断点：

- `REFLECTION_MAINLINE_REFACTOR_PLAN.md` 继续保留为 reflection 主线设计基线
- 但在 fixed segment 持久化坐标问题明确之前，暂不继续推进：
  - 中间 region 物理删除
  - targeted cleanup 正式实现
  - signal / material request 从 index 坐标迁移前的最终接线
- 这一块改由单独文档展开讨论：
  - [SEGMENT_COORDINATE_REFACTOR_PLAN.md](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/SEGMENT_COORDINATE_REFACTOR_PLAN.md)

### 2.2 旧好感度链路直接拆除

旧设计里，好感度曾经绑在：

- 高频 `LLMInteractionTask`
- `STMPool` 内部旧账本

这两条链路都不符合当前主线，并且当前已经从运行时代码里拆除。

当前结论：

- action 侧不再暴露 `favorability_update`
- `LLMInteractionTask` 不再执行好感度写入
- `STMPool` 不再维护好感度账本
- 当前好感度更新职责只保留在 reflection 主线模块

### 2.3 高频情绪更新不纳入这一轮

这轮先不扩大高频情绪系统。

当前重点是：

- 统一 reflection 主线
- 重构素材抽取与任务执行边界
- 重建 episode synthesis 这条主任务

情绪高频更新以后单独研究。

补一条当前约束：

- `emotion_update` 模块已经接入可热更新的情绪类型说明文本
- 但真正允许的情绪类型集合仍然固定为 `AgentState.PrimaryEmotion`
- 也就是说，当前只能动态改“描述”，不能动态改“类型集合”

### 2.4 不保留兼容和过渡层

当前策略固定为：

- 直接删旧类
- 直接删旧字段
- 直接删旧 parser / 旧 trigger / 旧 response
- 直接改注释和 README

不做：

- 兼容别名
- 旧新双栈长期并存
- “先桥接一下以后再说”的中间层

### 2.5 `ReflectionStateSnapshot` 名字先保留

虽然这个名字看起来不只包含狭义 state，但在本项目里：

- agent 的 `state` 语义本来就包含 `role / background / emotion / relation summary / current situation` 等信息
- `persona` 在这里更接近 preference / detail-tag-bound traits

因此当前结论：

- `ReflectionStateSnapshot` 这个名字先保留
- 不在这一轮为命名洁癖引入额外重命名成本
- 但文档语义上要明确：这里的 `state` 是项目语义下的 agent state，不是狭义运行时变量集合

### 2.6 Persona 召回语义

`RelevantPersonaEntries` 的语义固定为：

- 它不是“角色设定总表”
- 它是与当前 reflection material 相关的 preference / persona 条目集合
- 主要通过 detail tag 做召回

并且：

- 这条召回逻辑应与 action 侧 persona 召回保持一致
- 后续要统一抽公共机制
- 本轮先把它作为明确 TODO 记住，不提前做统一抽象

## 3. 反思素材主线

### 3.1 原始素材边界

reflection 不直接面向“region prompt 字符串”工作，而是先拿结构化材料。

当前固定边界：

- `episode`
  - 提供玩家行为时间线地基
- `STMPool`
  - 提供 Agent interaction trace
  - interaction 必须携带当时绑定的 context
- `agent state`
  - 提供角色身份、背景、情绪、关系、现状等状态事实
- `relevant persona`
  - 提供与当前材料相关的 preference 条目

这里特别强调：

- interaction trace 中包含 context 是必须的
- 因为 context 本身就是触发 Agent 反应的成因之一
- 这些 context 必须与当时 interaction 保持绑定，不能事后靠当前 state 回推

### 3.2 `ReflectionRawMaterial`

`ReflectionMaterialExporter` 负责从素材 owner 中抽取原始材料，并输出 `ReflectionRawMaterial`。

它只负责：

- 抽取
- 按请求裁切时间范围 / segment 范围
- 保留结构化原始记录

它不负责：

- prompt 组装
- LLM task 语义
- 业务提交

### 3.3 `ReflectionMaterial`

`ReflectionMaterialMerger` 负责把 `ReflectionRawMaterial` 合并成正式的 `ReflectionMaterial`。

当前固定要求：

- reflection 对外正式输入只有一个顶层 `ReflectionMaterial`
- 不再额外发明一个并列 root DTO
- `region` 在这里本质上就是多个 segment 的拼接视图

但是 `ReflectionMaterial` 内部必须保留稳定的 `ReflectionSegmentMaterial` 子单元。

原因：

- 选段天然是 segment 级
- detail tag 提取天然是 segment 级
- 很多低频分析天然也仍然是 segment 级

### 3.4 `ReflectionSegmentMaterial`

每个 segment 固定包含四类东西：

- `segment boundary`
- `start context snapshot`
- `player action timeline`
- `agent interaction timeline`

这里不再区分“有 interaction 的 segment”和“没有 interaction 的 segment”两套 shape。

统一策略是：

- 每个 segment 开头总是带一份 `start snapshot`
- 后续把玩家动作流和 interaction 绑定 context 的时间流统一合并

两类 context 语义不同：

- `start snapshot`
  - 表示这段开始时的默认环境基线
- `interaction bound context`
  - 表示某次 interaction 当下绑定的局部上下文

它们不能混成同一份东西。

### 3.5 Prompt Draft 不是事实源

`ReflectionPromptDraft` 如果继续保留，只能是 task 内部的渲染产物。

固定语义：

- 事实源是 `ReflectionMaterial`
- prompt draft 只是某个 task 为了一次 LLM 请求而做的投影
- 不能让 prompt draft 与 material 并列成为两个事实源

## 4. 新的 task-centric 执行模型

### 4.1 放弃“一个统一动态 ReflectionTask”

当前不再采用：

- 一个超通用的统一 `ReflectionTask`
- 再由 `ReflectionTaskSpec` 在运行时动态装配任意 modules、任意 apply 顺序、任意提交策略

原因：

- 这会把 reflection 变成一个过重的运行时框架
- 语义边界会被“通用性”冲淡
- 很多提交和联动动作其实是 task 语义，不是 module 语义

### 4.2 采用“抽象 task + 具体 task”

正式方向改为：

- `AbstractReflectionTask`
  - 只保留共用骨架
- `EpisodeSynthesisReflectionTask`
  - 第一条正式 task
- 后续按需要增加：
  - `EmotionRefreshReflectionTask`
  - `SituationRefreshReflectionTask`
  - 其他具体 reflection task

当前固定原则：

- 一个具体 task 装载固定的一组 modules
- 不是每次运行时动态启停 modules
- task 自己决定材料长度、state 披露、persona 披露、instruction 拼装、输出协议、commit 方式、后续联动

### 4.3 一次 task 的统一语义

一次具体 reflection task 内，固定有且只有：

- 一份 `ReflectionMaterial`
- 一份 `ReflectionStateSnapshot`
- 一份 `RelevantPersonaEntries`
- 一次 LLM 调用
- 一组由该 task 固定声明的 module outputs

结论：

- “一次 task 返回多个 module 输出”这个能力仍然保留
- 但 module 集合由 task 类型固定，不再由运行时任意拼装

### 4.4 任务输入拆分

LLM 输入仍然分成两部分：

- `Context`
- `Instruction`

其中：

- `Context`
  - 固定由 `ReflectionMaterial + ReflectionStateSnapshot + RelevantPersonaEntries` 组成
- `Instruction`
  - 由具体 task 负责拼装
  - 包含任务目标、约束、输出协议说明

这里不再需要 `ReflectionInputDependency` 一类抽象。

### 4.5 任务输出与校验

一次具体 task 的 LLM 返回应包含该 task 固定启用的一组 module 输出块。

校验顺序固定为：

1. root 结构校验
2. 单 module parse
3. 单 module validate
4. 跨 module validate

当前还要在设计里记一笔：

- 如果 `validate` 不通过，后续应接一条 LLM 重试链路
- 这条重试机制本轮先不实现，但必须作为正式 TODO 保留

## 5. Module 的收紧职责

### 5.1 Module 不是业务执行器

当前正式收紧为：

- module 是输出协议单元
- 不是业务执行单元
- 不是提交执行单元
- 不是后续 trigger 决策单元

### 5.2 Module 负责什么

当前 module 只负责 output-side 事项：

- 提供自己对应的 module instruction prompt 片段
- 定义自己对应的输出块身份
- 定义自己对应的输出块 schema / contract
- 解析自己对应的输出块
- 校验自己对应的输出块是否合法

如果后面发现 schema 与 validate 必须强绑定，那么它们仍然留在 module 内；
但 module 不再承担业务 apply。

### 5.3 Module 不负责什么

module 明确不负责：

- 从 runtime 拉材料
- 决定读取哪些 state / persona
- 拼整次 task 的 instruction
- 直接写入 owner
- 直接截断 episode / STMPool
- 直接触发后续任务
- 决定整次 task 的提交顺序

这里要区分两层 instruction：

- module 可以提供“自己这一个输出块”的 instruction prompt 片段
- 但整次 task 的 instruction 拼装顺序、总目标、总约束、root 输出协议，仍然由 task 决定

### 5.4 任务负责 commit 和联动

具体 task 负责：

- 汇总全部合法 module result
- 将其肢解成对各 owner 的 commit 内容
- 决定这次 task 完成后要发哪些后续 trigger signal

也就是说：

- `commit` 跟着 task 走
- `post-task trigger` 也跟着 task 走
- 不跟着单个 module 走

这比“每个 module 自己 apply”更符合当前工程语义。

## 6. `EpisodeSynthesisReflectionTask` 的定位

### 6.1 旧链路直接清除

旧的：

- `EpisodeSynthesisTask.java`
- `DebugEpisodeSynthesisTask.java`
- 以及与之绑定的旧 trigger / 旧调试入口

当前视为老链路残留。

处理原则：

- 直接清除
- 然后按新骨架重建
- 不做兼容包裹

### 6.2 新 task 的职责

`EpisodeSynthesisReflectionTask` 是第一条正式 concrete task。

它负责：

- 从 episode gate 触发
- 请求对应范围的 `ReflectionRawMaterial`
- 合并成 `ReflectionMaterial`
- 准备 `ReflectionStateSnapshot`
- 召回 `RelevantPersonaEntries`
- 拼装 episode synthesis 专用 instruction
- 发起一次 LLM 请求
- 解析并校验固定的一组 module outputs
- 将结果肢解成正式提交内容
- 提交与该 task 绑定的后续信号

当前设计结论是：

- 第一版实现里，它曾暂时装载当前 reflection 包里全部现有 modules
- 但这已经不再是推荐方向
- 最新设计口径改为：将“消费型长期记忆结算”和“只读型近期状态刷新”正式拆成两条 task

新的 task 划分应为：

1. `EpisodeSynthesisReflectionTask`

- 定位：
  - 消费型 LTM 结算任务
- 触发时机：
  - 仍然走 region close 后的 gate / holdback / ready signal
- 是否消耗材料：
  - 是
- 是否推进 lifecycle / cleanup：
  - 是
- 固定 modules：
  - `memory_synthesis`

2. `AgentRecentStateUpdateReflectionTask`

- 定位：
  - 只读型近期状态刷新任务
- 触发时机：
  - 一个 episode / region 一旦封口就立即触发
- 是否消耗材料：
  - 否
- 是否推进 lifecycle / cleanup：
  - 否
- 固定 modules：
  - `player_profile_update`
  - `recap_update`
  - `favorability_update`
  - `emotion_update`

这么拆的原因是：

- `memory_synthesis` 本身允许高延迟
- 但 `player_profile / recap / favorability / emotion` 都不应继承 LTM 结算的延迟
- 它们更接近“近期状态刷新”，而不是“消费源材料后的最终结算”

### 6.3 成功后的业务后果

成功完成后，业务后果由 task 决定并提交给 owner：

1. `EpisodeSynthesisReflectionTask`

- 写入 LTM
- 发出“消费并截断 STM 已消费部分”的信号
- 发出“推出并移除 episode 已消费部分”的信号

2. `AgentRecentStateUpdateReflectionTask`

- 更新玩家画像 / 对玩家的看法
- 更新 `AgentState.recap`
- 更新好感度
- 更新情绪
- 不发 cleanup / consume / resolve 信号

这里的关键约束是：

- 具体截断逻辑写在 `episode` / `STMPool` 自己的 owner 里
- 不是 module 直接操作别人的内部结构

### 6.4 当前实现状态

截至 `2026-03-27`，当前代码已经完成下面这些骨架：

- `AbstractReflectionTask`
  - 已接起共用主干：
    - `ReflectionMaterialExporter`
    - `ReflectionMaterialMerger`
    - `ReflectionStateSnapshotLoader`
    - `ReflectionPersonaLoader`
    - `ReflectionPromptFormatter`
  - 已内建 module instruction prompt 的拼装
  - 已内建按 module 分发的 parse / validate 骨架
- `EpisodeSynthesisReflectionTask`
  - 已冻结固定 modules
  - 当前代码里固定 modules 仍然等于 reflection 包里全部现有 modules
  - 已冻结 material / state / persona 请求边界
  - `executePreparedTask(...)` 第一版已经能跑通：
    - task-owned prompt 拼装
    - 单次 LLM 调用
    - root JSON 提取
    - module parse / validate
    - LTM / 玩家画像 / 好感度 / 情绪的 owner-side commit
    - consumed cleanup + region resolved / rollback
  - 当前仍未完全闭合的点：
    - `recap_update` 已写回 `AgentState.recap`
    - validate 失败后的 LLM 重试链路还没接
    - synthesized LTM 的 tag / 时间锚点仍只是第一版
    - 任务粒度仍然过大：`player_profile_update / recap_update / favorability_update / emotion_update`
      仍被绑在这条高延迟消费型 task 上
- `AgentRecentStateUpdateReflectionTask`
  - 当前还不存在
  - 但已确定为接下来最高优先级的主线修正：
    - 在 episode 封口时立即触发
    - 只读，不消耗材料
    - 承载 4 个近期状态刷新 modules
- `ReflectionModule`
  - 当前职责已经和实现对齐为：
    - module instruction prompt
    - output contract
    - parse
    - validate

因此当前准确表述不是“reflection 还没有任何新实现”，而是：

- 新 reflection 主线骨架已经落地
- episode gate -> task 提交、单次 LLM 调用、task-owned commit、owner consumption signal 已经接回第一版闭环

### 6.5 当前后台任务队列语义

当前 reflection 没有单独的专属后台队列。

实际运行时结构是：

- `AgentRuntime` 只有一条共享的 `bgQueue`
- 所有后台任务都应进入这条 `PriorityBlockingQueue<AgentTask>`
- `AbstractReflectionTask` 继承自 `AgentTask`
- 当前内部固定后台优先级为 `40`

当前还要明确一点：

- 代码里已经存在 `EpisodeSynthesisReflectionTask`
- `EpisodeSynthesisInternalTrigger` 已经会把 ready episode signal 逐个提交到 `AgentRuntime.submitBackground(...)`
- 提交失败时会立刻把对应 region 从 `PENDING` 回滚到 `HOLDING`
- 所以 reflection task 现在已经能真正进入后台执行队列

## 7. 工程约束

### 7.1 Action / Reflection 分包

后续包结构必须清晰分离：

- `agent/action/...`
- `agent/reflection/...`

两者都应有清晰的：

- trigger
- task
- parser
- logger
- response / result

但输入源不同：

- action 主要吃 collector/context
- reflection 主要吃 material/state/persona

### 7.2 清除旧 reflection 残留

需要直接清理两类旧代码：

1. 旧 reflection 残留在 action 链路中的部分
2. 当前 `agent/reflection` 包里早期实验性残留的部分

目标是避免：

- 新旧概念同名不同义
- skeleton 看起来像新架构，实际还在跑老链路

### 7.3 内部 API 避免 string-heavy

Java 内部运行时 API 尽量保持强类型：

- task identity 用明确类型
- module identity 用明确类型
- config 用明确类型

只在 LLM JSON 边界才映射成字符串字段名。

原因：

- 重构时更安全
- 改名和扩展更容易被编译器兜住
- 不会把 reflection runtime 变成一套字符串配置系统

## 8. 当前推荐的实现顺序

按当前代码落地状态更新后，推荐顺序改为：

0. 先单独解决 fixed segment 持久化坐标问题
   - 参见 [SEGMENT_COORDINATE_REFACTOR_PLAN.md](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/SEGMENT_COORDINATE_REFACTOR_PLAN.md)
   - 先把“稳定坐标”和“当前数组位置”拆开
   - 解决中间 region 物理删除对 signal / task / material exporter 的冲击

1. 同步文档与实现
   - 以本文为准
   - 同步修改注释、README、状态说明文档
   - 明确哪些步骤已经完成，哪些还只是骨架

2. 接回 episode gate -> reflection task 提交
   - 让 `EpisodeTimelineStore.collectEpisodeSynthesisTriggerSignals()` 真正产出 ready signals
   - 重建对应 internal trigger
   - 由 internal trigger 逐个调用 `AgentRuntime.submitBackground(new EpisodeSynthesisReflectionTask(signal))`
   - 让 reflection 先真正进入共享后台队列

3. 补齐一次 task 的完整 LLM 输入/输出协议
   - 由 task 拼完整 instruction
   - 定义 root 输出结构
   - 把固定 module output contract 拼到一次响应协议里

4. 跑通单次 LLM 调用与解析
   - 让 `EpisodeSynthesisReflectionTask` 跑通：
     - material
     - state / persona
     - prompt draft
     - module instruction
     - response
     - parse / validate / cross-validate

5. 重建 task-owned commit
   - 由 task 汇总合法 module result
   - 肢解成对 LTM / state / persona / current situation 的正式提交

6. 重建 task-owned 后续联动
   - 由 task 发出 episode / STMPool consumption signals
   - 由 owner 自己处理实际截断、推出和消费

7. 最后做工程收尾
   - 更新日志与调试输出边界
   - 清理所有过时注释与 README
   - 再检查命名、语义和包结构是否还存在偏移

## 9. 当前基线总结

一句话概括当前正式方向：

- reflection 不再走“统一动态任务框架 + module 自己 apply”的路
- 而是走“抽象 task + 具体 task + 固定 modules + task 负责提交和联动”的路

第一条落地任务就是：

- 清掉旧 `EpisodeSynthesisTask` 老链路
- 按这份 plan 重建新的 `EpisodeSynthesisReflectionTask`
