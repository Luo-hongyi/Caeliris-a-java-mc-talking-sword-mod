# Episode Synthesis Plan

> 本文是 `episode -> LTM synthesis` 的后续设计计划。
> 它记录的是当前确认下来的推进方向，不等于已经落地的实现。
> 当前运行时真实状态请优先看 [EPISODE_CURRENT_STATE.md](EPISODE_CURRENT_STATE.md)。
>
> 当前约束：
> 这份文档现在就是 `episode / reflection` 方向的最新设计基线。
> 后续代码实现、类命名、包结构、代码注释、README、设计说明文档，都必须按这份设计同步修改。
> 代码库中凡是与本计划冲突的旧注释、旧 README、旧设计描述，都应直接改掉或删除，不保留历史口径并存。

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

也就是说，`episode` 应成为：

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

- 先直接拆掉当前高频交互中的旧 favorability 执行链
- `STMPool` 不再作为正式好感度账本
- 后续若重新接回，则按 action / reflection 两条平行链路分别定义
  - action 负责高频、小幅度更新
  - reflection 负责低频、大幅度更新

也就是说，后续正式主线应是：

- `action favorability update -> AgentPersona.favorability`
- `reflection favorability update -> AgentPersona.favorability`

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

### 2.4 Action / Reflection 必须拆成两条平行链路

这是当前新增确认的架构约束。

后续不再把“运行时动作”和“内部反思”继续塞在同一套包和响应模型里。

应直接拆成两条平行链路：

- `action`
  - 由 trigger 驱动
  - 主要输入来自 collector 采集出的 `AgentContext`
  - 主要负责运行时互动、说话、内心活动、情绪更新等即时输出
- `reflection`
  - 也由 trigger 驱动
  - 主要输入来自 `episode` 侧产出的内部 signal / material
  - 主要负责低频总结、长期记忆、关系更新、现状更新

两条链路在工程形态上应尽量一致：

- 都有明确的 trigger / task / response / parser / logger 边界
- 都由 task 显式提交
- 都不允许在别的系统里偷偷生成隐形任务

但它们的主要输入源不同：

- action 吃 collector
- reflection 吃 episode

### 2.5 `STM_SLICE_SUMMARY` 不是正式概念

当前确认：

- `STM_SLICE_SUMMARY` 不是现有代码中的正式对象
- 它只是文档里一个含糊的占位说法
- 这个字段名应直接删除，不再保留

`STM` 当前在代码里的正式职责其实很明确：

- 保存一段时间内的 `InteractionTurn`
- 保存对应时间段内的 `TaggedContextEntry`
- 支持按时间切片读取

其中对 reflection 真正有价值的核心材料是：

- `InteractionTurn`
  - 里面已经带有诱发原因
    - `triggerSource`
    - `userPrompt`
  - 也已经带有当时真正进入模型视野的上下文
    - `visibleContexts`
  - 以及 Agent 当轮输出
    - `agentActions`

因此当前结论是：

- 不再设计任何名为 `STM_SLICE_SUMMARY` 的输入字段
- 如果未来某个 reflection task 真的需要直接读 STM，则应读取“`InteractionTurn` 时间切片”
- 对 `EpisodeSynthesisTask` 来说，第一版更推荐由 `episode` 在 material 构造阶段完成 STM 融合，而不是让 task 再额外读一个 summary 字段

### 2.6 本计划默认直接替换，不保留兼容层

这是当前文档语气与实施策略上的硬约束。

后续涉及：

- 类重命名
- 包拆分
- response 拆分
- favorability 链路重做
- 旧 reflection 路线下线

一律按以下原则处理：

- 直接改名
- 直接删旧字段
- 直接删旧 parser / 旧 schema / 旧 trigger 连接
- 不保留兼容别名
- 不保留双栈长期并存
- 不保留“先接一层桥接以后再说”的过渡代码

如果确实需要短时切换步骤，也必须是：

- 明确
- 短期
- 可在同一轮重构内彻底移除

---

## 3. Planned Mainline

目标主线如下：

