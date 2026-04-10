# Interaction Message Structure Plan

## 0. 命名冻结

为了彻底拆开“通信载体”和“持久化记录”，本计划从现在开始固定使用以下命名：

- `AgentMsg`
  - 含义：真正承载 LLM 通信内容的内部消息结构
  - 作用域：请求拼装、模型调用、provider 适配
- `AgentReply`
  - 含义：统一承载 LLM 原始返回结果的通信响应结构
  - 作用域：provider 返回、原始响应落地、调用状态追踪
- `InteractionRecord`
  - 含义：持久化存储到 STM / reflection / Studio 的交互记录结构
  - 作用域：记忆、回放、分析、反思

也就是说：

- `AgentMsg` 负责通信
- `AgentReply` 负责通信返回
- `InteractionRecord` 负责存储

这两者可以在内容上有关联，但不应再共用同一个结构。

截至 `2026-04-10`，当前重构目标已收敛为：

- 仅重构 `InteractionTurn` 这一层的“单轮打包”
- 不在这一轮同时抽象 `AgentMsg / AgentReply`
- 当前所有实际在用的 `userPrompt`，在源头语义上统一视为 `reaction cue`

也就是说，这一轮代码改造会把旧的 `userPrompt` 正式映射为：

- `source = AGENT`
- `kind = REACTION_CUE`

当前代码状态（`2026-04-10`）：

- `InteractionTurn` 已正式切到：
  - `triggerSource`
  - `userScreenshotBase64`
  - `messages`
  - `visibleContexts`
  - `contextExtras`
- `LLMInteractionTask` 已不再构造 `AgentOutputRecord`
- `StudioRuntimeRecentInteractionsFile` / GUI / reflection exporter / formatter
  已全部切到统一 `messages`
- 旧的 `AgentOutputRecord` 已删除

下面第 1 节保留的是“这轮重构开始前的问题分析”，
用于解释为什么当时需要做这次拆分。

当时实现中，一轮交互被拆成了几块并列字段：

- `triggerSource`
- `userPrompt`
- `userScreenshotBase64`
- `agentActions`
- `actionTypes`
- `visibleContexts`
- `contextExtras`

这意味着系统目前记录的是：

- “这轮因为什么触发”
- “这轮给模型的用户侧文本是什么”
- “这轮剑输出了哪些动作”

但没有正式记录：

- 这一轮到底包含了哪些 message
- message 之间的顺序
- 每条 message 是谁发出的
- 面向谁
- 是否玩家可见
- 它属于输入、说话、内心独白，还是环境播报

## 1. 当前结构的问题

### 1.1 `InteractionTurn` 不是 message timeline

当前 [InteractionTurn.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/InteractionTurn.java)
更像是“一轮调用的打包结果”，不是“交互消息序列”。

例如：

- `userPrompt` 只是一段单独字符串
- `agentActions` 是另一组记录
- `actionTypes` 又是摘要副产物

这些字段之间没有统一的 message 语义容器。

### 1.2 旧问题：`AgentOutputRecord` 只描述了输出类型，没有 message 主体关系

在本轮重构开始前，`AgentOutputRecord` 只有：
只有：

- `type`
- `text`
- `timestamp`

没有：

- `speakerSource`
- `targetSource`
- `visibility`
- `messageKind`

所以它现在更像“剑输出过一条文本”，而不是一条正式 message。

### 1.3 旧问题：反射链和 Studio 曾经都在消费旧分裂结构

这个问题在 `2026-04-10` 已经完成第一轮修复。

当时的依赖方包括：

- [LLMInteractionTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/action/task/LLMInteractionTask.java)
- [STMPool.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/STMPool.java)
- [DefaultReflectionMaterialExporter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/reflection/service/DefaultReflectionMaterialExporter.java)
- [DefaultReflectionPromptFormatter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/reflection/service/DefaultReflectionPromptFormatter.java)
- [StudioRuntimeRecentInteractionsFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioRuntimeRecentInteractionsFile.java)
- [AgentRecentInteractionScreen.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/gui/AgentRecentInteractionScreen.java)
- [AgentLastInnerThoughtScreen.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/gui/AgentLastInnerThoughtScreen.java)

因此这次不是加一个类就结束，而是一次 interaction 主线结构升级。

### 1.4 当前 LLM 层仍沿用旧的 `system / user / assistant` 消息模型

