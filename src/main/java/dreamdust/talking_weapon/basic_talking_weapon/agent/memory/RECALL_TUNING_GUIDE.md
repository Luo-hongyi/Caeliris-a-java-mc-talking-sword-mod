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
7. `conscious memories -> prompt memories`
8. `conscious memories -> association sandbox`
9. 若启用且本轮未命中植入记忆：`cue_pool -> implanted fallback`

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
   - 另一部分逻辑是让 `旧 fermentation_buffer` 里的记忆直接按当前 `fermentationScore` 再次参赛
   - 所以这个函数返回的已经是一个“混合候选集”，不是纯粹的新召回结果
3. `selectPromotionCandidates(...)`
   - 从上一步的混合候选集中抽出本轮被提升的一批 `promotedCandidates`
4. `buildRetainedCandidates(promotedCandidates, fermentationSnapshot, ...)`
   - 再把 `promotedCandidates` 和旧 `fermentationSnapshot` 合并成 `SelectionCandidate`
   - 这一层更接近“最终候选记忆 -> retained selection”的准备阶段
5. `selectTopCandidates(...)`
   - 从候选里先截出 `retained fermentation`
6. `selectConsciousCandidates(retainedSelection, ...)`
   - 再从 retained 中单独计算显性浮现分，截出 `conscious memories`
7. `fillPromptMemories(consciousSelection, ...)`
   - 对 `conscious memories` 再过一层“愿不愿意说出口”的随机阀门
   - 这一层允许欠填，甚至允许本轮 `0` 条记忆进入 prompt

其中：

- `tag_pool` 是跨轮维护的 `detail tag -> score` 缓冲层
- `fermentation_buffer` 是跨轮维护的候选记忆层
- `conscious memories` 是本轮真正浮到表层、被 agent 意识到的记忆
- `prompt memories` 是本轮最终真的被填入 prompt 的记忆
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
  - 具体是 `memoryId + score + supportTags + associativeTags + 时间戳`
  - 它表示“哪些记忆最近被激活过，但还不一定进入表层意识”
- `conscious_memories` 里装的也是 **记忆**
  - 只是这里是本轮真正浮到表层、已经“想到”的那几条
- `prompt_memories` 里装的也是 **记忆**
  - 它是 `conscious_memories` 里最终愿意说出口、会进入 prompt 的子集
- `association_sandbox` 里同时会记录
  - sandbox 用到的 `cueTags`
  - sandbox 命中的 `memoryIds`

可以把它们理解成：

- `tag_pool` 是“最近脑子里哪些线索最活跃”
- `主召回 cue` 是“这一轮被拿去检索的线索切片”
- `fermentation_buffer` 是“哪些记忆正在被这些线索慢慢煮热”
- `conscious_memories` 是“这一轮真正浮到表面的记忆”
- `prompt_memories` 是“这一轮最终真的说出口的记忆”

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

| Preset | `main_candidates_per_cue` | `sandbox_cues_per_memory` | `conscious_memory_cap` | `retention_memory_cap` |
|---|---:|---:|---:|---:|
| `SEED_FIRST` | 4 | 2 | 2 | 4 |
| `FULL_VISIBILITY` | 8 | 4 | 4 | 8 |
| `HYBRID` | 6 | 6 | 3 | 6 |
| `CUSTOM` | 读取 `recall_tuning` | 读取 `recall_tuning` | 读取 `recall_tuning` | 读取 `recall_tuning` |

## 3. 运行时缓冲层公式

### 3.1 Tag Pool 衰减

- 半衰期：`LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS * 游戏内一天`
- 形状参数：`LTM_RECALL_TAG_POOL_DECAY_SHAPE`
- 掉出阈值：`LTM_RECALL_TAG_POOL_DROP_THRESHOLD`
- 当前默认值：`0.40 * 游戏内一天`
- 公式：

```text
decayFactor = exp(- (deltaGameTime / TAG_POOL_HALF_LIFE_TICKS) ^ TAG_POOL_DECAY_SHAPE)
newScore = oldScore * decayFactor
```

- 若 `newScore < LTM_RECALL_TAG_POOL_DROP_THRESHOLD`，该 tag 会被移除
- 当前默认掉出阈值：`0.035`

### 3.2 Fermentation Buffer 衰减

