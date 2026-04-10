# LLM-Facing Action Archive 设计草案

本文档用于重新澄清三个容易混淆的概念：

1. `ActionArchive` 的固定时间窗分析视图
2. 用于替代 `RecentActionBuffer` 的**新动作缓冲层**
3. 真正用于概括整个 episode 的 **LLM-facing Action Archive**

当前结论是：

- 这三者不是同一层
- 它们可以共享相同的“segment / entry”结构
- 但**生命周期和用途不同**

---

## 1. 先确认当前实现

### 1.1 `ActionArchive` 本体

`ActionArchive` 本体保存的是：

- 原始动作时间线
- 不做 TTL
- 不做 destructive consume
- 不在写入时自动切段

也就是说：

- `ActionArchive` 自己不是“每 60 秒一段”的存储
- 它保存的是连续原始记录

### 1.2 当前 60 秒分段来自 `ActionArchiveCompactor`

当前真正的固定分段逻辑在：

- `ActionArchiveCompactor`

当前默认参数是：

```java
DEFAULT_FIXED_SEGMENT_MS = 60_000L
```

也就是：

- **每 60 秒切一个固定长度的分析段**

补充说明：

- 这个 60 秒窗口现在对齐真实系统时间的固定窗口
- 不再以当前 snapshot 的第一条动作作为锚点
- 因此不同时间导出的 snapshot，只要动作落在同一个真实分钟窗口里，就会得到一致的 fixed segment 边界

当前这层主要用于：

- 调试
- intent scoring
- episode split 辅助分析

---

## 2. 当前真正混淆的地方

上一版文档把下面两件事混在了一起：

### 2.1 新的 action buffer

它应该是：

- 一个**移动窗口**
- 用于替代当前 `RecentActionBuffer`
- 更接近 prompt / 近期感知层
- 在 LLM 触发时，按“**当前时刻向前看一段时间**”生成一个单一的 prompt window
- window 内部仍然保留多条 action entry，而不是整窗压成一句话

### 2.2 LLM-facing Action Archive

它应该是：

- 一个**episode 级概括层**
- 用于把一整段 episode 整理成适合给 LLM 看的材料
- 不是滑动窗口
- 不是“当前最近几分钟”的 open segment

也就是说，之前最大的问题是：

- 把“给 prompt 的新 action buffer”
- 和“给 episode 总结的 LLM-facing archive”

写成了同一层。

这需要拆开。

---

## 3. 现在重新定义四层动作视图

### 3.1 Layer A: `ActionArchive`

职责：

- 保存原始动作时间线

特点：

- 连续
- 不消费
- 不 TTL
- 是 source of truth

### 3.2 Layer B: `ActionArchiveCompactor`

职责：

- 生成固定 60 秒分析段

特点：

- 固定时长
- 对每段做 `same type + same target` 聚合
- 适合：
  - intent scoring
  - episode split
  - 调试观察

### 3.3 Layer C: 新的 action buffer

职责：

- 替代当前 `RecentActionBuffer`
- 给 prompt / 近期感知层提供输入

特点：

- **移动窗口**
- 生命周期短
- 可以和 fixed 60 秒切段错开
- 更关心：
  - 刚刚发生了什么
  - 最近这一小段是否值得说出来
- 当前 prompt 侧实现语义是：
  - 触发 LLM 时，只取一个向后看的 moving prompt window
  - 再把 window 内的 action entry 拆成多条 `TaggedContextEntry`
  - 不再使用“多个 moving segment -> 每段一条 prompt 文案”的旧表达

### 3.4 Layer D: LLM-facing Action Archive

职责：

- 对**整个 episode** 做概括整理
- 为 LTM 生成提供输入

特点：

- 不是移动窗口
- 不是 open segment
- 不是 prompt 的即时缓冲
- 它是：
  - 从 episode 边界内的动作材料出发
  - 做进一步压缩
  - 做重要度过滤
  - 做 generation gate 之前的整理层

一句话说：

- Layer C 是“最近这段”
- Layer D 是“这一整个 episode”

---

## 4. 结构上仍然建议统一

虽然 Layer C 和 Layer D 不是同一层，
但它们依然应该尽量复用同一套基础结构。

理想上，三层压缩视图都尽量长得像：

- `segment`
- `entry`

也就是：

- `ActionArchiveCompactor` 的 fixed segment
- 新 action buffer 的 dynamic recent segment
- LLM-facing archive 的 episode summary segment

尽量用同一种 segment / entry 形状。

这样可以保证：

- prompt 层
- intent 层
- LTM 生成层

虽然用途不同，但不会各自维护完全不同的数据模型。

