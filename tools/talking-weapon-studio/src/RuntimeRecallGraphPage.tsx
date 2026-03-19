import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps
} from "@xyflow/react";
import { useMemo, useState } from "react";
import "@xyflow/react/dist/style.css";
import type { FocusTag, RuntimeRecalledMemoryEntry, RuntimeRecallTraceFile } from "./types";

const ROOT_NODE_ID = "recall-root";
const ROOT_NODE_X = 48;
const ROOT_NODE_HEIGHT = 56;
const MEMORY_NODE_X = 320;
const MEMORY_NODE_HEIGHT = 208;
const MEMORY_ROW_GAP = 240;

const FOCUS_TAG_LABELS: Record<FocusTag, string> = {
  COMBAT: "战斗",
  MOVEMENT: "移动",
  LABOR: "劳作",
  CREATION: "创造",
  CREATURE: "生物",
  SOCIAL: "社交",
  LANDSCAPE: "风景",
  HAZARD: "危险",
  STATUS: "状态",
  CONSUMABLE: "消耗",
  BLOCK: "方块",
  TOOL: "工具",
  COMMON: "通用"
};

type RecallEdgeKind = "detail" | "time" | "focus" | "none";

type RecallRootNode = Node<{ label: string }, "recallRoot">;
type RecallMemoryNode = Node<
  {
    description: string;
    edgeKind: RecallEdgeKind;
    edgeLabel: string;
    focusTags: FocusTag[];
    semanticTimeLabel: string;
    title: string;
  },
  "recallMemory"
>;
type RecallFlowNode = RecallRootNode | RecallMemoryNode;
type RecallFlowEdge = Edge<{ kind: RecallEdgeKind }, "smoothstep"> & {
  pathOptions?: {
    borderRadius?: number;
    offset?: number;
  };
};

function abbreviateText(text: string | undefined, maxLength: number, fallback = "(无)"): string {
  if (!text || !text.trim()) {
    return fallback;
  }
  const compact = text.replace(/\s+/g, " ").trim();
  if (compact.length <= maxLength) {
    return compact;
  }
  return `${compact.slice(0, Math.max(0, maxLength - 1))}…`;
}

function formatRuntimeTimestamp(timestamp: number): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return "未知时间";
  }
  return new Date(timestamp).toLocaleString("zh-CN", {
    day: "2-digit",
    hour: "2-digit",
    hour12: false,
    minute: "2-digit",
    month: "2-digit",
    second: "2-digit",
    year: "numeric"
  });
}

function formatRecallEdge(memory: RuntimeRecalledMemoryEntry, triggerTagSet: Set<string>): {
  kind: RecallEdgeKind;
  label: string;
} {
  const detailMatch = memory.detailTags.find((tag) => triggerTagSet.has(tag));
  if (detailMatch) {
    return {
      kind: "detail",
      label: abbreviateText(detailMatch, 18, "tag")
    };
  }

  if (memory.semanticTimeTag && triggerTagSet.has(memory.semanticTimeTag)) {
    return {
      kind: "time",
      label: abbreviateText(memory.semanticTimeTag, 18, "time")
    };
  }

  if (memory.focusTags.length) {
    return {
      kind: "focus",
      label: abbreviateText(memory.focusTags[0], 18, "focus")
    };
  }

  return {
    kind: "none",
    label: "召回"
  };
}

function edgeColor(kind: RecallEdgeKind): string {
  switch (kind) {
    case "detail":
      return "#bd5a2d";
    case "time":
      return "#2c7da0";
    case "focus":
      return "#8b7e72";
    default:
      return "#b7ada3";
  }
}

function formatMemoryTitle(index: number): string {
  return `记忆 ${index + 1}`;
}

function RecallRootNodeView({ data }: NodeProps<RecallRootNode>) {
  return (
    <div className="recall-flow-root-node">
      <Handle className="recall-flow-handle recall-flow-root-handle" isConnectable={false} position={Position.Right} type="source" />
      <div className="recall-flow-root-dot" />
      <span className="recall-flow-root-label">{data.label}</span>
    </div>
  );
}

