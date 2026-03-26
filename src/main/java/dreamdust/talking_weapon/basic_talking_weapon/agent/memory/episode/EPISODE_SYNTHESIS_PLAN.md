# Episode Synthesis Plan

> 本文是 `episode -> LTM synthesis` 的后续设计计划。
> 它记录的是当前确认下来的推进方向，不等于已经落地的实现。
> 当前运行时真实状态请优先看 [EPISODE_CURRENT_STATE.md](EPISODE_CURRENT_STATE.md)。

## 1. Goal

当前 `episode` 子系统已经能提供：

- `fixed segments`
- `pending marks`
- `split points`
- `regions`
- 一个 `region` 级的 prompt draft

下一阶段的目标，是把这条链正式延伸为：

1. `episode` 自己判断是否值得进行一次 LTM 合成
2. 生成一次后台 `EpisodeSynthesisTask`
3. 将 `region` prompt draft 和必要背景一并发给 LLM
4. 由 LLM 决定重点记住哪些 segment，并生成统一的 LTM 文本
5. 系统只从被选中的 segment 中提取 detail tags
6. 同时完成低频的人格 / 关系 / 现状更新

也就是说，`episode` 应逐步成为：

- 长期记忆生成的正式上游
- 低频关系结算的正式上游
- region 级经验沉淀的唯一主线

---

## 2. Confirmed Decisions

### 2.1 记不记忆，应先由 episode 侧加一道阀门

这是当前已经确认的第一条调整。

原因：

- 并不是每一个 closed region 都值得消耗一次 LLM token
- 很多 region 可能只是短暂过渡、空白切段、弱语义尾段
- 若不先做 gate，后面会把大量低价值 region 送去合成

因此后续正式链路应改为：

1. `EpisodeTimelineStore` 先做 region 资格判断
2. 只有通过 gate 的 region，才产出 `EpisodeSynthesisTriggerSignal`
3. `EpisodeSynthesisTask` 只消费已经通过 gate 的 signal

### 2.2 好感度更新从 STMPool 和当前 LLMInteraction 主线中剥离

这是当前已经确认的第二条调整。

当前旧设计里，好感度存在两条过时链路：

- 高频 `LLMInteractionTask -> favorability_update`
- `STMPool` 内部按 visible context tag 分账的暂态好感度账本

这两条链路的问题是：

- 它们绑定的是“单次 interaction”
- 而当前正式的长期记忆主线已经改为“episode / region”
- 关系判断的时间尺度和记忆生成尺度已经错位

因此后续应改为：

- 高频交互不再正式结算 favorability
- `STMPool` 不再作为正式好感度账本
- 好感度只在 `EpisodeSynthesisTask` 中做一次 region 级低频更新

也就是说，后续正式主线应是：

- `episode synthesis -> favorability_delta -> AgentPersona.favorability`

### 2.3 高频情绪更新是否要扩展，暂不纳入这轮设计

这是当前已经确认的第三条调整。

当前我们先不扩大“高频情绪更新”的设计范围。

原因：

- 这次真正要解决的是 `episode-first` 的长期沉淀主线
- 如果在这个阶段同时重做情绪模块，会把边界重新搅乱
- 情绪更新和长期记忆沉淀虽然有关，但不是同一层问题

因此当前结论是：

- 先不把“更频繁的情绪更新”纳入本轮设计目标
- 现有情绪链路先保持现状
- 等 `episode synthesis` 正式闭环后，再单独研究情绪层是否需要迁移或加密度

---

## 3. Planned Mainline

目标主线如下：

1. `EpisodeTimelineStore` 检查是否出现“已闭合且值得合成”的 region
2. 若通过 gate，则产出 `EpisodeSynthesisTriggerSignal`
3. `EpisodeSynthesisTrigger` 提交后台 `EpisodeSynthesisTask`
4. `EpisodeSynthesisTask` 读取：
   - `EpisodeSynthesisMaterial`
   - 对应时间切片内的 STM 材料
   - `AgentMemory.identity`
   - `AgentState`
   - `AgentPersona`
5. 组织正式 synthesis prompt 并调用 LLM
6. LLM 返回：
   - 是否写入 LTM
   - 重点 segment 序号
   - 一条统一 LTM 文字
   - 对玩家看法的更新
   - 对现状的更新
   - favorability 变化
7. 系统只从被选中的 segment 中收集 detail tags
8. 统一提交：
   - 写入 `LTMEntry`
   - 更新 `AgentPersona.playerProfile`
   - 更新 `AgentState.currentSituationSummary`
   - 更新 `AgentPersona.favorability`
   - 丢弃已消费的 STM slice
   - 推进 episode timeline 的已消费 region