- 半衰期：`LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS * 游戏内一天`
- 形状参数：`LTM_RECALL_FERMENTATION_DECAY_SHAPE`
- 掉出阈值：`LTM_RECALL_FERMENTATION_DROP_THRESHOLD`
- 当前默认值：`1.20 * 游戏内一天`
- 公式：

```text
decayFactor = exp(- (deltaGameTime / FERMENTATION_HALF_LIFE_TICKS) ^ FERMENTATION_DECAY_SHAPE)
newScore = oldScore * decayFactor
```

- 若 `newScore < LTM_RECALL_FERMENTATION_DROP_THRESHOLD`，该记忆会被移除
- 当前默认掉出阈值：`0.06`

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
remainingCooldown = suppressionTable[memoryId]    // default 0

if remainingCooldown <= 0:
    recentPenalty = 1.0
else:
    releaseStep =
      0                                                    if window <= 1
      clamp(0, window - 1, window - remainingCooldown)     otherwise

    ratio =
      0.0                                        if window <= 1
      clamp01(releaseStep / max(1, window - 1))  otherwise

    t = clamp01((ratio - 0.15) / (0.85 - 0.15))

    recentPenalty =
      0.02
      + (0.86 - 0.02) * pow(t, 1.8)
```

含义：

- 当前不再维护“最近召回 queue”，而是维护 `memoryId -> remainingCooldown` 的 suppression table
- 每轮 recall 开始时，表里所有 `remainingCooldown` 先 `-1`
- 本轮真正进入 `prompt memories` 的记忆，会在 recall 结束后写回表中，并设置为 `window + 1`
- 因此下一轮 recall 再计算时，刚被显性提起的记忆会看到 `remainingCooldown = window`
- 不在最近召回窗口里的记忆：`recentPenalty = 1.0`
- 在窗口里的记忆：统一按一条平滑释放曲线降权
- 越接近最近一次被提起的位置，惩罚越强
- 越靠近窗口尾部，惩罚越接近释放完成，但在窗口内仍保留少量抑制
- 同一条记忆不会因为后来又有别的记忆被显性提起，就被从队列尾部挤掉而提前解除抑制
- `LTM_RECALL_RECENT_WINDOW` 现在表示“抑制持续轮数”，而不是“队列容量”
- `LTM_RECALL_RECENT_WINDOW = 0` 时，整条 recent recall 抑制逻辑关闭，`recentPenalty` 恒为 `1.0`
- 当前 `recentPenalty` 会用于：
  - `retained fermentation -> conscious memories` 的显性浮现排序
  - `implanted fallback` 的候选权重
- 不用于旧 `fermentation_buffer` 的保留 / 续存竞争
- suppression table 的**登记时机**已经后移到 `prompt fill` 之后，而不是 `conscious` 阶段

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
  + importance * 0.35

sourceFactor =
  0.60 + clamp01(sourceScore / 2.0) * 0.40

totalScore =
  semanticFactor
  * focusFactor
  * (0.55 + ageFactor * 0.45)
  * strengthFactor
  * sourceFactor
```

阈值：

```text
totalScore >= 0.22
```

## 6. 发酵层保留评分公式

已有发酵记忆再次参与竞争时，不再做第二次 focus 重编码，而是直接使用当前发酵分数：

```text
totalScore =
  fermentationScore
```

阈值：

```text
totalScore >= 0.16
```

说明：

- 发酵层不再额外乘 `ageFactor`，记忆年龄只在主召回阶段参与评分
- 发酵层不再使用 `recentPenalty`
- 发酵层不再额外乘 focusFactor；focus 只在主召回阶段编码一次
- `cueBoostThisTurn` 只负责把分数加回 `fermentationScore`，不再在 persistence 公式里重复加一次
- 发酵记忆的黏性 / 惯性主要由 `fermentation_buffer` 自身的半衰期曲线决定
- `fermentationScore` 已经包含了跨轮衰减后的剩余热度，以及本轮 cue 回灌后的再加热结果

## 7. 抽样与阀门模型

### 7.1 温度抽样

以下这些“候选之间的抽样竞争”使用同一套温度模型：

```text
exponent = 2.6 - clamp01(wanderingMind) * 2.0
sampleWeight = max(0.0001, score) ^ exponent
```

含义：

- `wanderingMind` 越低，指数越高，分布越尖锐，越偏高分
- `wanderingMind` 越高，指数越低，分布越平坦，越容易抽到次高分

当前温度模型接入的位置：