1. `EpisodeTimelineStore` 检查是否出现“已闭合且值得合成”的 region
2. 若通过 gate，则产出 `EpisodeSynthesisTriggerSignal`
3. `EpisodeSynthesisTrigger` 提交后台 `EpisodeSynthesisTask`
4. `EpisodeSynthesisTask` 读取：
   - `EpisodeSynthesisMaterial`
   - `AgentMemory.identity`
   - `AgentState`
   - `AgentPersona`
5. `EpisodeSynthesisMaterial` 在进入 task 前，已经完成本 region 对应的 STM 交互切片融合
6. 组织正式 synthesis prompt 并调用 LLM
7. LLM 返回：
   - 是否写入 LTM
   - 重点 segment 序号
   - 一条统一 LTM 文字
   - 对玩家看法的更新
   - 对现状的更新
   - favorability 变化
8. 系统只从被选中的 segment 中收集 detail tags
9. 统一提交：
   - 写入 `LTMEntry`
   - 更新 `AgentPersona.playerProfile`
   - 更新 `AgentState.currentSituationSummary`
   - 更新 `AgentPersona.favorability`
   - 丢弃已消费的 STM interaction turn / context slice
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
- 已融合进 segment 的 `InteractionTurn`
- interaction 当时绑定的 `visibleContexts`

### 5.5 STM 的职责边界

`STM` 在这条链路中的职责不再写成模糊的“summary”。

当前明确为：

- 保存交互回合
- 保存这些回合对应时间段内的上下文
- 按时间切片提供给 `episode`
- 由 `episode` 决定怎样把它们并入 segment / region material

也就是说：

- `reflection` 不直接消费“STM 摘要字符串”
- `reflection` 吃的是已经被 `episode` 收束过的 material
- 若未来确实需要直读 STM，也应读取结构化 `InteractionTurn` 切片，而不是临时拼一段 summary 文本

### 5.6 Task Instruction

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

### 6.5 模块对象结构

当前建议每个 reflection module 至少定义以下几部分：

#### A. `moduleKey`

例如：

- `memory_synthesis`
- `favorability_update`
- `player_profile_update`
- `current_situation_update`

运行时内部更推荐使用：

- `ReflectionModuleKey` 枚举

再由它稳定映射到输出 JSON 中的字段名。

它的作用是：

- 作为内部模块标识
- 作为返回 JSON 中的字段名
- 作为 prompt 中对应模块说明段的锚点
- 作为后续 parser / validator / applier 的分发 key

#### B. `purpose`

描述该模块要解决什么问题。

例如：

- `memory_synthesis`：决定是否写入 LTM，并生成统一记忆文本
- `favorability_update`：决定本次低频关系增减

#### C. `inputDependencies`

声明该模块需要哪些输入材料。

建议不要让模块直接说“我要整个 runtime”，
而是只声明自己依赖哪些标准输入槽位，例如：

- `IDENTITY`
- `AGENT_STATE_CORE`
- `AGENT_STATE_SITUATION`
- `PERSONA_RELATION`
- `PERSONA_TRAITS`
- `EPISODE_PROMPT_DRAFT`
- `EPISODE_SEGMENT_INDEX_MAP`

这样后续 task 在启用多个模块时，
可以先求并集，再统一组装输入。

#### D. `promptContribution`

该模块自己的提示词说明段。

它只负责解释：

- 模型在这个模块里要做什么
- 需要遵守什么约束
- 应如何输出

它不负责重新拼整份 prompt。

#### E. `outputContract`

该模块对应的 JSON 对象结构定义。

建议每个模块都定义：

- required fields
- optional fields
- 字段取值约束
- 是否允许空对象

#### F. `resultValidator`

模块级校验逻辑。

例如：

- `selected_segment_indices` 是否越界
- `favorability delta` 是否超出允许范围
- `importance` 是否在 `[0.0, 1.0]`

#### G. `resultApplier`

模块结果如何落到 runtime。

例如：

- `memory_synthesis` -> 构造 `LTMEntry`
- `favorability_update` -> 更新 `AgentPersona.favorability`
- `player_profile_update` -> 更新 `AgentPersona.playerProfile`
- `current_situation_update` -> 更新 `AgentState.currentSituationSummary`