这套设计最早是为了依附旧的 LangChain 风格接口，
因此很多地方默认认为：

- 请求只能拆成 `system` + `user`
- 响应天然是 `assistant`
- trigger 层必须产出一个 `user message`

但现在我们的 LLM 层已经是自己维护的，
不再需要继续被这套旧抽象强绑定。

这意味着：

- 后续 message 结构重构，不应只改 `InteractionTurn`
- 还应顺手清理旧的 `user message` / `system prompt` 设计前提

## 1.5 当前旧消息模型的设计锚点清单

下面这些位置，当前都还明显带有旧 `system / user / assistant` 模型前提。

### A. LLM API 抽象层

- [LLMMessage.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/llm_service/LLMMessage.java)
  - `Role.SYSTEM / USER / ASSISTANT`
  - `system(...) / user(...) / assistant(...) / userWithImage(...)`
- [LLMChatClient.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/llm_service/LLMChatClient.java)
  - 当前接口直接收 `List<LLMMessage>`
- [OpenAiCompatibleChatClient.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/llm_service/OpenAiCompatibleChatClient.java)
  - 直接把 `LLMMessage.role` 序列化成 OpenAI chat completion 的 `messages[]`

### B. trigger / context 输入抽象层

- [IAgentTrigger.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/trigger/IAgentTrigger.java)
  - `getUserMessage()`
  - 文档上仍明确把它定义成 `UserMessage`
- [AgentSensor.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/AgentSensor.java)
  - 仍把 trigger 输入写进 `extras["user_message"]`
  - 同时也有 `extras["user_prompt"]`
- [PromptRegistry.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/dictionary/PromptRegistry.java)
  - `getTriggerUserMessage(...)`

### C. action 交互调用链

- [LLMInteractionTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/action/task/LLMInteractionTask.java)
  - `buildSystemPrompt(...)`
  - 从 `ctx.extras["user_prompt"]` 组装 user message
  - `messages.add(LLMMessage.system(...))`
  - `messages.add(LLMMessage.user(...))`
- [AgentLogger.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/logging/AgentLogger.java)
  - 日志字段也 still 用 `UserMessage / System Prompt`

### D. interaction 存储层

- [InteractionTurn.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/InteractionTurn.java)
  - 当前已切到 `messages`
- [StudioRuntimeRecentInteractionsFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioRuntimeRecentInteractionsFile.java)
  - 当前已切到 `messages`
- [AgentRecentInteractionScreen.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/gui/AgentRecentInteractionScreen.java)
- [AgentLastInnerThoughtScreen.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/gui/AgentLastInnerThoughtScreen.java)
  - 当前展示逻辑已改为依赖 `messages`

### E. reflection 调用链

- [EpisodeSynthesisReflectionTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/reflection/task/EpisodeSynthesisReflectionTask.java)
  - `buildSystemPrompt(...)`
  - `buildUserPrompt()`
  - 仍以 `LLMMessage.system(...) + LLMMessage.user(...)` 方式发起调用
- [ReflectionInteraction.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/reflection/material/ReflectionInteraction.java)
  - 当前已切到 `messages`
- [DefaultReflectionPromptFormatter.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/reflection/service/DefaultReflectionPromptFormatter.java)
  - 当前已改为渲染 `interaction.messages()`

### F. 文档与开发口径层

- [README.md](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/README.md)
  - 多处仍使用 `user_prompt / UserMessage / buildSystemPrompt` 口径
- [EPISODE_CURRENT_STATE.md](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/memory/episode/EPISODE_CURRENT_STATE.md)
  - 仍把 interaction 描述成 `userPrompt + outputs`

## 1.6 这轮 message 重构需要顺手优化的旧设计

基于上面的锚点，后续除了做 `InteractionMessage` 外，还应一起优化这些旧设计：

1. trigger 层不要再强制要求产出“user message”
2. LLM 请求层不要再把“必须存在一个 user message”当成固定协议
3. interaction 存储层不要再把 `userPrompt` 作为正式字段
4. reflection 调用层未来也应摆脱固定 `system + user` 二元结构

注意：

- 这不意味着下一步立刻把 OpenAI API 的 `role` 彻底废掉
- 而是说明我们内部抽象不该再直接等同于 `system / user / assistant`
- 内部应先升级成更灵活的 message / request part 模型，再由 provider 适配层决定如何序列化

