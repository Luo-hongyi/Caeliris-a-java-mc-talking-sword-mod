# Recall 调参指南

本文档只描述 **当前代码已经实现** 的 recall 模型，用于调参与排查。

相关实现：

- `LTMRecallEngine.java`
- `RecallRuntimeState.java`
- `AgentState.java`
- `TalkingWeaponConfig.java`

## 1. 总流程

### 1.1 设计层流程

按我们现在讨论的统一模型，单轮 recall 更准确的理解是：

1. `visibleContexts -> tag_pool`
2. `tag_pool -> 主召回 cue`
3. `主召回 cue -> 新候选记忆`
4. `新候选记忆 + 旧 fermentation_buffer -> 最终候选记忆`
5. `最终候选记忆 -> retained fermentation`
6. `retained fermentation -> conscious memories`
7. `conscious memories -> association sandbox`
8. 若启用且本轮未命中植入记忆：`cue_pool -> implanted fallback`

这里第 3 步和第 4 步要刻意分开理解：

- 第 3 步是“用 cue 去捞新的记忆候选”
- 第 4 步是“让这些新候选和旧发酵记忆一起竞争，形成本轮最终候选池”

这样就不会把 `fermentation_buffer` 误看成 cue 层的一部分。

### 1.2 代码实现层流程

当前代码和上面的设计层含义 **基本一致**，但实现上把第 3、4 步部分折叠了：

1. `buildInitialCueInputs(...)`
   - 把 `tag_pool` 投影成临时的 `main recall cue`
   - 这里得到的是 `Seed` 列表，不是记忆层
2. `collectLayerCandidates(mainCueBatch, fermentationSnapshot, ...)`
   - 一部分逻辑是 `主召回 cue -> 新候选记忆`
   - 另一部分逻辑是让 `旧 fermentation_buffer` 里的记忆按 persistence 公式再次参赛
   - 所以这个函数返回的已经是一个“混合候选集”，不是纯粹的新召回结果
3. `selectPromotionCandidates(...)`
   - 从上一步的混合候选集中抽出本轮被提升的一批 `promotedCandidates`
4. `buildRetainedCandidates(promotedCandidates, fermentationSnapshot, ...)`
   - 再把 `promotedCandidates` 和旧 `fermentationSnapshot` 合并成 `SelectionCandidate`
   - 这一层更接近“最终候选记忆 -> retained selection”的准备阶段
5. `selectTopCandidates(...)`
   - 先截出 `retained fermentation`
   - 再从 retained 中截出 `conscious memories`

其中：

- `tag_pool` 是跨轮维护的 `detail tag -> score` 缓冲层
- `fermentation_buffer` 是跨轮维护的候选记忆层
- `conscious memories` 是本轮真正进入 prompt 的记忆
- `association sandbox` 只服务本轮，不会回写主发酵层

结论：

- 从设计语义上，你刚刚的改写是对的
- 从代码实现上，没有本质偏差
- 但代码里“新候选生成”和“旧发酵记忆再参赛”并不是完全分开的两个函数，而是部分折叠在 `collectLayerCandidates(...)` 里

### 1.3 这几层里到底装的是什么

- `tag_pool` 里装的是 **tag**
  - 具体是 `detail tag -> score`
  - 它表示“最近一段时间里，哪些 cue 正在活跃、强度多高”
- `主召回 cue` 里装的也是 **tag/cue**
  - 只是它不是持久层，而是本轮由 `tag_pool` 临时投影出来的输入视图
  - 当前代码里对应的是 `Seed`
- `fermentation_buffer` 里装的是 **记忆**
  - 具体是 `memoryId + score + supportTags + associativeTags + reinforcementCount + 时间戳`
  - 它表示“哪些记忆最近被激活过，但还不一定进入表层意识”
- `conscious_memories` 里装的也是 **记忆**
  - 只是这里是本轮真正入选、会进入 prompt 的那几条
