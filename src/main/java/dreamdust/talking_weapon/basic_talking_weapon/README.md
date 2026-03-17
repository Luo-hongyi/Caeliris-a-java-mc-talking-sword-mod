# talking_weapon — 会说话的武器

一个 Minecraft Forge 1.20.1 模组，让你的铁剑拥有视觉和语言——它会"看到"你的游戏画面并用 LLM 生成的文字回应你。采用 Embodied Agent 架构，具备感知、记忆、状态和动作系统，支持模块化的触发器和采集器扩展。

## 当前开发状态

截至 `2026-03-16`，当前分支的认知与记忆架构状态如下：

- `DetailTag` 新架构已落地：`native_specific` / `native_generic` / `custom_specific` / `custom_generic` 四类标签已经统一。
- `native_generic` 已全量进入 Collector、Context、STM、Persona 匹配和 LTM 检索层，但在 Prompt 合成阶段默认隐藏，不直接暴露给 LLM。
- `TagDescriptionDictionary` 与 `TagRelationDictionary` 已拆分：前者只负责描述文本，后者维护一对多、无环的语义关系图。
- `PersonaMatcher` 已按最新定义修正 `specific` / `generic` 匹配逻辑。
- 长期记忆召回链已按“visible context 起点 + focus 导向 + 温度采样 + recent recall 抑制 + nostalgia 时间衰退”的新方案重构完成。
- `associativeOnly` 的最新语义已经落地：隐藏条目只能由 `SubconsciousBooster` 直通解锁，后续 Persona / Memory 阶段不会再次反向显露未见上下文。
- 全局植入记忆 (`implanted memories`) 的读写链路可用。
- 当前仍未完成的关键组件，是与这套新 tag / recall 架构完全一致的 **LTM 生成闭环**。仓库中仍保留旧版 `ReflectionTask` / `ReflectionTrigger` 骨架，但它还没有完成最新设计下的稳定接线、筛选策略与生成质量约束，因此不能视为该阶段已经彻底完成。

## ✨ 核心特性

- **多模态 AI 交互**：右键持剑即可截图并发送给 LLM，获得基于画面的个性化回复
- **Embodied Agent 架构**：感知 → 记忆 → 决策 → 动作 的完整智能体流水线
- **Trigger / Collector 策略模式**：触发器声明所需采集器列表，采集器按需动态组合
- **Per-Trigger 独立配置**：每种触发器独立配置自己的采集器开关和专属 User Prompt
- **感知系统增强**：进入新群系、穿越维度或完成长距离旅行时自动感知并发表评论
- **智能动作流 (ActionBuffer)**：高频合并动作，支持 3 分钟滑动窗口自动过期与“阅后即焚”机制
- **环境自适应摘要**：建造复杂房子时自动对 5 种以上方块动作进行聚类折叠，保护上下文长度
- **Action Bar 显示**：回复显示在快捷栏上方，自动换行，不干扰聊天记录
- **多厂商支持**：支持 OpenAI / Qwen / DeepSeek(无视觉) / Custom（通过 OpenAI 兼容接口）
- **左键打断**：思考过程中可随时左键中断请求
- **游戏内前台设定与调试 GUI**：支持右下方界面查看和热更新 武器情绪、熟悉度、基础人格词条。
- **跨地图设定植入 (Implanted Memory)**：允许通过 `LTMStorage` 保存和读取跨地图永恒人设。游戏内通过 OS 桥接跳转编辑器，免于前台版面臃肿，并在状态栏重新激活时底层自动热更！
- **完全可自定义**：触发 / 打断 / 系统 Prompt / Per-Trigger Prompt / 输出长度 / 武器名称

## 📁 模块结构

