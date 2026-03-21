# 长期记忆召回总览

本文档用于总结 **当前 recall 子系统已经实现的设计与代码结构**。

它不是参数手册，也不是未来方案草稿，而是当前版本的稳定总览。

配套文档：

- `RECALL_STRATEGIES.md`：统一模型的设计语义与抽象目标
- `RECALL_TUNING_GUIDE.md`：当前代码中的具体公式、参数与调参建议
- `MEMORY_RECALL_NEXT_STEPS.md`：后续 recall 续做事项

## 1. 当前 recall 的目标

当前 recall 不再把“召回”理解为一次性的 top-k 检索，而是理解为一个跨轮维护的认知流：

1. 当前 `visibleContexts` 提供新的 cue
2. cue 写入跨轮维护的 `tag_pool`
3. `tag_pool` 激活新的长期记忆候选，并与旧 `fermentation_buffer` 一起竞争
4. 少量记忆进入 `conscious_memories`
5. 其中一部分通过 `prompt fill` 真正进入 prompt
6. `conscious_memories` 还会进入一次隔离的 `association_sandbox`

## 2. 运行时状态层

当前运行时主要维护 5 个层和 1 个沙盒：

### 2.1 `visibleContexts`

- 本轮可见上下文输入。
- 来源是 `LLMInteractionTask` 当前回合采集并经注意力筛选后的上下文。
- 它不是跨轮池，只是本轮 recall 的入口。

### 2.2 `tag_pool`

- 跨轮维护的 `detail tag -> score` 激活池。
- 表示“最近哪些概念还在活跃”。
- 它会随时间衰减，也会因为本轮新输入而重新注入。
- 当前还额外挂了 `reheatGain`，用来抑制常见 tag 的无损反复再加热。

### 2.3 `fermentation_buffer`

- 跨轮维护的候选记忆层。
- 每条条目本质上是“正在发热的长期记忆”。
- 这层是记忆，不是 tag。
- 它的黏性主要来自自身半衰期和本轮 cue 回灌。

### 2.4 `conscious_memories`

- 本轮真正浮到表层意识的记忆。
- 它是从 retained fermentation 再做一次显性排序后得到的。
- 这层已经是“想到了”，但还不等于“说出口了”。

### 2.5 `prompt_memories`

- `conscious_memories` 经过 `prompt fill` 阀门后，真正进入 prompt 的子集。
- 只有这一层会登记 recent recall suppression。
- 只有这一层会被从主发酵层里抛出。

### 2.6 `association_sandbox`

- 本轮隔离联想层，不回写主发酵层。
- 输入来自本轮 `conscious_memories` 的 associative tags。
- 当前记录：
  - `cueTags`
  - `sourceMemoryIds`
  - `memoryIds`
  - `hopLimit`
  - `recallBudget`

## 3. 代码主入口

### 3.1 `RecallRuntimeState`

负责跨轮状态维护：

- 衰减 `tag_pool`
- 衰减 `fermentation_buffer`
- 将本轮 `visibleContexts` 注入 `tag_pool`
- 对发酵层应用 cue boost
- 在 recall 完成后写回 retained / conscious / sandbox

这是 recall 的状态机部分。

### 3.2 `LTMRecallEngine`

负责单轮 recall 算法：

- 从 `tag_pool` 构建主召回 cue
- 收集主召回候选与旧发酵记忆
- 抽样选出 promoted candidates
- 构建 retained selection
- 选出 `conscious_memories`
- 做 `prompt fill`
- 做 `association_sandbox`
- 必要时执行 implanted fallback

这是 recall 的评分、筛选和抽样部分。

### 3.3 `AgentMemory`

负责承载 recall 相关的长期状态：

- `ltmRecords`
- `recentRecallSuppressionTable`
- `recallRuntimeState`

## 4. 当前单轮 recall 流程

设计语义和代码实现当前基本对齐，可以按下面这条链理解：

1. `visibleContexts -> tag_pool`
2. `tag_pool -> main recall cue`
3. `main recall cue -> new memory candidates`
4. `new memory candidates + old fermentation_buffer -> retained fermentation`
5. `retained fermentation -> conscious memories`
6. `conscious memories -> prompt memories`
7. `conscious memories -> association sandbox`
8. 若启用且未命中植入记忆：`cue_pool -> implanted fallback`

其中代码里的对应函数大致是：

1. `RecallRuntimeState.beginTurn(...)`
2. `buildInitialCueInputs(...)`
3. `collectLayerCandidates(...)`
4. `selectPromotionCandidates(...)` + `buildRetainedCandidates(...)` + `selectTopCandidates(...)`
5. `selectConsciousCandidates(...)`
6. `fillPromptMemories(...)`
7. `recallAssociationSandbox(...)`
8. `pickWeightedImplantedFallback(...)`

## 5. 当前 recall 的几个关键实现判断

### 5.1 `wanderingMind`

- 当前只控制抽样温度。
- 它影响的是候选之间有多随机，不改变底层评分公式。

### 5.2 recent recall suppression

- 当前维护的是 `memoryId -> remainingCooldown` 表。
- 只在真正进入 `prompt_memories` 后登记。
- 主要作用于 `conscious` 层浮现排序和 implanted fallback 权重。

### 5.3 `tag_pool` reheat

- 当前已经实现首见脉冲 + 连续命中习惯化。
- `reheatGain` 跟着 `tag_pool` 条目一起存在。
- tag 掉出池时，reheat 状态也一起消失。

### 5.4 sandbox

- 当前是 1-hop、隔离、不污染主池。
- 它只服务本轮输出，不改变下一轮主 recall 状态。

### 5.5 authored 真源

- recall 细粒度公式参数当前以 `studio_workspace/memory_recall_tuning.json` 为真源。
- 游戏启动与 Studio 发布时，会同步回 `TalkingWeaponConfig`。
- 游戏内 config screen 不再承担这组细粒度公式调参职责。

## 6. 当前 recall 和文档的边界

如果要理解：

- “模型语义为什么这样设计”
  看 `RECALL_STRATEGIES.md`

- “当前公式到底怎么写、参数怎么接”
  看 `RECALL_TUNING_GUIDE.md`

- “当前系统整体是怎么组织的”
  看本文档

## 7. 当前 recall 已完成到什么程度

目前可以认为，长期记忆 **召回** 这一半已经形成完整闭环：

- 有跨轮运行时状态
- 有分层选择
- 有 recent suppression
- 有 sandbox
- 有 implanted fallback
- 有 Studio 调参和 trace 观察

当前仍未完成的主要缺口不是 recall，而是：

- **从 STM 生成新的 SYNTHESIZED LTM**
- 何时结算
- 如何筛选 STM 素材
- 如何约束生成质量
- 如何与 favorability / persona 的低频结算配合