- `association_sandbox` 里同时会记录
  - sandbox 用到的 `cueTags`
  - sandbox 命中的 `memoryIds`

可以把它们理解成：

- `tag_pool` 是“最近脑子里哪些线索最活跃”
- `主召回 cue` 是“这一轮被拿去检索的线索切片”
- `fermentation_buffer` 是“哪些记忆正在被这些线索慢慢煮热”
- `conscious_memories` 是“这一轮真正浮到表面的记忆”

所以我们之前流程里：

- `tag_pool -> 主召回 cue`
- `主召回 cue -> 新候选记忆`
- `新候选记忆 + 旧 fermentation_buffer -> 最终候选记忆`

这两步的真实含义是：

1. 先用 tag 作为线索去捞新的记忆
2. 再让“旧的正在发酵的记忆”和“新捞上来的记忆”一起竞争

不是 tag 在发酵，而是 **记忆在发酵**

## 2. 预设参数

`recall_preset` 只是在统一模型上选一组参数：

| Preset | `main_candidates_per_cue` | `sandbox_cues_per_memory` | `conscious_memory_cap` |
|---|---:|---:|---:|
| `SEED_FIRST` | 1 | 1 | 2 |
| `FULL_VISIBILITY` | 2 | 1 | 4 |
| `HYBRID` | 1 | 2 | 3 |
| `CUSTOM` | 读取 `recall_tuning` | 读取 `recall_tuning` | 读取 `recall_tuning` |

## 3. 运行时缓冲层公式

### 3.1 Tag Pool 衰减

- 半衰期：`LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS * 游戏内一天`
- 当前默认值：`0.40 * 游戏内一天`
- 公式：

```text
decayFactor = exp(-deltaGameTime / TAG_POOL_HALF_LIFE_TICKS)
newScore = oldScore * decayFactor
```

- 低于 `0.035` 的 tag 会被移除

### 3.2 Fermentation Buffer 衰减

- 半衰期：`LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS * 游戏内一天`
- 当前默认值：`2.50 * 游戏内一天`
- 公式：

```text
decayFactor = exp(-deltaGameTime / FERMENTATION_HALF_LIFE_TICKS)
newScore = oldScore * decayFactor
```

- 低于 `0.06` 的记忆会被移除

### 3.3 长间隔补偿

新一轮 `visibleContexts` 注入 `tag_pool` 时，使用额外间隔加成：

```text
days = deltaGameTime / TICKS_PER_DAY
intervalBonus = clamp(
  1.0,
  2.6,
  1.0 + min(
    1.6,
    log1p(days * LTM_RECALL_INTERVAL_BONUS_STEEPNESS)
    * LTM_RECALL_INTERVAL_BONUS_SCALE
  )
)
```

含义：

- 间隔越久，新 cue 注入越重
- 旧 tag 本身不会被额外增幅
- 上限是 `2.6`
- 该补偿 **只作用于本轮 `visibleContexts -> tag_pool` 的 injectedScore**
- 不会放大旧 `tag_pool` 分数，也不会放大旧 `fermentation_buffer` 分数

当前默认值：

- `LTM_RECALL_INTERVAL_BONUS_SCALE = 0.95`
- `LTM_RECALL_INTERVAL_BONUS_STEEPNESS = 4.0`

### 3.4 Visible Context 注入 Tag Pool

对每条 `TaggedContextEntry`：

```text
entryScore = max(0.01, AttentionFilter.calculateFinalScore(entry, concentration))
```

其中 `AttentionFilter.calculateFinalScore(...)` 的当前公式为：

```text
if entry.focusTags is empty:
    subjectiveScore = baseImportance
else:
    maxWeight = max(getFocusWeight(tag) for tag in entry.focusTags)

    scale = 0.2 + (maxWeight / 10.0) * 1.8

    subjectiveMultiplier =
      1.0 + (scale - 1.0) * clamp01(concentration)

    subjectiveScore = baseImportance * subjectiveMultiplier

crosshairBonus =
  LOOKING_AT_ATTENTION_BONUS_VALUE    if entry.crosshairTarget && LOOKING_AT_ATTENTION_BONUS_ENABLED
  0.00                                otherwise

finalScore = subjectiveScore + crosshairBonus
```

