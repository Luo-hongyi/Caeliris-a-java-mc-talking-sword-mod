# Segment Coordinate Refactor Plan

> 本文单独讨论 fixed segment 的“稳定持久化坐标”问题。
> 它是当前 episode targeted cleanup / 中间 region 物理删除 / reflection task 稳定引用的前置议题。
> 在本文收敛之前，reflection 主线文档只保留断点，不继续深挖中间删除实现。

## 1. 为什么要单开这份文档

当前 reflection 主线已经推进到：

- region lifecycle
- gate 独立结构
- signal 带 `regionId`
- owner-side state 回调骨架

但一旦我们认真允许：

- 删除某个中间 region
- 删除某个中间 STM 记录

就会撞到一个更底层的问题：

- 当前系统把“当前 timeline 数组位置”错误地当成了正式引用坐标

这不是 reflection 包内部能局部修掉的问题，而是会同时影响：

- `playeraction/fixed_segment`
- `episode`
- `reflection`
- runtime 持久化

因此这里单开一份文档，专门把 segment 坐标问题收敛干净。

## 2. 当前问题的本质

### 2.1 当前实际存在的坐标体系清单

先把现状说准：当前不是“只有一套 segment index 被到处复用”，而是已经存在多套相互缠绕的坐标体系。

#### A. 原始动作快照坐标

这一套坐标来自 `ActionArchive` 当前快照中的动作位置：

- `ActionArchive.add(...)`
  - 增量路径里用 `records.size() - 1` 作为本次新动作的 `rawIndex`
- `ActionArchiveCompactor`
  - 静态重建时直接用当前 `records` list 的下标 `i`
- `FixedActionSegment`
  - 保存 `rawStartIndex / rawEndIndex`
- `FixedActionSegmentEntry`
  - 也保存 `rawStartIndex / rawEndIndex`

它的语义是：

- “这个 fixed segment / entry 覆盖了当前 archive 快照中的哪一段 raw action”

它当前的问题是：

- 这不是全局稳定坐标
- 一旦 `ActionArchive` 环形淘汰，raw action 本体会左移
- 一旦上游触发 `replaceAll` 重建，`ActionArchiveCompactor` 会按当前快照重新编号

所以：

- `rawStartIndex / rawEndIndex` 当前只是“相对本次 archive 快照”的坐标
- 它比 timeline 下标稳定一些
- 但还不能直接当最终的持久化正式坐标

#### B. Fixed segment 时间窗锚点

这一套不是 index，而是 segment 自己的时间锚点：

- `FixedActionSegment.startTimestamp / endTimestamp`
- `EpisodeRegion.startTimestamp / endTimestamp`
- `EpisodeSplitPoint.splitTimestamp / confirmationTimestamp`
- `EpisodeSynthesisTriggerSignal.regionStartTimestamp / regionEndTimestamp / stmSliceExclusiveTimestamp`

它的优点是：

- 天然可持久化
- 中间删除不会导致整体重编号

它的限制是：

- 时间戳本身只能表达时间区间
- 不足以表达“第几段 fixed segment”
- 也不适合直接承载 `delay by N segments` 这种顺序语义

#### C. Episode timeline 位置坐标

这一套坐标来自当前 `EpisodeTimeline.segments()` list 的位置。

它已经扩散到：

- `EpisodePendingMark`
  - `detectedAtSegmentIndex`
  - `splitSegmentIndex`
  - `confirmAtSegmentIndex`
- `EpisodeSplitPoint`
  - `splitSegmentIndex`
  - `confirmationSegmentIndex`
- `EpisodeRegion`
  - `startSegmentIndex`
  - `endSegmentIndex`
- `EpisodeTracker`
  - detect / confirm / cleanup / rebuild 的内部比较
- `EpisodeTimeline`
  - `getSegment(segmentIndex)`
  - `sliceSegments(startInclusive, endInclusive)`

它本来的合理用途其实只有：

- 当前一轮 tracker 内部计算
- 当前 timeline 的局部顺序比较

它当前被误用的地方在于：

- 被当成 region 的正式边界身份
- 被带进 signal
- 被带进 reflection material request

这才是中间 region 删除时会整体漂移的直接根源。

#### D. Reflection 异步引用坐标