## 2. 设计目标

这次重构的目标不是“给现在的字段再套一层壳”，而是把通信层和记忆层正式拆开。

重构后应满足：

1. `AgentMsg` 承载真正进入 LLM 请求的通信消息
2. `AgentReply` 只统一原始返回 transport，不统一业务解析结果
3. `InteractionRecord` 只存有交互语义和记忆价值的内容
4. 系统提示词、schema、module instruction 不再污染持久化交互记录
5. reflection / studio / UI 不再依赖 `userPrompt + agentActions` 这种分裂结构
6. 不保留兼容层，不维护旧 schema

## 2.2 当前阶段的收窄目标

上面的完整设计是长期方向，
但当前这一轮重构的核心目标需要明确收窄：

- 不是立即重做整条 LLM request / response 抽象
- 不是一次性重写全部 prompt 调用结构
- 当前真正要解决的核心问题，只是给 interaction message / record 增加合适的 `source` 语义

也就是说，本轮最重要的目标只有一条：

- 明确区分一条交互消息是谁发出的
  - 是哪个玩家
  - 还是剑自己
  - 还是环境播报

因此这轮设计的判断标准应是：

- 能否把“玩家行为 / 玩家输入 / 剑输出 / 剑内心活动 / 环境输入”在 interaction 层正式区分开

而不是：

- 是否已经拥有完美统一的 LLM transport 抽象

## 2.3 当前阶段的执行策略

基于这个收窄目标，当前阶段固定采用以下策略：

1. `InteractionRecord` 设计优先
   - 先把 interaction 持久化层的主体语义立住
2. `InteractionMessage.type` 优先
   - 先通过消息类型把“谁发出的”语义编码清楚
3. `AgentMsg / AgentReply` 暂时只保留设计，不要求立即落地
   - 它们是后续通信层优化方向
   - 不是本轮实现阻塞项

换句话说：

- 本轮重构是一次 interaction source 语义升级
- 不是一次完整的 LLM 通信框架重写

## 2.1 顶层关系决策

这里固定一条重要设计决策：

- `InteractionRecord` 不再被定义为 `AgentMsg` 或 `AgentReply` 的简单投影结果
- 它与 `AgentMsg` / `AgentReply` 是平行结构

也就是说，未来一轮交互的真实关系应是：

- `AgentMsg`
  - 描述“我们如何向 LLM 发起这次通信”
- `AgentReply`
  - 描述“LLM 原始返回了什么”
- `InteractionRecord`
  - 描述“这轮交互中有哪些值得持久化的消息与绑定上下文”

因此：

- `InteractionRecord` 可以参考 `AgentMsg` 和 `AgentReply`
- 但不应被实现成“从其中某一个机械转换出来的结构”
- 它应由业务层按语义筛选和重组生成

## 3. 推荐的新模型

### 3.1 通信层：`AgentMsg`

`AgentMsg` 是内部通信消息，不是持久化记录。

它的职责是：

- 承载所有发给 LLM 的信息
- 统一替代当前零散的 `system prompt / user prompt / image / schema 指令` 拼装方式
- 在内部形成一条更灵活的请求消息序列
- 再由 provider 适配层决定如何映射到 OpenAI 之类接口的 `role/messages`

关键原则：

- 所有发给 LLM 的信息，都应先包装成 `AgentMsg`
- 但不是所有 `AgentMsg` 都会进入 `InteractionRecord`

### 3.1.3 `AgentMsg` 与 `InteractionRecord` 的关系

`AgentMsg` 和 `InteractionRecord` 是“相关但平行”的关系。

不要把它理解成：

- `AgentMsg` 持久化后就变成 `InteractionRecord`

更准确的关系是：

- `AgentMsg` 提供通信输入侧的候选语义材料
- 业务层从中挑出真正属于交互消息的部分
- 再结合 LLM 输出和运行时上下文，生成 `InteractionRecord`

因此，`recordEligible` 的作用是：

- 提供“是否允许进入持久化筛选”的标记

而不是：

- 保证这条 `AgentMsg` 必然一比一变成 `InteractionRecord`

### 3.1.1 `AgentMsg` 字段分区

为了避免后续把 message 结构越做越散，建议先把字段按职责分区。

#### A. 路由分区

这一组字段回答“这条通信消息属于什么类型，以及是否应该落进持久化记录”。