```
agent/
├── context/                        ← 感知 & 上下文
│   ├── AgentContext.java            — 不可变游戏状态快照 (Builder 模式 + extras Map)
│   ├── AgentSensor.java             — 唯一事件入口：防抖 + 遍历采集器 + 写入 user_prompt + 提交任务
│   ├── collector/                   ← 采集器（Strategy 模式，纯数据采集，无开关逻辑）
│   │   ├── IContextCollector.java    — 采集器接口：getName() + collect(Builder, Player, Level)
│   │   ├── BaseContextCollector.java — HP / 饥饿值 / 坐标 / 群系 / 天气 / 时间
│   │   ├── ScreenshotCollector.java  — 截图 → Base64（受全局 ENABLE_SCREENSHOT 控制）
│   │   ├── NearbyEntitiesCollector.java — 附近实体扫描（16格内，按类型分组）
│   │   ├── SurroundingBlocksCollector.java — 周报方块统计与环境高阶推断（露天/洞穴/高空）
│   │   ├── PlayerEnvironmentCollector.java — 维度 / 光照 / 持有物品 / 盔甲 / 准星目标
│   │   └── README.md                — 采集器开发指南
│   └── trigger/                     ← 触发器（定义事件 → 动态采集器列表 → 专属 prompt → 任务类型）
│       ├── IAgentTrigger.java        — 触发器接口 + default getUserPrompt()
│       ├── RightClickTrigger.java    — 右键触发（按 RC_* 配置动态组合采集器 → LLMInteractionTask）
│       ├── BiomeChangeTrigger.java   — 群系变化触发（含 3 分钟平滑防抖/节流）
│       ├── DimensionChangeTrigger.java — 维度切换触发（支持跨维度坐标重置）
│       ├── TravelTrigger.java        — 长距离位移触发（默认横向 200 格或纵向 50 格）
│       ├── TickEventWatcher.java     — 统一事件感知器（Forge Events → ActionBuffer + 群系/维度/旅行检测）
│       ├── CombatRecapTrigger.java   — 连杀回顾触发器（骨架）
│       ├── BossKillTrigger.java      — Boss击杀触发器（骨架）
│       └── README.md                — 触发器开发指南
├── state/                          ← 显式状态
│   └── AgentState.java              — 情绪 Mood 枚举 + 熟悉度 + 当前目标
├── memory/                         ← 记忆系统
│   ├── AgentOutputRecord.java       — Agent 自身输出的行为记录 (语音/内心独白)
│   ├── InteractionTurn.java         — 完整的单轮回合（含 TriggerSource、User Prompt 与产生的 Output 列表）
│   ├── STMPool.java                 — 短期记忆聚合池：收集本纪元的全局 Context Entries + Interaction回合
│   ├── ActionType.java              — 动作类型枚举（带主/跨域 FocusTag 映射）
│   ├── ActionRecord.java            — 极轻量动作记录（TTL 刷新 + 全缓冲区扫描合并）
│   ├── ActionBuffer.java            — 环形动作缓冲区（3min 滑动窗口淘汰，支持 drainTo 已读池）
│   ├── LTMEntry.java                — 长期记忆切片（基于Tag检索，含重要度和清晰度衰减）
│   ├── LTMStorage.java              — 长期记忆跨存档读取保存持久化服务
│   ├── AgentMemory.java             — 行动流、短期汇总、LTM库、偏好身份管理
│   └── README.md                    — 记忆系统现状与设计边界
├── task/                           ← 任务调度
│   ├── AgentTask.java               — 抽象基类 (taskId/priority/cancel/Comparable)
│   ├── LLMInteractionTask.java      — LLM 交互：从 extras 读 user_prompt → 调 LLM → 更新记忆
│   ├── MemoryUpdateTask.java        — 零网络开销的本地记忆更新
│   ├── AgentStateUpdateTask.java    — 零网络开销的状态更新（含 description）
│   └── ReflectionTask.java          — 旧版后台反思 / LTM 合成骨架（待按新架构重做）
├── action/                         ← 动作执行
│   ├── AgentAction.java             — 抽象基类 (perform/estimatedDuration)
│   ├── SpeechAction.java            — ActionBar 逐行文字展示
│   ├── SoundAction.java             — 音效播放
│   └── EmoteAction.java             — 粒子表情
├── runtime/                        ← Agent 运行时
│   └── AgentRuntime.java            — 双队列双 worker（前台交互 + 后台反思）+ 全局 Memory/State
├── dictionary/                     ← 配置字典
│   ├── TagDescriptionDictionary.java — DetailTag → 自然语言描述（只负责描述文本）
│   ├── TagRelationDictionary.java  — DetailTag 一对多关系图（匹配 / fallback，要求无环）
│   └── LocationRegistry.java        — 关键地点注册表（运行时增删 + 即时持久化）
└── llm_service/                    ← LLM 工具
    ├── LLMAgentService.java         — 纯工具类：buildModel()
    ├── AgentResponse.java           — LLM JSON 响应结构（thinking/inner_monologue/speech）
    └── AgentResponseParser.java     — 三级回退解析器（JSON → 代码块 → 纯文本）

config/
├── TalkingWeaponConfig.java         — Forge 客户端配置（per-trigger 采集器开关 + per-trigger prompt）
└── TalkingWeaponConfigScreen.java   — 游戏内可滚动配置界面

TalkingSword.java                    — 剑物品：右键 → fireTrigger(RightClickTrigger)
ClientEventHandler.java              — 左键打断 → AgentSensor.triggerCancel()
ScreenshotHelper.java                — 截图 + 缩放 + Base64 编码
basic_talking_weapon.java            — Forge 主类（注册 TickEventWatcher + ConfigScreen）
```