---

## 5. 如何避免和 intent 分段设计冗余

当前最容易出现的冗余是：

- action buffer 自己做一套 segment 切分
- intent summary 再做一套“定期总结”的切分
- episode builder 又做第三套阶段边界

这样会导致：

- 三套边界不一致
- 三套压缩规则不一致
- 三套“这一段到底是什么”的解释互相打架

### 5.1 修正后的收敛方案：允许两套 segment，但只保留一套 canonical segment

当前更合理的做法不是强行只保留一套 segment，
而是允许两套 segment 并存，但明确谁是正式分析层。

建议区分为：

- `fixed segment`
  - 来自 `ActionArchiveCompactor`
  - 固定 60 秒
  - 用于 intent / episode / LTM generation
- `moving segment`
  - 来自新的 action buffer
  - 生命周期短
  - 用于 prompt / 近期感知
  - 可以做轻量 local intent 分析

其中只有前者应被视为：

- **canonical segment**

也就是说：

- fixed 60 秒 segment 负责正式结构切分
- moving segment 负责 prompt 侧的近期组织
- 两者都可以从原始 `ActionArchive` 读取
- 但 episode 和最终 LTM 生成只认 fixed 60 秒 segment 这一套正式边界

这样：

- fixed segment 负责回答“正式分析层的一小段动作材料长什么样”
- moving segment 负责回答“刚刚这一小段 prompt 材料长什么样”
- episode 只负责基于 fixed segments 去做更大的阶段聚合

### 5.2 intent 不再拥有单独的切段权

为了避免冗余，建议把 intent 层收敛成：

- **对已有 segment 的注释层**

而不是：

- 自己再定义一套 intent segment

也就是说，`PlayerIntentScorer` 更好的定位是：

- 输入：fixed 60 秒 segment
- 输出：这个 segment 的 intent 分数、主导 intent、理由

这样 intent 是：

- `segment -> annotation`

而不是：

- `raw actions -> another segmentation system`

补充：

- moving action buffer 也可以做简单 intent 分析
- 但它的 intent 只应理解为：
  - 近期描述
  - prompt 辅助
  - 局部趋势提示

而不应被拿去直接决定：

- episode 边界
- 最终 LTM 生成输入范围

### 5.3 action buffer 可以拥有独立 moving segment，但不拥有 canonical 切段权

为了避免和 intent / episode 重复，新 action buffer 更合理的定位是：

- 可以有自己的 dynamic segment builder

但必须满足两个边界：

1. 它直接从原始动作读取，但只服务 prompt / 近期感知
2. 它不参与 episode 和最终 LTM generation 的正式边界定义

也就是说：

- action buffer 是独立的 prompt-facing segmentation layer
- 但不是 episode-facing segmentation layer

### 5.4 episode 才是真正的上层聚合

在这套收敛方案里：

1. 原始动作流进入 `ActionArchive`
2. `ActionArchiveCompactor` 生成 fixed 60 秒 segments
3. `PlayerIntentScorer` 给 fixed segments 打上 intent annotation
4. 新 action buffer 从原始动作流生成 moving segments
5. moving segments 可做简单 local intent 分析，供 prompt 参考
6. episode builder 只把若干个连续 fixed segments 合并成一个 episode
7. LLM-facing archive 再对这个 episode 做概括整理

这样三层关系就很清楚：

- `fixed segment` 是正式分析材料单元
- `moving segment` 是 prompt 材料单元
- `intent` 是对 segment 的解释
- `episode` 是对 fixed segments 的聚合

### 5.5 这样保留了什么，去掉了什么

保留：

- 固定 60 秒 segment 的稳定分析边界
- `RecentActionBuffer` 那种“近期窗口 + 轻量压缩”的使用体验
- intent 作为阶段性解释层的设计
- 新 action buffer 的独立 moving segment 能力

去掉：

- intent 自己再切一套段
- episode 再重新发明一套底层压缩单元
- 把 moving segment 误当成 episode / LTM 的正式边界

一句话总结：

- 可以有两套 segment
- 但只有 fixed segment 是 canonical
- `intent` 做注释
- `episode` 只聚合 fixed segments

---

## 6. 新 action buffer 应该怎么理解

### 6.1 它是移动窗口

新的 action buffer 仍然要继承当前 `RecentActionBuffer` 的核心价值：

- 近期
- 动态
- 增量维护
- 可用于 prompt

因此它应当是：

- 一个 moving window
- 带有独立的 moving segment
- 从原始动作流增量刷新

这里的关键修正是：

