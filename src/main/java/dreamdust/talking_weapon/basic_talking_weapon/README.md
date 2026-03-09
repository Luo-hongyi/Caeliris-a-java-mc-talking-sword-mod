# talking_weapon — 会说话的武器

一个 Minecraft Forge 1.20.1 模组，让你的铁剑拥有视觉和语言——它会"看到"你的游戏画面并用 LLM 生成的文字回应你。采用 Embodied Agent 架构，具备感知、记忆、状态和动作系统。

## ✨ 核心特性

- **多模态 AI 交互**：右键持剑即可截图并发送给 LLM，获得基于画面的个性化回复
- **Embodied Agent 架构**：感知 → 记忆 → 决策 → 动作 的完整智能体流水线
- **三层记忆系统**：Identity 人格 / Short-term 对话窗口 / Long-term 语义摘要
- **显式状态机**：情绪（Mood）、熟悉度、当前目标，注入到 LLM prompt 中
- **Action Bar 显示**：回复显示在快捷栏上方，自动换行，不干扰聊天记录
- **多厂商支持**：支持 OpenAI / Qwen / Gemini（通过 OpenAI 兼容接口）
- **左键打断**：思考过程中可随时左键中断请求
- **游戏内配置界面**：从模组列表直接打开配置，或点击按钮编辑 `.toml` 文件
- **完全可自定义**：触发 / 打断 / 系统 Prompt / 输出长度 / 武器名称均可配置

## 📁 模块结构

```
agent/
├── context/                  ← 感知 & 上下文
│   ├── AgentContext.java      — 不可变游戏状态快照 (Builder 模式)
│   └── AgentSensor.java       — 唯一事件入口：防抖 + 采集 + 提交任务 + 取消
├── state/                    ← 显式状态
│   └── AgentState.java        — 情绪 Mood 枚举 + 熟悉度 + 当前目标
├── memory/                   ← 记忆系统
│   ├── AgentMsg.java          — 不可变消息 (Role/Content/Image/Metadata)
│   └── AgentMemory.java       — 三层记忆：Identity / Short-term / Long-term
├── task/                     ← 任务调度
│   ├── AgentTask.java         — 抽象基类 (taskId/priority/cancel/Comparable)
│   ├── LLMInteractionTask.java — LLM 交互任务：构建 prompt → 调 LLM → SpeechAction → 更新记忆
│   ├── MemoryUpdateTask.java  — 零网络开销的本地记忆更新
│   ├── AgentStateUpdateTask.java — 零网络开销的状态更新
│   └── ReflectionTask.java    — 后台记忆压缩/反思
├── action/                   ← 动作执行
│   ├── AgentAction.java       — 抽象基类 (perform/estimatedDuration)
│   ├── SpeechAction.java      — ActionBar 逐行文字展示
│   ├── SoundAction.java       — 音效播放
│   └── EmoteAction.java       — 粒子表情
├── runtime/                  ← Agent 运行时
│   └── AgentRuntime.java      — 单 worker + PriorityBlockingQueue + 全局 Memory/State
└── llm_service/              ← LLM 工具
    └── LLMAgentService.java   — 纯工具类：buildModel()

config/
├── TalkingWeaponConfig.java   — Forge 客户端配置
└── TalkingWeaponConfigScreen.java — 游戏内配置界面

TalkingSword.java              — 剑物品：右键委托给 AgentSensor
ClientEventHandler.java        — 左键打断委托给 AgentSensor
ScreenshotHelper.java          — 截图 + 缩放 + Base64 编码
basic_talking_weapon.java      — Forge 主类
```

## 🎮 使用流程

### 数据流

```
TalkingSword.use() ──→ AgentSensor.triggerRightClick(player)
                         ├─ 防抖检查 (canTrigger)
                         ├─ capture("right_click", withScreenshot=true)
                         │    └─ 主线程采集：HP / 饥饿值 / 生物群系 / 坐标 / 天气 / 截图
                         ├─ 显示触发提示
                         └─ AgentRuntime.submit(new LLMInteractionTask(context))
                                ↓ worker 线程 (PriorityBlockingQueue)
                           LLMInteractionTask.execute()
                                ├─ buildSystemPrompt(memory + state + context)
                                ├─ LLMAgentService.buildModel()
                                ├─ model.generate(systemMsg, userMsg)
                                ├─ SpeechAction("剑：" + reply).perform()
                                │    └─ 逐行提交到主线程 ActionBar
                                └─ memory.addShortTerm(user + assistant)

ClientEventHandler ──→ AgentSensor.triggerCancel(player)
                         ├─ AgentRuntime.cancelCurrentAndClearQueue()
                         │    └─ queue.clear() + currentTask.cancel() + interrupt
                         └─ 显示取消提示
```

### 显示机制
- 所有剑的发言在**快捷栏上方（Action Bar）**显示
- 长文本自动按 26 字符 / 行切分
- 每行停留时间自适应（1.2s ~ 2.8s，取决于字符数）
- 不会进入聊天记录，不干扰游戏体验

## ⚙️ 配置