这一套其实是把 episode timeline 位置坐标跨异步边界泄露出去了：

- `EpisodeSynthesisTriggerSignal`
  - `leftBoundarySplitSegmentIndex`
  - `regionStartSegmentIndex`
  - `regionEndSegmentIndex`
  - `rightBoundarySplitSegmentIndex`
- `ReflectionMaterialRequest`
  - `startTimelineSegmentIndex`
  - `endTimelineSegmentIndex`
  - `leftBoundarySplitSegmentIndex`
  - `rightBoundarySplitSegmentIndex`
- `ReflectionRawMaterial` / `ReflectionMaterial`
  - 继续保存这些 index
- `DefaultReflectionMaterialExporter`
  - 直接按 `timeline.sliceSegments(startIndex, endIndex)` 取 segment

这套坐标的风险最高，因为：

- signal 发出后，task 未必立刻执行
- 如果执行前 timeline 发生了中间删除或重排
- 那么 task 在真正 exporter 阶段就会读错 segment

#### E. 伪稳定 identity

当前还有一个容易误判的点：

- `EpisodeRegion.regionId` 看起来像稳定 identity
- 但它当前的推导仍然混入了 `startSegmentIndex / endSegmentIndex`

这意味着：

- `regionId` 已经比纯 index 好很多
- 但还没有彻底摆脱当前位置坐标的污染

### 2.2 现在混在一起的其实是两种不同语义

当前代码里的 `segmentIndex` 同时承担了两种语义：

1. 当前内存数组中的临时位置
   - 例如 `segments.get(i)` 里的 `i`
   - 它只适合做 tracker 内部的当前轮计算

2. 跨模块流转时的正式引用坐标
   - region 引用它
   - split / pending mark 引用它
   - signal 引用它
   - reflection material request 引用它

问题就在这里：

- “当前数组位置”不是稳定 identity
- 一旦中间物理删除，它就会整体漂移

### 2.3 一旦中间删除，漂移会直接打坏哪些东西

当前直接依赖 segment index 的正式结构包括：

- `EpisodePendingMark`
  - `detectedAtSegmentIndex`
  - `splitSegmentIndex`
  - `confirmAtSegmentIndex`
- `EpisodeSplitPoint`
  - `splitSegmentIndex`
  - `confirmationSegmentIndex`
- `EpisodeRegion`
  - `startSegmentIndex`
  - `endSegmentIndex`
  - 当前 `regionId` 也混入了 index 推导
- `EpisodeSynthesisTriggerSignal`
  - `leftBoundarySplitSegmentIndex`
  - `regionStartSegmentIndex`
  - `regionEndSegmentIndex`
  - `rightBoundarySplitSegmentIndex`
- `ReflectionMaterialRequest`
- `ReflectionRawMaterial`
- `ReflectionMaterial`
- `DefaultReflectionMaterialExporter`
  - 现在仍按 `timeline.sliceSegments(startIndex, endIndex)` 取数据

因此，如果直接删除中间 region，对后续会造成四类破坏：

1. 后面的 region / split / pending mark 坐标整体失效
2. 已经发出去的 signal 指向错误范围
3. 已入队但尚未执行的 reflection task 在执行时读取到错误 segment
4. runtime 持久化中的 region state 会和当前内存 timeline 脱节

### 2.4 当前“统一”的目标到底是什么

这里需要把目标说得更精确一些。

我们说“将所有的 segment index 体系重构为统一的，并且可持久化的”，真正意思不是：

- 把系统里所有位置字段都强行合并成一个 `int`

而是：

- 所有跨模块、跨异步、跨持久化边界的正式引用
  - 统一到一套 `SegmentCoordinate`
- 所有只服务局部算法的当前位置值
  - 保留为临时位置坐标
  - 但不再伪装成正式 identity

也就是说，统一的对象是：

- 正式引用体系

而不是：

- 所有内部位置变量

## 3. 还有一个比 index 漂移更深的坑

当前 episode 并不是 fixed segment 的唯一真源。

实际更上游的真源仍然是：

- `ActionArchive`
- `FixedActionSegmentBuffer`
- 静态重建时的 `ActionArchiveCompactor`

这意味着即使 episode 侧本地把中间 region 物理删掉了，也还会遇到一个问题：

