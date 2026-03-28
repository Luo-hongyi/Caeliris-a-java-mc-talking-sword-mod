# Performance Hotspots

> 本文专门记录 `agent.memory` 及其关联 Studio runtime 持久化链路中的当前热点、已完成优化，以及后续可继续推进的性能债。
> 当前目标不是立刻把所有热点都清零，而是把“现阶段可接受”和“后续真要动时先打哪里”说清楚。

## 1. 当前判断

截至 `2026-03-28`，当前这一轮性能问题的结论是：

- 在普通游戏环境中，当前 memory / studio runtime 写盘热度已经基本可接受。
- 在大型整合包场景中，由于整包本身就对 CPU 更敏感，仍可能出现周期性卡顿。
- 当前观测到的主要瓶颈不是“flush 频率过高”，而是“某些持久化路径仍会做整份构建与整份重写”，在 CPU 余量不够时容易被放大。
- 因此，这个问题当前应归类为：
  - 已识别的性能债
  - 需要持续记录
  - 但暂时不阻塞 segment / episode / reflection 主线继续推进

## 2. 这一轮已经完成的优化

本轮已经落地的收敛包括：

- sealed fixed segment 主 payload 已从高频 `studio_runtime_action_timeline.json` 中拆出，转移到独立的：
  - `studio_runtime_action_segments.json`
  - `studio_runtime_action_segments.data.jsonl`
- fixed segment discard windows 也已从高频 `studio_runtime_action_timeline.json` 中拆出，转移到独立的：
  - `studio_runtime_action_segment_discards.jsonl`
- `studio_runtime_action_segments.data.jsonl` 在普通 append 场景下，已经不再整份重写，而是追加写入。
- `studio_runtime_action_segment_discards.jsonl` 在普通 discard append 场景下，也已经改为追加写入。
- fixed segment runtime state 已升级为正式持久化状态，`segmentId` 发号进度也随之落盘。
- source material cleanup 已切到 discard window 语义：
  - 先做逻辑 discard
  - 再由后续 compact 物理压实
- 启动恢复链已经支持读取 persisted discard windows。
- 游戏内 state screen 已挂出手动 compact 按钮，便于测试和主动压实。
- startup compact 的功能链已经验证通过。

这些优化说明：

- 当前系统已经不是“每次 flush 都重写整份 fixed segment 数据文件”。
- 但高频 snapshot 路径仍然存在整份构建 / 整份写盘，所以性能债并没有完全消失。

## 3. 当前确认的热点

下面只记录目前最值得继续关注的热点，按优先级从高到低排列。

### 3.1 `studio_runtime_action_timeline.json`

这是当前最可疑的热点之一。

现状：

- `TickEventWatcher.flushStudioRuntimeSnapshotsIfDirty(...)` 会持续尝试刷新 action runtime snapshot。
- `StudioRuntimeActionTimelineFile.recordSnapshot(...)` 虽然有最小写间隔，但一旦触发写入，仍会：
  - 重新构建整份 `RuntimeActionTimelineFileModel`
  - 整份序列化 `studio_runtime_action_timeline.json`

这里的真实成本不只在写文件，还在“构建整份快照对象”：

- `runtimeState.sealedSegmentContexts` 目前仍会按全部 sealed segments 扫描和收集。
- discard windows 本身已经不再是这个文件里的主要负担。
- 也就是说，即使最终文件不大，CPU 侧仍然已经为整份 snapshot 付出了代价。

当前判断：

- 这是后续如果继续做性能优化，最优先该拆的点。

### 3.2 `studio_runtime_episode_timeline.json`

这是第二个明确的整份 snapshot 热点。

现状：

- `StudioRuntimeEpisodeTimelineFile.recordSnapshot(...)` 会先做 signature 判定。
- 如果 timeline 发生变化，就会重新构建整份：
  - `segments`
  - `pendingMarks`
  - `splitPoints`
  - `regions`
- 然后整份写回 `studio_runtime_episode_timeline.json`

当前判断：

- 它没有 action timeline 那么高频。
- 但在 fixed segment seal、episode region 重建频繁发生时，仍可能成为整合包场景中的可见卡顿源。