- 它可以是移动窗口
- 也可以拥有自己独立的 segment 切段规则
- 但这套规则只服务 prompt / 近期感知
- 不进入 episode 与最终 LTM generation 的正式链路

### 6.2 它应明确继承 `RecentActionBuffer` 的哪些设计

当前判断是：

- `RecentActionBuffer` 的运作效果整体是好的
- 新 action buffer 不应推倒重来
- 应当明确继承其中已经被证明好用的设计

第一版建议明确继承以下几点：

#### A. 近期窗口 + 自动淘汰

保留：

- “只关心最近一段时间动作”的窗口语义
- 超出时间窗口后自动淘汰旧内容

原因：

- prompt / 近期感知本来就不需要无限历史
- 近期窗口比每次全量重算原始 archive 更轻，也更符合“刚刚发生了什么”的语义

#### B. 增量写入，而不是每次全量重算

保留：

- 每来一条新动作就立即更新缓冲层
- 不等到 prompt 触发时再临时重扫全量 archive

原因：

- 这能保持低延迟和稳定语义
- 也更适合未来把 prompt 层改成 segment 结构

#### C. 同类动作持续聚合

保留：

- 相同 `type + target` 的动作继续累加
- 即使中间被其他动作打断，只要仍在近期窗口内，也允许继续并到同一个近期行为簇里

这点非常重要，因为当前 `RecentActionBuffer` 的一个有效特征就是：

- 不只合并“尾部连续动作”
- 而是会把窗口内相同 `type + target` 的动作重新聚起来

这对 prompt 的可读性帮助很大，后续新 buffer 也应尽量保留这种“行为簇”语义。

#### D. 语义覆盖 / 语义遮蔽

保留：

- 更高层结果事件覆盖更底层铺垫事件
- 低语义密度的结果信号可在 prompt / LLM 视图中直接隐藏

当前最典型的例子是：

- `KILL` 覆盖同目标 `ATTACK`

后续新 buffer 里，这条设计应继续保留。

同理，后续也可以继续扩展：

- `USE_GATHER_TOOL` 覆盖同目标 `MINE_BLOCK`

当前已明确的一条是：

- `ACQUIRE_ITEM` 可保留在 raw / intent 侧
- 但在 prompt / LLM-friendly 视图中默认隐藏
- 原因是它更像“结果入手回执”，语义密度通常不如：
  - `USE_GATHER_TOOL`
  - `CRAFT_ITEM`
  - `TRADE`
  - `KILL`

#### E. 消费链与存档链解耦

保留：

- prompt 侧可以消费近期 buffer
- 但原始 `ActionArchive` 不受影响

原因：

- 即时感知和长期分析本来就不应该争用同一份底层原始数据

### 6.3 不应直接照搬的部分

虽然新 action buffer 要继承 `RecentActionBuffer` 的很多优点，
但也不是完全原样复制。

当前不应直接照搬的是：

#### A. 扁平 `ActionRecord` 列表形状

新 buffer 的目标是：

- 最终转成 segment-shaped 结构

因此它不应长期停留在：

- 扁平 `ActionRecord` 队列

#### B. 仅面向旧 prompt 文案的摘要逻辑

当前 `createSummaryForGroup(...)` 很适合旧 prompt，
但新 buffer 不应一开始就把自己锁死在“字符串摘要层”。

更合理的是：

- 先保留结构化 segment / entry
- 最后再由 prompt 层决定怎样把它转成文案

#### C. 现有的动作可见性白名单不一定照搬

当前 `RecentActionCollector` 已经切到读取 `MovingActionBuffer`，
并在 prompt 侧消费它的 folded segment 视图。

`ACQUIRE_ITEM` 这类低语义信号会在这层被隐藏，
其余动作则由 collector 决定如何组织成更自然的 prompt 文案。

旧的“白名单只放旧动作类型”策略已经不是主路径，
因此新 action buffer 的设计本身不应默认绑定那套旧白名单。

动作可见性应留给上层 prompt 选择，而不是写死在 buffer 结构里。

### 6.4 它的目标不是概括整个 episode

它回答的是：

- 玩家刚刚做了什么
- 最近这一小段发生了什么

而不是：

- 这一整个阶段该如何总结成记忆

### 6.5 它可以直接替换 `RecentActionBuffer`

目前右键 prompt 主路径已经使用新的 segment-shaped buffer。

后续可以继续让当前的 `RecentActionBuffer` 退出主舞台，
只保留给 legacy trigger 或彻底移除。

这个新 buffer 的每个单位，不再是：

- 单条 `ActionRecord`

而更接近：

- 一个小型动态 segment

---

