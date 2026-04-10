# Tagged Context Source Refactor Plan

截至 `2026-04-10`，当前系统仍默认存在一条旧前提：

- 大多数 `TaggedContextEntry` 中涉及的行动、状态、经历，都可被理解为玩家正在经历的事

这条前提曾经是合理的，因为剑没有真正的自主行动能力。

但后续我们会给剑补出一部分简单的自主行动能力。在那之后：

- context 中出现的事实，不再天然都属于玩家
- prompt / reflection / memory 也不能继续默认把所有行动都解释成“玩家在做”

因此，本次重构目标是：

- 给所有 `TaggedContextEntry` 增加正式 `source` 字段
- 让 source 成为贯穿 context / STM / segment / reflection / prompt 的稳定字段
- 清理所有仍写死“context 中所有行动都是玩家行动”的旧逻辑
- 整体保持无兼容层，旧 runtime / preview / fixture 直接废弃并重建

## 当前进度 (`2026-04-10`)

### 已完成

- 已新增：
  - `TaggedContextSourceType`
  - `TaggedContextSource`
- 已新增：
  - `PlayerIdentityRegistry`
- `TaggedContextEntry` 已正式携带 `source`
- `TaggedContextEntry.builder()` 已改为必须显式传入 `source`
- `CollectedContextSnapshot.source` 已改名为 `snapshotSource`
- 主要 collectors / runtime desire 注入点已经全部显式赋 source：
  - `PLAYER`
  - `AGENT`
  - `ENVIRONMENT`
- action prompt / reflection prompt formatter 已开始把 source 渲染进正式提示词
- Studio runtime 调试文件已开始写出 visible context 的 source 信息
- interaction 单轮打包已切到统一 `InteractionMessage` 结构
- `InteractionTurn` / Studio recent interactions / reflection interaction 已不再依赖
  `AgentOutputRecord`

### 当前仍待完成

- 新增 producer 时，需要继续遵守“显式 source + 文案不偷带默认主体”的约束
- Studio / authored / fixture 若后续新增 context 样例，也要同步保持 source-aware
- interaction 的 actor / target source 仍未进入正式数据模型
- 相关计划文档仍需继续同步新的 interaction message 口径

### producers 全量审计结果 (`2026-04-09`)

本轮已经完成一次全量审计，结论如下：

- `TaggedContextEntry` 的正式生产点已全部收口到显式 `builder(source)`
- 没有发现绕过 `source` 的隐藏构造路径
- 当前 producer 主链分成三类：

1. collector 主链
   - `BaseContextCollector`
   - `PlayerEnvironmentCollector`
   - `RecentActionCollector`
   - `LookingAtCollector`
   - `NearbyEntitiesCollector`
   - `NearbyPoiCollector`
   - `NearbyStructureCollector`
   - `SurroundingBlocksCollector`
   - 结论：都已经显式传入 `PLAYER / ENVIRONMENT` source

2. 非 collector 运行时注入
   - `AgentContextCollectionRunner.injectRuntimeDesire(...)`
   - 结论：当前已固定使用 `TaggedContextSource.agentSelf()`，语义正确

3. 复制 / 提权路径
   - `TaggedContextEntry.toBuilder()`
   - `SubconsciousBooster`
   - 结论：复制时保留原 source，没有发生 source 丢失或重置

此外，以下内容也已同步对齐：

- preview mock 已包含 `player / agent / environment` 三类 source
- prompt / reflection formatter 已能正式渲染 source
- collector 文案、README 示例、默认提示词中的旧默认玩家主体语义已完成一轮清理

因此，本阶段的结论是：

- `producers 全量审计` 已完成
- 当前 source 重构已不再卡在“还有哪个 producer 没接 source”
- 后续剩余工作重点不再是补 producer，而是消费端继续利用 source 做更细的语义区分

## 1. 当前实现与核心问题

### 1.1 初始问题：`TaggedContextEntry` 没有 source

