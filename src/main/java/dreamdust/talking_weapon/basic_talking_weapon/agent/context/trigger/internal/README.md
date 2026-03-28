# Internal Trigger 开发说明

旧的 internal reflection trigger 骨架已经清除。

当前结论：

- 不再保留旧 `EpisodeSynthesisTrigger -> EpisodeSynthesisTask` 链路
- internal trigger 会在新的 reflection task 主线稳定后重建
- 重建后的内部 trigger 仍然只负责“把 owner 已经准备好的内部 signal 显式包装成后台任务”
- 对于 episode synthesis，这些 signal 应按 closed region 多个产出，并由 internal trigger 逐个提交

边界原则保持不变：

- 普通 trigger
  - 面向玩家操作或游戏世界事件
  - 走 `AgentSensor`
  - 可声明 collectors
- internal trigger
  - 面向 Agent 内部状态流转
  - 不走 `AgentSensor`
  - 不声明 collectors
  - 不替 owner 做二次资格判断
