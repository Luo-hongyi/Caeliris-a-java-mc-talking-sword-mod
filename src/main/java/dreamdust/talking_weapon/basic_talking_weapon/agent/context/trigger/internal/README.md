# Internal Trigger 开发说明

`trigger/internal` 用来放置 **Agent 内部反应型 trigger**。

它和普通 `trigger` 包的边界是：

- 普通 trigger
  - 面向玩家操作或游戏世界事件
  - 走 `AgentSensor`
  - 可声明 collectors
- internal trigger
  - 面向 Agent 内部状态流转
  - 不走 `AgentSensor`
  - 不声明 collectors
  - 只负责把已经形成的内部 signal 显式包装成后台任务

当前第一条内部 trigger 骨架：

- `EpisodeSynthesisTrigger`
  - 输入：`EpisodeSynthesisTriggerSignal`
  - 输出：`EpisodeSynthesisTask`

设计原则：

- episode 子系统内部可以很复杂
- 但它对外只应输出一个干净的 trigger signal
- “是否适合生成记忆”的判断应尽量留在 episode 侧完成
- internal trigger 不负责替 episode 做二次资格判断
