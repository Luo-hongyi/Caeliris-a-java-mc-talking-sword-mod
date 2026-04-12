# Dialogue LTM Studio Service Plan

## 背景

当前长期记忆 recall 主链服务的是：

- 行动时的自然浮现
- 基于当前 `visibleContexts` 和 `tag_pool` 的 cue 驱动召回

它并不是为“玩家自然语言提问 -> 定向检索 LTM”设计的。

因此，当玩家在游戏内直接和剑灵对话时，会出现一个明显 gap：

- 玩家输入是自然语言
- 当前 LTM recall 入口是 tag / cue
- 两者之间缺少稳定的桥接层

## 当前约束

本方案基于两个现实约束成立：

1. 游戏端当前持久化方案不适合直接存储 encoding / vector
2. mod 侧不适合继续引入语义检索相关重依赖，否则会带来较大的依赖和打包问题

因此，本轮不再尝试把 semantic retrieval 直接塞进 mod，而是将其转移到 Studio 侧。

## 结论

采用 **Studio 侧对话记忆服务** 方案：

- 游戏端继续作为 LTM 真源持有者
- Studio 侧负责：
  - 读取 LTM journal
  - 建立语义索引
  - 处理对话中的 LTM 检索请求
- 游戏端只负责：
  - 发起查询
  - 接收召回结果
  - 将结果继续接入对话 prompt 或直接用于回答生成

## 目标

本方案要解决的是：

- 玩家自然语言问题无法直接命中 LTM 的问题
- 在不污染当前 action recall 主链的前提下，为对话功能增加一条独立的 LTM 检索入口

## 非目标

本方案暂时 **不** 做下面这些事：

- 不替换当前 `LTMRecallEngine`
- 不把 Studio 变成记忆真源
- 不在第一阶段处理 STM / recent turns / interaction turns 的语义检索
- 不要求游戏端持久化 embedding
- 不要求第一阶段就让 Studio 全包对话生成

## 职责边界

### 游戏端职责

- 继续生成并持有 LTM
- 继续维护当前行动链中的 recall
- 将 synthesized LTM 以 append-only journal 形式落到 workspace
- 在玩家发起对话时向 Studio 请求 LTM 检索
- 在 Studio 不可用时执行降级策略

### Studio 侧职责

- 读取并重放 LTM journal
- 为 LTM 建立语义索引或其他检索 projection
- 对外提供本地 loopback 检索服务
- 返回与当前玩家问题最相关的 LTM 候选和 trace

## 真源与投影视图

### 真源

对 Studio 检索服务来说，LTM 的真源应是：

- `studio_runtime_synthesized_memories.jsonl`

它是 append-only journal，语义上应被视为：

- 游戏端输出的 memory event stream

### 只读调试视图

下面这份文件仍然只作为 Studio / GUI 调试快照使用：

- `studio_runtime_synthesized_memories.json`

它不应作为语义索引的真源。

### Studio 侧 projection

Studio 可以额外维护自己的 sidecar 数据，例如：

- embedding cache
- ANN / inverted index
- memory text normalization cache
- query trace cache

但这些都只是 projection / cache，不是主真源。

## 整体架构

### 1. 写入阶段

游戏端在 LTM 合成完成后，继续向：

- `studio_runtime_synthesized_memories.jsonl`

追加：

- `put`
- `remove`

事件。

### 2. 建索引阶段

Studio 侧服务监听或定期扫描该 journal，并将新事件增量同步到自己的语义索引。

### 3. 查询阶段

当玩家触发对话时：

1. 游戏端收集玩家文本问题
2. 游戏端向本地 Studio 服务发起检索请求
3. Studio 返回 top-k LTM 候选与检索 trace
4. 游戏端将这些 LTM 填入对话 prompt

## 为什么不直接复用当前 recall 主链

当前 recall 主链的设计目标是：

- 行动中的自然联想
- 基于世界上下文的动态浮现

而对话问答的设计目标是：

- 基于玩家显式问题的定向检索

两者在入口语义上不同：

- 现有 recall 入口：`visibleContexts -> tag_pool -> cue`
- 对话 recall 入口：`player query -> semantic retrieval over LTM`

因此本方案明确采用两条平行链，而不是强行复用同一入口。

## 第一阶段建议服务形态

第一阶段先只做：

- **Dialogue LTM Retrieval Service**

而不是完整的对话总控服务。

也就是说，Studio 暂时只负责：

- 查询理解
- LTM 检索
- rerank
- trace 输出

最终是否由游戏端继续调用 LLM 生成回答，可以先保留在游戏端。

这样做的好处是：

- 边界更小
- 排错更容易
- 不会一次性把 persona / state / action prompt / output schema 全部跨进程搬走

## 建议 API

建议新增一个 Studio 本地接口：

- `POST /api/studio/dialogue-memory/query`

请求体第一阶段可保持极简：

```json
{
  "query": "你还记得上次在村庄发生的事吗",
  "playerId": "player-uuid",
  "topK": 5
}
```

响应体建议包含：

```json
{
  "ok": true,
  "query": "你还记得上次在村庄发生的事吗",
  "matchedMemories": [
    {
      "id": "memory-id",
      "description": "……",
      "score": 0.91,
      "focusTags": ["SOCIAL", "SURVIVAL"],
      "detailTags": ["custom_specific:village", "custom_generic:zombie"]
    }
  ],
  "trace": {
    "retrievalMode": "semantic_ltm",
    "candidateCount": 24
  }
}
```

## 查询流程建议

Studio 侧查询流程建议分三步：

### 1. query normalization

将玩家自然语言问题整理成较稳定的查询文本。

### 2. semantic retrieval

在 Studio 本地索引中取回 top-k LTM 候选。

### 3. rerank

结合以下因素做二次排序：

- semantic 相似度
- LTM importance
- 时间锚点匹配度
- detail tag 命中情况

## 降级策略

Studio 服务不应成为对话功能的单点硬依赖。

当 Studio 不在线、索引不可用、接口报错时，游戏端建议按下面顺序降级：

1. 不使用语义检索，只走当前已有对话链
2. 若有条件，可退回简单的 tag-based LTM recall
3. 在日志中明确记录本次 Studio dialogue-memory 调用失败

## 第一阶段实现顺序

### Phase 1

先把职责边界和协议定下来：

- 新增 Studio dialogue-memory API
- 明确 journal 是真源
- 明确 snapshot 只是调试视图

### Phase 2

在 Studio 侧做最小可用索引：

- 先不追求完整向量数据库
- 先支持对 `description` 的语义检索即可

### Phase 3

游戏端接入本地查询调用：

- 对话触发时发请求
- 将 top-k LTM 回填到 prompt

### Phase 4

补 trace、错误处理和降级逻辑。

## 后续可扩展方向

在第一阶段跑通之后，再评估是否继续扩展：

- 将 implanted memories 一并纳入 Studio 检索索引
- 为 LTM 增加专门的 retrieval hooks
- 让 Studio 同时接管对话生成，而不只做 LTM retrieval
- 将更复杂的 query planning 接入 Studio 服务

## 一句话总结

本方案的核心不是“在 mod 里硬上 semantic RAG”，而是：

- 让游戏端继续做记忆真源
- 让 Studio 侧承接 LTM 的语义检索服务
- 用本地服务桥接玩家自然语言问题和 LTM 检索