## 🎮 使用流程

### 数据流（Trigger → Collector → Task）

```
TalkingSword.use()
  └→ AgentSensor.fireTrigger(new RightClickTrigger(), player)
       ├─ 防抖检查 (canTrigger / requiresDebounce)
       ├─ 忙检查 (isRuntimeBusy)
       ├─ 遍历 trigger.getCollectors()         ← 根据 [trigger.right_click] 配置动态构建
       │    ├─ BaseContextCollector.collect()     ← RC_ENABLE_BASE
       │    ├─ ScreenshotCollector.collect()      ← RC_ENABLE_SCREENSHOT
       │    ├─ NearbyEntitiesCollector.collect()  ← RC_ENABLE_NEARBY_ENTITIES
       │    └─ PlayerEnvironmentCollector.collect() ← RC_ENABLE_PLAYER_ENVIRONMENT
       ├─ 写入 extras["user_prompt"] ← trigger.getUserPrompt()
       ├─ 构建不可变 AgentContext（core 字段 + extras Map）
       ├─ 显示触发提示
       └─ AgentRuntime.submit(trigger.createTask(context))
              ↓ worker 线程 (PriorityBlockingQueue)
         LLMInteractionTask.execute()
              ├─ buildSystemPrompt(memory + state + context + extras)
              ├─ userPrompt = ctx.extras["user_prompt"]   ← 触发器专属 prompt
              ├─ LLMAgentService.buildModel()
              ├─ model.generate(systemMsg, userMsg)
              ├─ SpeechAction("剑：" + reply).perform()
              │    └─ 逐行提交到主线程 ActionBar
              ├─ STMPool.pushInteractionTurn() ← 将这一个回合的操作收录至短期记忆池
              └─ STMPool自动淘汰逻辑触发
```

### 被动触发（TickEventWatcher）

```
TickEventWatcher.onClientTick()  ← 每 100 tick (5 秒) 检测
  ├─ 检查 ENABLE_PROACTIVE_TRIGGERS
  ├─ 检查 ENABLE_BIOME_DETECTION
  ├─ 检测群系变化 → fireTrigger(new BiomeChangeTrigger(oldBiome, newBiome))
  │    ├─ 根据 [trigger.biome_change] 配置动态组合采集器
  │    ├─ getUserPrompt() 将 {old_biome}/{new_biome} 替换为实际群系名
  │    └─ → LLMInteractionTask（调用 LLM 对新群系发表评论）
  └─ （可扩展：低血量、日夜交替、长时间挂机等）
```

