# 长期记忆召回统一模型设计

## 文档目标

本文档用于定义长期记忆召回的唯一统一模型。

当前不再把 `SEED_FIRST`、`FULL_VISIBILITY`、`HYBRID` 理解为三套不同实现，而只把它们理解为：

- 同一套运行时模型上的三种参数预设
- 主要区别体现在 cue 权重曲线、发酵层强度、表层意识刷新速度、联想扩散预算

因此，后续实现与文档讨论都以这套统一模型为准。

## 统一模型的核心思想

记忆召回不再被理解为“当前场景直接搜出几条记忆”，而被理解为一个持续维护的认知流：

1. 当前 `visibleContexts` 提供新的 tag cue
2. 新 cue 进入一个持续维护的 `tag_pool`
3. `tag_pool` 触发并更新 `fermentation_buffer`
4. `fermentation_buffer` 中的记忆经过两次筛选，少量进入 `conscious_memories`
5. `conscious_memories` 再在本轮隔离环境中触发一次受限联想

这套模型的重点不是单次 recall，而是：

- cue 如何积累
- 记忆如何发酵
- 哪些记忆真正浮到表层意识
- 联想如何在不污染主状态的前提下影响本轮输出

## 统一模型中的四个状态对象

### 1. `tag_pool`

`tag_pool` 是一个持续维护的 `tag -> score` buffer。

它表示：

- 当前脑内哪些概念处于活跃状态
- 这些概念当前活跃到什么程度

它不是简单的“本轮 visible tag 列表”，而是一个跨轮维护的语义激活池。

### 2. `fermentation_buffer`

`fermentation_buffer` 保存一批“已经被碰到、正在发酵的候选记忆”。

其中每条记忆都带有自己的运行时分数，这个分数会随着：

- 游戏内时间衰减
- 当前 `tag_pool` 的 cue 加分
- 自身属性模型

而持续变化。

### 3. `conscious_memories`

`conscious_memories` 是本轮真正进入表层意识的少量记忆。

只有这一层的记忆才会真正参与：

- prompt 中的 recalled memories
- inner monologue
- 角色直接说出口的回忆

### 4. `association_sandbox`

`association_sandbox` 是本轮专用的联想隔离环境。

它的作用是：

- 从本轮 `conscious_memories` 中提取 tag
- 在一个隔离的、本轮临时存在的环境中做受限联想
- 只服务本轮输出，不污染下一轮主状态

这个对象是为了避免“表层意识的回灌联想”直接污染主 `tag_pool`。

## 统一模型的标准轮转流程

下面的流程是后续实现的标准顺序。

### 第 0 步：计算游戏内时间差

计算本次触发与上次触发之间的游戏内时间差：

- `delta_game_time`

后续所有衰减与冷启动增幅，都以游戏内时间而不是触发频率为基础。

### 第 1 步：衰减主 `tag_pool`

对主 `tag_pool` 中已有的 tag 分数按 `delta_game_time` 做衰减。

这里的原则是：

- 旧 tag 分数只会衰减
- 不会因为这次间隔变长而额外获得“补偿性抬升”

### 第 2 步：衰减主 `fermentation_buffer`

对 `fermentation_buffer` 中已有的候选记忆按 `delta_game_time` 做衰减。

这里的衰减同样只看游戏内时间，而不是触发次数。

### 第 3 步：从 `visibleContexts` 注入新 tag

从当前 `visibleContexts` 中汇总本轮新出现的 detail tags。

这些新 tag 会形成一次新的注入值：

- `injected_tag_score`

### 第 4 步：只对新注入 tag 应用冷启动间隔增幅

如果本次触发与上次触发之间存在较长的游戏内时间差，那么：

- 只对本轮新注入的 tag 分值应用 `interval_bonus(delta_game_time)`
- 不对旧 `tag_pool` 中原有 tag 的残留分数应用该增幅

这样做的意义是：

- 模拟“这些新 cue 已经在背景中积累了一段时间”
- 让长间隔后的第一次触发更容易冷启动
- 避免旧池子中的所有 tag 一起被无差别抬高

因此，主池写入可以概括为：

- `tag_pool[tag] = decayed_old_score + injected_tag_score * interval_bonus(delta_game_time)`

其中：

- `decayed_old_score` 只经过衰减
- `injected_tag_score` 才会吃到间隔增幅

### 第 5 步：用主 `tag_pool` 召回新候选记忆

主 `tag_pool` 中的 tag 及其分数会用于召回新的长期记忆候选。

这里的 recall 不直接决定“进入 prompt 的记忆”，而是先生成本轮的新候选记忆集合。

### 第 6 步：更新 `fermentation_buffer`

`fermentation_buffer` 中已有的记忆会先经历两类变化：

1. 已在第 2 步完成的时间衰减
2. 来自主 `tag_pool` 的 cue 加分

然后，旧发酵记忆与新召回出来的记忆候选合并，统一进入发酵层竞争。

### 第 7 步：第一次 `top-k`，决定谁留在发酵层

对“旧发酵记忆 + 本轮新记忆候选”的统一集合做第一次 `top-k`：

- 低分记忆被逐出 `fermentation_buffer`
- 高分记忆构成新的 `fermentation_buffer`

这一步决定的是：

- 哪些记忆继续留在脑中发酵

### 第 8 步：第二次 `top-k`，决定谁进入表层意识

对新的 `fermentation_buffer` 再做第二次 `top-k`：

- 只有顶部少量记忆进入 `conscious_memories`

这一步决定的是：

- 本轮真正“被想到”或“被说出来”的记忆

因此：

- 发酵层容量与表层意识容量不应相同
- 一条记忆可以继续留在发酵层，但这轮不一定进入表层意识