- `kind`
  - 必填
  - 类型：`AgentMsgKind`
  - 含义：消息类别
- `recordEligible`
  - 必填
  - 类型：`boolean`
  - 含义：这条 `AgentMsg` 是否允许被抽取进 `InteractionRecord`
- `timestamp`
  - 必填
  - 类型：`long`
  - 含义：消息生成时间

这一组是 `AgentMsg` 的核心路由语义。

#### B. 载荷分区

这一组字段回答“这条消息本身是什么”。

- `parts`
  - 必填
  - 类型：`List<AgentMsgPart>`
  - 含义：消息承载的内容片段

建议第一版就支持至少两类 part：

- `TextPart`
- `ImagePart`

这样可以直接接住：

- 文本提示
- 截图输入

而不需要再为“带图 user msg”单独做特殊分支。

#### C. 语义主体分区

这一组字段回答“这条消息在语义上是谁发出的、面向谁”。

- `source`
  - 可空
  - 类型：`TaggedContextSource`
  - 含义：消息的语义发出主体
- `target`
  - 可空
  - 类型：`TaggedContextSource`
  - 含义：消息的语义目标主体

注意：

- 对于系统级 `AgentMsg`，这组字段可以为空
- 对于真正的交互性 `AgentMsg`，这组字段应尽量补齐

例如：

- system header
  - `source = null`
  - `target = null`
- 玩家聊天
  - `source = PLAYER`
  - `target = AGENT`
- 剑说话
  - `source = AGENT`
  - `target = PLAYER`

### 3.1.1.1 内容分类边界

这里需要再加一条明确边界：

- 不是所有进入一次 LLM 调用的文本，都应该进入持久化交互记录

`AgentMsg` 可以承载通信内容，
但 `InteractionRecord` 只应记录其中真正有交互语义的部分。

这条边界在结构上建议直接由 `recordEligible` 表达，
而不是只靠 kind 推断。

`InteractionRecord` 应记录的是：

- 这一轮交互中，真正属于“交互事件”的文本
- 能被视为输入、输出、内心活动或环境播报的文本

而不应记录：

- system prompt
- cognition rule
- output schema / output contract
- module instruction
- prompt formatter 生成的结构化约束文本
- 仅用于约束模型行为的系统级提示词

也就是说，`InteractionRecord` 记录的是“交互内容”，不是“完整提示词拼装产物”。

### 3.1.1.2 建议纳入 `InteractionRecord` 的内容

建议纳入的典型内容：

- 玩家聊天输入
- trigger 生成的 user-facing / event-facing 文本指令
- 环境播报型输入
- 剑说出口的话
- 剑的内心独白

这些内容具备共同特征：

- 它们属于一轮具体交互
- 它们对记忆、reflection、Studio 回放有直接语义价值

### 3.1.1.3 不应纳入 `InteractionRecord` 的内容

以下内容建议明确排除：

- `buildSystemPrompt()` 产生的完整 system prompt
- 共享 header
- cognition rule
- state snapshot 注入文本
- persona 注入文本
- output rules / schema 指令
- reflection task 的 module instruction

这些内容的问题是：

- 它们是“模型约束环境”
- 不是“交互消息本身”
- 如果写进 interaction message，会严重污染 STM / reflection 对真实交互内容的理解

### 3.1.1.4 第一版建议的内容抽取原则

第一版建议直接采用一条硬规则：

- `InteractionRecord` 只记录“会被人或 agent 当作一条消息理解的文本”

换句话说：

- 面向模型的系统级框架提示词，不记录
- 面向这轮交互语义本身的输入 / 输出，记录

这样后面即使 prompt 架构继续变化，
`InteractionRecord` 这一层也不会反复被 system prompt 设计牵着走。

#### D. 可见性分区

这一组字段回答“谁能看到这条消息”。

- `playerVisible`
  - 必填
  - 类型：`boolean`
  - 含义：玩家是否能直接看到这条消息

当前阶段先不要把可见性做得太复杂。
`playerVisible` 足够区分：

- `speech`
- `inner_monologue`
- 环境播报 / 输入提示

如果以后真的需要更细的受众控制，再考虑扩展为 audience / visibility policy。

注意：

- 对系统级 `AgentMsg`，`playerVisible` 仅作为默认 `false` 的通信标记存在
- 对会进入 `InteractionRecord` 的交互性消息，它才真正有业务意义