### Prompt 流转机制

```
IAgentTrigger.getUserPrompt()          ← 每种触发器返回各自专属 prompt
       ↓
AgentSensor → builder.extra("user_prompt", ...)
       ↓
AgentContext.extras["user_prompt"]      ← 随 context 传递
       ↓
LLMInteractionTask → 作为 UserMessage 发送给 LLM
```

- **RightClickTrigger** → 读取 `RIGHT_CLICK_PROMPT` 配置
- **BiomeChangeTrigger** → 读取 `BIOME_CHANGE_PROMPT` 配置，替换 `{old_biome}` / `{new_biome}` 占位符
- 新增触发器只需覆写 `getUserPrompt()` 即可拥有独立 prompt

### DetailTag 可见性规范

- `native_specific` 与全部 `custom_*`：允许进入 Prompt。
- `native_generic`：Collector 会全量接收，进入 `TaggedContextEntry`、STM、Persona / Memory 检索，但在 Prompt 合成阶段默认隐藏。
- `associativeOnly`：控制的是“整条 ContextEntry 默认是否可见”，且仅能由 `SubconsciousBooster` 直通提升为可见。
- `native_generic` 隐藏：控制的是“条目可见时，哪些 detail tag 不输出给 LLM”。

这两个机制是正交的：

- `associativeOnly = true` 的条目默认不会进入 Prompt；只有被 `SubconsciousBooster` 选中后，才会以可见条目的身份进入后续 Persona / Memory 流程。
- 某条条目即使进入 Prompt，其中的 `native_generic` 仍不会直接序列化给 LLM。

### 长期记忆召回

- `LLMInteractionTask` 会先从当前 `visibleContexts` 中采样少量 seed entries，再从其中采样 seed tags，逐层联想到少量 `LTMEntry`。
- 长期记忆不是全量匹配，而是“focus 导向 + 温度采样”的召回链：`wanderingMind` 提供随机温度，`concentration` 与当前 Focus 压力共同决定联想方向和联想深度。
- 多个不同 seed 可以共同指向同一条记忆；系统会在记忆层聚合这些证据，而不是在 seed 层过早去重。
- 系统会维护一个最近召回窗口，抑制同一段记忆在连续几轮中反复出现；`nostalgia` 则控制旧记忆随时间衰退的力度。
- `LTMEntry` 现在会记录轻量的游戏内时间锚点（如“第 N 天的深夜”），并在召回时优先按游戏内时间而不是现实时间计算记忆老化。
- 长期记忆只会从当前 `visibleContexts` 起跳，不会反向解锁未被 booster 提升的隐藏 `ContextEntry`。
- `AssociationMode.CUSTOM` 的具体参数由 `talking_weapon-client.toml` 中的 `agent.memory_recall` 配置控制，其中包含 seed 数、联想深度、最终记忆上限和最近召回窗口。

### 长期记忆生成

- 当前代码库中保留了一个旧版 `ReflectionTask` / `ReflectionTrigger` 路线，用于把一个纪元内的 STM 压缩为 `LTMEntry`。
- 但这条生成链还没有完成与最新 detail-tag 语义、噪声过滤、visible context 设计、recent recall 策略的统一，因此当前阶段不应视为“LTM 生成系统已完成”。
- 当前开发的关键缺口是：构建一个与新召回方案对齐的 LTM 生成闭环，包括何时结算、如何从 STM 提炼 detail tags / focus tags、如何控制植入与生成记忆的质量边界，以及如何与 persona / favorability 的低频更新协同。

