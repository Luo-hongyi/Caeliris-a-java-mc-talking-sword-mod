# Talking Weapon Studio

`Talking Weapon Studio` 是面向非程序用户的独立内容编辑器。

它负责编辑：

- `custom tag`
- `tag description`
- `tag relation`
- `persona`
- `implanted memory`

它不直接依赖 Minecraft GUI，而是围绕当前整合包实例的运行时语义快照工作。

## 设计目标

- 让用户可以浏览当前实例中实际加载出来的 `native tag`
- 让用户可以在 `persona`、`implanted memory`、`custom tag` 中引用其他 mod 的 tag
- 让 `tag description` 和 `tag relation` 不再只面向默认资源，而是面向整个实例中的“已知 tag 全集”
- 让 Studio、mod 运行时、后续数据导出器使用同一套 schema

## 核心数据源

Studio 的核心数据源分为六层：

1. `native_catalog.snapshot.json`
   由 mod 在真实运行环境中导出，描述当前实例里发现的原生 tag、注册对象及其来源。
   当前已覆盖常规 registry，以及 `structure`、`dimension` 这类更特殊的运行时注册表视图。
   对于可识别的 `native_specific` registry 条目，还会附带本地化 `displayName`，以及供 Studio 渲染用的轻量图标提示（如 `iconKind` / `iconId` / `iconRelativePath`）。

2. `custom_tags.json`
   用户维护的 custom tag 注册表。
   运行时是否承认一个 `custom_*` tag，取决于它是否出现在这里，而不是是否写入 `tag_descriptions.json`。

3. `tag_descriptions.json`
   用户维护的 tag 描述文本，可作用于 `native_*` 与 `custom_*`。
   它只负责自然语言说明，不负责 custom tag 注册。

4. `tag_relations.json`
   用户维护的语义关系图，一对多、无环。

5. `studio_runtime_tag_view.json`
   由 mod 运行时导出，用于把 `native catalog`、`tag_descriptions.json`、`tag_relations.json` 合成为 Studio 可直接消费的统一视图，并附带基础校验结果。

6. `native_catalog_icons/`
   由 mod 运行时导出，保存 native tag 对应的 PNG 图标文件。图标相对路径直接记录在 `native_catalog.snapshot.json` 的 metadata 中。

7. `memory_recall_tuning.json`
   Recall 公式参数的 workspace 真源。Studio 会用它来渲染函数曲线、调整 recall 公式参数；游戏运行时会直接读取它，并同步到真实的 Forge config / toml。

## 推荐配置目录

```text
.minecraft/config/talking_weapon/
├─ custom_tags.json
├─ tag_descriptions.json
├─ tag_relations.json
├─ personas.json
├─ implanted_memories.json
└─ studio_workspace/
   ├─ native_catalog.snapshot.json
   ├─ memory_recall_tuning.json
   ├─ custom_tags.json
   ├─ tag_descriptions.json
   ├─ tag_relations.json
   ├─ studio_runtime_tag_view.json
   ├─ native_catalog_icons/
   ├─ personas.json
   ├─ implanted_memories.json
   └─ studio_workspace.manifest.json
```

## 设计原则

- `native catalog` 是只读快照，不应由 Studio 直接编辑。
- `custom tag`、`description`、`relation` 是用户可编辑层。
- `custom_tags.json` 是 custom tag 的唯一注册源；`tag_descriptions.json` 只保存描述。
- `description` 不等于 `relation`。前者负责自然语言解释，后者负责匹配 / fallback。
- `relation` 图必须保持无环。
- Studio 的所有选择器都基于“合并后的 tag 视图”工作，而不是只看某一个文件。
- `studio_runtime_tag_view.json` 是只读诊断/聚合产物，用于校验当前实例内的 tag 语义层是否完整。
- `studio_workspace/` 是 authored data 工作区；这批 authored 文件会被游戏运行时直接读取或在启动 / 发布时同步到运行时。
- `studio_workspace.manifest.json` 是当前的同步桥：Studio 前端轮询它感知游戏导出变化，游戏客户端也轮询它，并在 `updatedBy=studio` 时自动发布编辑结果回运行时。
- `memory_recall_tuning.json` 是 recall 公式参数的 workspace 真源；`TalkingWeaponConfig` 是运行时承接层，不再是这组参数的人工编辑真源。

## Studio 页面与这四个 schema 的关系

### 1. Tag Catalog

- 读取 `custom_tags.json`
- 读取 `tag_descriptions.json`
- 提供 `custom tag` 的列表页与单独编辑页
- 当前原型只暴露 `type`、`id`、`description` 三个核心字段

### 2. Persona

- 引用合并后的 tag 视图
- 以 `focus tag` 对人格偏好条目分组
- 列表页支持按 `focus tag` 分类筛选
- 每条 persona 由 `associated_focus`、`description`、`detail_tags` 构成
- 可选 `always_recalled` 开关，用于标记“总是记住”的核心人格偏好；应谨慎使用，避免 prompt 过度膨胀
- 采用“列表页 -> 单独编辑页”结构，在编辑页内接入 tag 检索与增删

### 3. Memory