### 6.6 任务规格对象结构

当前建议再加一个 `ReflectionTaskSpec`，描述“一次内部反思任务”的总配置。

它至少应包含：

#### A. `taskKey`

例如：

- `episode_synthesis`
- `segment_relation_review`
- `segment_situation_refresh`

运行时内部更推荐使用：

- `ReflectionTaskKey` 枚举

#### B. `enabledModules`

本次任务启用哪些模块。

例如：

- `EpisodeSynthesisTask`
  - `memory_synthesis`
  - `favorability_update`
  - `player_profile_update`
  - `current_situation_update`

#### C. `sharedInputScopes`

任务级别强制启用的共享输入段。

例如：

- `thinking` 说明
- 基础 JSON 输出规则
- 通用角色身份段

#### D. `moduleConfigs`

为某个模块补充任务级参数。

例如：

- `memory_synthesis.maxSelectableSegments = 4`
- `favorability_update.minDelta = -12`
- `favorability_update.maxDelta = 12`

#### E. `outputPolicy`

任务如何控制输出严格度。

当前建议至少支持：

- `STRICT_ENABLED_ONLY`
  - 只允许返回已启用模块
- `ALLOW_MISSING_OPTIONAL`
  - 可省略未必需模块

第一版建议先只实现：

- `STRICT_ENABLED_ONLY`

#### F. `applyOrder`

任务完成后的提交顺序。

例如对 `EpisodeSynthesisTask`：

1. 校验所有模块结果
2. 先构造 `LTMEntry`
3. 再更新 persona / state
4. 最后提交 timeline / STM slice 的消费

### 6.7 模块如何控制 LLM Input

输入控制建议分两步：

#### 第一步：模块声明依赖

每个启用模块先声明自己的 `inputDependencies`。

例如：

- `memory_synthesis`
  - `IDENTITY`
  - `AGENT_STATE_CORE`
  - `PERSONA_RELATION`
  - `PERSONA_TRAITS`
  - `EPISODE_PROMPT_DRAFT`
  - `EPISODE_SEGMENT_INDEX_MAP`
- `favorability_update`
  - `PERSONA_RELATION`
  - `AGENT_STATE_CORE`
  - `EPISODE_PROMPT_DRAFT`
- `current_situation_update`
  - `AGENT_STATE_CORE`
  - `AGENT_STATE_SITUATION`
  - `EPISODE_PROMPT_DRAFT`

#### 第二步：任务统一求并集并组装

`ReflectionTaskSpec` 不逐模块重复拼 prompt，
而是：

1. 汇总所有启用模块的依赖
2. 对依赖去重
3. 统一生成共享输入段
4. 再附加每个模块自己的 instruction block

这样可以避免：

- 同一份身份 / persona / state 被重复写多次
- 模块之间各自偷偷加一堆 prompt 噪音

### 6.8 模块如何控制 LLM Output

输出控制也建议分两步：

#### 第一步：任务生成 root schema

任务先根据 `enabledModules` 生成本轮的 root JSON schema：

- `thinking`
- `modules`

其中：

- `modules` 是一个对象
- 只有已启用模块可以出现在 `modules` 里
- 未启用模块不出现在 schema 中

#### 第二步：模块分别提供 object schema

每个启用模块分别提供自己的字段定义。

例如：

- `memory_synthesis`
  - `should_write_ltm`
  - `selected_segment_indices`
  - `ltm_description`
  - `focus_tags`
  - `importance`
- `favorability_update`
  - `delta`

最终由任务把这些模块对象拼成 `modules` 下的一次完整输出协议。

### 6.9 第一版字段约束建议

第一版建议尽量保守，减少解析歧义：

- `memory_synthesis`
  - `should_write_ltm`: required
  - `selected_segment_indices`: 仅当 `should_write_ltm=true` 时 required
  - `ltm_description`: 仅当 `should_write_ltm=true` 时 required
  - `focus_tags`: optional
  - `importance`: optional but strongly recommended
- `favorability_update`
  - `delta`: required
- `player_profile_update`
  - `text`: required