function RecallMemoryNodeView({ data }: NodeProps<RecallMemoryNode>) {
  return (
    <div className="recall-flow-memory-node">
      <Handle className="recall-flow-handle recall-flow-memory-handle" isConnectable={false} position={Position.Left} type="target" />
      <div className="custom-tag-meta">
        <span className={`badge ${data.edgeKind === "focus" || data.edgeKind === "none" ? "muted" : "warn"}`}>{data.edgeLabel}</span>
        {data.semanticTimeLabel ? <span className="badge muted">{data.semanticTimeLabel}</span> : null}
      </div>
      <div className="recall-flow-memory-body nowheel nopan">
        <strong>{data.title}</strong>
        <p>{data.description}</p>
        <div className="chip-row compact">
          {data.focusTags.map((focusTag) => (
            <span className="chip" key={`${data.title}-${focusTag}`}>
              {FOCUS_TAG_LABELS[focusTag] ?? focusTag}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

const recallFlowNodeTypes = {
  recallMemory: RecallMemoryNodeView,
  recallRoot: RecallRootNodeView
};

function buildRecallFlow(recallTrace: RuntimeRecallTraceFile): {
  edges: RecallFlowEdge[];
  nodes: RecallFlowNode[];
} {
  const triggerTagSet = new Set(recallTrace.triggerTags);
  const memoryCount = recallTrace.recalledMemories.length;
  const totalHeight = memoryCount > 0 ? (memoryCount - 1) * MEMORY_ROW_GAP + MEMORY_NODE_HEIGHT : MEMORY_NODE_HEIGHT;
  const rootY = Math.max(0, totalHeight / 2 - ROOT_NODE_HEIGHT / 2);

  const nodes: RecallFlowNode[] = [
    {
      data: { label: "联想起点" },
      draggable: false,
      id: ROOT_NODE_ID,
      position: { x: ROOT_NODE_X, y: rootY },
      selectable: false,
      type: "recallRoot"
    }
  ];

  const edges: RecallFlowEdge[] = [];

  recallTrace.recalledMemories.forEach((memory, index) => {
    const edgeInfo = formatRecallEdge(memory, triggerTagSet);
    const nodeId = `recall-memory-${index}`;
    const stroke = edgeColor(edgeInfo.kind);

    nodes.push({
      data: {
        description: memory.description || "该记忆没有 description。",
        edgeKind: edgeInfo.kind,
        edgeLabel: edgeInfo.label,
        focusTags: (memory.focusTags as FocusTag[]) ?? [],
        semanticTimeLabel: memory.semanticTimeLabel || memory.semanticTimeTag || "",
        title: formatMemoryTitle(index)
      },
      draggable: false,
      id: nodeId,
      position: {
        x: MEMORY_NODE_X,
        y: index * MEMORY_ROW_GAP
      },
      selectable: false,
      type: "recallMemory"
    });

    edges.push({
      animated: false,
      data: { kind: edgeInfo.kind },
      focusable: false,
      id: `recall-edge-${index}`,
      label: edgeInfo.label,
      labelBgBorderRadius: 999,
      labelBgPadding: [6, 10],
      labelBgStyle: {
        fill: "#fffaf4",
        fillOpacity: 0.96,
        stroke,
        strokeOpacity: 0.18
      },
      labelShowBg: true,
      labelStyle: {
        fill: stroke,
        fontSize: 11,
        fontWeight: 700
      },
      markerEnd: {
        color: stroke,
        type: MarkerType.ArrowClosed
      },
      pathOptions: {
        borderRadius: 16,
        offset: 22
      },
      selectable: false,
      source: ROOT_NODE_ID,
      style: {
        stroke,
        strokeDasharray: edgeInfo.kind === "focus" ? "6 6" : undefined,
        strokeWidth: edgeInfo.kind === "focus" ? 1.8 : 2.4
      },
      target: nodeId,
      type: "smoothstep"
    });
  });

  return { edges, nodes };
}

export function RuntimeRecallGraphPage({ recallTrace }: { recallTrace: RuntimeRecallTraceFile }) {
  const [visibleContextsExpanded, setVisibleContextsExpanded] = useState(false);
  const flow = useMemo(() => buildRecallFlow(recallTrace), [recallTrace]);

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Recall Trace</p>
          <h2>最近一次记忆联想</h2>
          <p className="muted-copy">
            图结构已改为 React Flow 渲染。根节点、连线、缩放与拖拽由图库处理，边标签优先显示单个触发 detail tag，未命中时回退到 semantic time tag 或 focus tag。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${recallTrace.recalledMemories.length} 条记忆`}</span>
          <span className="badge muted">{recallTrace.associationMode || "未记录 association mode"}</span>
          <span className="badge muted">
            {recallTrace.generatedAt
              ? `最近同步 ${formatRuntimeTimestamp(Date.parse(recallTrace.generatedAt))}`
              : "尚未读到 recall trace"}
          </span>
        </div>
      </div>

      <div className="two-col runtime-recall-summary">
        <section className="info-block">
          <h3>本轮输入</h3>
          <p className="muted-copy">{`触发源：${recallTrace.triggerSource || "(未知)"}`}</p>
          <p className="muted-copy">{`用户提示：${recallTrace.userPrompt || "(无提示词)"}`}</p>
          <div className="chip-row compact">
            {recallTrace.currentDesireTag ? <span className="chip">{`渴望 ${recallTrace.currentDesireTag}`}</span> : null}
            {recallTrace.triggerTags.map((tag) => (
              <span className="chip" key={tag}>
                {tag}
              </span>
            ))}
            {!recallTrace.triggerTags.length ? <span className="chip">无触发 tags</span> : null}
          </div>
        </section>

        <section className="info-block">
          <div className="section-head">
            <div>
              <h3>可见上下文</h3>
              <p className="muted-copy">{`共 ${recallTrace.visibleContexts.length} 条，可展开后滚动查看。`}</p>
            </div>
            <button className="action-button quiet" onClick={() => setVisibleContextsExpanded((value) => !value)} type="button">
              {visibleContextsExpanded ? "收起" : "展开"}
            </button>
          </div>
          {visibleContextsExpanded ? (
            <div className="runtime-visible-contexts-scroll">
              <div className="runtime-visible-contexts">
                {recallTrace.visibleContexts.length ? (
                  recallTrace.visibleContexts.map((entry, index) => (
                    <article className="runtime-visible-context-card" key={`${entry.description}-${index}`}>
                      <strong>{abbreviateText(entry.description, 40, "(无描述)")}</strong>
                      <p>{`score ${entry.finalScore.toFixed(2)} / base ${entry.baseImportance.toFixed(2)}`}</p>
                      <div className="chip-row compact">
                        {entry.focusTags.map((focusTag) => (
                          <span className="chip" key={`${entry.description}-${focusTag}`}>
                            {focusTag}
                          </span>
                        ))}
                      </div>
                    </article>
                  ))
                ) : (
                  <p className="muted-copy">当前 trace 里没有可见上下文摘要。</p>
                )}
              </div>
            </div>
          ) : (
            <p className="runtime-mind-map-status">已折叠可见上下文。</p>
          )}
        </section>
      </div>

      {recallTrace.recalledMemories.length ? (
        <section className="info-block runtime-graph-panel">
          <div className="section-head">
            <div>
              <h3>思维导图</h3>
              <p className="muted-copy">固定卡片尺寸，内部滚动；边直接连接到节点 handle，不再依赖手写 SVG 定位。</p>
            </div>
            <span className="badge muted">React Flow</span>
          </div>

          <div className="runtime-flow-shell">
            <ReactFlow
              defaultEdgeOptions={{
                type: "smoothstep"
              }}
              edges={flow.edges}
              elementsSelectable={false}
              fitView
              fitViewOptions={{ padding: 0.18 }}
              maxZoom={1.35}
              minZoom={0.25}
              nodes={flow.nodes}
              nodesConnectable={false}
              nodesDraggable={false}
              nodeTypes={recallFlowNodeTypes}
              onlyRenderVisibleElements
              panOnDrag
              proOptions={{ hideAttribution: true }}
            >
              <Background color="rgba(105, 79, 48, 0.12)" gap={20} size={1} variant={BackgroundVariant.Dots} />
              <MiniMap
                className="runtime-flow-minimap"
                maskColor="rgba(255, 255, 255, 0.68)"
                nodeBorderRadius={12}
                nodeColor={(node) => (node.type === "recallRoot" ? "#bd5a2d" : "#ead8c5")}
                nodeStrokeColor={(node) => (node.type === "recallRoot" ? "#8c4722" : "#c89c79")}
                pannable
                zoomable
              />
              <Controls className="runtime-flow-controls" showInteractive={false} />
            </ReactFlow>
          </div>
        </section>
      ) : (
        <div className="empty-state">
          <strong>当前没有可绘制的召回记忆</strong>
          <p>需要先让游戏端完成一次 LTM recall，并写出 `studio_runtime_recall_trace.json`。</p>
        </div>
      )}

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>Trace 详情</h3>
            <p className="muted-copy">这里直接展示后端写入的 trace lines，方便对照实际召回链路。</p>
          </div>
          <span className="badge">{`${recallTrace.traceLines.length} 行`}</span>
        </div>
        {recallTrace.traceLines.length ? (
          <pre className="state-preview runtime-trace-preview">{recallTrace.traceLines.join("\n")}</pre>
        ) : (
          <p className="muted-copy">当前没有 trace lines。</p>
        )}
      </section>
    </div>
  );
}
