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
import { useMemo } from "react";
import "@xyflow/react/dist/style.css";
import {
  applyTemperature,
  buildSampleSeries,
  buildTagReheatHitSamples,
  computeDesiredFocusBonus,
  computeDesireProfileBonus,
  computeEffectiveRecentRecallWindow,
  computeFermentationDecay,
  computeIntervalBonus,
  computePromotionBudget,
  computePromptFillProbability,
  computeRecentRecallPenalty,
  computeRetentionCap,
  computeSandboxCueBudget,
  computeSandboxRecallBudget,
  computeSynthesizedAgeFactor,
  computeTagReheatGainAfterHit,
  computeTagReheatGainAfterTime,
  computeTagPoolDecay,
  computeTemperatureExponent,
  gameTicksToDays
} from "./memoryRecallModel";
import type {
  AgentStateFile,
  AgentStateRecallTuning,
  FocusTag,
  MemoryRecallTuningFile,
  RuntimeRecalledMemoryEntry,
  RuntimeRecallTraceFile
} from "./types";

const ROOT_NODE_ID = "recall-root";
const ROOT_NODE_X = 48;
const ROOT_NODE_HEIGHT = 56;
const MEMORY_NODE_X = 320;
const MEMORY_NODE_HEIGHT = 208;
const MEMORY_ROW_GAP = 240;
const CHART_WIDTH = 320;
const CHART_HEIGHT = 176;

