# Startup Compact Fixture

这组文件用于手动验证：

- 游戏启动时会读取 `studio_runtime_action_timeline.json` 中持久化的 discard window
- `AgentMemory` 初始化阶段会执行一次启动期 compact
- compact 成功后会立即把新的 action / episode runtime snapshot 写回 workspace

## 初始状态

- physical fixed segments: `1, 2, 3`
- discard window: 只覆盖 `segmentId=2`

## 预期结果

游戏启动后：

- `studio_runtime_action_segments.data.jsonl` 应只剩 `segmentId=1` 和 `segmentId=3`
- `studio_runtime_action_segments.json` 的 `fixedSegmentCount` 应变成 `2`
- `studio_runtime_action_timeline.json` 中：
  - `fixedSegmentCount` 应变成 `2`
  - `fixedSegmentSequenceToken` 应变成 `2|1:0:60000|3:120000:180000`
  - `runtimeState.discardWindows` 应变成空数组

## 文件清单

- `studio_runtime_action_timeline.json`
- `studio_runtime_action_segments.json`
- `studio_runtime_action_segments.data.jsonl`
