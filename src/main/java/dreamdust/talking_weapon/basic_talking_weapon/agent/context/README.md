# 核心上下文引擎 (Context Engine)

## 概述

`agent.context` 包掌管着剑灵对游戏世界的 **感知输入与注意力过滤**。它是连接 Minecraft 本地环境与上层 LLM 认知模型（及记忆池）的桥梁。

整个感知引擎基于“**触发源 -> 收集数据 -> 标签化 -> 注意力过滤 -> 提示词注入/记忆池**”的管线运行。

## 目录结构

- `collector/`
  环境上下文采集器，负责提取具体的游戏侧信息（例如：“检测到僵尸”、“在山洞里”、“在注视钻石矿”）。
  每一个 Collector 采集特定领域的信息，并封装成标准数据模型。详见 [Collector 简介](collector/README.md)。
- `trigger/`
  触发器系统，定义了大模型**何时**被唤醒。它们管理冷却、节流防抖，并规定每次唤醒应启动哪些采集器套餐。详见 [Trigger 简介](trigger/README.md)。
- `tag/`
  数据标签与分类模型。包含 `FocusTag` (注意力分类)、`DetailTag` (具体词条) 以及封装数据的 `TaggedContextEntry` (含客/主观重要度分析)。

## 工作管线 (Data Pipeline)

### 1. 触发激活 (Trigger -> Sensor)

当游戏内的事件发生（例如右键剑、群系发生变化、受到攻击），`AgentSensor` 会接管触发事件 `IAgentTrigger`。
`AgentSensor` 检查当前是否可用（避免被 LLM 并发淹没），如果允许触发，就会执行。

### 2. 采集执行 (Collector -> AgentContext)

一旦被触发，事件对应的多个 `IContextCollector` 会被一并在主线程运行。
它们将周围的物体、环境、结构提取成 `TaggedContextEntry`，并将数据塞入 `AgentContext`（本次上下文的一个完整快照）。

> **亮点：**海量冗余方块采用了 `associativeOnly = true` 隐式标签。它们默认不会进入 Prompt，只有在 `SubconsciousBooster` 把某条隐藏条目直通提升后，才会进入后续人格与记忆流程，对大模型保持严格限流。

> **补充：**`native_generic:*` 与 `associativeOnly` 是两套不同机制。前者会进入系统检索层，但在 Prompt 合成阶段默认隐藏；后者控制的是整条 `TaggedContextEntry` 是否默认对大模型可见。

> **记忆召回方向：**长期记忆不会从隐藏条目反向解锁上下文，而是只从已经进入 `visibleContexts` 的条目出发；随后由当前 Focus 压力、随想值和忆旧值共同影响联想方向、采样随机性与时间衰退。

### 3. 注意力过滤 (AttentionFilter)

这是 **Phase 3** 加入的核心机制，它保证大模型不会被纷繁复杂的垃圾数据和信息冲毁思维 (Token Overflow)。
1. **主观重要度计分**：`Feature Base Importance` * `Subjective Modifier` 计算。不同标签 (`FocusTag`) 有不同的喜好权重（存在 `TalkingWeaponConfig` 中），且受到剑的 `concentration`（专注度属性）扭曲放大或碾平。
2. **截断过滤 (Top-K)**：对于分数低于 `0.2` 的环境信息直接摒弃噪音（潜意识过滤）。最终取排名前 `Top-K`（K根据触发源 `triggerSource` 在 config 中独立动态配置）的数据条目！

### 4. 字典转换注入 (LLMInteractionTask / Prompt Generation)

最后，通过 Attention Filter 筛选下来的黄金信息，会进入提示词合成组装：
经过 `TagDescriptionDictionary` 的翻译（将生硬的 ID 转换为人类自然语言如 `minecraft:diamond` -> `钻石`），最终打印上“(主观重要度: 0.8)”丢给大模型推演。

关系 fallback 不再由描述字典维护，而是转移至 `TagRelationDictionary`：

- `tag_descriptions.json`：只维护描述文本
- `tag_relations.json`：维护一对多、无环的语义关系图

在 Prompt 阶段，系统会隐藏 `native_generic:*`，只向 LLM 暴露：

- `native_specific:*`
- `custom_specific:*`
- `custom_generic:*`
