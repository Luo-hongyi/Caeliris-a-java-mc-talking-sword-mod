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
  - `detectedAtTimelineIndex`
  - `splitTimelineIndex`
  - `confirmAtTimelineIndex`
- `EpisodeSplitPoint`
  - `splitTimelineIndex`
  - `confirmationTimelineIndex`
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

这条链在旧设计里，确实把 episode timeline 位置坐标跨异步边界泄露出去了。

当前已经完成一轮正式清理：

- `EpisodeSynthesisTriggerSignal`
  - 只保留 `regionId`
  - `regionStartSegmentId / regionEndSegmentId`
  - 左右 boundary 的 segment ids
  - `regionStartTimestamp / regionEndTimestamp`
  - `stmSliceStartTimestamp / stmSliceExclusiveTimestamp`
- `ReflectionMaterialRequest`
- `ReflectionRawMaterial` / `ReflectionMaterial`
- `ReflectionRawSegmentMaterial` / `ReflectionSegmentMaterial`
  - 都已经拔除了 `timelineIndex`
- `DefaultReflectionMaterialExporter`
  - 现在只按 `segmentId` 解析正式 segment range
  - 如果引用的 segment 已经不存在，会直接 fail fast，而不是 fallback 到 timeline index

这意味着：

- “异步 task 因 index 漂移而读错 segment” 这一层已经被切掉
- 但“异步 task 执行时引用的 segment 已被 owner 清理掉”这个问题仍然存在
- 这个剩余问题后续要靠：
  - source material cleanup plan
  - owner-side task cancel / consume / stale check
  来闭环

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
  - `detectedAtTimelineIndex`
  - `splitTimelineIndex`
  - `confirmAtTimelineIndex`
- `EpisodeSplitPoint`
  - `splitTimelineIndex`
  - `confirmationTimelineIndex`
- `EpisodeRegion`
  - `startSegmentIndex`
  - `endSegmentIndex`
  - 当前 `regionId` 也混入了 index 推导

因此，如果直接删除中间 region，对后续会造成四类破坏：

1. 后面的 region / split / pending mark 坐标整体失效
2. 已经发出去但尚未消费的 signal / cleanup plan 会变成 stale 引用
3. 已入队但尚未执行的 reflection task 会在 exporter 阶段因为 segmentId 不存在而 fail fast
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

## 11. 本阶段性能优化收尾

在上面的工程断点之后，这一轮实际先处理的是 runtime 持久化写盘成本问题。

当时观测到的现象不是单纯“写盘过于频繁”，而是：

- 单次写盘本身过重
- 写入时不仅游戏主线程会卡顿
- 整台机器也会感受到明显的 I/O / 序列化抖动

因此这一轮优化的目标固定为：

- 先降低单次持久化成本
- 先把最大 payload 从高频 snapshot 主路径里拆出去
- 再决定是否还需要继续做更激进的频率节流

### 11.1 已完成的优化

1. action runtime 持久化拆成“两层文件”

- `studio_runtime_action_timeline.json`
  - 保留轻量 runtime state
  - 保留 moving window、计数、sequence token 等摘要信息
- `studio_runtime_action_segments.json`
  - 只保留 segment store meta
- `studio_runtime_action_segments.data.jsonl`
  - 正式承载 sealed fixed segment 的大 payload

这样做的直接结果是：

- 高频 action timeline snapshot 不再每次带整份 sealed segment 列表

2. sealed fixed segment 主体不再总是整文件重写

- 当 sealed segments 只是尾部新增时：
  - `studio_runtime_action_segments.data.jsonl` 走 append-only
- 只有在不是“纯追加”的情况下：
  - 才回退为整文件重写

这一步是本轮最关键的减负点，因为最大的 payload 已经从“每次全量重写”降成了“常见路径下只追加新段”。

3. action timeline 不再把 embedded fixed segments 当正式真源

- sealed fixed segments 的恢复改为从独立 segment store 读取
- action timeline 自己只承载轻量运行时信息
- Studio 前端也同步切到同一条新主线，不再优先消费 timeline 里内嵌的 fixed segment 列表

4. 增加 cheap token / signature 检查，避免无意义重建与写出

- action timeline 新增 `fixedSegmentSequenceToken`
  - sealed fixed segments 未变化时，直接复用已有 segment 视图，不重新构造重 payload model
- episode timeline 新增 `timelineSignatureToken`
  - timeline 未变化时，直接跳过一次 snapshot 写出

5. 去掉高频 runtime JSON 的 pretty printing