- 只要上游触发一次 `replaceAll`
- 如果上游不知道“这段已经删过了”
- 那这段 segment 仍然可能被整批重建回来

所以现在的问题不只是“中间删除后怎么重排 index”，而是：

- 我们还没有一个稳定的、跨 rebuild 仍然成立的 segment 正式坐标

## 4. 这次重构要拆开的两个概念

### 4.1 `timelineIndex`

这是当前内存 list 中的临时位置。

它应只服务：

- tracker 内部计算
- 当前一轮 detect / confirm / cleanup 比较
- 当前 UI / debug 展示

它不应该继续作为跨模块的正式引用身份。

### 4.2 `segmentCoordinate`

这才是我们要补的正式持久化坐标。

它应该满足：

- 稳定
- 可持久化
- 可跨重建恢复
- 可被 signal / task / material request 直接引用
- 不会因为中间 region 删除就整体漂移

后续所有跨 subsystem 的正式引用，都应优先绑定到它，而不是 `timelineIndex`。

## 5. 我们到底需要什么样的坐标

### 5.1 必须满足的条件

正式坐标至少要满足：

- 唯一标识一个 fixed segment
- 能够跨 `replaceAll` / runtime 重建尽量保持稳定
- 能进入 JSON 持久化
- 能让 exporter 不依赖“当前 list 下标”定位 segment
- 能让已入队 task 在稍后执行时仍找到原本指向的 segment

### 5.2 不能混进来的额外诉求

有些需求其实不应该强压到“稳定坐标”上：

- “延迟几段 confirm”
- “比较当前 latest segment 和前几段”
- “按当前顺序扫描 closed regions”

这些需求本质上需要的是“当前顺序位置”，不是稳定 identity。

也就是说：

- 稳定坐标解决 identity
- 当前顺序位置继续解决局部算法

这两者不要再混成同一个字段。

这里进一步补一句：

- 如果后续继续保留 `segmentIndex` 这个词同时表示这两种语义，文档和代码都会继续混乱

因此后续命名上也应尽量收敛为：

- `timelineIndex` / `timelinePosition`
- `segmentCoordinate`

## 6. 候选方案分析

### 6.1 方案 A：继续用当前 `segmentIndex`

优点：

- 不用重构

缺点：

- 中间删除后全部漂移
- signal / task / exporter 都会失效
- 无法支持干净的 targeted cleanup

结论：

- 不可接受

### 6.2 方案 B：直接拿 `rawStartIndex / rawEndIndex` 当正式坐标

优点：

- 这组数据已经存在于 `FixedActionSegment`
- 比当前 list 下标稳定得多
- 对 exporter / signal / task 来说，已经足够做“找到原 segment”的引用

缺点：

- 它是 raw action 级坐标，不是 fixed segment 顺序号
- 不能直接承载“delay by N segments”这种语义
- 如果未来 compaction 规则改变，segment 切法变化，旧 raw-range 绑定的 segment 也可能整体失效

结论：

- 它可以成为稳定 identity 的重要组成部分
- 但不能直接替代“当前顺序位置”语义

### 6.3 方案 C：新增独立的稳定 `segmentOrdinal`

优点：

- 对人来说更直观
- 天然表达“第几个 fixed segment”
- 既可持久化，也可作为稳定引用

缺点：

- 如果它只是运行时递增计数，那么 `replaceAll` 时必须有办法恢复旧 ordinal
- 也就是说，需要更上游真源参与持久化或稳定重建

结论：

- 可行
- 但它要求更明确的上游真源设计

### 6.4 当前更合理的方向：双坐标制

当前最干净的方向不是二选一，而是明确分层：

1. 保留 `timelineIndex`
   - 只做当前局部算法

2. 新增稳定 `segmentCoordinate`
   - 只做正式 identity

稳定 `segmentCoordinate` 的具体形状，可以继续讨论：

- 纯 `segmentOrdinal`
- 纯 `rawStartIndex/rawEndIndex`
- 或者一层显式 value object，把 raw range 与必要锚点包起来

但无论最终选哪种形式，都应先把这两个概念拆开。

并且当前已知一定要从“正式坐标体系”中剥离出去的字段包括：

- `EpisodeSynthesisTriggerSignal` 里的 segment index 边界
- `ReflectionMaterialRequest` 里的 start/end timeline index
- `ReflectionRawMaterial` / `ReflectionMaterial` 里的 index 边界描述