---

## 4. Episode Gate

### 4.1 位置

gate 应位于 `episode` 侧，而不是 task 侧。

原因：

- 这样外部调度层只看到“该不该触发”
- `EpisodeSynthesisTask` 不需要再重复做一套“是否值得记忆”的判断
- 可以避免把大量无意义 region 变成隐形后台任务

### 4.2 目标

gate 主要负责挡掉以下 region：

- 纯过渡段
- 几乎没有有效动作和交互的弱 region
- 只由 fallback 勉强切出来、但没有明显主题的尾段
- 不值得消耗 token 的低价值合成候选

### 4.3 当前计划

这一层先保持为 episode 内部的独立决策模块，不在 task 内部偷偷做。

也就是说后续理想边界应是：

- `EpisodeTimelineStore.peekEpisodeSynthesisTriggerSignal()`
  - 若通过 gate，返回 signal
  - 若不通过 gate，直接返回空

---

## 5. LLM Input Package

当前计划中，一次正式 episode synthesis 至少应包含以下输入：

### 5.1 Identity

- 角色背景
- 角色性格

来源：

- `AgentMemory.identity`

### 5.2 AgentState

- 当前主导情绪
- 情绪强度
- 熟悉度
- 对玩家称呼
- 当前渴望与描述
- 当前现状概述（后续新增）

### 5.3 AgentPersona

- 当前 favorability
- 当前 player profile
- 与当前 episode 相关的人格条目

### 5.4 Episode Material

- region prompt draft
- segment 序号
- segment 时间范围
- 必要时的 region 边界信息

### 5.5 Task Instruction

明确要求 LLM 做四件事：

1. 判断这段经历是否值得写成长期记忆
2. 选择真正值得重点记住的 segment
3. 生成一条统一的 LTM 文本
4. 顺带更新长期关系和当前现状

---

## 6. Planned LLM Output

当前计划不再沿用旧的 `ReflectionTask` 风格。

当前进一步确认的方向是：

- 内部 reflection 不走当前 `agent action` 那种自由动作列表风格
- 内部 reflection 走“模块化、强类型、可插拔”的返回结构

### 6.1 为什么要加这一层模块抽象

原因不是为了泛化而泛化，而是为了应对后续很可能出现的多种内部反思任务：

- episode 级 LTM 合成
- region / segment 级好感度更新
- region / segment 级现状分析

这些任务之间的共同点是：

- 都属于内部反思任务
- 都会调用 LLM
- 都不是运行时动作

它们之间的不同点是：

- 每种任务需要的输入材料不同
- 每种任务需要的提示词段落不同
- 每种任务需要的输出字段也不同

因此，更合理的抽象不是：

- 一个自由字符串动作总线

而是：

- 一组稳定的 reflection modules
- 每个任务声明自己启用了哪些模块
- prompt builder 只披露这些模块所需的说明
- output schema 也只要求这些模块所需的结构

### 6.2 建议的抽象边界

后续更适合拆成两层：

#### A. `ReflectionTaskSpec`

描述一次内部反思任务：

- 输入材料来源
- 启用哪些模块
- 需要哪些提示词段落
- 如何提交结果

#### B. `ReflectionModule`

描述一组稳定的输出模块，例如：

- `memory_synthesis`
- `favorability_update`
- `player_profile_update`
- `current_situation_update`

任务共享的是模块协议，而不是共享一个自由解释执行器。

### 6.3 第一版建议模块

当前建议先固定四个模块：

- `memory_synthesis`
  - 决定是否写入 LTM
  - 返回重点 segment 序号
  - 返回统一 LTM 文本
  - 返回 `focus_tags`
  - 返回 `importance`
- `favorability_update`
  - 返回 `delta`
- `player_profile_update`
  - 返回新的玩家画像文本
- `current_situation_update`
  - 返回新的现状概述文本

### 6.4 按模块披露 prompt / schema

这一条现在也确认下来：

- 一个内部反思任务，只应披露“已启用模块”所需的提示词说明
- 一个内部反思任务，也只应要求“已启用模块”所需的输出格式

也就是说：

- 如果某任务没有启用 `favorability_update`
  - prompt 中就不应出现好感度要求
  - schema 中也不应出现对应字段
- 如果某任务只启用 `current_situation_update`
  - 它就只组装现状分析所需输入
  - 也只要求现状分析对应的输出对象

这样做的好处是：

- 减少提示词噪音
- 降低模型误输出概率
- 保持任务边界干净

### 6.5 第一版 episode synthesis 返回形态

因此，对 `EpisodeSynthesisTask` 来说，更适合的第一版返回是：

