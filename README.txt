Project Overview
================
这是一个基于 Minecraft Forge 1.20.1 的 Mod 开发工作区（MDK），包含若干示例与自定义模块。

本仓库同时包含一个名为 "Talking Weapon" 的扩展模块（位于 `src/main/java/dreamdust/talking_weapon/`），
该模块演示了如何在游戏触发（如右键、群系变化）时调用 LLM 生成文本回复并在游戏内展示。

快速上手
--------
- 构建项目：

```bash
./gradlew build
```

- 本地启动客户端：

```bash
./gradlew runClient
```

如果遇到依赖问题：

```bash
./gradlew --refresh-dependencies
./gradlew clean
```

Talking Weapon 模组 - 功能概览
-------------------------------
- LLM 集成：支持通过配置调用外部 LLM（模型、API key 在配置文件中设置），用于生成武器的语音/对话文本。
- 思考模式：LLM 可输出内部“思考”段（以 <think>...</think> 标记）。模组会将思考内容写入日志，但不会把这些“思考”保存到 agent 的记忆中，保护隐私与上下文清洁。
   - 思考按触发器可分别启用（例如右键触发、群系变化触发），并支持全局长度上限以防止过长输出。
- 环境采集：模组采集玩家附近的实体、结构与 POI（兴趣点）信息作为 LLM 上下文。
   - POI 探查已实现并作了性能优化：当前实现以优化的方块扫描为主，并在单机/集成服务端可用时回退使用服务端索引以提高准确性与性能。

配置与日志
-----------
- 主要配置文件（客户端）: `talking_weapon-client.toml`（在运行游戏后由 Forge 的配置目录生成/管理）。
- 关键配置项示例：模型名称、API Key、是否启用思考（按触发器分别控制）、思考最大长度、是否启用附近 POI 探查等。
- 日志文件：模组会在运行目录下生成 `talking_weapon_logs/`，其中包含会话日志和 LLM 的“思考”段（对玩家不可见）。

开发者说明
-------------
- 代码入口与核心实现：
   - LLM 调用与处理：`src/main/java/dreamdust/talking_weapon/llm/LLMInteractionTask.java`
   - 配置定义：`src/main/java/dreamdust/talking_weapon/config/TalkingWeaponConfig.java`
   - 日志记录：`src/main/java/dreamdust/talking_weapon/logging/AgentLogger.java`
   - 采集器（结构/POI/环境）：`src/main/java/dreamdust/talking_weapon/collectors/` 下的相关类

- 常见开发操作：修改代码后运行 `./gradlew build` 或 `./gradlew runClient` 验证。

更多资源
----------
- Forge 官方文档： https://docs.minecraftforge.net/en/1.20.1/gettingstarted/
- 若需讨论或报告问题，请在仓库中创建 issue 或联系仓库维护者。

原始 MDK 说明
----------------
（以下为原始 MDK 安装说明，保留以便参考）

Mapping Names:
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license, if you do not agree with it you can change your mapping names to other crowdsourced names in your
build.gradle. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/MinecraftForge/MCPConfig/blob/master/Mojang.md

Additional Resources:
Community Documentation: https://docs.minecraftforge.net/en/1.20.1/gettingstarted/
LexManos' Install Video: https://youtu.be/8VEdtQLuLO0
Forge Forums: https://forums.minecraftforge.net/
Forge Discord: https://discord.minecraftforge.net/