const FOCUS_TAG_LABELS: Record<FocusTag, string> = {
  COMBAT: "战斗",
  EXPLORE: "探索",
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

type RuntimeRecallGraphPageProps = {
  agentStateFile: AgentStateFile;
  memoryRecallTuningFile: MemoryRecallTuningFile;
  onAgentRecallTuningChange: (key: keyof AgentStateRecallTuning, value: number) => void;
  onAgentStateChange: (patch: Partial<AgentStateFile["state"]>) => void;
  onMemoryRecallTuningChange: (patch: Partial<MemoryRecallTuningFile>) => void;
  recallTrace: RuntimeRecallTraceFile;
};

type LayerChip = {
  label: string;
  tone?: "default" | "muted" | "warn";
};

type LayerMonitorItem = {
  id: string;
  title: string;
  scoreLabel?: string;
  detail?: string;
  chips?: LayerChip[];
};

type ParsedSandboxTrace = {
  cueCount: number;
  selectedEntries: Array<{
    description: string;
    matched: string;
    score: number;
    supports: number;
  }>;
  sourceMemoryCount: number;
  budget: number;
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

function formatShortNumber(value: number, digits = 2): string {
  return Number.isFinite(value) ? value.toFixed(digits) : "0.00";
}

function formatMillis(value: number): string {
  return `${formatShortNumber(value)} ms`;
}

function readNumber(raw: string, fallback: number, round = false): number {
  const parsed = Number.parseFloat(raw);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return round ? Math.round(parsed) : parsed;
}

function activePromptMemories(recallTrace: RuntimeRecallTraceFile): RuntimeRecalledMemoryEntry[] {
  if (recallTrace.promptMemories.length) {
    return recallTrace.promptMemories;
  }
  return recallTrace.recalledMemories;
}

function activeConsciousMemories(recallTrace: RuntimeRecallTraceFile): RuntimeRecalledMemoryEntry[] {
  if (recallTrace.consciousMemories.length) {
    return recallTrace.consciousMemories;
  }
  return activePromptMemories(recallTrace);
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
  const memories = activePromptMemories(recallTrace);
  const triggerTagSet = new Set(recallTrace.triggerTags);
  const memoryCount = memories.length;
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

  memories.forEach((memory, index) => {
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

function RangeField({
  formatValue,
  label,
  max,
  min,
  onChange,
  round = false,
  step,
  value
}: {
  formatValue?: (value: number) => string;
  label: string;
  max: number;
  min: number;
  onChange: (next: number) => void;
  round?: boolean;
  step: number;
  value: number;
}) {
  const output = formatValue
    ? formatValue(value)
    : round || step >= 1
      ? `${Math.round(value)}`
      : value.toFixed(2);

  return (
    <label className="field">
      <span>{label}</span>
      <div className="range-field">
        <input
          max={max}
          min={min}
          onChange={(event) => onChange(readNumber(event.target.value, value, round))}
          step={step}
          type="range"
          value={value}
        />
        <strong className="range-field-output">{output}</strong>
      </div>
    </label>
  );
}

function FormulaChart({
  formatX,
  formatY,
  samples,
  xLabel,
  yLabel
}: {
  formatX?: (value: number) => string;
  formatY?: (value: number) => string;
  samples: Array<{ x: number; y: number }>;
  xLabel: string;
  yLabel: string;
}) {
  const padding = { bottom: 26, left: 42, right: 14, top: 14 };
  const innerWidth = CHART_WIDTH - padding.left - padding.right;
  const innerHeight = CHART_HEIGHT - padding.top - padding.bottom;
  const minX = samples[0]?.x ?? 0;
  const maxX = samples[samples.length - 1]?.x ?? 1;
  const values = samples.map((sample) => sample.y);
  const minY = Math.min(...values);
  const maxY = Math.max(...values);
  const safeYRange = Math.max(0.0001, maxY - minY);

  const toPoint = (sample: { x: number; y: number }) => {
    const normalizedX = maxX <= minX ? 0 : (sample.x - minX) / (maxX - minX);
    const normalizedY = (sample.y - minY) / safeYRange;
    return {
      x: padding.left + normalizedX * innerWidth,
      y: padding.top + innerHeight - normalizedY * innerHeight
    };
  };

  const path = samples
    .map((sample, index) => {
      const point = toPoint(sample);
      return `${index === 0 ? "M" : "L"} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`;
    })
    .join(" ");

  const first = samples[0] ? toPoint(samples[0]) : null;
  const middle = samples[Math.floor(samples.length / 2)] ? toPoint(samples[Math.floor(samples.length / 2)]) : null;
  const last = samples[samples.length - 1] ? toPoint(samples[samples.length - 1]) : null;

  return (
    <div className="formula-chart-shell">
      <svg className="formula-chart" viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}>
        <line className="formula-chart-axis" x1={padding.left} x2={padding.left} y1={padding.top} y2={CHART_HEIGHT - padding.bottom} />
        <line
          className="formula-chart-axis"
          x1={padding.left}
          x2={CHART_WIDTH - padding.right}
          y1={CHART_HEIGHT - padding.bottom}
          y2={CHART_HEIGHT - padding.bottom}
        />
        {[0.25, 0.5, 0.75].map((ratio) => (
          <line
            className="formula-chart-grid"
            key={ratio}
            x1={padding.left}
            x2={CHART_WIDTH - padding.right}
            y1={padding.top + innerHeight * ratio}
            y2={padding.top + innerHeight * ratio}
          />
        ))}
        <path className="formula-chart-line" d={path} />
        {first ? <circle className="formula-chart-point" cx={first.x} cy={first.y} r={4} /> : null}
        {middle ? <circle className="formula-chart-point muted" cx={middle.x} cy={middle.y} r={3.5} /> : null}
        {last ? <circle className="formula-chart-point" cx={last.x} cy={last.y} r={4} /> : null}
        <text className="formula-chart-label" x={padding.left - 10} y={padding.top + 6}>
          {formatY ? formatY(maxY) : formatShortNumber(maxY)}
        </text>
        <text className="formula-chart-label" x={padding.left - 10} y={CHART_HEIGHT - padding.bottom + 4}>
          {formatY ? formatY(minY) : formatShortNumber(minY)}
        </text>
        <text className="formula-chart-caption" x={padding.left} y={CHART_HEIGHT - 4}>
          {xLabel}
        </text>
        <text className="formula-chart-caption" textAnchor="end" x={CHART_WIDTH - padding.right} y={CHART_HEIGHT - 4}>
          {formatX ? formatX(maxX) : formatShortNumber(maxX)}
        </text>
        <text className="formula-chart-caption vertical" x={12} y={padding.top + 8}>
          {yLabel}
        </text>
      </svg>
    </div>
  );
}

function FormulaCard({
  children,
  formula,
  stats,
  summary,
  title
}: {
  children: React.ReactNode;
  formula: string;
  stats?: React.ReactNode;
  summary: string;
  title: string;
}) {
  return (
    <article className="recall-formula-card">
      <div className="section-head">
        <div>
          <h3>{title}</h3>
          <p className="muted-copy">{summary}</p>
        </div>
      </div>
      <pre className="recall-formula-copy">{formula}</pre>
      {stats ? <div className="recall-formula-stats">{stats}</div> : null}
      {children}
    </article>
  );
}

function chipClassName(tone?: LayerChip["tone"]): string {
  switch (tone) {
    case "warn":
      return "badge warn";
    case "muted":
      return "badge muted";
    default:
      return "badge";
  }
}

function buildLayerChips(values: Array<string | undefined>, tone: LayerChip["tone"] = "muted", limit = 6): LayerChip[] {
  const normalized = Array.from(new Set(values
    .map((value) => (value ?? "").trim())
    .filter((value) => value.length > 0)));
  const chips = normalized.slice(0, limit).map((label) => ({ label, tone }));
  if (normalized.length > limit) {
    chips.push({ label: `+${normalized.length - limit}`, tone: "muted" });
  }
  return chips;
}

function parseSandboxTrace(traceLines: string[]): ParsedSandboxTrace {
  const parsed: ParsedSandboxTrace = {
    cueCount: 0,
    selectedEntries: [],
    sourceMemoryCount: 0,
    budget: 0
  };

  for (const line of traceLines) {
    const headerMatch = line.match(/^\[Sandbox联想\] source_memories=(\d+) cue_count=(\d+) budget=(\d+)$/);
    if (headerMatch) {
      parsed.sourceMemoryCount = Number.parseInt(headerMatch[1] ?? "0", 10) || 0;
      parsed.cueCount = Number.parseInt(headerMatch[2] ?? "0", 10) || 0;
      parsed.budget = Number.parseInt(headerMatch[3] ?? "0", 10) || 0;
      continue;
    }

    const pickedMatch = line.match(/^\[Sandbox联想\] score=([0-9.]+) supports=(\d+) matched=(.+?) memory=(.*)$/);
    if (pickedMatch) {
      parsed.selectedEntries.push({
        description: pickedMatch[4] ?? "",
        matched: pickedMatch[3] ?? "",
        score: Number.parseFloat(pickedMatch[1] ?? "0") || 0,
        supports: Number.parseInt(pickedMatch[2] ?? "0", 10) || 0
      });
    }
  }

  return parsed;
}

function LayerMonitorBlock({
  emptyText,
  items,
  stats,
  summary,
  title
}: {
  emptyText: string;
  items: LayerMonitorItem[];
  stats?: React.ReactNode;
  summary: string;
  title: string;
}) {
  return (
    <section className="info-block">
      <div className="section-head">
        <div>
          <h3>{title}</h3>
          <p className="muted-copy">{summary}</p>
        </div>
        {stats ? <div className="stacked-badges">{stats}</div> : <span className="badge">{`${items.length} 项`}</span>}
      </div>
      {items.length ? (
        <div className="layer-entry-list">
          {items.map((item) => (
            <article className="layer-entry-card" key={item.id}>
              <div className="layer-entry-head">
                <strong>{item.title}</strong>
                {item.scoreLabel ? <span className="layer-entry-score">{item.scoreLabel}</span> : null}
              </div>
              {item.detail ? <p className="layer-entry-copy">{item.detail}</p> : null}
              {item.chips && item.chips.length ? (
                <div className="chip-row compact">
                  {item.chips.map((chip, index) => (
                    <span className={chipClassName(chip.tone)} key={`${item.id}-${chip.label}-${index}`}>
                      {chip.label}
                    </span>
                  ))}
                </div>
              ) : null}
            </article>
          ))}
        </div>
      ) : (
        <p className="muted-copy">{emptyText}</p>
      )}
    </section>
  );
}

export function RuntimeRecallGraphPage({
  agentStateFile,
  memoryRecallTuningFile,
  onAgentRecallTuningChange,
  onAgentStateChange,
  onMemoryRecallTuningChange,
  recallTrace
}: RuntimeRecallGraphPageProps) {
  const flow = useMemo(() => buildRecallFlow(recallTrace), [recallTrace]);
  const promptMemories = useMemo(() => activePromptMemories(recallTrace), [recallTrace]);
  const consciousMemories = useMemo(() => activeConsciousMemories(recallTrace), [recallTrace]);
  const effectiveRecentWindow = useMemo(
    () => computeEffectiveRecentRecallWindow(memoryRecallTuningFile.recentRecallWindow, 16),
    [memoryRecallTuningFile.recentRecallWindow]
  );
  const currentDeltaDays = useMemo(
    () => gameTicksToDays(recallTrace.runtimeState.deltaGameTime),
    [recallTrace.runtimeState.deltaGameTime]
  );
  const currentRetentionCap = useMemo(
    () => computeRetentionCap(agentStateFile.state.recall_tuning.retention_memory_cap),
    [agentStateFile.state.recall_tuning.retention_memory_cap]
  );
  const runtimeConsciousCount = recallTrace.runtimeState.consciousMemories.length || consciousMemories.length;
  const promptScoreById = useMemo(() => {
    const scores = new Map<string, number>();
    for (const entry of recallTrace.runtimeState.consciousMemories) {
      if (!entry.memoryId) {
        continue;
      }
      scores.set(entry.memoryId, entry.score);
    }
    return scores;
  }, [recallTrace.runtimeState.consciousMemories]);

  const sandboxTrace = useMemo(() => parseSandboxTrace(recallTrace.traceLines), [recallTrace.traceLines]);
  const timingItems = useMemo(
    () => [
      {
        id: "timing-total",
        title: "Total Recall",
        scoreLabel: formatMillis(recallTrace.timings.totalMillis),
        detail: "整轮 recall 的总耗时，包含主召回、sandbox、prompt fill 和状态提交。"
      },
      {
        id: "timing-collect",
        title: "Candidate Collection",
        scoreLabel: formatMillis(recallTrace.timings.candidateCollectionMillis),
        detail: "候选收集阶段。主召回与发酵层参赛记忆在这里汇总。"
      },
      {
        id: "timing-sandbox",
        title: "Sandbox Recall",
        scoreLabel: formatMillis(recallTrace.timings.sandboxMillis),
        detail: "独立 1-hop 联想沙盒的整段耗时。"
      },
      {
        id: "timing-commit",
        title: "State Commit",
        scoreLabel: formatMillis(recallTrace.timings.stateCommitMillis),
        detail: "把 retained / conscious / sandbox 结果回写到运行时状态的耗时。"
      },
      {
        id: "timing-focus",
        title: "Focus Profile",
        scoreLabel: formatMillis(recallTrace.timings.focusProfileMillis),
        detail: "从 visible contexts 构造 focus profile。"
      },
      {
        id: "timing-cues",
        title: "Cue Build",
        scoreLabel: formatMillis(recallTrace.timings.cueBuildMillis),
        detail: "从 tag pool 投影出本轮 cue 输入。"
      },
      {
        id: "timing-promote",
        title: "Promotion Select",
        scoreLabel: formatMillis(recallTrace.timings.promotionSelectionMillis),
        detail: "从候选集中按温度抽样选出 promoted candidates。"
      },
      {
        id: "timing-retained",
        title: "Retained Selection",
        scoreLabel: formatMillis(recallTrace.timings.retainedSelectionMillis),
        detail: "构建 retained 候选并截出 retention 层。"
      },
      {
        id: "timing-conscious",
        title: "Conscious Selection",
        scoreLabel: formatMillis(recallTrace.timings.consciousSelectionMillis),
        detail: "按 conscious score 从 retained 层浮现到表层意识。"
      },
      {
        id: "timing-fallback",
        title: "Implanted Fallback",
        scoreLabel: formatMillis(recallTrace.timings.implantedFallbackMillis),
        detail: "植入记忆保底检测与补入耗时。"
      },
      {
        id: "timing-prompt",
        title: "Prompt Fill",
        scoreLabel: formatMillis(recallTrace.timings.promptFillMillis),
        detail: "conscious -> prompt 的 Bernoulli 阀门耗时。"
      },
      {
        id: "timing-assemble",
        title: "Result Assembly",
        scoreLabel: formatMillis(recallTrace.timings.resultAssemblyMillis),
        detail: "把 selection 物化成输出记忆列表并整理结果集。"
      }
    ],
    [recallTrace.timings]
  );
  const memoryDescriptionById = useMemo(() => {
    const descriptions = new Map<string, string>();
    for (const entry of recallTrace.runtimeState.fermentationBuffer) {
      if (entry.memoryId && entry.description) {
        descriptions.set(entry.memoryId, entry.description);
      }
    }
    for (const entry of recallTrace.runtimeState.consciousMemories) {
      if (entry.memoryId && entry.description) {
        descriptions.set(entry.memoryId, entry.description);
      }
    }
    for (const entry of consciousMemories) {
      if (entry.id && entry.description) {
        descriptions.set(entry.id, entry.description);
      }
    }
    for (const entry of promptMemories) {
      if (entry.id && entry.description) {
        descriptions.set(entry.id, entry.description);
      }
    }
    return descriptions;
  }, [consciousMemories, promptMemories, recallTrace.runtimeState.consciousMemories, recallTrace.runtimeState.fermentationBuffer]);

  const visibleContextLayerItems = useMemo(
    () =>
      [...recallTrace.visibleContexts]
        .sort((left, right) => right.finalScore - left.finalScore)
        .map((entry, index) => ({
          id: `visible-${index}`,
          title: abbreviateText(entry.description, 80, "(无描述)"),
          scoreLabel: `final ${formatShortNumber(entry.finalScore)}`,
          detail: `base ${formatShortNumber(entry.baseImportance)}`,
          chips: [
            ...buildLayerChips(entry.focusTags.map((tag) => `focus:${tag}`), "muted", 4),
            ...buildLayerChips(entry.detailTags.map((tag) => `detail:${tag}`), "default", 5)
          ]
        })),
    [recallTrace.visibleContexts]
  );

  const tagPoolLayerItems = useMemo(
    () =>
      recallTrace.runtimeState.tagPool.map((entry, index) => ({
        id: `tag-pool-${entry.tag}-${index}`,
        title: entry.tag || "(空 tag)",
        scoreLabel: `score ${formatShortNumber(entry.score)}`,
        detail: entry.newlyInjectedThisTurn
          ? `sources ${entry.sourceCount} · raw ${formatShortNumber(entry.rawInjectedScoreThisTurn)} × interval ${formatShortNumber(entry.intervalBonusThisTurn)} × reheat ${formatShortNumber(entry.reheatGainAppliedThisTurn)} = injected ${formatShortNumber(entry.injectedScoreThisTurn)}`
          : `sources ${entry.sourceCount} · stored reheat ${formatShortNumber(entry.reheatGain)}`,
        chips: [
          {
            label: `reheat:${formatShortNumber(entry.reheatGain)}`,
            tone: entry.reheatGain < 1 ? "warn" as const : "muted" as const
          },
          ...buildLayerChips(entry.focusTags.map((tag) => `focus:${tag}`), "muted", 6)
        ]
      })),
    [recallTrace.runtimeState.tagPool]
  );

  const fermentationLayerItems = useMemo(
    () =>
      recallTrace.runtimeState.fermentationBuffer.map((entry, index) => ({
        id: entry.memoryId || `fermentation-${index}`,
        title: abbreviateText(entry.description, 80, "(无描述)"),
        scoreLabel: `score ${formatShortNumber(entry.score)}`,
        detail: `${entry.sourceType || "UNKNOWN"} · cueBoost ${formatShortNumber(entry.cueBoostThisTurn)}`
          + (entry.enteredConsciousThisTurn ? " · 本轮进入 conscious" : ""),
        chips: [
          { label: `supports:${entry.supportTags.length}`, tone: "warn" as const },
          { label: `assoc:${entry.associativeTags.length}`, tone: "muted" as const },
          ...buildLayerChips(entry.focusTags.map((tag) => `focus:${tag}`), "muted", 3),
          ...buildLayerChips(entry.supportTags.map((tag) => `support:${tag}`), "default", 4)
        ]
      })),
    [recallTrace.runtimeState.fermentationBuffer]
  );

  const consciousLayerItems = useMemo(
    () =>
      recallTrace.runtimeState.consciousMemories.map((entry, index) => ({
        id: entry.memoryId || `conscious-${index}`,
        title: abbreviateText(entry.description, 80, "(无描述)"),
        scoreLabel: `score ${formatShortNumber(entry.score)}`,
        detail: entry.sourceType || "UNKNOWN",
        chips: [
          ...buildLayerChips(entry.focusTags.map((tag) => `focus:${tag}`), "muted", 3),
          ...buildLayerChips(entry.supportTags.map((tag) => `support:${tag}`), "default", 4),
          ...buildLayerChips(entry.associativeTags.map((tag) => `assoc:${tag}`), "muted", 4)
        ]
      })),
    [recallTrace.runtimeState.consciousMemories]
  );

  const promptLayerItems = useMemo(
    () =>
      promptMemories.map((entry, index) => ({
        id: entry.id || `prompt-${index}`,
        title: abbreviateText(entry.description, 80, "(无描述)"),
        scoreLabel: entry.id && promptScoreById.has(entry.id)
          ? `score ${formatShortNumber(promptScoreById.get(entry.id) ?? 0)}`
          : "score 未记录",
        detail: entry.semanticTimeLabel ? `time ${entry.semanticTimeLabel}` : "进入 prompt 的最终输出子集",
        chips: [
          ...buildLayerChips(entry.focusTags.map((tag) => `focus:${tag}`), "muted", 3),
          ...buildLayerChips(entry.detailTags.map((tag) => `detail:${tag}`), "default", 4)
        ]
      })),
    [promptMemories, promptScoreById]
  );

  const sandboxSourceItems = useMemo(
    () =>
      recallTrace.runtimeState.associationSandbox.sourceMemoryIds.map((memoryId, index) => ({
        id: `sandbox-source-${memoryId}-${index}`,
        title: abbreviateText(memoryDescriptionById.get(memoryId) ?? memoryId, 80, memoryId || "(空 id)"),
        scoreLabel: promptScoreById.has(memoryId)
          ? `score ${formatShortNumber(promptScoreById.get(memoryId) ?? 0)}`
          : undefined,
        detail: memoryId,
        chips: [{ label: "来自 conscious", tone: "muted" as const }]
      })),
    [memoryDescriptionById, promptScoreById, recallTrace.runtimeState.associationSandbox.sourceMemoryIds]
  );

  const sandboxCueItems = useMemo(
    () =>
      recallTrace.runtimeState.associationSandbox.cueTags.map((tag, index) => ({
        id: `sandbox-cue-${tag}-${index}`,
        title: tag || "(空 cue)",
        detail: "来自 source memories 的 associative tag 池",
        chips: [{ label: "sandbox cue", tone: "warn" as const }]
      })),
    [recallTrace.runtimeState.associationSandbox.cueTags]
  );

  const sandboxResultItems = useMemo(() => {
    if (sandboxTrace.selectedEntries.length) {
      return sandboxTrace.selectedEntries.map((entry, index) => ({
        id: `sandbox-result-${index}`,
        title: abbreviateText(entry.description, 80, "(无描述)"),
        scoreLabel: `score ${formatShortNumber(entry.score)}`,
        detail: `matched ${entry.matched} · supports ${entry.supports}`,
        chips: [{ label: "1-hop", tone: "muted" as const }]
      }));
    }

    const sourceSet = new Set(recallTrace.runtimeState.associationSandbox.sourceMemoryIds);
    return recallTrace.runtimeState.associationSandbox.memoryIds
      .filter((memoryId) => !sourceSet.has(memoryId))
      .map((memoryId, index) => ({
        id: `sandbox-result-${memoryId}-${index}`,
        title: abbreviateText(memoryDescriptionById.get(memoryId) ?? memoryId, 80, memoryId || "(空 id)"),
        detail: memoryId,
        chips: [{ label: "score 未结构化记录", tone: "muted" as const }]
      }));
  }, [
    memoryDescriptionById,
    recallTrace.runtimeState.associationSandbox.memoryIds,
    recallTrace.runtimeState.associationSandbox.sourceMemoryIds,
    sandboxTrace.selectedEntries
  ]);

  const wanderingSamples = useMemo(
    () => buildSampleSeries(2.4, 36, (score) => applyTemperature(score + 0.1, agentStateFile.state.wandering_mind)),
    [agentStateFile.state.wandering_mind]
  );
  const tagPoolDecaySamples = useMemo(
    () => buildSampleSeries(Math.max(1.2, memoryRecallTuningFile.tagPoolHalfLifeDays * 3), 36, (days) => computeTagPoolDecay(days, memoryRecallTuningFile)),
    [memoryRecallTuningFile]
  );
  const tagReheatHitSamples = useMemo(
    () => buildTagReheatHitSamples(8, memoryRecallTuningFile),
    [memoryRecallTuningFile]
  );
  const fermentationDecaySamples = useMemo(
    () => buildSampleSeries(Math.max(3, memoryRecallTuningFile.fermentationHalfLifeDays * 3), 36, (days) => computeFermentationDecay(days, memoryRecallTuningFile)),
    [memoryRecallTuningFile]
  );
  const intervalBonusSamples = useMemo(
    () => buildSampleSeries(6, 36, (days) => computeIntervalBonus(days, memoryRecallTuningFile)),
    [memoryRecallTuningFile]
  );
  const synthesizedDecaySamples = useMemo(
    () => buildSampleSeries(Math.max(12, memoryRecallTuningFile.synthesizedMemoryHalfLife * 2), 36, (days) => computeSynthesizedAgeFactor(days, memoryRecallTuningFile)),
    [memoryRecallTuningFile]
  );
  const recentPenaltySamples = useMemo(
    () => Array.from({ length: effectiveRecentWindow + 3 }, (_, index) => ({ x: index, y: computeRecentRecallPenalty(index, memoryRecallTuningFile) })),
    [effectiveRecentWindow, memoryRecallTuningFile]
  );

  return (
    <div className="panel page-panel recall-workbench">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Recall Workbench</p>
          <h2>记忆召回调参与轨迹页</h2>
          <p className="muted-copy">
            这里把 recall 的运行时分层、公式参数和函数曲线放在同一页。保存工作区后，`agent_state.json` 与 `memory_recall_tuning.json`
            会一起写回，并通过 manifest / bridge 发布给游戏端。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${promptMemories.length} 条 prompt`}</span>
          <span className="badge muted">{`${consciousMemories.length} 条 conscious`}</span>
          <span className="badge muted">{recallTrace.recallPreset || "未记录 recall preset"}</span>
          <span className="badge muted">
            {recallTrace.generatedAt
              ? `最近同步 ${formatRuntimeTimestamp(Date.parse(recallTrace.generatedAt))}`
              : "尚未读到 recall trace"}
          </span>
        </div>
      </div>

      <div className="recall-stat-grid">
        <section className="info-block">
          <h3>当前输入</h3>
          <p className="muted-copy">{`触发源：${recallTrace.triggerSource || "(未知)"}`}</p>
          <p className="muted-copy">{`用户提示：${recallTrace.userPrompt || "(无提示词)"}`}</p>
          <p className="muted-copy">{`本轮游戏时间增量：${formatShortNumber(currentDeltaDays)} 天`}</p>
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
          <h3>运行时层级</h3>
          <div className="chip-row compact">
            <span className="chip">{`tag_pool ${recallTrace.runtimeState.tagPool.length}`}</span>
            <span className="chip">{`fermentation ${recallTrace.runtimeState.fermentationBuffer.length}`}</span>
            <span className="chip">{`conscious ${consciousMemories.length}`}</span>
            <span className="chip">{`prompt ${promptMemories.length}`}</span>
            <span className="chip">{`sandbox ${recallTrace.runtimeState.associationSandbox.memoryIds.length}`}</span>
          </div>
          <p className="muted-copy">
            这页默认把 `prompt memories` 视为真正进入 LLM prompt 的输出层；`conscious memories` 作为上一层单独展示，方便对照 prompt fill 阀门。
          </p>
        </section>

        <LayerMonitorBlock
          emptyText="当前还没有 recall 耗时记录。"
          items={timingItems}
          stats={
            <div className="stacked-badges">
              <span className="badge">{`total ${formatMillis(recallTrace.timings.totalMillis)}`}</span>
              <span className="badge muted">{`collect ${formatMillis(recallTrace.timings.candidateCollectionMillis)}`}</span>
              <span className="badge muted">{`sandbox ${formatMillis(recallTrace.timings.sandboxMillis)}`}</span>
            </div>
          }
          summary="按单次 recall 的真实执行分段记录 wall time。这里看到的是 worker 线程里的 recall 耗时，用来定位哪一层最重。"
          title="Recall 耗时"
        />
      </div>

      <div className="recall-controls-grid">
        <section className="info-block">
          <div className="section-head">
            <div>
              <h3>运行时 Recall 状态</h3>
              <p className="muted-copy">这些字段写回 `agent_state.json.state`，直接影响当前 agent 的 recall 行为。</p>
            </div>
            <span className="badge muted">agent_state.json</span>
          </div>
          <div className="form-grid recall-form-grid">
            <label className="field">
              <span>Recall Preset</span>
              <select
                onChange={(event) => onAgentStateChange({ recall_preset: event.target.value })}
                value={agentStateFile.state.recall_preset}
              >
                <option value="SEED_FIRST">SEED_FIRST</option>
                <option value="FULL_VISIBILITY">FULL_VISIBILITY</option>
                <option value="HYBRID">HYBRID</option>
                <option value="CUSTOM">CUSTOM</option>
              </select>
            </label>

            <label className="field">
              <span>Current Desire Tag</span>
              <select
                onChange={(event) =>
                  onAgentStateChange({
                    current_desire_tag: event.target.value ? (event.target.value as FocusTag) : null
                  })}
                value={agentStateFile.state.current_desire_tag ?? ""}
              >
                <option value="">(无)</option>
                {Object.keys(FOCUS_TAG_LABELS).map((focusTag) => (
                  <option key={focusTag} value={focusTag}>
                    {FOCUS_TAG_LABELS[focusTag as FocusTag]}
                  </option>
                ))}
              </select>
            </label>

            <RangeField
              label="Concentration"
              max={1}
              min={0}
              onChange={(next) => onAgentStateChange({ concentration: next })}
              step={0.01}
              value={agentStateFile.state.concentration}
            />

            <RangeField
              label="Wandering Mind"
              max={1}
              min={0}
              onChange={(next) => onAgentStateChange({ wandering_mind: next })}
              step={0.01}
              value={agentStateFile.state.wandering_mind}
            />
          </div>
          <label className="checkbox-field recall-checkbox">
            <input
              checked={agentStateFile.state.implanted_memory_safety_net}
              onChange={(event) => onAgentStateChange({ implanted_memory_safety_net: event.target.checked })}
              type="checkbox"
            />
            <span>植入记忆保底。若正常 conscious 层没有命中 implanted，允许 fallback 从 cue_pool 里额外补 1 条。</span>
          </label>
        </section>

        <section className="info-block">
          <div className="section-head">
            <div>
              <h3>CUSTOM Runtime Tuning</h3>
              <p className="muted-copy">这四项写回 `agent_state.json.state.recall_tuning`。只有 preset = `CUSTOM` 时，它们才直接接管运行时预算。</p>
            </div>
            <span className="badge">{agentStateFile.state.recall_preset}</span>
          </div>
          <div className="form-grid recall-form-grid">
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="main_candidates_per_cue"
              max={32}
              min={1}
              onChange={(next) => onAgentRecallTuningChange("main_candidates_per_cue", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.main_candidates_per_cue}
            />
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="sandbox_cues_per_memory"
              max={32}
              min={1}
              onChange={(next) => onAgentRecallTuningChange("sandbox_cues_per_memory", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.sandbox_cues_per_memory}
            />
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="conscious_memory_cap"
              max={16}
              min={1}
              onChange={(next) => onAgentRecallTuningChange("conscious_memory_cap", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.conscious_memory_cap}
            />
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="retention_memory_cap"
              max={16}
              min={1}
              onChange={(next) => onAgentRecallTuningChange("retention_memory_cap", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.retention_memory_cap}
            />
          </div>
          <div className="chip-row compact">
            <span className="chip">{`retentionCap ${currentRetentionCap}`}</span>
            <span className="chip">{`sandboxBudget ${computeSandboxRecallBudget(runtimeConsciousCount)}`}</span>
            <span className="chip">{`当前 conscious ${runtimeConsciousCount}`}</span>
          </div>
        </section>
      </div>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>Recall 分层监测</h3>
            <p className="muted-copy">按一次 recall 的真实流水线查看每一层当前留下了什么，以及这一层的分数或核心状态。</p>
          </div>
          <div className="stacked-badges">
            <span className="badge">{`${visibleContextLayerItems.length} visible`}</span>
            <span className="badge muted">{`${tagPoolLayerItems.length} tag_pool`}</span>
            <span className="badge muted">{`${fermentationLayerItems.length} fermentation`}</span>
            <span className="badge muted">{`${consciousLayerItems.length} conscious`}</span>
            <span className="badge muted">{`${promptLayerItems.length} prompt`}</span>
          </div>
        </div>
        <div className="recall-layer-grid">
          <LayerMonitorBlock
            emptyText="当前 trace 里没有 visible contexts。"
            items={visibleContextLayerItems}
            summary="原始 visible context 层。这里展示真正进入 recall 起点的上下文条目，以及它们的最终 attention 分。"
            title="1. Visible Context"
          />
          <LayerMonitorBlock
            emptyText="当前 tag_pool 为空。"
            items={tagPoolLayerItems}
            summary="跨轮维护的 cue 缓冲层。这里能看到每个 detail tag 当前在池中的活跃分数。"
            title="2. Tag Pool"
          />
          <LayerMonitorBlock
            emptyText="当前 fermentation buffer 为空。"
            items={fermentationLayerItems}
            summary="发酵层记忆缓冲。这里展示还在脑中发热、但未必浮到表层的记忆。"
            title="3. Fermentation"
          />
          <LayerMonitorBlock
            emptyText="当前没有进入 conscious 的记忆。"
            items={consciousLayerItems}
            summary="显性意识层。这里的 score 就是本轮 conscious 层实际保留下来的分数。"
            title="4. Conscious"
          />
          <LayerMonitorBlock
            emptyText="当前没有进入 prompt 的记忆。"
            items={promptLayerItems}
            summary="最终进入 prompt 的输出子集。分数优先复用 conscious 层记录，方便对照 prompt fill 阀门。"
            title="5. Prompt"
          />
        </div>
      </section>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>Sandbox 结构</h3>
            <p className="muted-copy">当前 sandbox 是一个独立的 1-hop 联想沙盒，不会回写主 fermentation。这里拆开看 source memories、cue 池和最终联想到的结果。</p>
          </div>
          <div className="stacked-badges">
            <span className="badge">{recallTrace.runtimeState.associationSandbox.active ? "active" : "inactive"}</span>
            <span className="badge muted">{`hop ${recallTrace.runtimeState.associationSandbox.hopLimit}`}</span>
            <span className="badge muted">{`budget ${sandboxTrace.budget || recallTrace.runtimeState.associationSandbox.recallBudget}`}</span>
            <span className="badge muted">{`sampled cues ${sandboxTrace.cueCount || recallTrace.runtimeState.associationSandbox.cueTags.length}`}</span>
          </div>
        </div>
        <p className="muted-copy">
          当前 snapshot 里的 `cueTags` 是 source memories 的 associative tag 池；真正被抽样出来参与 sandbox recall 的 cue 数量，优先从 trace line 里的
          `cue_count` 读取。
        </p>
        <div className="recall-layer-grid">
          <LayerMonitorBlock
            emptyText="当前没有 sandbox source memories。"
            items={sandboxSourceItems}
            stats={<span className="badge">{`${sandboxSourceItems.length} 条`}</span>}
            summary="这层直接来自 conscious memories，是 sandbox 的输入记忆层。"
            title="Sandbox Source Memories"
          />
          <LayerMonitorBlock
            emptyText="当前没有 sandbox cue tags。"
            items={sandboxCueItems}
            stats={<span className="badge">{`${sandboxCueItems.length} 个 tag`}</span>}
            summary="这层是由 source memories 的 associative tags 汇总出来的 cue 池。当前 UI 看到的是池内容，不一定等于最终被抽中的 sampled cue 子集。"
            title="Sandbox Cue Pool"
          />
          <LayerMonitorBlock
            emptyText="当前没有 sandbox recall 结果。"
            items={sandboxResultItems}
            stats={<span className="badge">{`${sandboxResultItems.length} 条结果`}</span>}
            summary="这层是 sandbox 最终联想到的 1-hop 记忆结果。若 trace line 可用，会直接显示结构化 score。"
            title="Sandbox Results"
          />
        </div>
      </section>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>Recall 公式参数</h3>
            <p className="muted-copy">这些字段写回 `memory_recall_tuning.json`，并在游戏启动或 Studio 发布时同步到 `TalkingWeaponConfig` 与真实 toml。前四项同时也是 CUSTOM 缺省预算的 workspace 兜底值。</p>
          </div>
          <span className="badge muted">memory_recall_tuning.json</span>
        </div>
        <div className="recall-formula-grid">
          <FormulaCard
            formula={`sampleWeight = max(0.0001, score) ^ exponent\nexponent = 2.6 - clamp01(wanderingMind) * 2.0`}
            stats={<span className="chip">{`exponent ${formatShortNumber(computeTemperatureExponent(agentStateFile.state.wandering_mind))}`}</span>}
            summary="同一套温度抽样目前用于 promotedCandidates、sandbox cue、sandbox recall 和 implanted fallback。"
            title="Wandering Mind 温度"
          >
            <RangeField
              label="wandering_mind"
              max={1}
              min={0}
              onChange={(next) => onAgentStateChange({ wandering_mind: next })}
              step={0.01}
              value={agentStateFile.state.wandering_mind}
            />
            <FormulaChart
              formatX={(value) => `${formatShortNumber(value)} score`}
              formatY={(value) => formatShortNumber(value)}
              samples={wanderingSamples}
              xLabel="候选 score"
              yLabel="sampleWeight"
            />
          </FormulaCard>

          <FormulaCard
            formula={`promotionBudget = clamp(1, 32, round(mainCandidatesPerCue))`}
            stats={
              <>
                <span className="chip">{`workspace 默认 ${computePromotionBudget(memoryRecallTuningFile.mainCandidatesPerCue)}`}</span>
                <span className="chip muted">{`runtime 当前 ${computePromotionBudget(agentStateFile.state.recall_tuning.main_candidates_per_cue)}`}</span>
              </>
            }
            summary="这是 `memory_recall_tuning.json` 里的 CUSTOM 兜底默认值。当前不再乘 `mainCueCount`，而是直接控制本轮主召回提升预算。"
            title="主召回候选预算"
          >
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="mainCandidatesPerCue"
              max={32}
              min={1}
              onChange={(next) => onMemoryRecallTuningChange({ mainCandidatesPerCue: Math.round(next) })}
              round
              step={1}
              value={memoryRecallTuningFile.mainCandidatesPerCue}
            />
          </FormulaCard>

          <FormulaCard
            formula={`sandboxCueBudget = clamp(1, 32, round(sandboxCuesPerMemory))`}
            stats={
              <>
                <span className="chip">{`workspace 默认 ${computeSandboxCueBudget(memoryRecallTuningFile.sandboxCuesPerMemory)}`}</span>
                <span className="chip muted">{`runtime 当前 ${computeSandboxCueBudget(agentStateFile.state.recall_tuning.sandbox_cues_per_memory)}`}</span>
              </>
            }
            summary="这是 `memory_recall_tuning.json` 里的 CUSTOM 兜底默认值。当前不再按每条 conscious 记忆分别乘预算，而是直接控制本轮 sandbox cue 总预算。"
            title="Sandbox Cue 预算"
          >
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="sandboxCuesPerMemory"
              max={32}
              min={1}
              onChange={(next) => onMemoryRecallTuningChange({ sandboxCuesPerMemory: Math.round(next) })}
              round
              step={1}
              value={memoryRecallTuningFile.sandboxCuesPerMemory}
            />
          </FormulaCard>

          <FormulaCard
            formula={`consciousCap = clamp(1, 16, round(consciousMemoryCap))`}
            stats={
              <>
                <span className="chip">{`workspace 默认 ${Math.round(memoryRecallTuningFile.consciousMemoryCap)}`}</span>
                <span className="chip muted">{`runtime 当前 ${agentStateFile.state.recall_tuning.conscious_memory_cap}`}</span>
              </>
            }
            summary="这是 `memory_recall_tuning.json` 里的 CUSTOM 兜底默认值。表层意识现在单独控制，不再顺带派生 retained fermentation 容量。"
            title="表层容量"
          >
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="consciousMemoryCap"
              max={16}
              min={1}
              onChange={(next) => onMemoryRecallTuningChange({ consciousMemoryCap: Math.round(next) })}
              round
              step={1}
              value={memoryRecallTuningFile.consciousMemoryCap}
            />
          </FormulaCard>

          <FormulaCard
            formula={`retentionCap = clamp(1, 16, round(retentionMemoryCap))`}
            stats={
              <>
                <span className="chip">{`workspace 默认 ${computeRetentionCap(memoryRecallTuningFile.retentionMemoryCap)}`}</span>
                <span className="chip muted">{`runtime 当前 ${currentRetentionCap}`}</span>
              </>
            }
            summary="这是 `memory_recall_tuning.json` 里的 CUSTOM 兜底默认值。发酵层 retained selection 现在由单独参数控制。"
            title="发酵层容量"
          >
            <RangeField
              formatValue={(value) => `${Math.round(value)}`}
              label="retentionMemoryCap"
              max={16}
              min={1}
              onChange={(next) => onMemoryRecallTuningChange({ retentionMemoryCap: Math.round(next) })}
              round
              step={1}
              value={memoryRecallTuningFile.retentionMemoryCap}
            />
          </FormulaCard>

          <FormulaCard
            formula={`decayFactor = exp(- (deltaDays / tagPoolHalfLifeDays) ^ tagPoolDecayShape)`}
            stats={<span className="chip">{`当前 Δt=${formatShortNumber(currentDeltaDays)} 天 -> ${formatShortNumber(computeTagPoolDecay(currentDeltaDays, memoryRecallTuningFile))}`}</span>}
            summary="控制 tag_pool 的跨轮衰减速度。"
            title="Tag Pool 半衰期"
          >
            <div className="form-grid recall-form-grid">
              <RangeField
                formatValue={(value) => `${formatShortNumber(value)}d`}
                label="tagPoolHalfLifeDays"
                max={1}
                min={0.1}
                onChange={(next) => onMemoryRecallTuningChange({ tagPoolHalfLifeDays: next })}
                step={0.05}
                value={memoryRecallTuningFile.tagPoolHalfLifeDays}
              />
              <RangeField
                formatValue={(value) => formatShortNumber(value)}
                label="tagPoolDecayShape"
                max={4}
                min={0.25}
                onChange={(next) => onMemoryRecallTuningChange({ tagPoolDecayShape: next })}
                step={0.05}
                value={memoryRecallTuningFile.tagPoolDecayShape}
              />
            </div>
            <FormulaChart
              formatX={(value) => `${formatShortNumber(value)}d`}
              formatY={(value) => formatShortNumber(value)}
              samples={tagPoolDecaySamples}
              xLabel="deltaDays"
              yLabel="decayFactor"
            />
          </FormulaCard>

          <FormulaCard
            formula={`drop if tagScore < tagPoolDropThreshold`}
            stats={<span className="chip">{`当前阈值 ${formatShortNumber(memoryRecallTuningFile.tagPoolDropThreshold, 3)}`}</span>}
            summary="只决定 tag_pool 条目何时掉出缓冲层，不改变衰减曲线本身。阈值越高，常见 tag 越容易被清掉。"
            title="Tag Pool 掉出阈值"
          >
            <RangeField
              formatValue={(value) => formatShortNumber(value, 3)}
              label="tagPoolDropThreshold"
              max={0.2}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ tagPoolDropThreshold: next })}
              step={0.005}
              value={memoryRecallTuningFile.tagPoolDropThreshold}
            />
          </FormulaCard>

          <FormulaCard
            formula={`appliedReheatGain = tagPoolReheatPulseGain                         if first insert\nappliedReheatGain = reheatFloor + (currentReheatGain - reheatFloor) * decayFactor otherwise\nnextReheatGain = reheatFloor + (appliedReheatGain - reheatFloor) * tagPoolReheatDecayPerHit\neffectiveInjectedScore = rawInjectedScore * intervalBonus * appliedReheatGain`}
            stats={
              <>
                <span className="chip">{`首击 ${formatShortNumber(memoryRecallTuningFile.tagPoolReheatPulseGain)}`}</span>
                <span className="chip">{`二连击 ${formatShortNumber(computeTagReheatGainAfterHit(memoryRecallTuningFile.tagPoolReheatPulseGain, memoryRecallTuningFile))}`}</span>
                <span className="chip muted">{`静置 ${formatShortNumber(memoryRecallTuningFile.tagPoolHalfLifeDays / 2)}d 后 ${formatShortNumber(computeTagReheatGainAfterTime(computeTagReheatGainAfterHit(memoryRecallTuningFile.tagPoolReheatPulseGain, memoryRecallTuningFile), memoryRecallTuningFile.tagPoolHalfLifeDays / 2, memoryRecallTuningFile))}`}</span>
              </>
            }
            summary="时间衰减复用 tag_pool 的 decay 曲线；第一次命中给脉冲，后续连续命中会快速掉向 1 附近并继续习惯化。"
            title="Tag Reheat Gain"
          >
            <div className="form-grid recall-form-grid">
              <RangeField
                formatValue={(value) => formatShortNumber(value)}
                label="tagPoolReheatPulseGain"
                max={3}
                min={1}
                onChange={(next) => onMemoryRecallTuningChange({ tagPoolReheatPulseGain: next })}
                step={0.05}
                value={memoryRecallTuningFile.tagPoolReheatPulseGain}
              />
              <RangeField
                formatValue={(value) => formatShortNumber(value)}
                label="tagPoolReheatDecayPerHit"
                max={1}
                min={0}
                onChange={(next) => onMemoryRecallTuningChange({ tagPoolReheatDecayPerHit: next })}
                step={0.05}
                value={memoryRecallTuningFile.tagPoolReheatDecayPerHit}
              />
              <RangeField
                formatValue={(value) => formatShortNumber(value)}
                label="tagPoolReheatFloor"
                max={1}
                min={0.1}
                onChange={(next) => onMemoryRecallTuningChange({ tagPoolReheatFloor: next })}
                step={0.05}
                value={memoryRecallTuningFile.tagPoolReheatFloor}
              />
            </div>
            <FormulaChart
              formatX={(value) => `${Math.round(value)} hit`}
              formatY={(value) => formatShortNumber(value)}
              samples={tagReheatHitSamples}
              xLabel="consecutiveHits"
              yLabel="appliedReheatGain"
            />
          </FormulaCard>

          <FormulaCard
            formula={`decayFactor = exp(- (deltaDays / fermentationHalfLifeDays) ^ fermentationDecayShape)`}
            stats={<span className="chip">{`当前 Δt=${formatShortNumber(currentDeltaDays)} 天 -> ${formatShortNumber(computeFermentationDecay(currentDeltaDays, memoryRecallTuningFile))}`}</span>}
            summary="控制 fermentation_buffer 的跨轮保温时长。"
            title="Fermentation 半衰期"
          >
            <div className="form-grid recall-form-grid">
              <RangeField
                formatValue={(value) => `${formatShortNumber(value)}d`}
                label="fermentationHalfLifeDays"
                max={2}
                min={0.1}
                onChange={(next) => onMemoryRecallTuningChange({ fermentationHalfLifeDays: next })}
                step={0.05}
                value={memoryRecallTuningFile.fermentationHalfLifeDays}
              />
              <RangeField
                formatValue={(value) => formatShortNumber(value)}
                label="fermentationDecayShape"
                max={4}
                min={0.25}
                onChange={(next) => onMemoryRecallTuningChange({ fermentationDecayShape: next })}
                step={0.05}
                value={memoryRecallTuningFile.fermentationDecayShape}
              />
            </div>
            <FormulaChart
              formatX={(value) => `${formatShortNumber(value)}d`}
              formatY={(value) => formatShortNumber(value)}
              samples={fermentationDecaySamples}
              xLabel="deltaDays"
              yLabel="decayFactor"
            />
          </FormulaCard>

          <FormulaCard
            formula={`drop if fermentationScore < fermentationDropThreshold`}
            stats={<span className="chip">{`当前阈值 ${formatShortNumber(memoryRecallTuningFile.fermentationDropThreshold, 3)}`}</span>}
            summary="只决定 fermentation_buffer 条目何时掉出缓冲层，不改变发酵分本身的衰减曲线。"
            title="Fermentation 掉出阈值"
          >
            <RangeField
              formatValue={(value) => formatShortNumber(value, 3)}
              label="fermentationDropThreshold"
              max={0.2}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ fermentationDropThreshold: next })}
              step={0.005}
              value={memoryRecallTuningFile.fermentationDropThreshold}
            />
          </FormulaCard>

          <FormulaCard
            formula={`intervalBonus = clamp(1.0, 2.6,\n  1.0 + min(1.6, log1p(days * intervalBonusSteepness) * intervalBonusScale)\n)`}
            stats={<span className="chip">{`当前 Δt=${formatShortNumber(currentDeltaDays)} 天 -> ${formatShortNumber(computeIntervalBonus(currentDeltaDays, memoryRecallTuningFile))}`}</span>}
            summary="只作用于本轮 visibleContexts 注入 tag_pool 的 injectedScore，不放大旧分数。"
            title="长间隔补偿"
          >
            <div className="form-grid recall-form-grid">
              <RangeField
                formatValue={(value) => formatShortNumber(value)}
                label="intervalBonusScale"
                max={2.5}
                min={0}
                onChange={(next) => onMemoryRecallTuningChange({ intervalBonusScale: next })}
                step={0.05}
                value={memoryRecallTuningFile.intervalBonusScale}
              />
              <RangeField
                formatValue={(value) => formatShortNumber(value)}
                label="intervalBonusSteepness"
                max={8}
                min={0.25}
                onChange={(next) => onMemoryRecallTuningChange({ intervalBonusSteepness: next })}
                step={0.05}
                value={memoryRecallTuningFile.intervalBonusSteepness}
              />
            </div>
            <FormulaChart
              formatX={(value) => `${formatShortNumber(value)}d`}
              formatY={(value) => formatShortNumber(value)}
              samples={intervalBonusSamples}
              xLabel="deltaDays"
              yLabel="intervalBonus"
            />
          </FormulaCard>

          <FormulaCard
            formula={`ageFactor = clamp(0.15, 1.0, exp(-ageDays / synthesizedMemoryHalfLife))`}
            stats={<span className="chip">{`半衰期 ${formatShortNumber(memoryRecallTuningFile.synthesizedMemoryHalfLife)} 天`}</span>}
            summary="只用于合成记忆的年龄衰减；IMPLANTED 不参与这条公式。"
            title="合成记忆年龄衰减"
          >
            <RangeField
              formatValue={(value) => `${Math.round(value)}d`}
              label="synthesizedMemoryHalfLife"
              max={240}
              min={1}
              onChange={(next) => onMemoryRecallTuningChange({ synthesizedMemoryHalfLife: next })}
              round
              step={1}
              value={memoryRecallTuningFile.synthesizedMemoryHalfLife}
            />
            <FormulaChart
              formatX={(value) => `${Math.round(value)}d`}
              formatY={(value) => formatShortNumber(value)}
              samples={synthesizedDecaySamples}
              xLabel="ageDays"
              yLabel="ageFactor"
            />
          </FormulaCard>

          <FormulaCard
            formula={`effectiveWindow = clamp(0, 16, round(recentRecallWindow))\nrecentPenalty = releaseCurve(turnsSincePrompt, effectiveWindow)`}
            stats={
              <>
                <span className="chip">{`effectiveWindow ${effectiveRecentWindow}`}</span>
                <span className="chip">{`turn=0 时 ${formatShortNumber(computeRecentRecallPenalty(0, memoryRecallTuningFile))}`}</span>
              </>
            }
            summary="这里只保留一个滑块。`recentRecallWindowCap` 现在只作为兼容字段保留，Studio 不再暴露；`0` 表示关闭最近召回抑制。"
            title="最近召回抑制"
          >
            <RangeField
              formatValue={(value) => `${Math.round(value)} 轮`}
              label="recentRecallWindow"
              max={16}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ recentRecallWindow: Math.round(next) })}
              round
              step={1}
              value={memoryRecallTuningFile.recentRecallWindow}
            />
            <p className="muted-copy">
              {effectiveRecentWindow <= 0
                ? "当前 recent recall 抑制已关闭。"
                : `当前会对最近 ${effectiveRecentWindow} 轮内真正进入 prompt 的记忆做平滑抑制。`}
            </p>
            <FormulaChart
              formatX={(value) => `${Math.round(value)} 轮`}
              formatY={(value) => formatShortNumber(value)}
              samples={recentPenaltySamples}
              xLabel="turnsSincePrompt"
              yLabel="recentPenalty"
            />
          </FormulaCard>

          <FormulaCard
            formula={`normalized = clamp01(consciousScore / 2.40)\nspeakProbability = clamp(0, 1, (0.08 + normalized * 0.84) * promptFillProbabilityScale)`}
            stats={<span className="chip">{`score=1.60 时 ${formatShortNumber(computePromptFillProbability(1.6, memoryRecallTuningFile))}`}</span>}
            summary="只影响 conscious -> prompt 的阀门；不会影响 conscious 层本身的排序。"
            title="Prompt Fill 阀门"
          >
            <RangeField
              formatValue={(value) => formatShortNumber(value)}
              label="promptFillProbabilityScale"
              max={2}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ promptFillProbabilityScale: next })}
              step={0.05}
              value={memoryRecallTuningFile.promptFillProbabilityScale}
            />
          </FormulaCard>

          <FormulaCard
            formula={`desireBonus = desireProfileBonusScale * focusWeight(tag)`}
            stats={<span className="chip">{`focusWeight=1 时 ${formatShortNumber(computeDesireProfileBonus(1, memoryRecallTuningFile))}`}</span>}
            summary="用于 buildFocusProfile，影响 desire tag 在焦点分布里的额外权重。"
            title="渴望 Focus Profile 加权"
          >
            <RangeField
              formatValue={(value) => formatShortNumber(value)}
              label="desireProfileBonusScale"
              max={2}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ desireProfileBonusScale: next })}
              step={0.01}
              value={memoryRecallTuningFile.desireProfileBonusScale}
            />
          </FormulaCard>

          <FormulaCard
            formula={`desiredFocusBonus = desiredFocusBonusScale * focusWeight(desireTag)`}
            stats={<span className="chip">{`focusWeight=1 时 ${formatShortNumber(computeDesiredFocusBonus(1, memoryRecallTuningFile))}`}</span>}
            summary="用于主召回评分阶段，给携带 desire focus 的记忆追加额外得分。"
            title="渴望记忆评分加成"
          >
            <RangeField
              formatValue={(value) => formatShortNumber(value)}
              label="desiredFocusBonusScale"
              max={2}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ desiredFocusBonusScale: next })}
              step={0.01}
              value={memoryRecallTuningFile.desiredFocusBonusScale}
            />
          </FormulaCard>

          <FormulaCard
            formula={`fallbackEntryScore = implantedFallbackEntryScore`}
            stats={<span className="chip">{`当前注入分 ${formatShortNumber(memoryRecallTuningFile.implantedFallbackEntryScore)}`}</span>}
            summary="这不是权重曲线，而是 fallback 记忆被补入 conscious / retained 后的固定注入分。"
            title="植入记忆保底注入分"
          >
            <RangeField
              formatValue={(value) => formatShortNumber(value)}
              label="implantedFallbackEntryScore"
              max={3}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ implantedFallbackEntryScore: next })}
              step={0.05}
              value={memoryRecallTuningFile.implantedFallbackEntryScore}
            />
          </FormulaCard>
        </div>
      </section>

      {promptMemories.length ? (
        <section className="info-block runtime-graph-panel">
          <div className="section-head">
            <div>
              <h3>Prompt 召回思维导图</h3>
              <p className="muted-copy">导图优先展示 `prompt memories`；若旧 trace 没有新 schema，则回退到 legacy `recalledMemories`。</p>
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
          <strong>当前没有可绘制的 prompt recall</strong>
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
