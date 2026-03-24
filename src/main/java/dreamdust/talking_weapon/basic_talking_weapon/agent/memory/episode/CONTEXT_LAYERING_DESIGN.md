# Context Layering Design

## Goal

在 `segment -> region -> episode synthesis` 这条链路里，`context entry` 不再只是一种东西。
我们需要明确区分：

1. 它是否应该进入记忆材料。
2. 它是否应该进入提示词候选池。
3. 它是否真的在某一轮里变成了 `visible context`。
4. 它是否只应该作为后续 recall 的边，而不应该进入提示词或总结。

这样可以避免两类典型污染：

- prompt 辅助条目污染 STM / episode / LTM
- recall 用的扁平 tag 反向污染 prompt 与总结

---

## Confirmed Layers

### Layer 1: `memoryEligible`

含义：

- 是否允许写入 `STMPool`
- 是否允许参与后续 `fixed segment` 富化
- 是否允许进入 episode / LTM synthesis 的长期材料

用途：

- 专门防止“为了 prompt 服务的重述条目”进入记忆链路
- 例如 `RecentActionCollector` 产出的动作叙述必须是 `memoryEligible=false`

### Layer 2: `promptEligible`

含义：

- 是否允许进入提示词候选池
- 这是一层“是否有资格被看见”的硬边界

建议：

- 这是独立于 `memoryEligible` 的另一层
- 一个条目可以：
  - `memoryEligible=true, promptEligible=true`
  - `memoryEligible=true, promptEligible=false`
  - `memoryEligible=false, promptEligible=true` 理论上应尽量避免，除非明确是纯 prompt 辅助条目

### Layer 3: `visibleContexts`

含义：

- 一轮 LLM 交互中，经过 `promptEligible -> associativeOnly -> AttentionFilter -> SubconsciousBooster`
  之后，真正进入提示词的上下文

要求：

- `visibleContexts` 必须保留原始语义文本
- 因为它和该轮 `InteractionTurn` 以及 LLM 产出的动作直接绑定
- 后续 region / episode summary 若要解释“为什么当时说了这句话”，只能依赖这一层

### Layer 4: `recall-only edges`

含义：

- 进入记忆，但不进入提示词和总结
- 最终通常被展平成 `DetailTag` / 关联边
- 这层服务后续 recall、episode synthesis、memory graph

要求：

- 不参与 prompt 文本构造
- 不参与 region 的自然语言总结
- 只作为隐含检索线索存在

---

## Relation With Current Code

### Current confirmed state

- `memoryEligible` 已存在
- `RecentActionCollector` 产出的 prompt 动作重述已被设为 `memoryEligible=false`
- `STMPool` 和 `episode` 富化都已过滤 `memoryEligible=false`

### Current code still missing

- `promptEligible` 还没有成为独立字段
- 当前 prompt 的硬过滤主要靠：
  - `associativeOnly=false` 才能进入正常 Top-K
  - `associativeOnly=true` 仍可能被 `SubconsciousBooster` 抽中并提升为可见

这意味着：

- `associativeOnly` 只是“软隐藏”
- 它不等价于“绝不进入提示词”
- 如果我们要真正支持 recall-only context，就应该再加一层独立的 `promptEligible`

### Important boundary

当前运行时 `LTMRecallEngine` 的 tag pool 仍然只从 `visibleContexts` 注入。

也就是说：

- “不进入提示词，但保留在 STM 里用于 recall 的边” 这个设计
- 对 episode / LTM synthesis 是成立的
- 对当前实时 `LTMRecallEngine.recall(...)` 还没有完全接上

因此后续应明确区分两条链：

1. `visibleContexts -> live recall`
2. `STM / segment / region recall-only edges -> episode synthesis / later recall`

---

## Segment / Region Structure

## Fixed Segment

建议最终至少分成三个区：

### 1. `visibleContextBindings`

内容：

- 与某个 `InteractionTurn` 同轮绑定的 `visibleContexts`

特点：

- 保留原始描述文本
- 后续可以解释这轮 LLM 输出是被什么上下文触发的

### 2. `memoryContexts`

内容：

- `memoryEligible=true`
- 但并不一定在当轮里变成 `visible`

特点：

- 这是原始的记忆材料层
- 后续可继续下沉为 recall edge

### 3. `recallEdgeTags`

内容：

- 从 `memoryContexts` 中展平得到的 detail tags / recall edges

特点：

- 不进入 prompt
- 不进入 segment / region 总结
- 只服务 recall / synthesis

## Region

region 应保留两类东西：

### A. 必须保留的 visible contexts

原因：

- 它们与 `InteractionTurn` 和 LLM 动作绑定
- 没有这层，后面只能看到“说了什么”，看不到“为什么说”

### B. 大量 recall-only 扁平 tag

原因：