当前 [TaggedContextEntry.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/tag/TaggedContextEntry.java)
只携带：

- `focusTags`
- `detailTags`
- `description`
- `baseImportance`
- `timestamp`
- `associativeOnly`
- `crosshairTarget`
- `memoryEligible`

没有“这条事实是谁的/谁做的”这一层正式语义。

### 1.2 当前还有另一层旧 `source`，但语义不同

[CollectedContextSnapshot.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/CollectedContextSnapshot.java)
现在已经有一个 `source` 字段，但它表示的是：

- 这次快照来自哪个 trigger / collection 场景

它不是：

- 这条 `TaggedContextEntry` 属于谁

因此，这次重构必须先把这两个语义拆开，不然代码会变得极其混乱。

### 1.3 初始问题：多条链路仍默认“context = 玩家正在经历”

这条旧前提现在散落在多个层面：

1. collectors 产出的 description 本身很多都写成“玩家正在……”
2. prompt / cognition rule 直接告诉模型“context 里的行动与状态都属于玩家”
3. reflection / action task 目前没有 source-aware 解释机制

典型位置：

- [PromptRegistry.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/dictionary/PromptRegistry.java)
- [LLMInteractionTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/action/task/LLMInteractionTask.java)
- [EpisodeSynthesisReflectionTask.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/reflection/task/EpisodeSynthesisReflectionTask.java)

所以这次不是单点改数据类，而是一次跨 context / prompt / reflection 的主线清理。

## 2. 冻结的设计决策

### 2.1 `TaggedContextEntry` 的 `source` 必须是结构化对象，不是裸字符串

虽然字段名可以叫 `source`，但类型不能只是 `"player"` / `"agent"` / `"environment"` 这样的字符串。

正式方向固定为：

- `TaggedContextEntry.source`
  - 类型应为一个独立值对象，例如 `TaggedContextSource`

推荐结构：

- `type`
  - `PLAYER`
  - `AGENT`
  - `ENVIRONMENT`
- `sourceId`
  - 对 `PLAYER` 必填
  - 对 `AGENT` 固定为稳定 id（例如 `self`）
  - 对 `ENVIRONMENT` 可为空或固定为 `environment`

原因：

- 未来不只是要知道“是不是玩家”
- 还要能区分“哪个玩家”
- prompt / studio / debug / future action ownership 都会依赖稳定身份

### 2.2 `source` 表示“事实的归属主体 / 行动主体”

这里必须先钉死语义，不然后面 collectors 会乱。

`TaggedContextEntry.source` 表示的是：

- 这条 context 主要在描述谁
- 或这条事件主要是谁做的

不是：

- 谁观察到了它
- 哪个 collector 采到了它
- 哪个 task 在使用它

### 2.3 三种 source 的正式语义

#### `PLAYER`

用于：

- 玩家的动作
- 玩家的身体状态
- 玩家的持有物
- 玩家的主观选择 / 位置 / 行进

示例：

- 玩家正在挥剑攻击
- 玩家手里握着铁镐
- 玩家经验等级为 12

#### `AGENT`

用于：

- 剑自己的主观状态
- 剑自己的内部驱动力
- 剑未来的自主动作
- 剑自己的自发表达或干预行为

示例：

- 剑当前强烈想阻止玩家继续冒进
- 剑主动释放了一个微弱的提示
- 剑短暂地自行漂浮或调整位置

#### `ENVIRONMENT`

用于：

- 与具体主体无关的世界事实
- 环境、结构、天气、附近实体、光照等客观状态

示例：

- 附近洞穴深处传来骷髅脚步声
- 当前维度是主世界
- 前方有废弃矿道木梁

### 2.4 `CollectedContextSnapshot.source` 必须改名

为了避免和 `TaggedContextEntry.source` 混淆，
[CollectedContextSnapshot.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/CollectedContextSnapshot.java)
当前的 `source` 必须在这次重构中一起改名。