补充说明：

- 这里只取该条上下文里 **权重最高的一个 `FocusTag`**
- `COMMON` 现在和其他 focus 一样，直接读取 `attention_weights.common`
- 准心额外加分已经从 `COMMON` 里拆出，改成 `TaggedContextEntry.crosshairTarget` 上的独立开关 + 可调绝对值
- `concentration = 0` 时，主观偏好完全被压平，`finalScore = baseImportance`
- `concentration = 1` 时，主观偏好完全生效
- `scale` 的理论范围约为 `0.2 ~ 2.0`

同一个 `detail tag` 会把多个 `entryScore` 累加，最后：

```text
injectedScore = accumulatedEntryScore * intervalBonus
```

### 3.5 Tag Pool 对发酵层的 cue 回灌

若某条发酵记忆的 `associativeTags` 命中当前 `tag_pool`：

```text
cueBoost += tagPoolScore * 0.16
cueBoostThisTurn = min(0.85, cueBoost)
newFermentationScore = clamp(0.0, 3.0, oldScore + cueBoostThisTurn)
```

## 4. Focus 模型

### 4.1 Focus 权重

在 recall 的 focus 对齐阶段，所有 `FocusTag` 都按 `1.0` 处理，`COMMON` 不再有额外惩罚。

也就是说：

- `COMMON` 是否更容易进入 `visibleContexts`，现在取决于 `attention_weights.common`
- 但一旦进入 recall，`COMMON` 和其他 focus 的对齐权重相同

### 4.2 Focus Profile 构造

对每条可见上下文：

```text
base = AttentionFilter.calculateFinalScore(entry, concentration)
weight = base * focusWeight(tag) + desireBonus
```

其中：

```text
desireBonus = LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE * focusWeight(tag)    if currentDesireTag == tag
desireBonus = 0                                                                 otherwise
```

随后把所有 focus 权重归一化，得到：

- `normalizedWeights`
- `dominantFocus`

### 4.3 记忆与 Focus 的对齐

记忆 focus 对齐：

```text
memoryFocusAlignment =
  clamp01(sum(normalizedWeights[tag] * focusWeight(tag)) / memoryFocusTagCount)
```

cue 和记忆的 focus overlap：

```text
cueFocusAlignment =
  clamp01(sum(overlapFocusWeight) / cueFocusTagCount)
```

渴望额外加分：

```text
desiredFocusBonus = LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE * focusWeight(desireTag)    if memory contains desireTag
desiredFocusBonus = 0                                                                      otherwise
```

## 5. 主召回评分公式

### 5.1 初始 cue 的 source score

`tag_pool -> 主召回 cue` 时，每个 cue 会得到一个 `sourceScore`：

```text
normalizedScore = tagPoolScore / maxTagPoolScore

sourceScore = clamp(
  0.60,
  2.00,
  0.85
  + normalizedScore * 0.80
  + min(0.20, max(0, sourceCount - 1) * 0.05)
  + min(0.25, injectedScoreThisTurn * 0.08)
)
```

### 5.2 语义匹配

`cue tag` 与记忆 `associativeDetailTags` 的最佳匹配分数：

- 完全相同：`1.00`
- relation overlap 中含 specific：`0.88`
- `generic -> specific`：`0.70`
- `specific -> generic`：`0.48`
- 只有 generic 级 overlap：`0.56`
- 无 overlap：`0.00`

### 5.3 年龄衰减

植入记忆：

```text
ageFactor = 1.0
```

补充说明：