- 它们是 episode synthesis 和后续检索的边
- 但不应进入提示词和自然语言总结

因此 region 的总结材料应只读取：

- actions
- interaction turns
- visible contexts
- 必要时的默认 visible snapshot

而不应直接把 recall-only tags 展开成文字

---

## Default Snapshot Classification

默认快照的职责不是复刻完整 prompt，而是在“这个 fixed segment 没有任何 LLM 交互，也没有有效上下文落入”时，给该 segment 一个最低限度的环境锚点。

因此默认快照应拆成两层：

- `default visible snapshot`
- `default recall-only snapshot`

原则：

- `default visible snapshot` 要非常稀疏，只保留最能描述“这一段当时的场景核心”的条目
- `default recall-only snapshot` 可以保留更多 tag，用于 episode synthesis / recall edge

## Collector-by-collector classification

### `BaseContextCollector`

#### Registered location

- `主人当前位于已登记地点`
- 建议：`default visible`
- 原因：它是强场景锚点，且常与 episode 切分和叙事位置直接相关

#### Health

- `health_critical`, `health_low`
- 建议：`default visible`
- 原因：高叙事强度，直接影响这段的解释

- `health_medium`
- 建议：`recall-only`
- 原因：有信息量，但通常不值得占用默认 visible 配额

- `health_full`
- 建议：`recall-only`
- 原因：对 prompt 和总结价值都低，但可保留为状态背景

#### Hunger

- `hungry_critical`, `hungry_low`
- 建议：`default visible`
- 原因：能直接解释行为和语气

#### Time

- `time_state`
- 建议：`recall-only`
- 原因：时间语义对记忆检索有用，但默认不应抢 prompt 位置

#### Weather

- `weather_thunder`
- 建议：`default visible`
- 原因：高显著性环境压力

- `weather_rain`
- 建议：`recall-only`
- 原因：有氛围价值，但默认不必进提示词

- `weather_clear`
- 建议：`recall-only`

#### Biome

- `主人正身处于群落`
- 建议：`recall-only`
- 原因：是典型环境标签，不是默认 visible 核心

### `PlayerEnvironmentCollector`

#### Dimension

- `主人目前身处于: dim`
- 建议：`recall-only`
- 原因：很重要，但更适合作为结构化环境锚点而不是默认 prompt 句子

#### Light

- `light_dark`
- 建议：`default visible`
- 原因：能直接改变风险和行为解释

- `light_bright`
- 建议：`recall-only`

#### Held item

- `主人的手中正紧紧握着`
- 建议：`default visible`
- 原因：和 intent、动作解释强相关

#### XP level

- 建议：`recall-only`
- 原因：可留作背景状态，不应抢默认 visible

### `LookingAtCollector`

#### Crosshair target

- 正在看某个实体 / 方块
- 建议：`default visible`
- 原因：这是玩家当下最直接的注意力锚点

### `NearbyPoiCollector`

#### Nearby POI

- 建议：默认 `recall-only`
- 条件提升为 `default visible`：
  - 极近
  - 单一 dominant target
  - 与本段动作 / intent 明显相关

原因：

- 默认全塞进 visible 会很吵
- 但作为 recall edge 很有价值

### `NearbyStructureCollector`

#### Nearby structure

- 建议：默认 `recall-only`
- 条件提升为 `default visible`：
  - 极近
  - 与探索 / 进入结构动作直接相关
  - 是本段主要场景中心

原因：

- 结构名很适合做 episode / recall anchor
- 但不适合在无交互 segment 中大量占用默认 visible 名额

---

## Practical Rule For Default Snapshot

当一个 fixed segment 没有任何真实 `visibleContexts` 可绑定时：

### Segment summary / episode summary 可以读取：

- registered location
- low / critical health
- low / critical hunger
- thunder / dark
- held item
- crosshair target
- 条件满足时的 dominant POI / structure

### Segment summary / episode summary 不应直接读取：

- biome
- dimension
- clear weather / normal rain
- xp level
- 普通 nearby structure / POI 列表
- 仅为 recall 服务的扁平 detail tags

这些应下沉为：

- `memoryContexts`
- `recallEdgeTags`

---

## Recommended Next Refactor

1. 给 `TaggedContextEntry` 增加独立的 `promptEligible`
2. prompt 构造阶段先过滤 `promptEligible=false`
3. `associativeOnly` 仅保留为“可被潜意识提升”的软隐藏机制
4. enriched fixed segment 中显式拆出：
   - interaction-bound visible contexts
   - memory-only contexts
   - recall edge tags
5. 默认快照生成时直接按上述规则分成：
   - default visible snapshot
   - default recall-only snapshot

这样之后：

- prompt 层
- live recall 层
- episode synthesis 层
- LTM graph 层

就不会再共享一份混杂语义的 `context entry` 列表。