### 显示机制
- 所有剑的发言在**快捷栏上方（Action Bar）**显示
- 长文本自动按 26 字符 / 行切分
- 每行停留时间自适应（1.2s ~ 2.8s，取决于字符数）
- 不会进入聊天记录，不干扰游戏体验

## ⚙️ 配置

### 方式一：游戏内配置界面
1. 进入 "Mods" 列表，找到 "Talking Weapon"
2. 点击 "Config" 按钮
3. 可滚动列表中可设置：
   - **LLM 设置**：厂商选择 / 模型选择
   - **截图设置**：宽度 × 高度（像素）
   - **右键触发 · 采集器**（4 个独立开关）：
     - 基础状态采集
     - 截图采集
     - 附近实体采集
     - 玩家环境采集
   - **群系变化 · 采集器**（4 个独立开关）：
     - 基础状态采集
     - 截图采集（默认关闭）
     - 附近实体采集
     - 玩家环境采集
   - **Agent 行为**：
     - 主动触发（总开关）
     - 群系变化检测
4. 点击 "打开配置文件(.toml)" 按钮编辑文本配置（包括 prompt、API Key 等长文本字段）

### 方式二：直接编辑配置文件
编辑 `config/talking_weapon-client.toml`（首次启动后自动生成）：

```toml
[llm]
  provider = "OpenAI"
  apiKey = "sk-..."
  baseUrl = "https://api.openai.com/v1/"
  modelName = "gpt-4o"
  maxTokens = 256

[screenshot]
  enableScreenshot = true
  width = 320
  height = 180

[text]
  weaponName = "塞了瑞思"
  rightClickPrompt = "请根据你看到的画面和当前状态，以武器灵魂的身份发表一到两句话的简短评论。"
  biomeChangePrompt = "你发现主人刚从{old_biome}走入了{new_biome}。以武器灵魂的身份对这个新环境发表一到两句话的简短评论。"
  triggerSpeech = "你感受到一丝灵魂的悸动……"
  cancelSpeech = "剑：沉默不语……"
  systemIdentity = "你是一把会说话的剑中的灵魂..."

[trigger.right_click]
  enableBase = true
  enableScreenshot = true
  enableNearbyEntities = true
  enablePlayerEnvironment = true

[trigger.biome_change]
  enableBase = true
  enableScreenshot = false
  enableNearbyEntities = true
  enablePlayerEnvironment = true

[agent]
  enableProactiveTriggers = true
  enableBiomeDetection = true
```

## 🔌 支持的厂商与模型

| 厂商 | 可用模型（游戏内可切换） | Base URL |
|------|--------------------------|----------|
| **OpenAI** | gpt-4o, gpt-4o-mini, gpt-5.2 | `https://api.openai.com/v1/` |
| **Qwen (通义千问)** | qwen3.5-plus, qwen3.5-flash | `https://dashscope.aliyuncs.com/compatible-mode/v1/` |
| **DeepSeek(无视觉)** | deepseek-v3.2 | `https://api.deepseek.com/v1/` |
| **Custom** | custom-model（自行填写） | 自定义 |

> **注**：所有厂商均通过 OpenAI 兼容接口调用。DeepSeek 标注"无视觉"表示不支持图片输入。

## 🏗️ Agent 架构

### 设计理念

采用 **Embodied Agent** (具身智能体) 模式，武器灵魂拥有：

| 能力 | 实现 |
|------|------|
| **感知** | `AgentSensor` + `IContextCollector` — 模块化采集器组合 → 不可变 `AgentContext` |
| **触发** | `IAgentTrigger` — 声明采集器列表 + 专属 prompt + 任务类型，支持被动/主动两种模式 |
| **记忆** | `AgentMemory` — 四级架构：Identity / ActionBuffer / STMPool / LTMRecords |
| **状态** | `AgentState` — 情绪(8种Mood) + 熟悉度(0-100) + 当前目标 |
| **决策** | `LLMInteractionTask` — 构建含记忆+状态+上下文+extras 的 prompt → LLM 推理（JSON输出） |
| **行动** | `SpeechAction` / `SoundAction` / `EmoteAction` — 主线程执行 |
| **调度** | `AgentRuntime` — 双队列（前台交互 + 后台反思），串行避免并发 |

