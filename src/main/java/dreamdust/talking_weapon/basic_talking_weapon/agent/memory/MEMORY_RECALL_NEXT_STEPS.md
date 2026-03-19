# 记忆系统重构明日清单

本文档用于承接 `2026-03-19` 之前的讨论，目标是让下一次继续工作时，不需要重新回忆今天已经做出的设计判断。

另见：

- [README.md](README.md)
- [RECALL_STRATEGIES.md](RECALL_STRATEGIES.md)

## 今天已经确定的结论

### 1. 用户层要按两条轴设计，而不是直接暴露底层算法

- `recall_style`
  - `NATURAL`
  - `FEEDBACK`
- `implanted_visibility`
  - `SUBTLE`
  - `BALANCED`
  - `EXPLICIT`

含义：

- `NATURAL` 强调自然浮现和持续发酵
- `FEEDBACK` 强调玩家能明显看到编辑结果
- `implanted_visibility` 单独控制植入记忆是否要被明确体现

补充：

- 底层仍可保留 `SEED_FIRST / FULL_VISIBILITY / HYBRID`
- 但三者应理解为同一套 recall 模型下的不同参数预设，而不是三套独立实现
- 其中 `SEED_FIRST` 可以理解为“cue 权重曲线非常陡的全量 visible tag 模式”

### 2. 运行时结构改为统一模型下的四个状态对象

- `tag_pool`
  - 主状态中的 `tag -> score` buffer
- `fermentation_buffer`
  - 正在发酵的候选记忆
- `conscious_memories`
  - 本轮真正进入表层意识的少量记忆
- `association_sandbox`
  - 本轮隔离的联想环境，只服务本轮输出

### 3. 联想系统保留，但必须隔离

- 普通回合里，联想默认只保留 `1-hop`
- `2-hop+` 不再作为默认显性 recall 主机制
- 表层意识回灌出来的联想必须进入 `association_sandbox`
- `association_sandbox` 默认不回写主 `tag_pool` 或主 `fermentation_buffer`

### 4. 冷启动间隔增幅的正式定义

- 按游戏内时间计算 `delta_game_time`
- 间隔增幅只作用于“本轮新注入的 tag 分值”
- 旧 `tag_pool` 中的残留 tag 不吃这个增幅
- 旧 `fermentation_buffer` 中的残留记忆也不吃这个增幅

### 5. 未来的长输出任务单独处理

- 长输出任务不是“普通 recall 加大号”
- 应设计成独立任务态：`rumination_monologue`
- 采用“多轮单跳联想”，而不是一次性深跳
- 起点优先来自“当前脑中记忆 + 发酵层高位记忆”，而不是重新从原始 world cue 冷启动

### 6. 记忆来源至少先分两类

- `IMPLANTED`
- `SYNTHETIC`

其中：

- `SYNTHETIC` 更像经验 / 总结记忆，主要靠 cue、时间、重要度竞争
- `IMPLANTED` 还要经过单独的“身份 / 关系共鸣门控”

### 7. 植入记忆保底保留，但定位变化

- 不再被理解为通用 recall 补丁
- 更适合作为 `EXPLICIT` 模式下的 authored 保障
- 以后建议在“正常链路未体现植入记忆，但植入记忆已在发酵层高位”时触发

## 明天优先做什么

建议按下面顺序继续，不要直接跳进代码。

### 第一步：把参数模型设计定下来

需要先在文档中明确：

- `SEED_FIRST`、`FULL_VISIBILITY`、`HYBRID` 三者共用哪些参数
- 哪些参数专门控制 cue 权重曲线
- `NATURAL` 需要哪些参数
- `FEEDBACK` 需要哪些参数
- 哪些参数属于 recall
- 哪些参数属于 fermentation
- 哪些参数属于 conscious layer
- 哪些参数属于 implanted gate

目标：

- 不再沿用旧的 `AssociationMode = 深度 / 宽度` 直觉
- 让后续状态结构和 UI 设计有清晰依据

### 第二步：定义四个状态对象的数据结构

需要先在文档中明确：

- `tag_pool` 的 `tag -> score` 结构
- `association_sandbox` 的输入、生命周期和预算

同时明确一条发酵中的记忆至少要记录什么：

- memory id
- 当前发酵分数
- 上次被 cue 命中的时间或轮次
- 当前主要主题 / 关联 cue
- 是否刚进入过 conscious layer
- 来源类型：`IMPLANTED / SYNTHETIC`

目标：

- 先把运行时状态长相说清楚
- 再考虑落到 `AgentMemory` 还是单独 runtime state

### 第三步：定义植入记忆门控模型

需要在文档中明确：

- `cue_match` 最低要求是什么
- `relationship_resonance` 由哪些状态构成
- `authored_visibility` 如何影响最终得分
- safety net 在什么条件下才触发

目标：

- 避免未来把植入记忆重新做回“纯标签命中”

### 第四步：定义长输出任务态

需要在文档中明确：

- 什么条件会触发 `rumination_monologue`
- 最多跑几轮单跳联想
- 每轮最多保留多少条核心记忆线程
- 最终给 LLM 的输入格式是“列表”还是“主题线程”

目标：

- 把这条链路与普通回合彻底区分开

### 第五步：再回头决定旧参数怎么迁移

要重新审视这些旧字段：

- `maxDepth`
- `branchPerMemory`
- `memoryPickPerSeed`
- `finalMemoryCap`
- `seedEntryCount`
- `tagsPerEntry`

目标：

- 决定哪些保留
- 决定哪些改名
- 决定哪些彻底废弃

## 明天暂时不要做的事

- 不要直接改 `LTMRecallEngine`
- 不要先改 Studio UI
- 不要先改 `AgentState` 持久化结构
- 不要让表层意识回灌直接写回主 `tag_pool`
- 不要急着恢复多级显性联想

原因：

- 现在最容易返工的地方不是实现，而是参数结构和状态边界还没完全定型

## 明天开始前先看的文件

建议顺序：

1. [RECALL_STRATEGIES.md](RECALL_STRATEGIES.md)
2. [MEMORY_RECALL_NEXT_STEPS.md](MEMORY_RECALL_NEXT_STEPS.md)
3. [README.md](README.md)

如果明天要进入实现阶段，再看：

1. [LTMRecallEngine.java](LTMRecallEngine.java)
2. [AgentState.java](../state/AgentState.java)
3. [AgentMemory.java](AgentMemory.java)

## 明天最可能产出的文档成果

如果进展顺利，明天最好至少完成下面三件文档工作：

- 为 `SEED_FIRST / FULL_VISIBILITY / HYBRID` 列一张共享参数表
- 补一节“四个状态对象的数据结构设计”
- 补一节“Implanted Gate 的评分公式草案”

## 一句话提醒

明天继续时，先守住这条原则：

- 普通回合强调自然与稳定
- 长输出任务强调主题化展开
- 联想默认只做 `1-hop` 显性影响
- 深层联想主要服务隔离沙盒和主题展开，而不是直接污染主状态