### 第 9 步：基于 `conscious_memories` 建立 `association_sandbox`

从本轮进入表层意识的记忆中提取它们的 tags。

这些 tag 不直接写回主 `tag_pool`，而是被写入一个本轮隔离环境：

- `association_sandbox`

这个环境的特点是：

- 只在本轮存在
- 与主 `tag_pool` 隔离
- 与主 `fermentation_buffer` 隔离

### 第 10 步：在 `association_sandbox` 中做一次受限联想

在 `association_sandbox` 中，用来自 `conscious_memories` 的 tag 做一次受限 recall。

约束建议：

- 默认只允许 `1-hop`
- 预算应明显小于主 recall
- 结果主要用于补充本轮输出，而不是重新塑造主状态

### 第 11 步：沙盒结果只服务本轮输出

`association_sandbox` 中得到的联想结果默认只服务本轮：

- 可参与本轮 prompt
- 可参与本轮 inner monologue
- 可参与长输出任务中的主题展开

但默认不回写：

- 主 `tag_pool`
- 主 `fermentation_buffer`

这样可以彻底避免联想链对下一轮主状态的污染。

## 统一模型中的评分流向

这套统一模型中，分数的流动分为两条主线：

### 1. `tag_pool` 的 tag 分线

tag 的分数由以下内容决定：

- 上一轮残留分数经游戏内时间衰减后的值
- 本轮新注入的 cue 分值
- 本轮新注入 cue 的间隔增幅

这里的关键点是：

- 间隔增幅只作用于新注入 cue
- 不作用于旧 tag 残留值

### 2. `fermentation_buffer` 的记忆分线

一条候选记忆在发酵层中的分数，建议理解为：

- 旧残留值
- 减去时间衰减
- 加上来自主 `tag_pool` 的 cue 加分
- 再结合自身模型分数

自身模型分数可以继续由以下因子构成：

- cue 匹配
- focus 对齐
- source gate
- importance / clarity
- age factor
- recent recall penalty

## 联想系统在统一模型中的位置

联想系统仍然保留，但定位已经变化。

它不再是默认的“主 recall 深跳机制”，而主要用于：

- 让本轮已进入表层意识的记忆继续激活相关主题
- 支持本轮输出中的局部延展
- 支持特殊任务态中的主题化展开

因此，联想的默认角色是：

- 受限
- 隔离
- 不污染主状态

## 长输出任务如何接入统一模型

后续如果加入情绪驱动的长输出任务，例如：

- 自白
- 掏心窝
- 大谈自身往事

则不应重开另一套记忆系统，而应复用统一模型。

推荐方式是：

- 仍然先用主模型得到 `conscious_memories`
- 再从 `conscious_memories + association_sandbox` 出发
- 在任务态里执行“多轮单跳联想”

也就是说：

- 普通回合只做一次 `association_sandbox`
- 长输出任务可以连续做多轮隔离沙盒联想

这样既保留了统一模型，也允许长输出时做更深的主题展开。

## 三种策略在统一模型上的区别

既然只保留一套统一模型，那么三种策略的区别只应体现在参数上。

### `SEED_FIRST`

重点参数倾向：

- cue 权重曲线非常陡
- 低重要度 tag 更容易被压到 `0`
- 表层意识容量更小
- 本轮显性回忆更稀疏

效果上更像：

- 被少数强 cue 触发

### `FULL_VISIBILITY`

重点参数倾向：

- cue 权重曲线更平滑
- 大多数 visible tag 仍保留非零贡献
- recall 更积极地把候选送入发酵层
- 更强调冷启动稳定与反馈

效果上更像：

- 所有当前线索共同参与召回

### `HYBRID`

重点参数倾向：

- cue 权重曲线可以接近 `FULL_VISIBILITY`
- 更强调 `fermentation_buffer` 的持续性
- 更强调 `conscious_memories` 的稳定度
- 更强调联想在发酵与主题延续中的作用

效果上更像：

- 起点稳定，但浮现更慢、更持续

## 当前不做的复杂化设计

为了控制实现成本，当前阶段先不引入更复杂的 tag 分层字典，例如：

- `instant tags`
- `persistent tags`
- `associative tags`

原因是：

- 当前系统实现这类分层需要额外维护来源字典
- 代价较高
- 当前设计已经可以用“主 `tag_pool` + `association_sandbox`”满足主要需求

因此，当前阶段的设计约束是：

- 主状态只维护一个主 `tag_pool`
- 回灌联想通过隔离的 `association_sandbox` 完成

## 对当前项目的建议

### 近期建议

- 后续文档和实现都以这套统一模型为准
- 三种策略仅作为参数预设存在
- 优先先把以下变量和流程固定下来：
  - `tag_pool`
  - `fermentation_buffer`
  - `conscious_memories`
  - `association_sandbox`
  - `interval_bonus(delta_game_time)`

### 中期建议

- 为三种策略补出共享参数表与差异参数表
- 定义 `fermentation_buffer` 的精确数据结构
- 定义 `association_sandbox` 的生命周期与预算
- 定义 `IMPLANTED` 与 `SYNTHETIC` 的独立评分模型

## 一句话总结

- 当前只保留一套统一记忆模型
- 主状态由 `tag_pool -> fermentation_buffer -> conscious_memories` 构成
- 表层意识的联想回灌只发生在隔离的 `association_sandbox` 中
- 间隔增幅只作用于本轮新注入的 tag，而不作用于旧 buffer 中的残留值
- `SEED_FIRST`、`FULL_VISIBILITY`、`HYBRID` 只是这套统一模型上的三种参数预设