### 3.3 `studio_runtime_action_segments.data.jsonl` 的非 append 路径

当前这条链已经比之前干净很多，但并没有完全消除整份重写。

现状：

- append-only 正常增量路径已经可用。
- 但在下面这些场景里，仍会退回整份重写：
  - `replaceAll`
  - physical compact
  - startup compact
  - 任何无法判定为“前缀一致 + 尾部追加”的更新

当前判断：

- 这条路径现在更像“低频但重”的热点。
- 它不是当前普通移动场景卡顿的第一嫌疑。
- 但如果后续 compact 数据规模继续增长，它仍会回来成为显性问题。

### 3.4 `studio_workspace.manifest.json`

这是伴生写路径。

现状：

- runtime 文件更新后，manifest 也会随之整份改写。

当前判断：

- 文件很小。
- 不是当前主要瓶颈。
- 后续除非做极端瘦身，否则不值得单独优化。

### 3.5 其他整份写但当前不是主因的文件

包括但不限于：

- `studio_runtime_recall_trace.json`
- `studio_runtime_recent_interactions.json`
- `agent_state.json`
- `implanted memories` / authored 字典类文件

当前判断：

- 它们大多是低频写、上限较小，或者与本轮“移动时周期性卡顿”不吻合。
- 现阶段先不作为主热点处理。

## 4. 为什么大型整合包更容易暴露这个问题

当前观察比较符合下面这个判断：

- 在普通环境里，整份 snapshot 构建和整份 JSON 写盘的成本还能被 CPU 余量吞掉。
- 在大型整合包里，Minecraft 本体、Forge、其他模组和渲染 / AI / 世界逻辑已经共同吃掉了大量 CPU 预算。
- 这时即使 memory 自己的写盘频率没有进一步升高，只要某次 flush 触发了整份构建 / 整份重写，就更容易放大成可见卡顿。

所以这个问题本质上更像：

- CPU headroom 不足时暴露出来的结构性热点

而不是：

- 一个必须通过继续降频才能解决的调度问题

## 5. 当前可接受性结论

当前建议把这轮性能状态定性为：

- 正常游戏环境：可接受
- 大型整合包环境：有已知热点，但尚未达到必须立即停下主线专门抢修的程度

因此当前策略应保持为：

- 继续记录热点
- 避免引入新的整份重写链
- 主线优先继续回到：
  - segment identity
  - episode cleanup API
  - reflection 主线闭环

## 6. 后续优化候选

如果后面决定继续做一轮性能收尾，建议优先顺序如下。

### 6.1 第一优先：瘦身 action runtime snapshot

目标：

- 继续减少 `studio_runtime_action_timeline.json` 每次 flush 的构建成本与写盘成本

优先方向：

- 评估 `sealedSegmentContexts` 是否继续留在高频 snapshot 中
- 如果不需要高频同步，可拆到独立 sidecar
- 或改成更轻量的 runtime state 持久化结构

### 6.2 第二优先：瘦身 episode runtime snapshot

目标：

- 避免每次 timeline 变化都重新构建整份 episode 结构

可选方向：

- region state 与完整调试视图分离
- runtime 真源与 Studio 调试导出分离
- 或改为更明确的 append / delta 模式

### 6.3 第三优先：让 compact 更少参与常规运行时

目标：

- 进一步把 physical compact 从常规运行路径中抽离

可选方向：

- 更偏向逻辑 discard + 稀疏 tombstone
- 只在人工触发或明确维护窗口执行 physical compact

## 7. 当前断点

这份文档在当前阶段的断点是：

- 已经确认了“当前剩余热点主要在哪里”
- 已经确认问题更偏向“整份构建 / 整份重写”
- 已经确认在普通环境中当前热度可接受
- 因此暂时先不继续围绕性能链路做大改

后续如果再次出现明显卡顿，优先从下面三条重新排查：

1. `StudioRuntimeActionTimelineFile`
2. `StudioRuntimeEpisodeTimelineFile`
3. `StudioRuntimeActionSegmentStoreFile` 的非 append 分支