- `current_situation_update`
  - `text`: required

### 6.10 第一版 episode synthesis 返回形态

因此，对 `EpisodeSynthesisTask` 来说，更适合的第一版返回是：

```json
{
  "thinking": "...",
  "modules": {
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
}
```

这意味着：

- `EpisodeSynthesisTask` 会启用全部四个模块
- 未来如果新增 `segment` 级内部分析任务，则可以只启用其中一部分模块

### 6.11 Segment 序号的约定

LLM 返回的 `selected_segment_indices` 应使用：

- `region` 内局部序号

而不是：

- 整条 timeline 的全局序号

原因：

- region 被消费后，timeline 后续会推进
- 用局部序号更稳定，也更容易和 prompt 保持一致

### 6.12 统一 LTM 文本

LLM 不应为每个 segment 各写一条记忆。

后续目标是：

- 由 LLM 从整个 region 中挑重点 segment
- 再把它们合成成“一条 region 级长期记忆”

### 6.13 第一版最小对象模型

为了避免实现时重新滑回“自由 JSON”，
第一版建议直接固定一套最小对象模型。

#### A. 输入依赖枚举

```java
enum ReflectionInputDependency {
    IDENTITY,
    AGENT_STATE_CORE,
    AGENT_STATE_SITUATION,
    PERSONA_RELATION,
    PERSONA_TRAITS,
    EPISODE_PROMPT_DRAFT,
    EPISODE_SEGMENT_INDEX_MAP
}
```

注意：

- 这里只放“可复用输入槽位”
- 不把整个 runtime 直接塞进去
- module 只能声明依赖这些槽位，而不能偷偷自行取数
- `STM_SLICE_SUMMARY` 已直接删除，不再作为正式输入名存在
- 如果未来有 reflection task 需要直接消费 STM，应新增明确名字的结构化依赖，例如 `STM_INTERACTION_TURNS`

#### B. 模块定义

```java
interface ReflectionModuleDefinition<R extends ReflectionModuleResult> {
    ReflectionModuleKey moduleKey();
    Set<ReflectionInputDependency> inputDependencies();
    String buildPromptInstruction(ReflectionTaskSpec taskSpec);
    JsonSchema buildOutputSchema(ReflectionTaskSpec taskSpec);
    ValidationResult validate(R result, ReflectionTaskSpec taskSpec, ReflectionTaskContext context);
    void apply(R result, ReflectionApplyContext context);
}
```

它控制的不是“整份 prompt”，而是：

- 自己需要哪些输入
- 自己在 prompt 里贡献哪一段 instruction
- 自己的输出对象长什么样
- 自己的结果如何校验与落地

#### C. 任务规格

```java
final class ReflectionTaskSpec {
    ReflectionTaskKey taskKey;
    EnumSet<ReflectionModuleKey> enabledModules;
    EnumSet<ReflectionInputDependency> sharedInputScopes;
    Map<ReflectionModuleKey, ReflectionModuleConfig> moduleConfigs;
    OutputPolicy outputPolicy;
    List<ReflectionModuleKey> applyOrder;
}
```

它的作用是：

- 告诉系统这次是哪个内部反思任务
- 告诉系统启用了哪些模块
- 给模块补充任务级参数
- 定义最终的输出严格度与回写顺序

这里的关键约束是：

- 内部 runtime API 不再使用 `String -> Object` 这种松散结构
- 只有到 LLM JSON 边界时，才把 module key 映射成字符串字段名

#### D. 任务上下文

```java
final class ReflectionTaskContext {
    EpisodeSynthesisMaterial material;
    AgentState state;
    AgentPersona persona;
    String identity;
}
```

这里建议只放“已经整理好的输入材料”，
不直接把完整 runtime 暴露给 module。

#### E. 根响应对象

```java
final class AgentReflectionResponse {
    String thinking;
    Map<String, JsonObject> modules;
}
```

当前第一版直接使用这个通用根对象：

- 不再为 `EpisodeSynthesisTask` 单独发明一个专用 root response DTO
- task 的差异由 `enabledModules` 和 validator 决定
- response 根结构从一开始就统一