### 方式一：游戏内配置界面
1. 进入"Mods"列表，找到"Talking Weapon"
2. 点击"Config"按钮
3. 可设置：
   - **厂商选择**：OpenAI / Qwen / Gemini
   - **模型选择**：根据厂商动态显示可用模型
   - **截图分辨率**：宽度 × 高度（像素）
4. 点击"打开配置文件(.toml)"按钮编辑文本配置

### 方式二：直接编辑配置文件
编辑 `config/talking_weapon-client.toml`（首次启动后自动生成）：

```toml
[llm]
  # 厂商：OpenAI | Qwen | Gemini
  provider = "OpenAI"
  
  # API Key（必填）
  api_key = "sk-..."
  
  # OpenAI 兼容接口地址
  base_url = "https://api.openai.com/v1/"
  
  # 模型名称
  model_name = "gpt-4o"
  
  # 最大输出 token（默认 300）
  max_tokens = 300
  
  # 触发时的发言
  trigger_speech = "你感受到一丝灵魂的悸动……"
  
  # 打断时的发言
  cancel_speech = "剑：沉默不语……"
  
  # 系统 Prompt（剑的人格设定）
  prompt = "你是一把充满个性的会说话的剑..."

[screenshot]
  width = 512
  height = 288

[item]
  weapon_name = "会说话的铁剑"
```

## 🔌 支持的厂商与模型

| 厂商 | 可用模型（游戏内可切换） | Base URL |
|------|--------------------------|----------|
| **OpenAI** | gpt-4o, gpt-4o-mini, gpt-5.2 | `https://api.openai.com/v1/` |
| **Qwen** | qwen3.5-plus, qwen3.5-flash, qwen3-vl-plus, qwen3-vl-flash | `https://dashscope.aliyuncs.com/compatible-mode/v1/` |
| **Gemini** | gemini-3.1-flash-lite-preview, gemini-3-flash-preview | 需第三方 OpenAI 兼容代理 |

> **注**：当前所有厂商均通过 OpenAI 兼容接口调用。如需原生 Claude API 支持，需额外添加 `langchain4j-anthropic` 依赖。

## 🏗️ Agent 架构

### 设计理念

采用 **Embodied Agent** (具身智能体) 模式，武器灵魂拥有：

| 能力 | 实现 |
|------|------|
| **感知** | `AgentSensor` — 采集玩家状态、环境、截图 → 不可变 `AgentContext` |
| **记忆** | `AgentMemory` — 三层：Identity(人格) / Short-term(滑动窗口) / Long-term(语义摘要) |
| **状态** | `AgentState` — 情绪(8种Mood) + 熟悉度(0-100) + 当前目标 |
| **决策** | `LLMInteractionTask` — 构建含记忆+状态+上下文的 prompt → LLM 推理 |
| **行动** | `SpeechAction` / `SoundAction` / `EmoteAction` — 主线程执行 |
| **调度** | `AgentRuntime` — 单 worker + 优先级队列，串行避免并发 |

### 任务优先级

| 优先级 | 任务类型 | 说明 |
|--------|----------|------|
| 5 | `MemoryUpdateTask` | 本地记忆更新，零网络开销 |
| 5 | `AgentStateUpdateTask` | 本地状态更新，零网络开销 |
| 10 | `LLMInteractionTask` | 需要 LLM 调用的核心交互 |
| 50 | `ReflectionTask` | 后台记忆压缩，空闲时执行 |

## 🛠️ 技术栈

- **Minecraft Forge** `1.20.1-47.4.10`
- **LangChain4j** `0.31.0`（核心 + OpenAI 模块）
- **Shadow Plugin** `8.1.1`（依赖打包与 relocate）

所有第三方依赖通过 Shadow Plugin 打包进 jar 并 relocate 至 `dreamdust.shadow.*`，避免与 Forge 模块冲突。

## 📦 构建

```bash
./gradlew shadowJar
```

生成产物：`build/libs/basic_talking_weapon-1.0.0-all.jar`

放入 `.minecraft/mods/` 文件夹即可。

## 🎯 使用技巧

1. **调整截图分辨率**：更大分辨率 = 更准确但更慢 / 更贵；推荐 `512×288`
2. **自定义人格**：修改 `prompt` 字段，可以让剑变成毒舌 / 傲娇 / 冷漠等任意风格
3. **多模型切换**：在游戏内配置界面快速切换不同厂商和模型
4. **打断长时请求**：如果 LLM 响应过慢，直接左键即可中断等待
5. **自定义武器名称**：在配置文件中修改 `weapon_name`

## 🐛 常见问题

**Q: 剑不说话？**  
A: 检查配置文件中的 `api_key` 是否正确填写，且不含"填写"等占位符。

**Q: 如何使用 Qwen / Gemini？**  
A: 在游戏内配置界面选择对应厂商，然后编辑配置文件填写正确的 `base_url` 和 `api_key`。

**Q: Action Bar 显示太快？**  
A: 每行停留时间自动计算（基于字符数），如需调整请修改 `SpeechAction.calculateLineDisplayMillis()` 方法。

**Q: 想要更长的回复？**  
A: 修改配置文件中的 `max_tokens` 值（建议不超过 500，避免 Action Bar 轮播过久）。

## 📄 许可

MIT License
