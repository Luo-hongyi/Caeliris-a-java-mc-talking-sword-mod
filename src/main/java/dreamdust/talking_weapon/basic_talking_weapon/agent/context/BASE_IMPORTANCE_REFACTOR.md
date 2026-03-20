# Base Importance 重构草案

本文档记录我们当前对 `baseImportance` 重构的设计想法。

注意：

- 这是一份 **设计草案**
- 当前代码 **尚未实现** 本文档中的新模型
- 本文档的目标是为后续重构提供统一方向，避免继续在 collector 中散落手写 `importance(0.xf)`

相关现状实现：

- `TaggedContextEntry.java`
- `AttentionFilter.java`
- `ImportanceRegistry.java`
- `collector/*`

## 1. 当前问题

当前 `baseImportance` 的来源混合了三种东西：

1. 事件的客观显著性
2. collector 内部的业务判断
3. 若干散落在 `ImportanceRegistry` 和 collector 代码中的经验常量

这会带来几个直接问题：

- collector 直接产出最终 `importance`，导致逻辑分散
- 同一类事件在不同 collector 里可能使用不同口径
- 很难系统调参，只能改一堆离散的 magic number
- `importance.json` 的 key 设计依赖具体事件名，扩展性弱
- 对大型整合包不友好，不可能为上万个 mod tag 单独维护基础重要度

核心矛盾不是“要不要客观重要度”，而是：

- 客观重要度是必要的
- 但它不应该由 collector 直接手写为最终浮点数

## 2. 重构目标

我们希望把当前模型改造成：

```text
collector -> Objective Signals -> Objective Salience Model -> baseImportance
```

其中：

- collector 只负责产出“证据”
- 统一模型负责把证据转成 `baseImportance`
- `baseImportance` 短期内仍保留这个字段名，以减少下游改动

设计目标：

- 不对每个具体 tag 单独维护 importance
- collector 产出的信号必须低成本、稳定、易理解
- 调参集中在少量公共参数，而不是分散在每个 collector
- 对原版和大型整合包都能泛化

## 3. 核心原则

### 3.1 Collector 不产最终分数

collector 不再直接决定：

```text
importance = 0.6
```

而是改为产出：

```text
archetype = HOSTILE_ENTITY
severity = HIGH
immediacy = CLOSE
scale = FEW
targetedness = INDIRECT
```

最终 `baseImportance` 由统一模型计算。

### 3.2 Collector 只产它天然知道的东西

只有当某个因子可以由 collector 在本轮、基于本地上下文稳定判断时，才允许它负责生产。

例如：

- 距离近不近
- 是否正在看着目标
- 数量多不多
- 状态是否危急
- 这是敌对生物还是普通环境信息

### 3.3 跨轮因素不放进 Collector

以下因素不应该由 collector 直接生产：

- novelty
- change_from_last_turn
- redundancy_penalty
- recently_seen_penalty
- repeated_context_decay

原因：

- 它们依赖跨轮状态
- 如果放到 collector，会导致每个 collector 都要自己维护状态
- 这会让系统失去统一性

这些因素如果需要，应该在 collector 之后的统一层处理。

## 4. 推荐的最小因子集合

为了保证 collector 易实现，建议第一版只保留 5 个因子。

### 4.1 `archetype`

表示事件的客观类型原型，用于提供一个基础先验。

建议候选值：

- `SELF_STATUS`
- `HOSTILE_ENTITY`
- `PASSIVE_ENTITY`
- `LOOK_TARGET`
- `LANDMARK`
- `AMBIENT_ENVIRONMENT`
- `RESOURCE_OPPORTUNITY`
- `HAZARD_SIGNAL`
- `RECENT_ACTION`

作用：

- 避免对具体 tag 配重要度
- 改为对“事件原型”配置先验

### 4.2 `severity`

表示事情本身有多严重、强烈或值得注意。

建议离散值：

- `NONE`
- `LOW`
- `MEDIUM`
- `HIGH`
- `CRITICAL`

适合由 collector 判断的例子：

- 生命值是否危急
- 是否雷暴
- 是否靠近岩浆
- 战斗是否激烈

### 4.3 `immediacy`

表示事件离玩家当前感知中心有多近。

建议离散值：

- `AMBIENT`
- `NEARBY`
- `CLOSE`
- `DIRECT`

典型判断依据：

- 空间距离
- 是否在视线中心
- 是否正在接触/使用

### 4.4 `scale`

表示数量规模或覆盖规模。

建议离散值：

- `SINGLE`
- `FEW`
- `MANY`
- `MASS`

典型判断依据：

- 附近敌对生物数量
- 方块聚集规模
- POI/结构数量

### 4.5 `targetedness`

表示这件事是否直接作用于玩家当前状态或当前意图。