- `IMPLANTED` 不参与任何时间衰退
- 不看 `timestamp`
- 不看 `occurredGameTime`
- 它的“出现频率控制”主要来自语义命中、focus 对齐、recentPenalty，以及植入记忆保底逻辑，而不是 age decay

合成记忆半衰期：

- `LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE`
- 当前默认值：`63.0`
- 单位固定为“游戏内天数”
- 当前实现要求 recall 时必须拿到 `currentGameTime`
- 同时要求 `SYNTHESIZED` 记忆自身带有 `occurredGameTime`
- 持久化层会优先长期保存 `occurredDayIndex + occurredTimeOfDayTick`
- 若只拿到这两个拆分字段，`LTMEntry` 会在构造时重建内部 `occurredGameTime`
- 若缺失，直接报错，不再回退到现实时间小时数

合成记忆优先使用游戏内时间：

```text
ageDays = (currentGameTime - occurredGameTime) / TICKS_PER_DAY
halfLifeDays = LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE
decay = exp(-ageDays / max(1.0, halfLifeDays))
ageFactor = clamp(0.15, 1.0, decay)
```

### 5.4 最近召回惩罚

```text
rank 0 -> 0.01
rank 1 -> 0.03
rank 2 -> 0.07
rank 3 -> 0.14
rank 4 -> 0.24
else   -> clamp(0.24, 0.86, 0.24 + pow(clamp01(rank / window), 1.8) * 0.62)
```

此外有硬跳过：

- 若最近 rank 为 `0` 或 `1`
- 且当前语义匹配 `< 0.88`

则这条记忆直接跳过，不只是降权

### 5.5 主召回总分

进入主召回计算前，需满足：

- 有语义信号，或
- `memoryFocusAlignment >= 0.38` 且 `cueFocusAlignment >= 0.20`

总分公式：

```text
semanticFactor = 0.50 + semanticScore * 0.50     if semanticScore > 0
semanticFactor = 0.32                            otherwise

focusFactor =
  0.60
  + memoryFocusAlignment * 0.70
  + cueFocusAlignment * 0.25
  + desiredFocusBonus

strengthFactor =
  0.65
  + importance * 0.20
  + clarity * 0.15

sourceFactor =
  0.60 + clamp01(sourceScore / 2.0) * 0.40

totalScore =
  semanticFactor
  * focusFactor
  * (0.55 + ageFactor * 0.45)
  * recentPenalty
  * strengthFactor
  * sourceFactor
```

阈值：

```text
totalScore >= 0.22
```

## 6. 发酵层保留评分公式

已有发酵记忆再次参与竞争时，使用 persistence 模型：

```text
persistenceFactor =
  0.58
  + clamp(0.0, 1.60, fermentationScore) * 0.38
  + min(0.18, reinforcementCount * 0.03)
  + min(0.10, cueBoostThisTurn * 0.08)

focusFactor =
  0.70
  + memoryFocusAlignment * 0.22
  + desiredFocusBonus

totalScore =
  persistenceFactor
  * focusFactor
  * (0.60 + ageFactor * 0.40)
  * recentPenalty
```

阈值：

```text
totalScore >= 0.16
```

额外硬跳过：

- 若最近 rank 为 `0` 或 `1`
- 且当前 `fermentationEntry.score < 0.70`

则直接跳过

## 7. 抽样温度模型

所有“带随机性”的抽样都使用同一套温度模型：

```text
exponent = 2.6 - clamp01(wanderingMind) * 2.0
sampleWeight = max(0.0001, score) ^ exponent
```

含义：

- `wanderingMind` 越低，指数越高，分布越尖锐，越偏高分
- `wanderingMind` 越高，指数越低，分布越平坦，越容易抽到次高分

## 8. 候选保留与表层意识

发酵层保留上限：

```text
retentionCap = clamp(8, 32, max(consciousMemoryCap * 4, 8))
```

排序规则：

1. `score` 降序
2. `reinforcementCount` 降序
3. `supportTags.size()` 降序
4. `memoryId` 字典序