### 6.14 第一版模块 DTO 建议

第一版建议直接固定如下结果对象：

#### A. `MemorySynthesisResult`

```java
final class MemorySynthesisResult {
    boolean should_write_ltm;
    List<Integer> selected_segment_indices;
    String ltm_description;
    List<String> focus_tags;
    Float importance;
}
```

约束：

- `should_write_ltm=false` 时，其余字段允许为空
- `selected_segment_indices` 不允许重复、不允许越界
- `ltm_description` 只写一条统一记忆，不写 segment-by-segment 清单
- `focus_tags` 允许为空，但不能出现未登记 focus
- `importance` 建议 clamp 到 `[0.0, 1.0]`

#### B. `FavorabilityUpdateResult`

```java
final class FavorabilityUpdateResult {
    int delta;
    String reason;
}
```

约束：

- 第一版真正落库只吃 `delta`
- `reason` 只用于调试日志，可选
- `delta` 由 taskSpec 提供上下限，例如 `[-12, 12]`

#### C. `PlayerProfileUpdateResult`

```java
final class PlayerProfileUpdateResult {
    String text;
}
```

约束：

- 不做 patch / diff
- 直接返回“新的完整玩家画像文本”
- 为空时视为无效结果

#### D. `CurrentSituationUpdateResult`

```java
final class CurrentSituationUpdateResult {
    String text;
}
```

约束：

- 不做 patch / diff
- 直接返回“新的完整现状概述文本”
- 为空时视为无效结果

### 6.15 Action / Reflection 平行分包与响应模型边界

为了避免后续命名与语义继续混淆，
后续不再只拆 response 类名，而是直接按两条平行链路分包：

- `action`
- `reflection`

其中应分别承接各自的：

- trigger
- task
- response
- parser
- logger

在这条基础上，再把现有 [AgentResponse.java](../../llm_service/AgentResponse.java) 直接重命名为：

- `AgentActionResponse`

同时新增一套平行的内部反思响应模型：

- `AgentReflectionResponse`

#### A. 为什么要这样拆

原因：

- 现有 `AgentResponse` 的主语义其实是运行时互动输出
- 它围绕 `actions`、`speech`、`inner_monologue` 设计
- 若继续把 episode synthesis 之类的内部反思结果塞进去，会持续污染同一个响应模型

因此后续边界应明确为：

- `AgentActionResponse`
  - 服务运行时交互任务
  - 核心载荷是 `actions`
- `AgentReflectionResponse`
  - 服务内部反思任务
  - 核心载荷是并列的 typed modules

#### B. 两边不应强行统一成同一种 payload 形态

这里建议“统一协议控制方式”，但不要“统一载荷结构”。

原因：

- action response 本质上是有顺序的动作流，天然是 imperative 的
- reflection response 本质上是低频结算结果，天然是 declarative 的
- 如果把 action 侧也强行改成和 reflection 一样的并列 module，反而会把动作顺序、多条同类动作和 ActionRegistry 的开放性搞僵

因此当前建议是：

- `AgentActionResponse` 继续保持 action-centered
- `AgentReflectionResponse` 采用 module-centered

#### C. 真正值得统一的层

后续如果要统一，建议统一的是这些外层机制：

- task / trigger 声明这次允许哪些输出段
- prompt builder 只披露这些输出段需要的说明
- parser / validator 只接受这些输出段
- 未启用字段直接视为无效输出

而不建议统一成：

- 一套同时承载 action 与 reflection 的 payload DTO

#### D. 当前结论

- 先把 `AgentResponse` 更名为 `AgentActionResponse`
- 把当前 action response 中所有 reflection 残留字段直接移出
- 新建独立的 `AgentReflectionResponse`
- `favorability_update` 与其他 reflection 相关字段不再留在旧 action / reflection 混合结构里
- 不做兼容层，不做“双栈长期并存”

### 6.16 `EpisodeSynthesisTask` 第一版具体规格

第一版 `EpisodeSynthesisTask` 建议直接固定如下 spec：

#### A. `taskKey`

- `episode_synthesis`

#### B. `enabledModules`

