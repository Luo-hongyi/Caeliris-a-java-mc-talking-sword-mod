# 记忆系统说明

## 当前状态

截至 `2026-03-16`，`agent.memory` 子系统的状态可以概括为：

- `ActionBuffer`、`STMPool`、`AgentMemory`、`LTMEntry`、`LTMStorage` 已经构成可运行的短期记忆 + 长期记忆存储骨架。
- 全局植入记忆 (`IMPLANTED`) 的加载、保存和运行时热重载链路可用。
- 长期记忆召回 (`LTMRecallEngine`) 已切换到最新方案：从 `visibleContexts` 中抽取 seed entry / seed tag，按 focus、温度、recent recall 和 nostalgia 进行联想式召回。
- `LTMEntry` 已补入可持久化的游戏内语义时间锚点，用于叙事展示与基于游戏时间的衰退计算。
- `semantic_time_tag` 已作为独立时间语义层接入：它会参与记忆之间的联想，但不会混入普通 `detail_tags` 的 Prompt cue。
- 当前仍未完成的关键环节，是和这套新 recall 架构一致的 **LTM 生成闭环**。

## 记忆分层

### 1. ActionBuffer

- 记录高频动作事件。
- 用于战斗、采集、旅行等底层行为的短窗口缓冲。
- 只负责原始动作积累，不直接等于最终可召回记忆。

### 2. STMPool

- 聚合近期 `InteractionTurn` 与上下文条目。
- 为后续的记忆总结、人格清算、LTM 生成提供原始材料。
- 当前系统中，LTM 的“召回起点”来自 `LLMInteractionTask` 中已经进入 `visibleContexts` 的上下文，而不是直接从隐藏上下文或原始 ActionBuffer 起跳。

### 3. LTMRecords

- `AgentMemory` 中持有的长期记忆集合。
- 包含两类来源：`IMPLANTED` 与 `SYNTHESIZED`。
- `IMPLANTED`：外部植入的人设 / 背景记忆。
- `SYNTHESIZED`：运行时从经验中沉淀出的长期记忆。
- 每条 `LTMEntry` 现在除了描述、focus 和 detail tags 以外，还会持有轻量的世界语义锚点，例如：发生时的游戏内时间描述、维度、群系和是否下雨。

## 长期记忆召回

当前的 `LTMRecallEngine` 采用的是“联想式召回”，而不是旧式的全量 deterministic top-k：

- 起点是当前 `visibleContexts`。
- 系统先抽取少量 seed entries，再从其中抽取 seed tags。
- 每层联想都会对候选记忆做 semantic match、focus 对齐、recent recall 抑制和时间衰退加权。
- 时间衰退优先根据游戏内时间推进，而不是现实时钟。
- 不同 seed 可以共同支持同一条记忆，证据会在 memory layer 聚合。
- `native_generic` 可以参与内部检索，但不会直接进入 Prompt。
- `semantic_time_tag` 会加入记忆的 associative tag 视图，因此可以成为记忆到记忆的联想边；但它仍然保持为独立时间语义，不会被塞进普通 detail cue 列表。
- `nostalgia` 当前取值范围为 `0.0 ~ 2.0`：
  - `0.0 ~ 1.0`：只影响旧记忆随时间衰退的力度。
  - `1.0 ~ 2.0`：在保留上述作用的同时，若本轮正常召回没有命中任何 `occurredGameTime < 0` 的植入记忆，会额外启用一次“植入记忆保底”。
- 这个“植入记忆保底”不会重跑整套 recall；它只复用本轮已经产生的总 seed 池，遍历所有 `SourceType.IMPLANTED && occurredGameTime < 0` 的记忆，并仅按 seed 与记忆 associative tags 的语义命中强度做一次加权抽取，最终最多补入 1 条记忆。

## 当前缺口：LTM 生成

仓库中仍保留 `ReflectionTask` 和 `ReflectionTrigger`，但这条链路目前只能视作旧版过渡骨架，而不是当前设计意义上的完整实现。原因包括：

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

## 维护原则

- Prompt 是否可见，与 tag 是否参与内部检索，是两个不同层次的问题。
- `native_generic` 可以进入系统，但应被视为内部冗余语义层；真正喂给 LLM 的是 `native_specific` 和 `custom_*`。
- 记忆召回不应该回忆“所有相关记忆”，而应该按当前注意力与随机温度抽取少量联想结果。