推荐方向：

- `snapshotSource`
或
- `captureSource`

它表示的是：

- 这次上下文快照来自哪个 trigger / collection 场景

而不再与 entry-level source 混名。

### 2.5 玩家名字不能直接写死在 `source` 里，必须通过登记表维护

用户这次已经明确提出：

- 玩家 source 需要区分 player id
- 需要一个 id -> 玩家名字的可维护登记字典

因此正式方向固定为：

- 新增一个 `PlayerIdentityRegistry`（命名可再细化）
- 作用类似 `LocationRegistry`
- 负责维护：
  - `playerId`
  - `displayName`
  - 可选别名 / 备注

`TaggedContextEntry.source` 自己只存稳定 id，不直接把“显示名”当身份真源。

## 3. 本次重构的影响面

### 3.1 数据模型层

必须修改：

- `TaggedContextEntry`
- `CollectedContextSnapshot`
- `InteractionTurn`
- `ReflectionContextSnapshot`
- 所有直接持有 `List<TaggedContextEntry>` 的结构

### 3.2 producers

所有生产 `TaggedContextEntry` 的地方都必须显式赋 source。

当前至少包括：

- [AgentContextCollectionRunner.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/AgentContextCollectionRunner.java)
- [BaseContextCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/BaseContextCollector.java)
- [LookingAtCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/LookingAtCollector.java)
- [NearbyEntitiesCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/NearbyEntitiesCollector.java)
- [NearbyPoiCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/NearbyPoiCollector.java)
- [NearbyStructureCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/NearbyStructureCollector.java)
- [PlayerEnvironmentCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/PlayerEnvironmentCollector.java)
- [RecentActionCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/RecentActionCollector.java)
- [SurroundingBlocksCollector.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/context/collector/SurroundingBlocksCollector.java)

### 3.3 STM / segment / reflection 主线

这条链当前会完整地携带 `TaggedContextEntry`：

- `AgentContext` -> `CollectedContextSnapshot`
- `STMPool`
- `InteractionTurn.visibleContexts`
- `FixedActionSegmentContext`
- `ReflectionRawMaterial / ReflectionMaterial`
- `DefaultReflectionPromptFormatter`

因此这条主线需要做的是：

- 让新 source 字段原样贯穿
- 不允许中间层丢失 source

### 3.4 prompt / cognition rule

这次必须一起改：

- action 侧 prompt
- reflection 侧 prompt
- dictionary 中的 cognition rule

因为当前 [PromptRegistry.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/agent/dictionary/PromptRegistry.java)
仍然默认：

- “所有环境交互、移动、受伤、持有物品等物理状态，都是玩家在经历”

这条规则在自主行动引入后不再成立。

### 3.5 Studio / debug / runtime 文件

至少会影响：

- [StudioRuntimeRecentInteractionsFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioRuntimeRecentInteractionsFile.java)
- [StudioRuntimeRecallTraceFile.java](/Users/jackluo/Documents/self_forge_mod/forge-1.20.1-47.4.10-mdk/src/main/java/dreamdust/talking_weapon/basic_talking_weapon/studio/StudioRuntimeRecallTraceFile.java)

这些文件现在会写出 `TaggedContextEntry` 的部分字段，
但还没有 source 维度。

## 4. 无兼容层的实施原则

这次明确不做兼容。

固定原则：

1. 新字段引入后，不保留“缺省无 source 也能跑”的旧路径
2. 所有 `TaggedContextEntry` builder 调用点都必须显式指定 source
3. 旧 runtime JSON / studio debug 文件 / preview fixtures 直接废弃并重建
4. 不做旧 schema 自动迁移，不做 fallback 填默认玩家 source

也就是说，这次应该利用编译错误和 schema 破坏，强迫整条链一起升级。

## 5. 推荐重构顺序

### Phase 1. 基础类型重构

目标：

- 先把正式数据结构立住

