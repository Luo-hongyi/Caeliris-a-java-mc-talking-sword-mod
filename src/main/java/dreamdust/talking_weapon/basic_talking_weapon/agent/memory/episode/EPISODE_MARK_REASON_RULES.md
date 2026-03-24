# Episode Mark Reason Rules

本文档记录当前 episode boundary mark 的简化版实现方向。

目标很明确：

- 我们要的是稳定、可维护、容错高的 split
- 不是追求“理论上最精确”的边界定位
- 因此不再为 mark 做复杂的 parked / restore 调度

---

## 1. 当前统一模型

### 1.1 最小单位

episode 分析的最小单位仍然是：

- `fixed segment`

也就是 `ActionArchive` 固定时间窗压缩后的段。

### 1.2 public mark 只表达 split at

当前对外公开的候选边界是 `EpisodePendingMark`，正式断点是 `EpisodeSplitPoint`。

`EpisodePendingMark` 只表达：

- `splitSegmentIndex`
- `splitTimestamp`
- `reason`
- `confirmAtSegmentIndex`
- `ownerSplitterId`

不再公开：

- `detectedSegmentIndex`
- `detectedTimestamp`

这些“系统是在什么时候发现这个候选边界”的信息，只保留在 tracker 内部。

原因很简单：

- `mark` 的语义应该是“如果成立，真正切在哪里”
- 不是“我是何时才注意到这里可能要切”

### 1.3 统一流程

当前统一流程是：

1. 先产生 `mark`
2. 如果是低优先级 reason，则等待若干 segment 后进入 confirm
3. 如果是高优先级 reason，则其生效延迟与 `coverAfterSegments` 对齐
4. confirm 时只执行该 reason 自己的内部确认逻辑
5. confirm 成功后，在统一 cover 范围内清理附近 mark

### 1.4 优先级只有两层

当前只保留两层优先级：

- `HIGH`
- `LOW`

当前约定：

- `DEATH` 是唯一 `HIGH`
- 其他 reason 先都按 `LOW`

### 1.5 high mark 的策略

对于 `HIGH` mark，不再做 parked low mark，也不做恢复。

改为直接策略：

1. high mark 入场后，先占据自己的覆盖窗口
2. 它的生效延迟与 `coverAfterSegments` 保持一致
3. 在这段等待窗口内，落入范围的低优先级 mark 直接被清理
4. 等窗口结束后，high mark 自身再完成 confirm
5. 后续不再恢复被清理的 low mark

这是一种故意偏保守的简化：

- 会损失一部分“理论上可能保留的低优先级边界”
- 但换来更简单、更稳定的调度
- 对当前 episode / LTM 的容错需求来说是可接受的

### 1.6 cover 范围

为避免 reason 之间的覆盖语义过于混乱，当前约定：

- 所有 reason 使用同一套 cover 范围

当前代码侧使用的是统一的默认值：

- `coverBeforeSegments = 1`
- `coverAfterSegments = 1`

如果后面需要调参，可以统一外提，而不是按 reason 分裂出不同窗口。

---

## 2. 当前 reason 状态

### 2.1 已进入当前代码链路

- `INTENT_HARD_SHIFT`
- `INTENT_SOFT_SHIFT`
- `DEATH`

其中：

- `DEATH` 是唯一 `HIGH`
- 其他当前都按 `LOW`

---

## 3. 各 reason 规则

### 3.1 `DEATH`

#### mark logic

- 当某个 fixed segment 内出现 `ActionType.DEATH`
- 在该 segment 结尾挂一个 `DEATH mark`

推荐 split at：

- 死亡 segment 的结尾

也就是：

- 把死亡视作上一个 episode 的硬中断

#### confirm logic

- `DEATH` 不再即时确认
- 它的 `confirmDelaySegments` 与 `coverAfterSegments` 保持一致
- 当前默认值下，就是等待 `1` 个 segment

这样做的目的不是为了“更谨慎地确认死亡”，而是为了：

- 让 `DEATH` 能覆盖它之后一小段范围内新出现的低优先级 mark
- 避免“死亡刚发生，下一段又因为 soft shift / length cap 再切一次”

#### cover logic

- `DEATH` 是唯一 `HIGH`
- 入场后开始占据自己的覆盖窗口
- 在等待窗口内，会持续清理落入范围的低优先级 mark
- confirm 后继续作为最终 split 保留

---

### 3.2 `INTENT_HARD_SHIFT`

#### mark logic

- 当当前 region 已积累到最小长度
- 且新 segment 的主 intent 明显脱离当前 region 的 active intents
- 则在新 segment 起点处挂 `INTENT_HARD_SHIFT mark`

#### confirm logic

- 延迟 `1` 个 segment
- 在 confirm 阶段检查这次 shift 是否真的持续成立

当前实现上，这个“是否持续成立”的逻辑主要来自：

- confirm 阶段重新检查 split 前 region 与 split 后连续 segments 的 intent profile
- 如果后续没有真正形成稳定偏移，则该 pending mark 直接失效

#### cover logic

- 它本身是 `LOW`
- 不会在入场时清理别的 low mark
- 只有确认成功后，才会按统一范围清理附近未决/弱标记

---

### 3.3 `INTENT_SOFT_SHIFT`

#### mark logic

- 与 hard shift 一样，先在候选 split 处挂 mark
- 区别只在于 shift 强度更弱

#### confirm logic

- 同样延迟 `1` 个 segment
- 需要后续观察来证明这不是短暂偏移

#### cover logic

- 也是 `LOW`
- 不做即时覆盖

---

## 4. 当前实现上的取舍

### 4.1 我们接受的误差

当前设计明确接受这些误差：

- 某些 low mark 会因为更晚出现的强事件而被直接清理
- split 不一定落在“理论最佳点”
- 一些边界会更粗糙，但整体 episode 语义仍然可用

这是有意为之，因为当前系统的下游是：

- STM 切分
- LTM 生成
- LLM 对一段旅程的概括

它们都具备较高容错。

### 4.2 当前不再追求的能力

当前版本不再追求：

- high mark 失败后的 low mark 恢复
- 为每个 reason 单独维护不同覆盖窗口
- 让 public mark 同时承担 split 位置和 detection 位置两层语义

---

## 5. 后续实现顺序

建议后续按下面顺序推进：

1. 先把当前 intent / death 链路按这个简化模型保持干净
2. 再增加地点 / major structure 等其他 low priority reason
3. 最后再考虑 length cap 这类保底切分规则是否需要回归

这样可以保证 episode 系统始终只增加一种复杂度：

- 先收口模型
- 再扩展 reason