#### E. 扩展元数据分区

这一组字段不是第一阶段必须完成，但要预留位置。

- `meta`
  - 可空
  - 类型建议为 `Map<String, String>` 或一个小型值对象
  - 用途：
    - 截图占位
    - 触发器补充信息
    - 将来的 structured notes

建议：

- 第一版可以不落 `meta`
- 但文档上应明确它属于扩展区，而不是核心区

### 3.1.2 第一版最小字段集

如果按“先把正式结构立住，再慢慢扩展”的原则，
第一版 `AgentMsg` 最小字段集建议固定为：

- `timestamp`
- `kind`
- `recordEligible`
- `parts`
- `playerVisible`
- `source`
- `target`

第一版 `InteractionRecord` 最小字段集建议固定为：

- `timestamp`
- `messages`
- `visibleContexts`
- `contextExtras`
- `triggerSource`
- `userScreenshotBase64`

也就是说：

- `meta` 暂不进第一版
- 不要一开始做过多可选字段
- 先让整条链完成“通信结构”和“持久化结构”的拆分

### 3.1.4 当前阶段对 `AgentMsg` 的处理

虽然长期设计里保留 `AgentMsg`，
但当前实现阶段先不推进它的正式落地。

也就是说：

- `AgentMsg` 当前只保留为通信层设计方向
- 本轮不以 `AgentMsg` 为实现中心
- 本轮实现中心回到 `InteractionRecord`

### 3.2 新增 `AgentMsgKind`

推荐至少包含：

- `SYSTEM_IDENTITY`
- `SYSTEM_RULE`
- `EVENT_INSTRUCTION`
- `STATE_SNAPSHOT`
- `PERSONA_CONTEXT`
- `MEMORY_CONTEXT`
- `TAGGED_CONTEXT`
- `SUPPLEMENTARY_CONTEXT`
- `PLAYER_INPUT`
- `ENVIRONMENT_INPUT`
- `AGENT_SPEECH`
- `AGENT_INNER_MONOLOGUE`
- `OUTPUT_SCHEMA`

如果后续需要，还可继续扩展：

- `TASK_CONTRACT`
- `EXAMPLE`
- `REFLECTION_MATERIAL`

建议的第一版默认 `recordEligible` 规则：

- `SYSTEM_IDENTITY / SYSTEM_RULE / STATE_SNAPSHOT / PERSONA_CONTEXT / MEMORY_CONTEXT / TAGGED_CONTEXT / SUPPLEMENTARY_CONTEXT / OUTPUT_SCHEMA / TASK_CONTRACT / EXAMPLE`
  - `recordEligible = false`
- `PLAYER_INPUT / ENVIRONMENT_INPUT / AGENT_SPEECH / AGENT_INNER_MONOLOGUE / EVENT_INSTRUCTION`
  - 默认 `recordEligible = true`

这里之所以把 `EVENT_INSTRUCTION` 放进默认可持久化，是因为它往往代表“这一轮到底发生了什么事”。
如果后面发现部分 instruction 其实是纯系统约束，再细分也不迟。

### 3.3 存储层：`InteractionRecord`

`InteractionRecord` 是进入 STM / reflection / Studio 的持久化结构。

重构后建议把当前 `InteractionTurn` 重命名或重写为 `InteractionRecord`，
并只保留轮级字段和真正有记忆价值的消息列表。

### 3.3.1 `InteractionRecord` 的定位

`InteractionRecord` 不是：

- 完整 prompt 存档
- 一次 LLM 请求对象
- 一次 LLM 响应对象

`InteractionRecord` 是：

- 一轮交互的“可记忆语义记录”
- 供 STM / reflection / Studio / GUI 回放使用的稳定结构

因此它的设计目标应优先保证：

- 语义清晰
- 持久化稳定
- 不被 prompt 架构调整频繁牵动

### 3.3.2 `InteractionRecord` 轮级字段

第一版建议字段：

- `recordId`
  - 可选，但推荐尽早加入
  - 用于 Studio / debug / 后续索引
- `timestamp`
  - 这一轮交互记录的主时间戳
- `triggerSource`
  - 触发来源
- `userScreenshotBase64`
  - 可选视觉输入快照
- `visibleContexts`
  - 本轮真正进入模型视野、并且允许进入记忆链的上下文条目