1. `selectPromotionCandidates(...)`
   - 用于从主召回 / 发酵层混合候选里抽出 `promotedCandidates`
   - 权重使用 `candidate.combinedScore`
2. `pickCueTags(...)`
   - 用于从每条表层记忆的 associative tags 中抽出 sandbox cue tags
   - 权重使用 tag priority
3. `recallAssociationSandbox(...)`
   - 用于从 sandbox 候选记忆里抽出最终联想结果
   - 权重使用 `candidate.combinedScore`
4. `pickWeightedImplantedFallback(...)`
   - 用于从植入记忆保底候选里补 1 条
   - 权重使用 `rawWeight * recentPenalty`

当前 **不使用温度抽样** 的位置：

- `tag_pool -> 主召回 cue` 的构造本身
- 主召回总评分公式
- 发酵层保留评分公式
- `retained fermentation` 的最终截断排序
- `conscious memories` 的最终截断排序

因此目前 `wanderingMind` 控制的是“高分候选之间有多随机”，而不是“候选本身怎么打分”。

### 7.2 Prompt Fill 阀门

`conscious memories -> prompt memories` 不使用上面的温度模型，而是单独走一层 Bernoulli 阀门：

```text
normalized = clamp01(consciousScore / 2.40)

speakProbability =
  clamp(
    0.0,
    1.0,
    (0.08 + normalized * 0.84)
    * LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE
  )

filledIntoPrompt = random(0, 1) < speakProbability
```

含义：

- `consciousScore` 越高，越可能真的被说出口
- 但即使已经进入 `conscious memories`，本轮仍然可能选择不说
- 允许欠填，也允许本轮 `0` 条记忆进入 prompt
- `LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE` 越低，整体越不愿意说；越高，整体越愿意说
- 若某条记忆是“植入记忆保底”强制补入的那一条，则会直接跳过这个阀门，保证它真的进入 prompt

## 8. 候选保留与表层意识

### 8.1 Retained Fermentation

发酵层保留上限：

```text
retentionCap = retention_memory_cap
```

排序规则：

1. `score` 降序
2. `supportTags.size()` 降序
3. `memoryId` 字典序

随后：

- 这里只截出 `retained fermentation`

### 8.2 Conscious Memories

`conscious memories` 不再直接等于 retained 的前 `N` 名，而是单独走一层显性浮现排序：

```text
consciousScore = retainedScore * recentPenalty
```

其中：

- `retainedScore` 是上一层 retained fermentation 的分数
- `recentPenalty` 来自第 5.4 节的 suppression table 曲线

表层意识上限：

```text
consciousCap = conscious_memory_cap
```

排序规则：

1. `consciousScore` 降序
2. `retainedScore` 降序
3. `supportTags.size()` 降序
4. `memoryId` 字典序

含义：

- retained 表示“这条记忆当前还在脑中发热”
- conscious 表示“这条记忆这一轮是否真的浮到表层”
- 因此最近显性提起过的记忆，仍可以先进入 conscious
- 但它会在 conscious 层被 `recentPenalty` 压住，降低后续真的说出来的概率

### 8.3 Prompt Memories

`prompt memories` 是 `conscious memories` 的一个子集：

- 它们经过第 7.2 节的 `prompt fill` 阀门后，才真正进入 prompt
- 只有这一层会登记 suppression table
- 只有这一层会被从 `retained fermentation / fermentation_buffer` 中抛出
- 因此“想到但没说”的记忆，会继续留在 retained 里发酵

## 9. Sandbox 联想

每条表层记忆先挑若干个 associative tags，构造 sandbox cue：