当前已改为紧凑 JSON 的文件包括：

- `studio_runtime_action_timeline.json`
- `studio_runtime_episode_timeline.json`
- `studio_workspace.manifest.json`

这一步不是主收益来源，但它进一步降低了：

- 单次序列化体积
- 磁盘写入量
- Studio 文件热更新时的前端读取负担

6. 清理新旧双轨兼容路径

- action timeline / segment store / Studio 导入逻辑已收口到新格式主线
- 不再保留“timeline 内嵌 fixedSegments 兜底恢复”的长期兼容语义

这样做的收益是：

- 性能链路更干净
- 后续继续定位写盘热点时，不再被旧格式兼容分支干扰

### 11.2 当前收尾结论

当前这一轮性能优化之后，已经可以先得出下面的阶段性结论：

- 当前卡顿频率已经明显下降
- 最大的 sealed segment payload 已经脱离高频 snapshot 主路径
- 当前仍然存在的整文件重写，主要是轻量 JSON：
  - `studio_runtime_action_timeline.json`
  - `studio_runtime_action_segments.json`
  - `studio_runtime_episode_timeline.json`
  - `studio_workspace.manifest.json`

因此这轮性能处理先在这里收尾：

- 不继续为了“可能还可以更快一点”而暂停 segment / episode 主线
- 先回到固定坐标、episode region 生命周期和 reflection 接线这些主问题

后续如果又出现新的明显卡顿，再单独回看：

- manifest 是否需要再批处理
- 轻量 timeline JSON 是否还需要进一步降频
- episode timeline 是否需要进一步拆 payload

### 11.3 持久化后端再评估：SQLite / 二进制 / 继续沿用 JSON

在继续推进 fixed segment discard 之前，这里补一次后端形式评估，避免我们在“业务复杂度”和“基础设施复杂度”之间做错交换。

先把结论说准：

- 当前最大的性能风险，不是“JSON 这个格式天生太慢”
- 而是“中间删除语义会把 append-only 路径打回整份文件重写”
- 只要仍然要求对大文件做中间物理删除，那么：
  - 换成别的序列化格式也只能降低单次编码成本
  - 不能从根上消掉“整份重写”这个成本来源

也就是说，问题要拆成两层：

1. 写出格式本身的 CPU / 体积成本
2. 是否允许继续维持 append-only 主路径，而把删除改成小 delta / tombstone / discard state

#### A. 直接切到 SQLite

SQLite 的优点很明确：

- 天然支持 row-level insert / update / delete
- 中间删除不需要整份重写大 JSON 文件
- 可以直接按 `segmentId` / timestamp range 做查询
- 后续如果 discard、region、gate state 继续增多，它在数据组织上会更从容

但它在当前项目里的成本也很实在：

1. 依赖与打包成本

- 当前工程没有任何 JDBC / SQLite 依赖
- Forge 模组里引入 SQLite，意味着要处理：
  - 依赖引入
  - 最终 jar 打包
  - 运行时 native / classloader 行为
- 这不是业务层小改，而是基础设施升级

2. Studio 工具链也要一起改

- 当前 Studio 前端直接读取：
  - `studio_runtime_action_timeline.json`
  - `studio_runtime_action_segments.json`
- 如果 segment 真源迁到 SQLite：
  - Studio 就不能再直接读工作区文件得到完整视图
  - 必须补一层导出 / bridge / query API

3. 调试可见性会下降

- 现在 workspace 文件是可直接肉眼检查的
- SQLite 虽然更正式，但对当前“边开发边看文件”的工作流不如 JSON 直接

4. 它解决的是“存储引擎问题”，不是“领域模型问题”

- 即使换成 SQLite，我们仍然要定义：
  - discard 的正式语义
  - rebuild 时如何避免 deleted segment 复活
  - episode / reflection 如何引用 active view
- 也就是说，SQLite 不能替代 discard lifecycle 设计本身

因此，当前阶段如果直接切 SQLite，收益是：

- 可以把“中间删除导致整份重写”交给数据库层处理

代价是：

- 用一整套新的存储基础设施，替代当前已经接进 Studio 的 JSON workspace 主线

这个交换只有在下面场景里才值得：

- 我们明确接受 workspace 不再以 JSON 文件为主真源
- 后续不仅 fixed segment，连 episode / region / STM / recall trace 都准备系统性数据库化
- 我们愿意为此补 Studio 读取桥接层

如果暂时没有这三个前提，SQLite 现在偏重。

