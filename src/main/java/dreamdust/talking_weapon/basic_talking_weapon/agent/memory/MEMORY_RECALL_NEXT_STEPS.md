# 记忆系统重构明日清单

本文档用于承接 `2026-03-19` 之前的讨论，目标是让下一次继续工作时，不需要重新回忆今天已经做出的设计判断。

另见：

- [README.md](README.md)
- [RECALL_STRATEGIES.md](RECALL_STRATEGIES.md)

## 当前工作断点（2026-03-22）

本轮工作先暂时停在：

- recall 子系统已经基本完成当前阶段的设计与实现对齐
- `ActionArchive` 已拆成：
  - 原始动作时间线
  - 固定时间窗 compaction 视图
  - 第一版 `PlayerIntentScorer` 规则打分视图
- 当前在 `right_click` fire 时写出的 `action_archive_dump` 日志，已经可以直接看到：
  - 原始动作
  - compaction 结果
  - 每段的 intent 分数、主导 intent 和理由

也就是说：

- 现在已经能做“阶段性意图观察”
- 但还**不能直接把这份动作档案喂给 LLM 做长期记忆生成**

原因是当前的动作档案仍然过于接近“调试视图”，而不是“适合 LLM 消化的记忆生成输入”。

### 下一阶段的主任务

接下来优先级最高的不是继续改 recall，而是补上 **LTM 生成前的动作档案整理层**。

这一层至少要解决四件事：

1. **进一步压缩动作档案**
   - 当前固定时间窗 compaction 仍然偏接近事件统计层
   - 后续还需要一层更靠近 LLM 输入的压缩
   - 目标不是丢掉时间顺序，而是在保留阶段因果关系的前提下，把动作整理成更短、更稳定、更可读的片段

2. **按重要度过滤低价值信息**
   - 不是所有动作都值得进入记忆生成输入
   - 需要一层“动作 / 片段重要度”筛选，把大量重复、低价值、无叙事意义的行为过滤掉
   - 例如：惯性挖一块土、路上随手做的小动作，不应和真正的阶段行为占同样权重

3. **增加“是否值得生成记忆”的阀门**
   - 即使有一个阶段，也不代表一定要生成长期记忆
   - 需要一个 generation gate，判断这段行为是否值得结算成 LTM
   - 这层之后应负责回答：
     - 这段是否足够重要
     - 是否有明确主题
     - 是否和已有阶段相比发生了足够明显的偏移或结算点
     - 是否值得花一次 LLM 配额去生成记忆

4. **和“重要度重构”统一设计**
   - 上面的“压缩”“过滤”“生成阀门”都不应各自维护一套孤立规则
   - 它们应尽量共享同一套 importance / salience 设计语言
   - 也就是说，后续的“动作重要度”“episode 重要度”“是否值得生成记忆”，应该和之前计划中的重要度重构放在一起考虑

### 下一阶段建议的实现顺序

建议按下面顺序继续：

1. 先定义 **LLM-facing Action Archive** 的目标格式
   - 明确它和当前 debug compaction 的区别
   - 明确一段输入里保留哪些字段
   - 明确怎样保留时间顺序但降低语义噪声

2. 再定义 **动作 / 片段重要度模型**
   - 先给动作条目和压缩片段做 importance 打分
   - 再决定哪些信息会被过滤

3. 再定义 **memory generation gate**
   - 用 importance、intent shift、主题性、稀有性等信号做一道阀门
   - 决定“这一段要不要进入长期记忆生成”

4. 最后再把这套整理后的输入接给 LLM
   - 让 LLM 看到的是“值得总结的阶段材料”
   - 而不是原始动作流水账

### 下一阶段暂时不要做的事

- 不要急着把当前 `ActionArchive` compaction 直接喂给 LLM
- 不要在还没有 importance 模型之前就写死 episode 过滤规则
- 不要把“是否值得生成记忆”做成和 importance 完全割裂的独立黑盒
- 不要现在就回头重做 recall 调参

### 一句话提醒

接下来要做的不是“把更多动作塞给 LLM”，而是：

- 先把动作档案整理成真正适合 LLM 理解的阶段材料
- 再决定哪些阶段值得生成长期记忆

## 今天已经确定的结论

### 1. 用户层要按两条轴设计，而不是直接暴露底层算法

- `recall_style`
  - `NATURAL`
  - `FEEDBACK`
- `implanted_visibility`
  - `SUBTLE`
  - `STANDARD`
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

- `mainCandidatesPerCue`
- `sandboxCuesPerMemory`
- `consciousMemoryCap`

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