这些对象后续都不应继续以当前数组位置作为正式引用。

### 6.5 当前正式决议：采用持久化 `long segmentId`

在前面的方案分析基础上，这里先明确当前决议：

- fixed segment 的正式稳定 identity 采用独立的 `long segmentId`
- `timelineIndex` 继续只服务局部算法，不再承担正式 identity
- `rawStartIndex / rawEndIndex`
  - 保留为调试 / 辅助锚点
  - 但不再作为跨包正式坐标
- `startTimestamp / endTimestamp`
  - 继续保留为稳定时间锚点
  - 用于恢复、校验和必要时的 segment 对位

这个决议的核心原因是：

- 对 episode / reflection / signal / task 来说，需要的是一个简单、直接、可持久化的正式 identity
- `segmentId` 比 raw range 更清晰，也更适合作为后续 region 边界和异步引用的正式字段

### 6.6 `segmentId` 的发号点：`FixedActionSegmentBuffer`

当前也先明确 owner：

- `segmentId` 不由 `ActionArchive` 发
- `segmentId` 由 `FixedActionSegmentBuffer` 在 fixed segment **第一次正式 sealed** 时发出

这样划分的原因是：

- `ActionArchive` 更像原始动作缓存，不适合作为正式持久化 owner
- fixed segment 的正式封装边界本来就落在 `FixedActionSegmentBuffer`
- 跨包正式坐标应在 fixed segment 刚成为正式对象时就定好，而不是拖到 episode / reflection 再补

这里也顺手明确一条边界：

- open segment 在未 sealed 前，不需要正式 `segmentId`
- 正式 `segmentId` 只属于 sealed fixed segment

### 6.7 仅保存 `nextSegmentId` 计数器是不够的

之前提到“是否只需要在 buffer 的持久化文件中记录当前 segment id 已经发到第几个”，这里明确结论：

- 不够

原因不是计数器本身，而是：

- `replaceAll` / rebuild 时，当前仍存在的旧 fixed segment 必须继续复用旧 `segmentId`
- 游戏重启后，之前已经发出的 signal / region / task 引用，语义上仍然指向原来的 segment
- 如果只保存 `nextSegmentId`，但不保存“哪些 segment 已经拿到了哪些 id”，那么 rebuild 后同一段 segment 会被重新发新 id

一旦这样，后果是：

- 同一段 fixed segment 在重建前后 identity 变化
- episode region 边界引用会漂
- 已入队但稍后执行的 reflection task 会失配
- 之后做中间 region 物理删除时，仍然没有稳定坐标可依赖

所以真正需要持久化的不只是：

- `nextSegmentId`

还需要至少持久化：

- 当前 sealed fixed segments 与其 `segmentId` 的绑定关系

### 6.8 现有 runtime state 要升级为真正持久化

这里也补上当前新的方向：

- 不再把 fixed segment 的 runtime state 只看成 Studio/debug snapshot
- 现有 `studio_runtime_action_timeline.json` 应升级为 fixed segment runtime state 的正式持久化恢复源

也就是说，方向不是：

- 额外再造一份平行的 fixed segment 真源文件

而是：

- 直接把现在这一层 runtime state 升级成真正 owner-side persistence

这样做的目的有两个：

- 避免 fixed segment 出现两套真源
- 与 episode 当前正在推进的“runtime file 直接升级为正式 runtime state”方向保持一致

不过 owner 语义仍然应保持清楚：

- `FixedActionSegmentBuffer`
  - 是 runtime state 的语义 owner
  - 负责决定哪些字段构成正式状态、如何恢复、如何发新 id
- 当前 runtime state 文件
  - 当前对应 `studio_runtime_action_timeline.json`
  - 只负责序列化 / 反序列化这份状态
  - 不负责替代 buffer 的业务语义

### 6.9 第一版最小持久化状态

如果按这个方向推进，第一版 fixed segment runtime state 至少应覆盖：

1. 固定窗口配置
   - `fixedSegmentMs`
   - 用于校验恢复时的分段口径是否一致

2. 发号进度
   - `nextSegmentId`