随后：

- 先截出 `retained fermentation`
- 再从其中截出 `conscious memories`

## 9. Sandbox 联想

每条表层记忆先挑若干个 associative tags，构造 sandbox cue：

```text
cueCountPerMemory = sandbox_cues_per_memory
```

tag priority：

- `custom_specific` -> `4.0`
- `native_specific` -> `3.0`
- `custom_generic` -> `2.0`
- `native_generic` -> `1.0`
- other -> `0.5`

每个 sandbox cue 的 `sourceScore`：

```text
sourceScore = clamp(0.90, 2.40, 1.05 + consciousScore * 0.50)
```

sandbox 预算：

```text
sandboxBudget = max(1, min(4, consciousMemoryCount))
```

注意：

- `sandbox_cues_per_memory = 0` 会直接关闭 cue 回灌
- sandbox 结果只写入 `association_sandbox.memoryIds`
- 不会污染主 `fermentation_buffer`

## 10. 植入记忆保底

触发条件：

- `implanted_memory_safety_net = true`
- 正常召回结果里没有 `SourceType.IMPLANTED && occurredGameTime < 0`
- 本轮 `cue_pool` 非空

候选范围：

- 仅 `IMPLANTED`
- 且 `occurredGameTime < 0`
- 且不在本轮已召回集合里

权重：

```text
rawWeight = sum(all semantic cue hits)
weight = rawWeight * recentPenalty
```

额外硬跳过：

- 若最近 rank 为 `0` 或 `1`
- 且最大语义匹配 `< 0.88`

则直接跳过

最后用同一套 `wanderingMind` 温度抽样，只补 1 条

## 11. 当前真正可调的参数

### Agent State

- `concentration`
- `wandering_mind`
- `recall_preset`
- `recall_tuning.main_candidates_per_cue`
- `recall_tuning.sandbox_cues_per_memory`
- `recall_tuning.conscious_memory_cap`
- `implanted_memory_safety_net`
- `current_desire_tag`

### Config

- `LTM_RECALL_MAIN_CANDIDATES_PER_CUE`
- `LTM_RECALL_SANDBOX_CUES_PER_MEMORY`
- `LTM_RECALL_CONSCIOUS_MEMORY_CAP`
- `LTM_RECALL_RECENT_WINDOW`
- `LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS`
- `LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS`
- `LTM_RECALL_INTERVAL_BONUS_SCALE`
- `LTM_RECALL_INTERVAL_BONUS_STEEPNESS`
- `LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE`
- `LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE`
- `LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE`

注意：

- `LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS`
- `LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS`
- `LTM_RECALL_INTERVAL_BONUS_SCALE`
- `LTM_RECALL_INTERVAL_BONUS_STEEPNESS`

目前已经接入 Forge config、游戏内 config screen，并在运行时生效

### Attention Layer

- `attention_weights.*`
- `attention_weights.common`
- `LOOKING_AT_ATTENTION_BONUS_ENABLED`
- `LOOKING_AT_ATTENTION_BONUS_VALUE`

## 12. 调参建议

### 想提升命中率

- 提高 `main_candidates_per_cue`
- 提高 `conscious_memory_cap`
- 选择 `FULL_VISIBILITY`
- 适度提高与当前场景相关的 `attention_weights`

### 想减少话题乱飘

- 降低 `wandering_mind`
- 降低 `sandbox_cues_per_memory`
- 选择 `SEED_FIRST`
- 降低弱相关 focus 的注意力权重

### 想让旧记忆更耐久

- 提高 `LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE`

### 想减少重复提及

- 提高 `LTM_RECALL_RECENT_WINDOW`

### 想让手工植入记忆更容易显性出现

- 打开 `implanted_memory_safety_net`
- 选择 `FULL_VISIBILITY`
- 提高 `main_candidates_per_cue`

### 想关闭一跳回灌联想

- 将 `sandbox_cues_per_memory` 设为 `0`