- `memory_synthesis`
- `favorability_update`
- `player_profile_update`
- `current_situation_update`

#### C. 输入依赖并集

最终应包括：

- `IDENTITY`
- `AGENT_STATE_CORE`
- `AGENT_STATE_SITUATION`
- `PERSONA_RELATION`
- `PERSONA_TRAITS`
- `EPISODE_PROMPT_DRAFT`
- `EPISODE_SEGMENT_INDEX_MAP`

这里不再出现：

- `STM_SLICE_SUMMARY`

原因：

- 这个名字本身就是冗余占位，不是正式对象
- 当前设计要求 STM 在 episode 侧先被收束进 material
- `EpisodeSynthesisTask` 不再额外读一个 summary 字段

#### D. `moduleConfigs`

第一版建议至少固定：

- `memory_synthesis.maxSelectableSegments = 6`
- `favorability_update.minDelta = -12`
- `favorability_update.maxDelta = 12`
- `player_profile_update.maxChars = 220`
- `current_situation_update.maxChars = 220`

#### E. `outputPolicy`

- `STRICT_ENABLED_ONLY`

含义：

- root 只能返回 `thinking + modules`
- 不允许额外字段
- `modules` 中不允许出现未启用 module

### 6.17 Prompt 组装流水线

第一版建议明确分成以下步骤：

1. `EpisodeTimelineStore` 提供 `EpisodeSynthesisMaterial`
2. `EpisodeSynthesisTask` 创建 `ReflectionTaskContext`
3. 读取 `enabledModules`
4. 汇总所有模块的 `inputDependencies`
5. 将依赖转成共享输入段
6. 依次附加各模块的 instruction block
7. 根据模块 schema 生成最终 root output schema
8. 调用 LLM

其中 prompt 最好拆成四段：

#### A. Shared Identity Block

- 角色背景
- 角色性格
- 对玩家当前看法
- 当前状态与现状概述

#### B. Episode Material Block

- region prompt draft
- segment index map
- 必要时的 region 时间范围

#### C. Module Instruction Blocks

例如：

- `memory_synthesis` 负责告诉模型如何选 segment、如何写统一记忆
- `favorability_update` 负责告诉模型只做低频总结性增减

#### D. Output Contract Block

- 明确要求只输出 JSON
- 明确 root 字段
- 明确各 module 对象字段和约束

这样每个 module 控制的是：

- 自己的依赖
- 自己的 instruction
- 自己的 schema

而 task 控制的是：

- 最终是否启用该模块
- 所有模块的拼装顺序
- 全局 JSON 输出规则

### 6.18 解析、校验与回写流水线

第一版建议在 `EpisodeSynthesisTask` 内固定如下顺序：

1. 解析 root response
2. 检查是否出现未启用 module
3. 分别校验每个 module 的字段合法性
4. 做跨模块一致性校验
5. 全部通过后再统一 apply

跨模块校验至少包括：

- `should_write_ltm=false` 时不允许出现非空 `selected_segment_indices`
- `selected_segment_indices` 只能引用当前 region 的 segment 局部序号
- `memory_synthesis` 未通过校验时，不允许继续抽 detail tags

第一版回写顺序建议固定为：

1. 若 `should_write_ltm=true`，先构造 `LTMEntry`
2. 再应用 `favorability_update`
3. 再应用 `player_profile_update`
4. 再应用 `current_situation_update`
5. 最后标记该 region 已被消费

### 6.19 失败策略

为了避免内部反思任务半成功半失败，第一版建议采用保守策略：

- 任一 module 校验失败，则整次任务不提交正式写入
- 只写调试日志，不推进 region 消费
- 不做部分 apply

这比“写入一半状态、另一半丢失”更干净。

### 6.20 为什么说当前 API 的字符串味太重

这里需要特别明确一下。

“字符串味太重”指的不是：

- LLM 输出 JSON 字段名是字符串

这一点是正常的，因为 JSON 边界本来就是字符串世界。

真正有问题的是：

- Java 内部 runtime API 也大量使用裸字符串和 `Map<String, Object>`

例如如果内部继续用：