3. sealed fixed segment 正式列表
   - 每段自己的 `segmentId`
   - `startTimestamp / endTimestamp`
   - 该段 entries
   - 该段 context
   - 以及必要的调试字段

4. open segment 快照
   - 这一项建议保留
   - 它虽然还没有正式 `segmentId`
   - 但能避免重启时尾段上下文直接断掉

这里有一个很重要的含义变化：

- 一旦 fixed segment runtime state 变成正式真源，`rawStartIndex / rawEndIndex` 就只能继续作为辅助调试信息
- 后续恢复、跨包引用和 targeted cleanup 不能再依赖 raw index 成为正式坐标

`contextSeeds` 是否进入第一版持久化，可以后置讨论。
当前更重要的是先把：

- `segmentId`
- sealed segment 正式列表
- open segment 恢复

这三件事钉住。

## 7. 对 reflection 主线的直接影响

只要稳定坐标没补上，下面这些事都不应该最终定版：

- 中间 region 物理删除
- reject 的 targeted cleanup 正式实现
- signal 最终字段设计
- reflection material request 的最终字段设计
- exporter 的最终定位方式
- 已入队 reflection task 的稳定引用策略

因此 reflection 主线目前应视为：

- 上层边界已经讨论清楚
- 但在 segment coordinate 问题解决前，暂时停在断点

## 8. 第一批需要迁移的结构

后续如果进入实现，第一批应优先改下面这些对象：

1. `FixedActionSegment`
   - 新增正式 `segmentId`

2. `EpisodePendingMark`
   - 从 index-only 迁到稳定坐标 + 当前顺序位置分离

3. `EpisodeSplitPoint`
   - 同上

4. `EpisodeRegion`
   - region 边界应能引用稳定 segment 坐标
   - `regionId` 也不应继续依赖当前数组下标

5. `EpisodeSynthesisTriggerSignal`
   - 应逐步摆脱 index 边界字段对正式定位的依赖

6. `ReflectionMaterialRequest` / `DefaultReflectionMaterialExporter`
   - 应改成按稳定坐标 / region identity 找 segment，而不是按 list slice

## 9. 当前建议的讨论顺序

这里先不要急着改大量代码。

更好的顺序是：

1. 先定“稳定坐标”的正式形状
   - 当前已经收敛为持久化 `long segmentId`

2. 先定它的 owner 与发号点
   - 当前已经收敛为 `FixedActionSegmentBuffer`
   - 发号时机是第一次正式 seal

3. 把现有 action timeline runtime state 升级成 fixed segment 的正式持久化恢复源
   - 不再停留在 snapshot/debug 语义
   - 先保证 `segmentId` 和 sealed segment 绑定关系能跨重启延续

4. 先单独梳理“当前各套坐标分别服务什么语义”
   - 哪些是临时位置
   - 哪些已经被误当成正式 identity
   - 哪些只是辅助锚点

5. 再定“当前顺序位置”与“稳定 identity”的边界

6. 最后才进入 episode / reflection 的大面积迁移

## 10. 当前断点总结

当前已经明确的结论：

- 需要一个 fixed segment 的稳定持久化坐标
- 这个问题应该单开文档处理
- reflection 主线在这里先暂停
- 不解决这个问题，就不应该继续硬推中间 region 物理删除
- 正式 identity 当前采用持久化 `long segmentId`
- `segmentId` 由 `FixedActionSegmentBuffer` 在 segment 第一次 sealed 时发出
- 仅持久化 `nextSegmentId` 不够，必须持久化 sealed segment 与 `segmentId` 的绑定关系
- 现有 action timeline runtime state 应升级为 fixed segment 的正式持久化恢复源

当前在这里先留一个工程断点：

- segment coordinate 主线到这里先暂停，不继续往中间删除 / targeted cleanup 深挖
- 下一步优先处理这轮改动带出来的 runtime 性能问题
- 重点先看：
  - Studio runtime snapshot 的写盘频率
  - fixed segment runtime state 写入 `studio_runtime_action_timeline.json` 后的序列化成本
  - episode runtime snapshot 是否也在被高频全量写出

下一步讨论应专注于：

- fixed segment runtime state 的最小字段集合
- buffer 恢复时 sealed / open segment 的最小恢复语义
- `segmentId` 接回 `FixedActionSegment`、episode 和 reflection 的迁移顺序