## 7. LLM-facing Action Archive 应该怎么理解

### 6.1 它面向整个 episode

这层最终服务的是：

- LTM generation

因此它不应该是：

- 滑动窗口
- 最近三分钟
- 当前 open segment

而应该是：

- 在 episode 已经确定边界之后
- 对这个 episode 内的动作材料做概括整理

### 6.2 它应从 episode 材料中派生

输入更合理的是：

- 一个 episode 对应的动作范围
- 可能来自多个 fixed 60 秒 segments
- 也可能来自原始 archive 的某个 raw span

输出则是：

- 一个或多个更高层的 summary segments / summary entries

### 6.3 它会比 fixed compactor 更进一步

`ActionArchiveCompactor` 只是：

- 固定时间段聚合

LLM-facing Action Archive 则应额外负责：

- 语义压缩
- 重要度过滤
- 去掉大量低价值重复动作
- 保留对 episode 主题有帮助的行为线索

因此它更接近：

- “episode material normalizer”

而不是：

- “最近动作窗口”

---

## 8. 参照 `RecentActionBuffer` 时，应该参照什么

这里也需要纠偏。

我们参照 `RecentActionBuffer` 时，真正值得继承的是它的**压缩直觉**，
而不是把 LLM-facing archive 也做成移动窗口。

应该继承的是：

### 7.1 同类动作聚合

- `same type + same target` 合并

### 7.2 语义覆盖

例如：

- `KILL` 覆盖同目标 `ATTACK`
- `USE_GATHER_TOOL` 覆盖同目标 `MINE_BLOCK`

### 7.3 高种类动作折叠

例如：

- `MINE_BLOCK`
- `PLACE_BLOCK`

目标种类过多时，不逐条展开，而是做折叠概括。

也就是说，当前可继承的是：

- **压缩规则**

而不是：

- **窗口生命周期**

---

## 9. 三层压缩规则的关系

### 8.1 fixed 60 秒分析段

用途：

- 让系统稳定地做 intent / episode 分析

特点：

- 固定 60 秒
- 全局稳定
- 易比较

### 8.2 新 action buffer 的 dynamic 段

用途：

- 让 prompt 知道最近发生了什么

特点：

- 移动窗口
- 生命周期短
- 可增量更新

### 8.3 episode 级 LLM-facing archive

用途：

- 让 LLM 看到“这一整段旅程里值得总结的动作材料”

特点：

- 不滑动
- 以 episode 为边界
- 会做更强的压缩与过滤

---

## 10. 重新定义第一版实现顺序

现在更合理的顺序应当是：

### 第一步：先把 fixed 60 秒 segment 确认为唯一基础结构单元

目标：

- 不再新增第二套底层切段系统
- 明确 prompt / intent / episode 都建立在同一套基础 segment 上

### 第二步：再做新的 action buffer

目标：

- 用“最近若干 fixed segments 的窗口视图”替代当前 `RecentActionBuffer`
- 让 prompt 侧也开始使用 segment 结构，而不是扁平 `ActionRecord`

### 第三步：让 intent scorer 正式成为 segment annotation 层

目标：

- 不再把 intent 理解成另一套切段
- 而是理解成“对每个基础 segment 的解释”

### 第四步：在 episode 边界确定之后，再做 LLM-facing archive

目标：

- 把一整个 episode 内的动作材料进一步概括成 LLM 可读输入

### 第五步：最后再接 importance 和 generation gate

目标：

- 不是每个 episode 都送去生成记忆
- 先判断值不值得总结

---

## 11. 当前结论

当前需要明确记住这三条：

1. 当前 `ActionArchive` 的分析分段，确实是固定 **60 秒**
   - 但这是 `ActionArchiveCompactor` 的派生视图
   - 不是 `ActionArchive` 本体自己按 60 秒存储

2. 新的 action buffer 应该是**移动窗口**
   - 它的目标是替代 `RecentActionBuffer`
   - 它服务 prompt / 近期感知
   - 它可以拥有独立的 moving segment
   - 并且可以做简单 local intent 分析
   - 但不参与 episode 和最终 LTM generation 的正式边界

3. `LLM-facing Action Archive` 最终应是**episode 级概括层**
   - 它不是滑动窗口
   - 它服务长期记忆生成

4. 为了避免冗余：
   - `fixed segment` 负责正式切段
   - `moving segment` 负责 prompt 侧近期组织
   - `intent` 负责注释
   - `episode` 负责聚合 fixed segments

一句话总结：

- fixed 60 秒段是分析层
- moving window 段是 prompt 层
- episode 概括层才是真正的 LLM-facing archive