- `List<String> enabledModuleKeys`
- `Map<String, Map<String, Object>> moduleConfigs`
- `List<String> applyOrder`

会带来这些工程问题：

#### A. 重命名不安全

一旦把：

- `memory_synthesis`

改成别的名字，

编译器不会报错：

- prompt builder
- parser
- validator
- applier
- 调试日志

这些地方都可能悄悄失配。

#### B. 配置形状不透明

像：

- `moduleConfigs["memory_synthesis"]["maxSelectableSegments"]`

这种结构，调用方在代码里看不出：

- 这个 key 是否存在
- 值的类型是什么
- 哪些模块允许哪些参数

最后只会把错误推迟到运行时。

#### C. Java 侧缺少自动约束

这个项目当前主体是 Java，
而 Java 最有价值的地方之一就是：

- 编译期类型约束
- IDE 重构能力
- 明确的数据结构边界

如果内部也退回字符串 key，
这些优势会被白白丢掉。

#### D. 扩展时容易长成隐藏协议

一开始只有 4 个 module 时看起来还能撑住，
等后面出现：

- 第二种 reflection task
- 更多 module
- 更多 module config

字符串协议就会迅速膨胀成一套没有编译期保护的隐藏 DSL。

因此当前建议很明确：

- 在 LLM JSON 边界，可以用字符串字段名
- 在 Java 内部 runtime API，不再用裸字符串描述 task / module / config

而应改为：

- `ReflectionTaskKey`
- `ReflectionModuleKey`
- `ReflectionModuleConfig`
- `OutputPolicy`

这些强类型结构。

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

favorability 的旧链路应直接拆掉，不保留过渡态。

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

当前这轮重构要先做的不是“迁移”，而是“清空旧边界”。

也就是说，应直接删除：

- `LLMInteractionTask` 中旧的 `favorability_update` 执行链
- `ReflectionTask` 中旧的 `favorability_settlement`
- `STMPool.domainFatigue`
- `STMPool.volatileFavorabilityByTag`
- 旧 prompt / schema / parser 中与这套旧链路绑定的字段和说明

清空之后，再按两条平行链路重新接回：

- `action`
  - 允许存在一个高频、小幅度的 `favorability_update` action
  - 它属于 `AgentActionResponse.actions`
- `reflection`
  - 允许存在一个低频、大幅度的 `favorability_update` reflection module
  - 它属于 `AgentReflectionResponse.modules`

### 8.3 迁移后的边界

清理完成后的正式边界：

- 高频、小幅度关系反馈：
  - `action -> favorability_update -> AgentPersona`
- 低频、大幅度关系结算：
  - `reflection / episode synthesis -> favorability_update -> AgentPersona`

需要直接从主线移除的旧组件：

- `LLMInteractionTask` 中旧的 `favorability_update` 执行链
- `ReflectionTask` 中旧的 `favorability_settlement`
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

它在工程地位上，应与：

- `AgentPersona.playerProfile`

非常接近。

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

持久化处理原则也应尽量与 `playerProfile` 保持相似：

- 都是低频文本字段
- 都由后台任务重写
- 都走统一的 authored runtime state 持久化链路

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

1. 按 action / reflection 两条平行链路分包
2. 将 `AgentResponse` 更名为 `AgentActionResponse`
3. 新增独立的 `AgentReflectionResponse`
4. 清除当前 action response 中所有 reflection 残留字段与旧好感度链路
5. 删除 `STM_SLICE_SUMMARY` 这类冗余命名，只保留正式结构化输入
6. 新增 `AgentState.currentSituationSummary`
7. 按本计划同步修改代码库中的 README、类注释、方法注释与其他设计文档，清除旧口径
8. 设计并接入 `episode_synthesis` 的 reflection module 输出结构
9. 让 `EpisodeSynthesisTask` 真正调用 LLM
10. 实现“按 selected segments 提取 detail tags”
11. 实现 synthesis 成功后的统一提交
12. 按新边界重新接回高频 action 好感度更新与低频 reflection 好感度更新
13. 直接删除 `ReflectionTask` / `ReflectionTrigger` / `STMPool` 旧关系账本职责，不留兼容层

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