### Trigger / Collector 模式

```
IAgentTrigger (触发器)
├── getSourceName()      → "right_click" | "biome_change" | ...
├── requiresDebounce()   → 是否需要防抖
├── getCollectors()      → 根据各自的 per-trigger config 动态构建列表
├── getUserPrompt()      → 返回触发器专属 user prompt（default 方法）
└── createTask(context)  → LLMInteractionTask | AgentStateUpdateTask

IContextCollector (采集器)
├── getName()            → "base" | "screenshot" | "nearby_entities" | ...
└── collect(builder, player, level) → 写入 AgentContext 核心字段或 extras
```

- **触发器**决定"什么事件 → 动态组合哪些采集器 → 用什么 prompt → 创建什么任务"
- **采集器**为纯数据采集逻辑，不含开关判断（是否启用由触发器层根据配置决定）
- 新增触发器只需实现 `IAgentTrigger`，在 config 中添加同名 `[trigger.xxx]` 段
- 新增采集器只需实现 `IContextCollector`，然后加入触发器的 `getCollectors()`

> 详细指南见 `collector/README.md` 和 `trigger/README.md`

### 任务优先级

| 优先级 | 任务类型 | 说明 |
|--------|----------|------|
| 5 | `MemoryUpdateTask` | 本地记忆更新，零网络开销 |
| 5 | `AgentStateUpdateTask` | 本地状态更新，零网络开销 |
| 10 | `LLMInteractionTask` | 需要 LLM 调用的核心交互 |
| 50 | `ReflectionTask` | 旧版后台反思骨架，尚未完成与当前记忆架构的统一 |

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

1. **调整截图分辨率**：更大分辨率 = 更准确但更慢 / 更贵；推荐 `320×180`
2. **精细控制采集器**：每种触发器可独立开关采集器，按场景优化 token 消耗
3. **自定义 per-trigger prompt**：在 toml 中修改 `rightClickPrompt` / `biomeChangePrompt`，针对不同触发场景写不同提示词
4. **群系 prompt 占位符**：`biomeChangePrompt` 支持 `{old_biome}` / `{new_biome}` 占位符
5. **自定义人格**：修改 `systemIdentity` 字段，可以让剑变成毒舌 / 傲娇 / 冷漠等任意风格
6. **多模型切换**：在游戏内配置界面快速切换不同厂商和模型
7. **打断长时请求**：如果 LLM 响应过慢，直接左键即可中断等待
8. **关闭主动触发**：不希望武器主动说话时，关闭"主动触发"开关

## 🐛 常见问题

**Q: 剑不说话？**  
A: 检查配置文件中的 `apiKey` 是否正确填写，且不含"填写"等占位符。

**Q: 如何使用 Qwen / DeepSeek？**  
A: 在游戏内配置界面选择对应厂商，然后编辑配置文件填写正确的 `baseUrl` 和 `apiKey`。

**Q: Action Bar 显示太快？**  
A: 每行停留时间自动计算（基于字符数），如需调整请修改 `SpeechAction.calculateLineDisplayMillis()` 方法。

**Q: 想要更长的回复？**  
A: 修改配置文件中的 `maxTokens` 值（建议不超过 500，避免 Action Bar 轮播过久）。

**Q: 如何为新触发器添加专属 prompt？**  
A: 在 config 中新增对应配置字段，在触发器中覆写 `getUserPrompt()` 读取即可。prompt 通过 `extras["user_prompt"]` 自动传递给 `LLMInteractionTask`。

**Q: 如何扩展新的采集器或触发器？**  
A: 参见 `agent/context/collector/README.md` 和 `agent/context/trigger/README.md`。

## 📄 许可

MIT License
