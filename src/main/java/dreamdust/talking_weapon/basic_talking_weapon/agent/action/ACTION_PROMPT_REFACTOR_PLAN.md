# Action Prompt Refactor Plan

## 背景

当前 action 链已经有一部分 authored prompt 内容，但“文本资源”和“提示词结构拼装”仍然混在一起：

- authored 文本主要来自：
  - `PromptRegistry`
  - `default_prompts.json`
- 真正的 prompt 总装主要发生在：
  - `agent/action/task/LLMInteractionTask.java`

这导致几个问题：

- `LLMInteractionTask` 同时承担 task、formatter、prompt assembler 三种职责，过重
- 公共段落、task 专属段落、trigger 专属段落没有稳定分层
- output contract 的说明和渲染逻辑耦合在 Java 里，不利于维护
- action 链和 reflection 链的 prompt 组织方式已经开始分叉

本轮目标不是立刻改行为，而是先把 action prompt 的结构像 reflection 一样整理出来，为后续逐段迁移做准备。

## 本轮扫描结论

### 1. 当前主入口

Action prompt 的真正主入口是：

- `agent/action/task/LLMInteractionTask.java`

它当前负责：

- system prompt 总拼装
- user message 组装
- recent interaction turns 渲染
- tagged context 渲染
- recalled memories 渲染
- output contract 文本拼装

### 2. 当前 authored prompt 来源

当前已 authored 的文本主要集中在：

- `agent/dictionary/PromptRegistry.java`
- `resources/assets/talking_weapon/default_prompts.json`

已存在的 authored 键包括：

- `role_background`
- `role_personality`
- `system_cognition_rule`
- `focus_tag_description`
- `emotion_type_description`
- `trigger.<source>.user_message`
- `trigger.<source>.event_instruction`
- 若干杂项话术（如 `trigger_speech` / `cancel_speech` / `busy_speech`）

### 3. 当前 trigger 层职责

当前 trigger 基本只做：

- collector 选择
- task 创建
- 从 `PromptRegistry` 读取并格式化：
  - `user_message`
  - `event_instruction`

代表文件：

- `agent/action/trigger/RightClickTrigger.java`
- `agent/action/trigger/BiomeChangeTrigger.java`

### 4. 当前 schema 层职责

`LLMOutputSchema` 当前只控制：

- 输出字段子集
- 少量 guide 段落开关

它不是 prompt 资源层。

## 目标结构

将 action prompt 调整为与 reflection 类似的分层：

### A. task/shared

存放 action 任务共用段落：

- shared system header
- identity section
- state snapshot section
- recent interaction turns section header / rules
- tagged context section header / rules
- recalled memories section header / rules
- output rules

### B. task/interaction

存放 `LLMInteractionTask` 专属段落：

- interaction task system header
- interaction task user prompt
- interaction task constraints

### C. trigger

继续承载 trigger 专属模板：

- observation / user message
- event instruction

这一层短期内允许继续通过 `PromptRegistry` 提供，不强制本轮迁出。

### D. 代码侧保留职责

Java 代码只保留：

- 动态数据读取
- 条件开关判断
- 各 section 的数据渲染
- output contract 的结构生成

不再在 Java 中直接保存大段稳定文本。

## 重构原则

### 1. 先拆结构，不先改行为

第一轮优先把 prompt 结构拆清楚，不主动改变：

- 输出 schema 语义
- trigger 行为
- interaction record 语义
- recall / context 渲染逻辑

### 2. 先拆 task 公共段落，后拆 trigger authored 源

短期内不建议立刻把 `trigger.<source>.*` 从 `PromptRegistry` 全部迁走。

原因：

- 现在 trigger 文本已经 authored，可继续使用
- 真正混乱的是 `LLMInteractionTask` 内的大段固定文本
- 先把 task 主体结构拆出去，风险最小

### 3. output contract 不做纯文本硬编码迁移

不建议把整段 JSON contract 直接写成静态 txt。

建议：

- 结构继续由代码生成
- 规则说明文本再逐步资源化

这样既能保留严格结构控制，又能减少硬编码说明文字。

## 建议执行顺序

### Phase 1: 建立 action prompt 资源骨架

新增资源目录：

- `resources/assets/talking_weapon/action_prompts/task/shared/`
- `resources/assets/talking_weapon/action_prompts/task/interaction/`

新增资源加载入口，类似 reflection 的：

- `ActionPromptResources`

本阶段不改行为，只建立目录与 loader。

### Phase 2: 抽离 LLMInteractionTask 的固定公共段落

先从 `LLMInteractionTask` 中抽离最稳定的内容：

- system header
- identity section
- state snapshot section
- output rules

这一步完成后，`LLMInteractionTask` 仍然负责动态内容渲染，但不再内嵌这些大段说明文字。

### Phase 3: 抽离 interaction task 专属 prompt

继续抽离：

- interaction task header
- task constraints
- user prompt 模板

这一步之后，`LLMInteractionTask` 的 prompt 组装会更接近：

- 固定资源段
- 动态渲染段
- schema 输出段

### Phase 4: 整理动态 section renderer

把以下动态 section 的“结构模板”从 task 主流程中收口：

- recent interaction turns
- recalled memories
- tagged context
- supplementary info

这一步不一定全部资源化，但至少要让 `LLMInteractionTask` 不再是一整坨字符串拼接。

### Phase 5: 评估 trigger authored 层迁移

在 task 结构稳定后，再决定是否把 trigger 模板从：

- `PromptRegistry`
- `default_prompts.json`

迁到：

- `action_prompts/trigger/...`

这一阶段单独评估，不与前几阶段混做。

### Phase 6: 增加 action prompt preview

像 reflection 一样，为 action task 增加 mock preview：

- 输出最终 system prompt
- 输出 user prompt
- 支持不同 persona / state / trigger 场景对比

这样后续每次调 prompt 都可以先看最终结果，再决定是否继续调整。

## 第一轮建议落地点

如果下一步开始动代码，建议只做下面这些：

1. 新建 `ActionPromptResources`
2. 新建 `action_prompts/task/shared`
3. 新建 `action_prompts/task/interaction`
4. 把 `LLMInteractionTask` 中最顶部的固定段落先抽出去

明确不在这一轮做：

- trigger 模板源迁移
- output schema 语义改造
- interaction record 结构改造
- recall / context 渲染逻辑重写

## 风险点

### 1. PromptRegistry 仍然是旧结构

短期内 action 链会进入“半资源化”状态：

- task 公共段落走 resources
- trigger 模板继续走 `PromptRegistry`

这是刻意接受的中间态，不是设计遗漏。

### 2. LLMInteractionTask 过重问题不会在第一轮彻底消失

第一轮只是把静态文本拆走。

真正要把 `LLMInteractionTask` 继续减重，还需要后续把：

- section renderer
- output contract builder
- prompt assembly pipeline

进一步拆开。

### 3. Action 与 reflection 不应机械统一

目标是“分层方式一致”，不是“完全共用同一套模板”。

两条链的语义不同：

- reflection 面向总结、更新、沉淀
- action 面向当下反应、对话、短期行为

因此可以统一目录结构和装配方式，但不应强行共用同一批文案。

## 当前结论

当前最合适的推进方式是：

- 先把 action task 的 prompt 结构拆出来
- 暂时保留 trigger 模板仍由 `PromptRegistry` 提供
- 等 task 主体稳定后，再决定是否继续迁 trigger authored 层

这能最大程度降低一次性重构风险，同时让 action prompt 架构逐步向 reflection 靠拢。