```text
sandboxCueBudget = sandbox_cues_per_memory
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

- `sandbox_cues_per_memory` 现在表示“本轮 sandbox cue 总预算”，不是“每条 conscious memory 分到多少个 cue”
- Studio 当前将其限制为 `1 ~ 32`
- sandbox 结果只写入 `association_sandbox.memoryIds`
- 不会污染主 `fermentation_buffer`

## 10. 植入记忆保底

触发条件：

- `implanted_memory_safety_net = true`
- 正常 `conscious memories` 里没有 `SourceType.IMPLANTED && occurredGameTime < 0`
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

说明：

- 植入记忆保底不再额外使用独立的 recent hard skip
- 是否还能被补捞到，统一由 `recentPenalty * rawWeight` 决定

抽到之后，不会继承候选权重本身，而是以固定注入分写入本轮 selection：

```text
fallbackEntryScore = LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE
```

当前默认值：

- `1.25`

注意：

- `weight` 决定“从哪些 implanted 里抽中哪一条”
- `fallbackEntryScore` 决定“抽中之后，它在 conscious / retained 竞争里有多强”
- 被这条 safety net 补进来的记忆会强制通过 `prompt fill` 阀门

最后用同一套 `wanderingMind` 温度抽样，只补 1 条

## 11. 当前真正可调的参数

### Agent State

- `concentration`
- `wandering_mind`
- `recall_preset`
- `recall_tuning.main_candidates_per_cue`
- `recall_tuning.sandbox_cues_per_memory`
- `recall_tuning.conscious_memory_cap`
- `recall_tuning.retention_memory_cap`
- `implanted_memory_safety_net`
- `current_desire_tag`

### Config

- `LTM_RECALL_MAIN_CANDIDATES_PER_CUE`
- `LTM_RECALL_SANDBOX_CUES_PER_MEMORY`
- `LTM_RECALL_CONSCIOUS_MEMORY_CAP`
- `LTM_RECALL_RETENTION_MEMORY_CAP`
- `LTM_RECALL_RECENT_WINDOW`
- `LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE`
- `LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE`
- `LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS`
- `LTM_RECALL_TAG_POOL_DECAY_SHAPE`
- `LTM_RECALL_TAG_POOL_DROP_THRESHOLD`
- `LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS`
- `LTM_RECALL_FERMENTATION_DECAY_SHAPE`
- `LTM_RECALL_FERMENTATION_DROP_THRESHOLD`
- `LTM_RECALL_INTERVAL_BONUS_SCALE`
- `LTM_RECALL_INTERVAL_BONUS_STEEPNESS`
- `LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE`
- `LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE`
- `LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE`

注意：

- `LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS`
- `LTM_RECALL_TAG_POOL_DROP_THRESHOLD`
- `LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS`
- `LTM_RECALL_FERMENTATION_DROP_THRESHOLD`
- `LTM_RECALL_INTERVAL_BONUS_SCALE`
- `LTM_RECALL_INTERVAL_BONUS_STEEPNESS`

目前已经接入运行时，并通过 `studio_workspace/memory_recall_tuning.json` 作为 authored 真源生效

现在额外补充：

- `memory_recall_tuning.json` 是 recall 公式参数的 workspace 真源
- Studio 的 `Recall Workbench` 页面直接编辑这份文件
- 不改变函数形状的参数，在 Studio 上用简单滑块调节
- 会改变函数曲线形状的参数，在 Studio 上保留函数图像
- 游戏启动和 Studio 发布时，都会先读取这份文件，再同步回 `TalkingWeaponConfig` 与 `talking_weapon-client.toml`
- `LTM_RECALL_RECENT_WINDOW_CAP` 只作为兼容旧配置的保留字段继续存在，运行时和 Studio 现在都不再把它当成有效调参项

### Attention Layer

- `attention_weights.*`
- `attention_weights.common`
- `LOOKING_AT_ATTENTION_BONUS_ENABLED`
- `LOOKING_AT_ATTENTION_BONUS_VALUE`

### 11.1 参数 -> 公式 -> 输入/输出

下面只列 recall 页里当前重点暴露的参数。Studio 页面会按这个映射做卡片和函数图像。

#### Agent State / Runtime

| 参数 | 主公式 | 输入 | 输出 |
|---|---|---|---|
| `wandering_mind` | `exponent = 2.6 - clamp01(wanderingMind) * 2.0` | `candidate score` | `sampleWeight` |
| `recall_tuning.main_candidates_per_cue` | `promotionBudget = clamp(1, 32, round(mainCandidatesPerCue))` | configured budget | `promotion budget` |
| `recall_tuning.sandbox_cues_per_memory` | `sandboxCueBudget = clamp(1, 32, round(sandboxCuesPerMemory))` | configured budget | `sandbox cue count` |
| `recall_tuning.conscious_memory_cap` | `consciousCap = clamp(1, 16, round(consciousMemoryCap))` | retained candidates | `max conscious count` |
| `recall_tuning.retention_memory_cap` | `retentionCap = clamp(1, 16, round(retentionMemoryCap))` | retained candidates | `max retained count` |
| `implanted_memory_safety_net` | fallback trigger gate | `cue_pool` + implanted hit presence | `是否运行 implanted fallback` |
| `current_desire_tag` | desire branch selector | memory / focus 是否含该 tag | `是否激活 desire bonus 分支` |

#### Config / Formula Mirror

| 参数 | 主公式 | 输入 | 输出 |
|---|---|---|---|
| `LTM_RECALL_MAIN_CANDIDATES_PER_CUE` | `promotionBudget = clamp(1, 32, round(mainCandidatesPerCue))` | configured budget | `default promotion budget` |
| `LTM_RECALL_SANDBOX_CUES_PER_MEMORY` | `sandboxCueBudget = clamp(1, 32, round(sandboxCuesPerMemory))` | configured budget | `default sandbox cue count` |
| `LTM_RECALL_CONSCIOUS_MEMORY_CAP` | `consciousCap = clamp(1, 16, round(consciousMemoryCap))` | retained candidates | `default conscious cap` |
| `LTM_RECALL_RETENTION_MEMORY_CAP` | `retentionCap = clamp(1, 16, round(retentionMemoryCap))` | retained candidates | `default retained cap` |
| `LTM_RECALL_RECENT_WINDOW` | `effectiveWindow = clamp(0, 16, round(recentRecallWindow))` + release curve | `turnsSincePrompt` | `recentPenalty` |
| `LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE` | `fallbackEntryScore = config` | selected fallback memory | injected selection score |
| `LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE` | `speakProbability = clamp(0, 1, rawProbability * scale)` | `consciousScore` | `speakProbability` |
| `LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS` | `decayFactor = exp(- (deltaDays / halfLifeDays) ^ decayShape)` | `deltaDays` | `tagPool decayFactor` |
| `LTM_RECALL_TAG_POOL_DECAY_SHAPE` | 同上 | `deltaDays` | `tagPool decayFactor` |
| `LTM_RECALL_TAG_POOL_DROP_THRESHOLD` | `drop if tagScore < threshold` | `tagScore` | `tag 是否保留在 pool 中` |
| `LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS` | `decayFactor = exp(- (deltaDays / halfLifeDays) ^ decayShape)` | `deltaDays` | `fermentation decayFactor` |
| `LTM_RECALL_FERMENTATION_DECAY_SHAPE` | 同上 | `deltaDays` | `fermentation decayFactor` |
| `LTM_RECALL_FERMENTATION_DROP_THRESHOLD` | `drop if fermentationScore < threshold` | `fermentationScore` | `记忆是否保留在发酵层中` |
| `LTM_RECALL_INTERVAL_BONUS_SCALE` | `intervalBonus = clamp(1, 2.6, 1 + min(1.6, log1p(days * steepness) * scale))` | `deltaDays` | `intervalBonus` |
| `LTM_RECALL_INTERVAL_BONUS_STEEPNESS` | 同上 | `deltaDays` | `intervalBonus` |
| `LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE` | `ageFactor = clamp(0.15, 1.0, exp(-ageDays / halfLifeDays))` | `ageDays` | `ageFactor` |
| `LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE` | `desireBonus = scale * focusWeight(tag)` | `focusWeight(tag)` | `desireBonus` |
| `LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE` | `desiredFocusBonus = scale * focusWeight(desireTag)` | `focusWeight(desireTag)` | `desiredFocusBonus` |

`LTM_RECALL_MAIN_CANDIDATES_PER_CUE`、`LTM_RECALL_SANDBOX_CUES_PER_MEMORY`、`LTM_RECALL_CONSCIOUS_MEMORY_CAP`、`LTM_RECALL_RETENTION_MEMORY_CAP` 的定位补充：

- 它们是 `TalkingWeaponConfig` 中给 `RecallPreset.CUSTOM` 兜底的默认值
- 真正运行时若 `agent_state.json.state.recall_tuning` 存在，则优先吃 Agent State
- Studio recall 页会把这四项和 runtime 的 `recall_tuning.*` 分开显示，避免把“默认值”和“当前生效值”混在一起

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
- 降低 `LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE`

### 想让手工植入记忆更容易显性出现

- 打开 `implanted_memory_safety_net`
- 选择 `FULL_VISIBILITY`
- 提高 `main_candidates_per_cue`
- 提高 `LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE`

### 想关闭一跳回灌联想

- 当前 Studio 滑块最小值为 `1`，因此不会在页面上直接关到 `0`
