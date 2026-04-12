# Desire System Plan

## 背景

当前 `AgentState` 中已经存在：

- `currentDesireTag`
- `desireDescription`

它们现在主要承担：

- 提示词注入
- recall 权重偏置
- 上下文中的“当前渴望”文本提示

但它们还不是一个完整的 desire 机制。

当前缺口在于：

- desire 不是结构化对象
- desire 没有生命周期
- desire 不能稳定地被生成、检查、结算、消费
- desire 也不能形成对后续行动的持续约束

本方案的目标，是把 desire 从“单条描述字段”升级成一个可调度、可结算的小型目标系统。

## 核心结论

### 1. desire 不是单槽，而是小列表

运行时允许同时存在一个非常小的 desire 列表。

当前建议：

- 最多 `3` 个 active desire 槽

但同一时刻只允许：

- `1` 个主导 desire

其余 desire 作为背景 desire 保留，不应与主导 desire 抢占同等表达权。

### 2. desire 不会被高频改写

desire 一旦生成，其核心语义应被冻结：

- 不在普通触发中被重写
- 不在每次高频检查中被替换
- 不因普通情绪波动直接消失

desire 只会在下面两种情况下进入消费流程：

1. 被判定为完成
2. 被玩家手动标记为无法完成

### 3. 高频阶段只做“是否完成”的窄判断

在 desire 存续期间，每次正常 action 触发都可以顺手做一次低成本判断：

- 当前 active desires 中，是否有 desire 已被满足

这个高频判断应尽量窄，不负责：

- 改写 desire
- 替换 desire
- 推导情绪和好感度结算

### 4. 真正昂贵的结算延后到后台 reflection

当 desire 被标记为：

- `fulfilled`
- `impossible`

后，才额外接一个后台 reflection task，正式执行：

- desire 消费
- 情绪更新
- 好感度更新
- 必要时生成后续 desire

## 设计目标

本方案希望同时满足：

1. desire 能持续影响角色的后续表达与注意力
2. desire 不会因为高频触发而不断漂移
3. desire 的完成可以被高频、低成本地检测
4. 真正的状态结算仍然保留给 reflection
5. 玩家可以显式告知系统“这个 desire 无法完成”

## 游戏设计定位

desire 在这里不是：

- 单次行动意图
- 临时情绪
- 一句随机牢骚

而是：

- 一个中低频的、持续存在的、可被结算的内在目标张力

它的价值在于：

- 给角色增加“心里一直惦记着某件事”的连续性
- 让 reflection 结果真正延伸到后续 action
- 让角色在不同回合中保持可追踪的主线驱动力

## Desire 分类

当前先正式引入两种 desire 类型，用来指导 LLM 生成 desire。

### 1. `TASK_DISPATCH`

任务派遣型 desire。

语义上表示：

- 剑灵希望玩家去做某件事
- 剑灵希望世界进入某种明确状态
- 剑灵希望推进一个外部可观察的目标

典型例子：

- 希望玩家去清理附近的威胁
- 希望玩家前往某处地点
- 希望玩家寻找某种材料
- 希望玩家完成一次复仇或试炼

设计特点：

- completion 条件更明确
- 更容易对玩家说出口
- 更适合形成“请求 / 指派 / 怂恿 / 命令”式表达

### 2. `EMOTIONAL_NEED`

情感需求型 desire。

语义上表示：

- 剑灵想被怎样对待
- 剑灵想获得什么关系体验或心理满足
- 剑灵想修复、确认、报复、依附或摆脱某种关系状态

典型例子：

- 希望玩家表现出尊重
- 希望玩家证明自己值得信任
- 希望得到赞许、安慰、服从、陪伴
- 希望看到某个仇敌受惩罚，以满足内在怨念

设计特点：

- completion 更依赖语境和关系证据
- 不一定一次性说透
- 更适合通过语气、试探、抱怨、暗示来表现

## 为什么要分类

分类不是为了美术标签，而是为了约束 desire 的生成和后续判断。

两类 desire 至少会影响：

- 是否应该公开表达
- completion 判断应该偏“事件型”还是“关系型”
- 结算时对情绪和好感度的影响方式
- 提示词中 LLM 应如何扮演