#### B. 换成“比 JSON 更轻”的二进制格式

例如：

- MessagePack / CBOR
- protobuf length-delimited log
- 自定义 binary journal

这条路确实可以降低：

- 序列化 CPU
- 文件体积
- 解析对象时的字符串开销

但它有一个根本限制：

- 如果中间删除仍然要求物理改写“大 segment 主文件”
- 那么它还是会整份重写

所以二进制格式能解决的是：

- “单次重写更轻一些”

解决不了的是：

- “为什么会被迫重写整份文件”

同时它还会带来：

- Studio 不能直接读
- 调试不可视
- 需要自定义导出或反序列化工具

因此，单独把 JSONL 换成 binary log，不足以成为当前最优先方案。

#### C. 保持 JSON 主线，但把“删除”改成小型追加日志

如果目标是：

- 不引入数据库
- 不破坏当前 Studio workspace 读法
- 避免中间删除时重写整份大 segment 文件

那么最务实的方向其实是：

- 保留 `studio_runtime_action_segments.data.jsonl` 作为 append-only 的 physical segment journal
- 另加一个很小的 append-only discard log / tombstone log
  - 可以是 JSONL
  - 也可以是更简单的紧凑文本 / binary event log
- 运行时恢复时：
  - 先读 physical segments
  - 再 replay discard events
  - 得到 active segment view

这条路的优点是：

- 不需要改掉当前整体工作区架构
- 大文件仍然只负责追加
- 中间删除只写一条小记录
- Studio 仍然可以保留 JSON 入口

它的本质和“persisted tombstone / discard window”是一回事，但结构上更扁平：

- 不一定要把所有 discard state 都塞进 runtime state 大对象
- 可以单独放成一个 sidecar journal

如果后面确认 discard 规则会越来越复杂，再收敛回 runtime state 或数据库都还来得及。

#### D. 当前推荐

基于当前工程形态，当前更推荐的优先级是：

1. 不直接切 SQLite

- 它能解决部分写盘问题
- 但会显著抬高项目的基础设施复杂度
- 对当前已经成型的 Studio JSON 工作流冲击太大

2. 不只为了“JSON 更吃 CPU”而盲目换二进制

- 格式替换不能解决整份重写这个根因

3. 优先考虑“小 delta 日志”

- `physical segments` 继续 append-only
- `discard events / tombstones` 单独 append-only
- active view 在恢复时叠加求出

这样做的实际收益是：

- 把中间删除从“大文件重写”降成“小文件追加”
- 同时不需要把整个项目切到数据库

#### E. 第一版 compact 时机

关于 tombstone / discard state 的第一版落地时机，这里先冻结一版偏务实的策略：

- 运行时 reject / consumed cleanup 发生时：
  - 只登记 discard state
  - 不立刻重写整份 physical segment 主文件
- discard state 本身仍要立即持久化
  - 否则游戏异常退出后，被删除的 segment 会在下次启动时复活
- 手动保存时：
  - 对当前 physical segments + discard state 做一次 compact
  - 产出新的 active physical view
  - compact 成功后清空已消费的 tombstone / discard windows
- 每次开始游戏时：
  - 先按已持久化的 physical segments + discard state 恢复 active view
  - 然后再做一次启动期 rebuild / compact 作为兜底对账

这样收敛的原因是：

- 运行时热路径不再承担整份大文件重写
- 物理压实被推迟到用户可接受的低频时机
- 即使上一次会话在 pending tombstone 状态下退出，下一次进入游戏也会主动做一次对账修正

这里也顺手明确一个边界：

- 自动保存 / 高频 snapshot 主路径，不应默认承担整份 compact
- 否则 tombstone 设计虽然避免了“每次 reject 都重写”，但仍可能把周期性卡顿重新带回来

#### F. 当前断点后的建议顺序

如果按最小风险继续推进，下一步建议是：

1. 先把 discard state 从“整份重建 fixed segment store”改成“sidecar delta log”设计稿
2. 明确 delta log 的最小字段：
   - `startSegmentId / endSegmentId`
   - 或 timestamp range
   - `reason`
   - `createdAt`
3. 明确恢复顺序：
   - physical segment journal
   - discard journal
   - active filtered view
4. 等这条链跑顺后，再重新评估是否还需要更换底层格式

当前阶段的判断是：

- SQLite 是可行方案
- 但不是当前最省事、也不是最贴合现有架构的方案
- 比起“换格式”，更值得先做的是“把删除改成 delta，而不是物理改写主文件”