- `contextExtras`
  - 本轮绑定的补充上下文快照
- `messages`
  - 本轮真正需要被记住的消息列表

其中：

- `visibleContexts + contextExtras`
  - 回答“这轮交互发生时，周围是什么状态”
- `messages`
  - 回答“这轮交互里，真正发生了哪些可记忆消息”

### 3.3.3 `InteractionMessage`

建议给 `InteractionRecord.messages` 单独配一个正式类型：

- `InteractionMessage`

不要直接复用 `AgentMsg`，原因是：

- `AgentMsg` 是通信结构
- `InteractionMessage` 是持久化语义结构

即使两者字段相似，也不应混用。

### 3.3.4 `InteractionMessage` 字段

当前阶段建议采用极简字段设计：

- `source`
- `kind`
- `text`

字段语义：

- `source`
  - 这条消息在 interaction 语义上属于谁
- `kind`
  - 这条消息的表达形态
- `text`
  - 这条消息的正文

截至 `2026-04-10`，当前代码已经按这个方向落地：

- 不单独存 `target`
- 不单独存 `playerVisible`
- `source` 已正式进入 `InteractionMessage`
- `target / playerVisible` 仍留作后续扩展

### 3.3.5 `InteractionMessage.source`

当前阶段 `source` 已与统一 source 体系对齐，当前实际使用 / 预留为：

- `PLAYER`
  - 玩家发出的消息
- `AGENT`
  - 剑自己的说话 / 内心独白 / 反应线索
- `ENVIRONMENT`
  - 当前 interaction message 中尚未正式使用，先作为统一 source 体系预留

这组 source 的设计原则是：

- 它们优先表达“这条消息属于谁”
- 它们与 tagged context 的 source 体系保持一致
### 3.3.6 `InteractionMessage.kind`

当前阶段 `kind` 只保留最小集合：

- `TEXT`
- `INNER_MONOLOGUE`
- `REACTION_CUE`

这样拆开的原则是：

- `source` 表达“是谁”
- `kind` 表达“怎么说”
- 不再让单个 enum 同时编码 actor + message shape

### 3.3.7 `REACTION_CUE` 的语义

这是当前阶段需要单独钉住的一类。

它的特点是：

- 从系统机制层面看，它可能是派发给 agent 的反应提示
- 但从角色扮演语义看，它不应被视作环境客观事实
- 它也不应被视作玩家输入
- 它更接近“agent 自发反应的剧本钩子”

因此它应被记录为：

- 一条 `source = AGENT, kind = REACTION_CUE` 的 interaction message

而不是：

- environment 输入
- system prompt 文本
- 玩家消息

### 3.3.8 当前阶段的迁移关系

如果按当前旧结构迁移，第一版建议先这样映射：

当前实际映射为：

- 旧 `userPrompt / eventInstruction`
  - `source = AGENT`
  - `kind = REACTION_CUE`
- speech 输出
  - `source = AGENT`
  - `kind = TEXT`
- inner monologue 输出
  - `source = AGENT`
  - `kind = INNER_MONOLOGUE`
- player message（未来对话链）
  - `source = PLAYER`
  - `kind = TEXT`

### 3.3.9 `InteractionRecord` 的生成原则

`InteractionRecord` 不应该机械地从某一个结构直接转换出来。

推荐生成路径：

- 输入侧：
  - 从 trigger / context / 运行时状态中提取“真正属于本轮交互的输入消息”
- 输出侧：
  - 从解析后的 LLM 结果中提取“真正属于本轮交互的输出消息”
- 然后：
  - 统一整理成 `InteractionRecordMessage` 列表
  - 再和 `visibleContexts / contextExtras / screenshot` 一起组装为 `InteractionRecord`

这意味着：

- `InteractionRecord` 不直接等于 `AgentMsg`
- `InteractionRecord` 也不直接等于 `AgentReply`
- 它是业务层做完语义筛选后的产物

建议直接删除：

- `userPrompt`
- `agentActions`
- `actionTypes`

原因：

- 这三个字段本质上都应该被 `messages` 取代
- `actionTypes` 只是摘要，不应再作为正式存储字段

### 3.4 响应层：`AgentReply`

`AgentReply` 是统一的通信返回结构，
但不统一不同业务模块的解析结果。

建议字段：