步骤：

1. 新增 `TaggedContextSourceType`
2. 新增 `TaggedContextSource`
3. 给 `TaggedContextEntry` 增加 `source`
4. builder 强制要求设置 source
5. `toBuilder()` 保持 source 一并复制
6. `toString()` 输出 source 摘要
7. 将 `CollectedContextSnapshot.source` 改名为 `snapshotSource`

完成标志：

- 所有旧 builder 调用点开始编译报错，迫使后续逐个审计

### Phase 2. 玩家身份登记表

目标：

- 引入稳定 player id -> display name 的正式字典

推荐结构：

- 新增 `PlayerIdentityRegistry`
- 配置文件放在 studio workspace
- 维护：
  - `playerId`
  - `displayName`
  - 可选 alias / notes

最低要求：

- 当前玩家至少能用稳定 UUID 进入字典
- prompt / studio 侧可以从 id 解析出可读名字

### Phase 3. producers 全量审计

目标：

- 所有 entry 生产点都显式赋 source

建议分类规则：

- `RecentActionCollector`
  - `PLAYER`
- `AgentContextCollectionRunner.injectRuntimeDesire(...)`
  - `AGENT`
- 纯环境与地形事实
  - `ENVIRONMENT`
- 玩家状态 / 持有物 / 等级 / 所处位置
  - `PLAYER`

注意：

- 这一步不要偷懒搞默认值
- 每个 collector 都应做一次语义审计

### Phase 4. STM / segment / reflection 贯通

目标：

- 确保 source 不在中途丢失

涉及：

- `CollectedContextSnapshot`
- `STMPool`
- `InteractionTurn`
- `FixedActionSegmentContext`
- `DefaultReflectionMaterialExporter`
- `DefaultReflectionPromptFormatter`

这一步大多是“让已有结构透明传递新字段”，
不一定需要大改业务逻辑。

当前补充判断（`2026-04-10`）：

- `InteractionTurn.visibleContexts` 中的 `TaggedContextEntry.source` 已能正确保留并贯穿到 reflection exporter / formatter
- `InteractionTurn.contextExtras` 不是 tagged entry，本阶段不要求补 source
- `InteractionTurn` 已经切到统一 `messages` 列表，旧的 `AgentOutputRecord` 已删除
- `InteractionMessage` 现在已正式携带统一 `source`
  - `PLAYER`
  - `AGENT`
  - `ENVIRONMENT`（预留）
- `InteractionMessage.kind` 现在承载消息形态
  - `TEXT`
  - `INNER_MONOLOGUE`
  - `REACTION_CUE`
- 但 `InteractionMessage` / `ReflectionInteraction` 目前仍未形式化表达：
  - 这次交互是谁发起的
  - 这次输出面向谁

也就是说：

- `context source` 已贯通
- `interaction actor / target source` 仍主要依赖结构语义约定，而不是正式字段

因此当前 phase 4 的状态应理解为：

- `context source` 贯通已完成
- `interaction message source` 贯通已完成
- interaction 的 initiator / target 结构化仍未完成

这项不阻塞本轮 source 重构收尾，但应作为 phase 4 的后续扩展项单独记录。

### Phase 5. prompt 语义清理

目标：

- 清理旧的“所有行动都属于玩家”认知前提

必须修改：

- `PromptRegistry.getSystemCognitionRule()`
- action 侧 `LLMInteractionTask`
- reflection 侧 shared header / task prompt

新规则应表达：

- 玩家 source 的行动属于玩家
- agent source 的行动属于剑自己
- environment source 是客观世界事实
- 剑依然主要通过感知连接理解世界，但不能再把所有物理行为都默认归给玩家

当前状态（`2026-04-10`）：

- `PromptRegistry.getSystemCognitionRule()` 已完成 source-aware 改写
- action prompt / reflection prompt 的共享 header 与相关提示词已完成一轮旧前提清理
- 当前 phase 5 可视为“主体规则已接上，后续只剩 prompt 风格层面的持续优化”