- 采用“列表页 -> 单独编辑页”结构编辑 implanted memories
- 引用合并后的 tag 视图
- 在编辑页中维护记忆文本、focus tags、detail tags，以及独立的 `semantic_time_tag`
- `semantic_time_tag` 应指向一个与时间有关的 custom tag；它不会并入 `detail_tags`，但会作为独立时间语义参与记忆联想，并在 Prompt 中单独展示
- Studio 会从 `semantic_time_tag` 生成兼容当前运行时的 `time_label`
- 植入记忆不再直接编辑维度、群系、天气等运行时环境锚点
- 允许植入记忆带上其他 mod 的 native tags 或映射到 clean custom tags

### 4. Recall Workbench

- 读取 `studio_runtime_recall_trace.json`
- 读取 `agent_state.json`
- 读取 `memory_recall_tuning.json`
- 以“运行时参数 + 公式参数 + 函数图像 + trace flow”四块拼成 recall 专页
- 当前会把 `conscious memories` 和 `prompt memories` 分开显示，并为半衰期、长间隔补偿、recent suppression、prompt fill、temperature sampling 等公式绘图

## 当前落地范围

当前仓库已定义 9 个核心 schema：

- `shared-schema/native_catalog.snapshot.schema.json`
- `shared-schema/agent_state.schema.json`
- `shared-schema/custom_tags.schema.json`
- `shared-schema/tag_descriptions.schema.json`
- `shared-schema/tag_relations.schema.json`
- `shared-schema/personas.schema.json`
- `shared-schema/implanted_memories.schema.json`
- `shared-schema/location_registry.schema.json`
- `shared-schema/memory_recall_tuning.schema.json`

当前 mod 侧还额外提供三个运行时导出器：

- `native_catalog.snapshot.json`：实例级 native tag 快照
- `studio_runtime_tag_view.json`：description / relation 合并视图 + 校验结果
- `native_catalog.icons.json` + `native_catalog_icons/`：`native_specific` 图标包；真实图标能导出的尽量导出，剩余条目补占位 PNG。`native_generic` 不导出图标文件

当前游戏内配置界面还提供一个辅助动作：

- 用当前实例的 native `displayName` 自动补全缺失的 `tag_descriptions.json` 原生条目

## 前端原型

`tools/talking-weapon-studio/` 现在已经包含一个独立的前端原型，技术栈为：

- `Vite`
- `React`
- `TypeScript`

当前实现目标是先把三页工作台外壳搭起来：

- `Custom Tag Catalog`
- `Persona`
- `Memory`
- `Recall Workbench`

当前版本仍然使用 `examples/` 里的示例 JSON 作为 seed data，用于验证：

- schema 是否足够支撑 Studio 页面
- tag 检索与三页切换结构是否合理
- `custom tag` / `persona` 的列表页与单独编辑页结构是否顺手
- 前端内置导入 / 导出链是否足够支撑真实配置文件流转

当前前端已支持：

- 直接导入模组导出的 `studio_workspace/` 文件夹
- 导入 `native_catalog.snapshot.json`
- 导入 `custom_tags.json`
- 导入 `tag_descriptions.json`
- 导入 `tag_relations.json`
- 导入 `personas.json`
- 导入 `implanted_memories.json`
- 导入 `memory_recall_tuning.json`
- 导入 legacy 顶层数组格式的 implanted memories
- 导出上述单独文件
- 导出包含全部当前状态的 workspace bundle，便于做内容编辑中间稿备份
- 目录句柄模式下，Studio 会在写回 schema 后同步更新 `studio_workspace.manifest.json`，让游戏侧自动热发布

当前模组侧还额外提供一个整包导出目录：

- `config/talking_weapon/studio_workspace/`
- 其中会放入 snapshot / runtime tag view / icons / custom tags / descriptions / relations / implanted memories / memory recall tuning / manifest
- `personas.json` 现在会在发布阶段进入运行时目录，并作为静态 persona 条目来源被游戏读取
- `favorability` 与 `playerProfile` 仍属于运行时状态，不写入 `personas.json`
- 目录句柄模式下，Studio 改动会经由 manifest 被游戏自动发布；手动点击“发布 Studio 到运行时”仍然保留，作为显式兜底入口

后续再把数据源替换成真实的：

- `native_catalog.snapshot.json`
- `studio_runtime_tag_view.json`
- `custom_tags.json`
- `tag_descriptions.json`
- `tag_relations.json`
- `personas.json`
- `implanted_memories.json`

## 本地运行

在 `tools/talking-weapon-studio/` 目录下执行：

```bash
npm install
npm run dev
```

生产构建：

```bash
npm run build
```

桌面端开发壳：

```bash
# 终端 1
npm run desktop:dev:web

# 终端 2
npm run desktop:dev:shell
```

桌面端打包（unpacked，适合当前验证阶段）：

```bash
npm run desktop:pack
```

当前桌面端实现采用 Electron。React 前端通过 preload 暴露的 `workspace bridge` 访问本地工作区目录，因此不再依赖浏览器的 `showDirectoryPicker` 权限模型来验证连接链路。

当前已验证 `npm run build` 可通过。

## 示例文件

- `examples/custom_tags.example.json`
- `examples/tag_descriptions.example.json`
- `examples/tag_relations.example.json`
- `examples/personas.example.json`
- `examples/implanted_memories.example.json`
- `examples/implanted_memories.legacy.example.json`