## 槽位模型

### 1. desire 列表大小

运行时维护：

- `List<ActiveDesire>`

容量上限为：

- `3`

### 2. 主导 desire

在所有 active desire 中，选出一个：

- `dominant desire`

它承担：

- 当前提示词中的主愿望投影
- recall / attention 的主偏置来源
- 最有机会被说出口的 desire

### 3. 背景 desire

其余 desire 作为背景 desire 存在：

- 仍然会被检查是否完成
- 仍然能影响较弱的注意力偏置
- 但默认不主动抢话

## ActiveDesire 最小结构

建议 desire 至少具备以下字段：

- `desireId`
- `type`
- `focusTag`
- `summary`
- `status`
- `priority`
- `createdRegionId`
- `createdGameTime`
- `announcedOnce`
- `shouldAnnounce`
- `fulfillmentSpec`
- `lastCheckedGameTime`
- `lastEvidenceGameTime`
- `resolutionCause`

### 字段语义

- `type`
  - `TASK_DISPATCH`
  - `EMOTIONAL_NEED`

- `focusTag`
  - desire 所属的主认知领域

- `summary`
  - desire 的冻结自然语言描述

- `status`
  - 当前生命周期状态

- `priority`
  - 在多 desire 共存时用于选出 dominant desire

- `announcedOnce`
  - 是否已经正式对玩家表达过

- `shouldAnnounce`
  - 新生成后是否需要在后续 action 中表达一次

- `fulfillmentSpec`
  - 对“什么算完成”的简短结构化说明

## 状态机

建议 desire 生命周期先采用下面这组状态：

- `ACTIVE`
- `FULFILLED_PENDING_SETTLEMENT`
- `IMPOSSIBLE_PENDING_SETTLEMENT`
- `RESOLVED`

### 状态含义

- `ACTIVE`
  - 正在生效、会被高频检查

- `FULFILLED_PENDING_SETTLEMENT`
  - 已被高频检查判定完成，等待后台结算

- `IMPOSSIBLE_PENDING_SETTLEMENT`
  - 被玩家手动标记无法完成，等待后台结算

- `RESOLVED`
  - 已经结算并消费完成

## desire 生成

### 1. 生成应是低频行为

desire 生成不是每次 episode 封口都一定发生。

它必须满足：

- 当前存在明确 tension
- LLM 能说清 desire 想要什么
- LLM 能给出可检查的 fulfillmentSpec
- 当前 desire 槽未满

### 2. 生成 gate 必须保守

由于 desire 不会被普通流程自动替换或清除，
所以生成 gate 必须非常保守。

如果 gate 太松，会出现：

- 槽位被无意义 desire 占满
- 后续新的更强 desire 无法进入
- 系统长期卡死在弱目标上

### 3. 生成触发建议

低频 desire 生成建议由后台 reflection 驱动。

可选时机：

- recent state update 后
- episode 封口后
- 特定强 state signal 出现后

第一版建议：

- 先接到 recent state update 链路中

原因：

- 它本来就承担低频状态刷新
- 材料是只读的
- 不会消费 source material

## desire 表达

### 1. desire 不是必须立刻说出口

新 desire 生成后，不应默认立刻强行抢占当前前台交互。

更稳的方式是：

- desire 生成时只设置 `shouldAnnounce`
- 在下一次前台空闲且语义合适的机会表达

### 2. 谁更适合表达

- `TASK_DISPATCH`
  - 更适合主动对玩家说出口
- `EMOTIONAL_NEED`
  - 更适合保留试探、抱怨、暗示式表达

### 3. 第一版建议

第一版可以保守处理：

- 只允许 dominant desire 被表达
- 每个 desire 最多正式“宣告”一次

## 高频完成判断

### 1. 检查频率

对 active desire 的完成判断应尽量高频。

建议：

- 每次正常 action LLM 调用都顺手检查一次

### 2. 实现方式

这里不建议为 desire 判断单独再开一次 LLM 调用。

更适合的方式是：

- 在现有 action task 的输出 contract 中增加一个轻量字段
- 让同一次 LLM 调用顺手返回 active desire 的完成判断

### 3. 输出字段建议