### Phase 6. prompt / formatter source-aware 输出

目标：

- 让模型真正“看见” source

推荐方向：

- formatter / debug 输出中显式打印 source
- 至少在有歧义的地方输出 source 信息
- reflection material 中的 local context / visible context
  不应再被默认理解为“玩家视角事实”

当前状态（`2026-04-10`）：

- action 侧 `[Tagged Context]` 已显式输出 source
- reflection formatter 已显式输出 `[player:...] / [agent:...] / [environment]`
- reflection preview mock 已包含混合 source 样例
- 当前 phase 6 基本完成

### Phase 7. Studio / debug 文件升级

目标：

- 可视化和调试层同步升级

至少修改：

- `StudioRuntimeRecentInteractionsFile`
- `StudioRuntimeRecallTraceFile`

后续如有更多 context 可视化文件，也应统一补 source。

当前状态（`2026-04-10`）：

- `StudioRuntimeRecentInteractionsFile` 已写出 `sourceType / sourceId / sourceDisplayName`
- `StudioRuntimeRecallTraceFile` 已写出 `sourceType / sourceId / sourceDisplayName`
- 当前 phase 7 基本完成

### Phase 8. fixtures / testdata / 文档重建

目标：

- 彻底收尾，不留旧口径

包括：

- `testdata/*` 中所有依赖旧 prompt / old context 口径的 fixture
- reflection prompt preview
- context / memory / reflection 相关 README / 设计文档

当前状态（`2026-04-09`）：

- reflection prompt preview 已切到混合 source 样例
- context / memory / reflection 主线文档已完成一轮口径清理
- 仍未完全结束的主要是：
  - 后续若新增 fixture / sample，还要继续遵守 source-aware 口径
  - interaction actor / target source 还没有进入正式数据模型，因此相关文档还不能宣称“交互主体关系已完全结构化”
  - 部分旧文档仍保留 `AgentOutputRecord` 作为历史问题描述，需逐步替换为新的 `InteractionMessage` 口径

## 6. 这次重构中需要额外注意的坑

### 6.1 不要把 “entry source” 和 “snapshot source” 混为一谈

这是这次最容易把代码弄乱的点。

- `entry source`
  - 这条事实属于谁
- `snapshot source`
  - 这次采集是从哪个 trigger 来的

二者必须同时存在，但名字必须区分。

### 6.2 不要保留“默认玩家 source”

如果 builder 默认不传就补成 `PLAYER`，
这次重构就等于白做。

必须让所有旧调用点显式报错，然后逐个改。

### 6.3 prompt 层不能只改 wording，不改数据

如果 prompt 只是说“不是所有行动都属于玩家”，
但 formatter / visible context 里根本没有 source，
模型仍然只能猜。

所以：

- source 必须先作为正式数据进入 material
- prompt 层再消费它

### 6.4 不要把 agent 输出记录和 agent source context 混为一谈

当前已有：

- `InteractionMessage`
- `InteractionTurn`

后续新增 `AGENT` source 的 `TaggedContextEntry` 时，
语义上要区分：

- `InteractionMessage`
  - 剑在单轮交互中实际产出的说话 / 内心独白 / reaction cue 等消息记录
- `AGENT source TaggedContextEntry`
  - 剑自己的状态事实 / 感知事实 / 自主动作事实

它们可以并行存在，但不能互相替代。

## 7. 建议先做什么

如果按“干净、无兼容层”的标准推进，
下一步最合理的是：

1. 先立 `TaggedContextSourceType + TaggedContextSource`
2. 直接改 `TaggedContextEntry`
3. 同时把 `CollectedContextSnapshot.source` 改名
4. 让全工程先编译报错
5. 再按 collectors -> STM/segment -> prompt/formatter -> studio 的顺序逐层收口

这条顺序最干净，也最不容易留下旧语义残留。