建议离散值：

- `NONE`
- `INDIRECT`
- `DIRECT`

例子：

- 正在看着某物
- 正被某物攻击
- 当前手持物
- 当前交互对象

## 5. Collector 可生产性评估

这部分是重构是否成立的关键。

### 5.1 BaseContextCollector

适合生产：

- `archetype = SELF_STATUS`
- `severity`
- `targetedness = DIRECT`

原因：

- 血量、饥饿、天气、时间、群系都已由本 collector 直接掌握
- 不需要额外状态

### 5.2 NearbyEntitiesCollector

适合生产：

- `archetype = HOSTILE_ENTITY / PASSIVE_ENTITY`
- `severity`
- `immediacy`
- `scale`

原因：

- 它天然掌握敌意、数量、距离、LOS

### 5.3 LookingAtCollector

适合生产：

- `archetype = LOOK_TARGET`
- `immediacy = DIRECT`
- `targetedness = DIRECT`

原因：

- “正在看着”本身就是强客观显著性信号

### 5.4 NearbyStructureCollector / NearbyPoiCollector

适合生产：

- `archetype = LANDMARK`
- `immediacy`
- `scale`

原因：

- 结构和 POI 天生是“地点型事件”，不需要细粒度 tag importance

### 5.5 SurroundingBlocksCollector

适合生产：

- `archetype = AMBIENT_ENVIRONMENT / RESOURCE_OPPORTUNITY / HAZARD_SIGNAL`
- `severity`
- `immediacy`
- `scale`

原因：

- 它有大量环境几何和危险信号
- 但不适合继续手写很多具体 block importance

### 5.6 RecentActionCollector

适合生产：

- `archetype = RECENT_ACTION`
- `severity`
- `targetedness`

原因：

- 动作条目天然与当前玩家行为绑定

## 6. 统一模型草案

第一版推荐使用“原型先验 + 离散因子加成”的简单模型。

```text
baseImportance = clamp01(
  archetypePrior
  + severityBonus
  + immediacyBonus
  + scaleBonus
  + targetednessBonus
)
```

其中：

- `archetypePrior` 由事件原型决定
- 其他项由统一映射表决定
- 全部参数总量控制在“几十个以内”

这样做的好处是：

- 参数量小
- 调参有语义
- collector 负担低
- 不依赖具体 tag 数量

## 7. 为什么不按 tag 维护 importance

在大型 Minecraft 整合包中，tag 和 registry 项可能达到非常大的量级。

如果继续按具体 tag 维护 `baseImportance`：

- 配置量会爆炸
- 很多条目永远来不及补
- mod 更新后会持续失效
- collector 的语义判断会被具体数据内容绑死

因此更合理的策略是：

- 对少量 `archetype` 维护先验
- 对少量离散因子维护统一映射
- 具体 tag 只用于语义识别和记忆检索，不直接决定基础重要度

## 8. 推荐的数据结构方向

为了兼容现有链路，建议分两步走。

### 8.1 短期

保留：

- `TaggedContextEntry.baseImportance`

新增一个中间信号对象，例如：

```text
ObjectiveSignals {
  archetype
  severity
  immediacy
  scale
  targetedness
}
```

由统一模型先算出 `baseImportance`，再继续喂给现有 `AttentionFilter`。

### 8.2 中期

将 `baseImportance` 在语义上重命名为：

- `objectiveSalience`

但可以暂时不改字段名，先减少迁移成本。

## 9. 与 Recall 的关系

当前 recall 模型只依赖：

- `AttentionFilter.calculateFinalScore(...)`
- 由此产生的 `entryScore`

因此：

- 只要 `TaggedContextEntry` 最终仍能提供一个数值型 `baseImportance`
- recall 层本身不需要大改

这意味着：

- `baseImportance` 重构主要发生在 `agent.context`
- `agent.memory` 可以继续使用同一套下游公式

## 10. 迁移建议

建议分三步推进。

### 第一步

先写清 collector 应该产哪些因子，不改 recall。

### 第二步

新增统一的 `ObjectiveSalienceModel`，由它把 signals 转成 `baseImportance`。

### 第三步

逐个 collector 替换掉直接写死的 `.importance(0.xf)`。

## 11. 当前结论

目前我们认可的方向是：

- 保留客观重要度
- 取消 collector 直接手写最终 importance
- collector 只产低维、离散、可稳定判断的客观信号
- 不按每个具体 tag 配 importance
- 先把模型收敛到 5 个核心因子：
  - `archetype`
  - `severity`
  - `immediacy`
  - `scale`
  - `targetedness`

这套方案的关键优点是：

- collector 可生产
- 对大型整合包可扩展
- 调参集中
- 对 recall 层改动最小