建议 action LLM 响应新增：

```json
{
  "desire_checks": [
    {
      "desire_id": "d-001",
      "verdict": "unchanged | fulfilled",
      "confidence": 0.86,
      "reason": "..."
    }
  ]
}
```

### 4. 高频判断的边界

高频 desire check 只负责：

- `unchanged`
- `fulfilled`

不负责：

- desire 内容改写
- desire 替换
- 情绪结算
- 好感度结算

## segment / episode 保底判断

除了每次 action 调用中的高频判断之外，还需要保底判断：

- fixed segment 封口
- episode 封口

保底判断的作用是：

- 覆盖普通 action 检查漏掉的满足事件
- 在没有合适前台交互的情况下，仍能推进 desire 状态

## desire 完成后的后台结算

### 1. 触发条件

当 desire 被标记为：

- `FULFILLED_PENDING_SETTLEMENT`
- `IMPOSSIBLE_PENDING_SETTLEMENT`

时，才发出后台 settlement reflection task。

### 2. settlement task 的职责

它负责：

- 正式消费 desire
- 结算对情绪的影响
- 结算对好感度的影响
- 更新 `AgentState`
- 必要时派生后续 desire

### 3. settlement 前 desire 不被删除

在 settlement reflection 真正执行前：

- desire 仍留在列表里
- 但不再参与普通 active 检查

## 玩家手动标记 impossible

### 1. 入口

提供一个玩家侧 UI：

- 选择 desire
- 点击“无法完成”
- 输入说明文本

### 2. 处理方式

玩家手动标记后：

- desire 状态改为 `IMPOSSIBLE_PENDING_SETTLEMENT`
- 记录玩家给出的 impossible reason
- 后台提交 settlement reflection task

### 3. 为什么需要这个入口

因为有些 desire：

- 从游戏逻辑上可能已无法达成
- 但单靠系统观察不容易自动判断

玩家手动输入能提供：

- 一个显式的外部世界信号

## 心理学视角

### 1. desire 的核心作用

这个机制的核心价值在于：

- 让角色拥有持续的“未完成张力”

相比情绪，desire 更像：

- 一个在数个回合中持续影响注意力和解释方式的目标对象

### 2. 为什么允许小列表

现实中的人也并非只有一个愿望。

允许极小列表是合理的，因为角色可以同时存在：

- 一个眼前最强的追求
- 若干尚未消失的背景需求

但槽位必须严格受限，否则会造成：

- 角色目标分裂
- 台词风格发散
- 表现像随机切换人格

### 3. 为什么要区分两类 desire

因为“任务派遣”和“情感需求”在心理表现上不同：

- `TASK_DISPATCH`
  - 更像外部目标
  - 更容易通过单一事件满足
- `EMOTIONAL_NEED`
  - 更像关系与自我价值缺口
  - 更容易通过一组行为证据渐进满足

如果不区分类型，LLM 很容易把 desire 生成成：

- 既不像任务
- 也不像需求
- 最后只剩一句抽象抱怨

## 第一阶段建议实现顺序

### Phase 1

先升级 desire 数据模型：

- 单槽字段升级为小型 desire 列表
- 明确状态机和 dominant desire 规则

### Phase 2

新增 desire 生成 reflection：

- 先接入 recent state update 链路
- 只在严格 gate 满足时生成

### Phase 3

在 action task 的输出协议里加入高频 desire check。

### Phase 4

新增 desire settlement reflection task。

### Phase 5

补玩家手动 impossible UI。

## 与现有单槽 desire 的关系

当前 `currentDesireTag + desireDescription` 已不足以承载完整 desire 机制。

最终应当：

- 由 `ActiveDesire` 列表取代单槽 desire

而提示词层、上下文层若仍需要一个“当前愿望”视图，
则应从：

- `dominant desire`

投影得到，而不是继续把单槽 desire 当作主真源。

## 一句话总结

本方案把 desire 设计成：

- 一个最多 3 槽的小型持续目标系统
- 高频只检查是否完成
- 真正结算延后到后台 reflection
- 并通过 `TASK_DISPATCH` / `EMOTIONAL_NEED` 两种类型约束 LLM 生成 desire 的形状