```json
{
  "thinking": "...",
  "memory_synthesis": {
    "should_write_ltm": true,
    "selected_segment_indices": [0, 2, 3],
    "ltm_description": "...",
    "focus_tags": ["EXPLORE", "SOCIAL"],
    "importance": 0.78
  },
  "favorability_update": {
    "delta": 6
  },
  "player_profile_update": {
    "text": "..."
  },
  "current_situation_update": {
    "text": "..."
  }
}
```

这意味着：

- `EpisodeSynthesisTask` 会启用全部四个模块
- 未来如果新增 `segment` 级内部分析任务，则可以只启用其中一部分模块

### 6.6 Segment 序号的约定

LLM 返回的 `selected_segment_indices` 应使用：

- `region` 内局部序号

而不是：

- 整条 timeline 的全局序号

原因：

- region 被消费后，timeline 后续会推进
- 用局部序号更稳定，也更容易和 prompt 保持一致

### 6.7 统一 LTM 文本

LLM 不应为每个 segment 各写一条记忆。

后续目标是：

- 由 LLM 从整个 region 中挑重点 segment
- 再把它们合成成“一条 region 级长期记忆”

---

## 7. Detail Tag Extraction Rule

当前计划中，detail tag 的采集规则明确为：

- 只从 LLM 选中的 segment 中提取
- 不从整个 region 无差别提取
- 不从隐藏的 recall-only 材料中偷取

目标是避免两类污染：

- detail tag 泛滥
- 系统替 LLM 补充它其实没有重点记住的内容

因此后续更合理的规则是：

1. 先由 LLM 选 segment
2. 再从这些 segment 的正式暴露内容中提 detail tags
3. 最终写入 `LTMEntry`

---

## 8. Favorability Migration

## 当前判断

favorability 的正式主线应迁移到 `episode synthesis`。

### 8.1 旧设计的问题

当前旧系统包括：

- `LLMInteractionTask` 中的 `favorability_update`
- `STMPool` 中按 tag 进行的暂态账本 / 疲劳衰减
- `ReflectionTask` 中的 `favorability_settlement`

这些设计的问题在于：

- 粒度过高
- 主线仍围绕 interaction / epoch，而不是 episode / region
- 和新的长期记忆生成主线不一致

### 8.2 后续目标

后续 favorability 应改为：

- 不在普通 interaction 中正式结算
- 不在 STMPool 中维护正式账本
- 只在 `EpisodeSynthesisTask` 中做 region 级低频更新

### 8.3 迁移后的边界

正式主线：

- `EpisodeSynthesisTask -> favorability_delta -> AgentPersona`

应逐步退出主线的旧组件：

- `favorability_update`
- `favorability_settlement`
- `STMPool.domainFatigue`
- `STMPool.volatileFavorabilityByTag`
- `ReflectionTask` 中的关系结算职责

---

## 9. Current Situation Summary

后续需要新增一个“现状概述”字段。

当前计划是：

- 它属于 `AgentState`
- 它是一个纯文字字段
- 它反映的是“剑眼中当前局势的长期低频摘要”

它不应属于：

- `AgentPersona`

因为：

- `AgentPersona` 更适合承载长期关系和稳定人格
- “现状概述”更接近当前世界态势 / 当前阶段认知

因此后续建议增加：

- `AgentState.currentSituationSummary`

并接入：

- `agent_state.json`
- prompt 注入
- episode synthesis task 回写

---

## 10. Emotion Update

本轮不把“更频繁的情绪更新”纳入正式设计目标。

当前约束：

- 先不扩大高频 emotion update 的职责范围
- 先让 `episode -> synthesis -> LTM/persona/state` 主线闭环
- 情绪模块是否需要后续迁移，等 episode synthesis 稳定后再单独讨论

---

## 11. Suggested Implementation Order

建议后续按以下顺序推进：

1. 新增 `AgentState.currentSituationSummary`
2. 设计并接入 `episode_synthesis` 的 LLM 返回结构
3. 让 `EpisodeSynthesisTask` 真正调用 LLM
4. 实现“按 selected segments 提取 detail tags”
5. 实现 synthesis 成功后的统一提交
6. 将 favorability 正式迁移到 episode synthesis
7. 逐步下线 `ReflectionTask` / `STMPool` 中旧的关系结算职责

---

## 12. Scope Boundary

当前这份计划只处理：

- episode-first 的 LTM 合成主线
- episode 级的关系更新
- 现状摘要的新增位置

当前明确不在这份计划内的内容：

- 高频情绪系统重构
- prompt-facing 动作压缩策略的再次重写
- recall 公式的进一步调整
- Studio 前端页面实现