- `timestamp`
- `rawText`
- `finishReason`
- `provider`
- `model`
- `usage`
- `parts`

它回答的是：

- 模型原始回了什么
- 这次调用的 transport 状态如何

它不回答的是：

- 这段返回在 action 链里怎么解析
- 这段返回在 reflection 链里怎么解析

因此，后续解析层应保持分散：

- action 链解析成自己的结果结构
- reflection 链解析成自己的结果结构
- 不建立全局统一的 `parsedPayload`

固定原则：

- 统一返回层只统一 transport
- 不统一 business parse

## 4. source 语义建议

这次 message 结构要和 source 重构保持一致。

### 4.1 输入 message

典型规则：

- 玩家聊天 / 玩家显式输入
  - `source = PLAYER`
  - `target = AGENT`
- 环境事件播报
  - `source = ENVIRONMENT`
  - `target = AGENT`

### 4.2 剑输出 message

典型规则：

- `SPEECH`
  - `source = AGENT`
  - `target = PLAYER`
  - `playerVisible = true`
- `INNER_MONOLOGUE`
  - `source = AGENT`
  - `target = AGENT` 或 `null`
  - `playerVisible = false`

## 5. 对现有链路的影响

### 5.1 `LLMInteractionTask`

当前由它负责：

- 收集 `userPrompt`
- 解析 LLM 输出
- 构建 `AgentOutputRecord`
- 组装 `InteractionTurn`

重构后应改成：

- 先构建一组 `AgentMsg`
- 接收一个 `AgentReply`
- 再由业务层结合 `AgentMsg + AgentReply + runtime context` 生成 `InteractionRecord`
- 不再中间落 `AgentOutputRecord`

### 5.2 `STMPool`

`STMPool` 仍然继续存交互记录，
但应存 `InteractionRecord`，而不是通信态的 `AgentMsg`。

### 5.3 reflection exporter

当前 exporter 会把：

- `turn.getUserPrompt()`
- `turn.getAgentActions()`

拼成 `ReflectionInteraction`。

重构后建议：

- `ReflectionInteraction` 直接持有 `InteractionRecord.messages`
- formatter 再按 message kind 渲染

### 5.4 Studio / GUI

当前 recent interaction / last inner thought 都是从旧字段拼展示。

重构后建议：

- 统一从 `messages` 读取
- recent interaction screen 可筛 `SPEECH`
- inner thought screen 可筛 `INNER_MONOLOGUE`

## 6. 无兼容层原则

这次明确不做兼容。

固定原则：

1. 不保留 `userPrompt + agentActions + actionTypes` 的双写期
2. 不保留旧 runtime interaction schema 的读取兼容
3. 直接升级 Studio runtime recent interactions 文件结构
4. 旧 testdata / preview fixture 直接重建

## 7. 推荐实施顺序

### Phase 1. 立通信与存储数据结构

1. 新增 `AgentMsgKind`
2. 新增 `AgentMsg`
3. 新增或重写 `InteractionRecord`

完成标志：

- 通信层和存储层正式分离

### Phase 2. 替换 LLM 交互写入链

1. `LLMInteractionTask` 不再构造 `AgentOutputRecord`
2. 直接构造一轮 `AgentMsg` 列表
3. 接收统一的 `AgentReply`
4. 从 `AgentMsg + AgentReply + runtime context` 生成新的 `InteractionRecord`

完成标志：

- 运行时交互链完成“请求消息 / 原始响应 / 持久化记录”的三层分流

### Phase 3. 替换 reflection 消费链

1. exporter 改读 `InteractionRecord.messages`
2. `ReflectionInteraction` 改成 record-first
3. formatter 改按 `message kind` 渲染

完成标志：

- reflection 不再依赖 `userPrompt + outputs`

### Phase 4. 替换 Studio / GUI / 文档

1. recent interactions runtime file 改 schema
2. GUI 改读 `messages`
3. README / 设计文档同步

完成标志：

- interaction 主线不再有旧口径残留

## 8. 当前建议

如果按干净重构的标准推进，
下一步不应该继续给 `AgentOutputRecord` 打补丁，
而应该直接决定：

- 是否正式以 `AgentMsg + InteractionRecord` 取代当前的 `userPrompt + agentActions`

我的建议是：

- 直接替代
- 不保留兼容字段
- 让 interaction 这一层和当前 source 重构一起收口
