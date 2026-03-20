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
import {
  applyTemperature,
  buildSampleSeries,
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

type RuntimeRecallGraphPageProps = {
  agentStateFile: AgentStateFile;
  memoryRecallTuningFile: MemoryRecallTuningFile;
  onAgentRecallTuningChange: (key: keyof AgentStateRecallTuning, value: number) => void;
  onAgentStateChange: (patch: Partial<AgentStateFile["state"]>) => void;
  onMemoryRecallTuningChange: (patch: Partial<MemoryRecallTuningFile>) => void;
  recallTrace: RuntimeRecallTraceFile;
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

function NumberField({
  hint,
  label,
  max,
  min,
  onChange,
  round = false,
  step,
  value
}: {
  hint?: string;
  label: string;
  max: number;
  min: number;
  onChange: (next: number) => void;
  round?: boolean;
  step: number;
  value: number;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input
        max={max}
        min={min}
        onChange={(event) => onChange(readNumber(event.target.value, value, round))}
        step={step}
        type="number"
        value={value}
      />
      {hint ? <small className="field-hint">{hint}</small> : null}
    </label>
  );
}

function RangeField({
  label,
  max,
  min,
  onChange,
  step,
  value
}: {
  label: string;
  max: number;
  min: number;
  onChange: (next: number) => void;
  step: number;
  value: number;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <div className="range-field">
        <input
          max={max}
          min={min}
          onChange={(event) => onChange(readNumber(event.target.value, value))}
          step={step}
          type="range"
          value={value}
        />
        <strong className="range-field-output">{value.toFixed(2)}</strong>
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

function MemoryListBlock({
  emptyText,
  label,
  memories
}: {
  emptyText: string;
  label: string;
  memories: RuntimeRecalledMemoryEntry[];
}) {
  return (
    <section className="info-block">
      <div className="section-head">
        <div>
          <h3>{label}</h3>
          <p className="muted-copy">{`当前 ${memories.length} 条。`}</p>
        </div>
        <span className="badge">{`${memories.length} 条`}</span>
      </div>
      {memories.length ? (
        <div className="recall-memory-list">
          {memories.map((memory, index) => (
            <article className="recall-memory-pill" key={`${memory.id}-${index}`}>
              <strong>{formatMemoryTitle(index)}</strong>
              <p>{abbreviateText(memory.description, 96)}</p>
              <div className="chip-row compact">
                {memory.focusTags.map((focusTag) => (
                  <span className="chip" key={`${memory.id}-${focusTag}`}>
                    {FOCUS_TAG_LABELS[focusTag as FocusTag] ?? focusTag}
                  </span>
                ))}
              </div>
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
  const [visibleContextsExpanded, setVisibleContextsExpanded] = useState(false);
  const flow = useMemo(() => buildRecallFlow(recallTrace), [recallTrace]);
  const promptMemories = useMemo(() => activePromptMemories(recallTrace), [recallTrace]);
  const consciousMemories = useMemo(() => activeConsciousMemories(recallTrace), [recallTrace]);
  const effectiveRecentWindow = useMemo(
    () => computeEffectiveRecentRecallWindow(memoryRecallTuningFile.recentRecallWindow, memoryRecallTuningFile.recentRecallWindowCap),
    [memoryRecallTuningFile.recentRecallWindow, memoryRecallTuningFile.recentRecallWindowCap]
  );
  const currentDeltaDays = useMemo(
    () => gameTicksToDays(recallTrace.runtimeState.deltaGameTime),
    [recallTrace.runtimeState.deltaGameTime]
  );
  const currentRetentionCap = useMemo(
    () => computeRetentionCap(agentStateFile.state.recall_tuning.conscious_memory_cap),
    [agentStateFile.state.recall_tuning.conscious_memory_cap]
  );
  const runtimeConsciousCount = recallTrace.runtimeState.consciousMemories.length || consciousMemories.length;
  const parameterLedger = useMemo(() => [
    {
      currentValue: formatShortNumber(agentStateFile.state.wandering_mind),
      formula: "sampleWeight = max(0.0001, score) ^ (2.6 - clamp01(wanderingMind) * 2.0)",
      label: "wandering_mind",
      source: "agent_state.json.state"
    },
    {
      currentValue: String(agentStateFile.state.recall_tuning.main_candidates_per_cue),
      formula: "layerBudget = max(1, mainCueCount * mainCandidatesPerCue)",
      label: "recall_tuning.main_candidates_per_cue",
      source: "agent_state.json.state"
    },
    {
      currentValue: String(agentStateFile.state.recall_tuning.sandbox_cues_per_memory),
      formula: "cueCountPerMemory = sandboxCuesPerMemory",
      label: "recall_tuning.sandbox_cues_per_memory",
      source: "agent_state.json.state"
    },
    {
      currentValue: String(agentStateFile.state.recall_tuning.conscious_memory_cap),
      formula: "consciousCap = consciousMemoryCap; retentionCap = clamp(8, 32, max(consciousMemoryCap * 4, 8))",
      label: "recall_tuning.conscious_memory_cap",
      source: "agent_state.json.state"
    },
    {
      currentValue: String(memoryRecallTuningFile.recentRecallWindow),
      formula: "effectiveWindow = min(recentRecallWindow, recentRecallWindowCap)",
      label: "recentRecallWindow",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: String(memoryRecallTuningFile.recentRecallWindowCap),
      formula: "effectiveWindow = min(recentRecallWindow, recentRecallWindowCap)",
      label: "recentRecallWindowCap",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.implantedFallbackEntryScore),
      formula: "fallbackEntryScore = implantedFallbackEntryScore",
      label: "implantedFallbackEntryScore",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.promptFillProbabilityScale),
      formula: "speakProbability = clamp(0, 1, (0.08 + normalized * 0.84) * promptFillProbabilityScale)",
      label: "promptFillProbabilityScale",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.tagPoolHalfLifeDays),
      formula: "tagPoolDecay = exp(-deltaDays / tagPoolHalfLifeDays)",
      label: "tagPoolHalfLifeDays",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.fermentationHalfLifeDays),
      formula: "fermentationDecay = exp(-deltaDays / fermentationHalfLifeDays)",
      label: "fermentationHalfLifeDays",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.intervalBonusScale),
      formula: "intervalBonus = clamp(1, 2.6, 1 + min(1.6, log1p(days * intervalBonusSteepness) * intervalBonusScale))",
      label: "intervalBonusScale",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.intervalBonusSteepness),
      formula: "intervalBonus = clamp(1, 2.6, 1 + min(1.6, log1p(days * intervalBonusSteepness) * intervalBonusScale))",
      label: "intervalBonusSteepness",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.synthesizedMemoryHalfLife),
      formula: "ageFactor = clamp(0.15, 1.0, exp(-ageDays / synthesizedMemoryHalfLife))",
      label: "synthesizedMemoryHalfLife",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.desireProfileBonusScale),
      formula: "desireBonus = desireProfileBonusScale * focusWeight(tag)",
      label: "desireProfileBonusScale",
      source: "memory_recall_tuning.json"
    },
    {
      currentValue: formatShortNumber(memoryRecallTuningFile.desiredFocusBonusScale),
      formula: "desiredFocusBonus = desiredFocusBonusScale * focusWeight(desireTag)",
      label: "desiredFocusBonusScale",
      source: "memory_recall_tuning.json"
    }
  ], [agentStateFile.state.recall_tuning, agentStateFile.state.wandering_mind, memoryRecallTuningFile]);

  const wanderingSamples = useMemo(
    () => buildSampleSeries(2.4, 36, (score) => applyTemperature(score + 0.1, agentStateFile.state.wandering_mind)),
    [agentStateFile.state.wandering_mind]
  );
  const promotionBudgetSamples = useMemo(
    () => Array.from({ length: 12 }, (_, index) => ({ x: index + 1, y: computePromotionBudget(index + 1, agentStateFile.state.recall_tuning.main_candidates_per_cue) })),
    [agentStateFile.state.recall_tuning.main_candidates_per_cue]
  );
  const sandboxCueSamples = useMemo(
    () => Array.from({ length: 8 }, (_, index) => ({ x: index + 1, y: computeSandboxCueBudget(index + 1, agentStateFile.state.recall_tuning.sandbox_cues_per_memory) })),
    [agentStateFile.state.recall_tuning.sandbox_cues_per_memory]
  );
  const consciousCapSamples = useMemo(
    () => Array.from({ length: 9 }, (_, index) => ({ x: index, y: computeRetentionCap(index) })),
    []
  );
  const tagPoolDecaySamples = useMemo(
    () => buildSampleSeries(Math.max(1.2, memoryRecallTuningFile.tagPoolHalfLifeDays * 3), 36, (days) => computeTagPoolDecay(days, memoryRecallTuningFile)),
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
  const promptFillSamples = useMemo(
    () => buildSampleSeries(2.4, 36, (score) => computePromptFillProbability(score, memoryRecallTuningFile)),
    [memoryRecallTuningFile]
  );
  const desireProfileSamples = useMemo(
    () => buildSampleSeries(1, 24, (focusWeight) => computeDesireProfileBonus(focusWeight, memoryRecallTuningFile)),
    [memoryRecallTuningFile]
  );
  const desiredFocusSamples = useMemo(
    () => buildSampleSeries(1, 24, (focusWeight) => computeDesiredFocusBonus(focusWeight, memoryRecallTuningFile)),
    [memoryRecallTuningFile]
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
              <p className="muted-copy">这三项写回 `agent_state.json.state.recall_tuning`。只有 preset = `CUSTOM` 时，它们才直接接管运行时预算。</p>
            </div>
            <span className="badge">{agentStateFile.state.recall_preset}</span>
          </div>
          <div className="form-grid recall-form-grid">
            <NumberField
              label="main_candidates_per_cue"
              max={6}
              min={1}
              onChange={(next) => onAgentRecallTuningChange("main_candidates_per_cue", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.main_candidates_per_cue}
            />
            <NumberField
              label="sandbox_cues_per_memory"
              max={4}
              min={0}
              onChange={(next) => onAgentRecallTuningChange("sandbox_cues_per_memory", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.sandbox_cues_per_memory}
            />
            <NumberField
              label="conscious_memory_cap"
              max={8}
              min={0}
              onChange={(next) => onAgentRecallTuningChange("conscious_memory_cap", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.conscious_memory_cap}
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
            <h3>参数索引</h3>
            <p className="muted-copy">{"这里把每个可调参数明确绑定到一个公式说明，便于核对“参数 -> 公式 -> 输出”的关系。"}</p>
          </div>
          <span className="badge muted">{`${parameterLedger.length} 个参数`}</span>
        </div>
        <div className="recall-ledger-grid">
          {parameterLedger.map((entry) => (
            <article className="recall-ledger-card" key={entry.label}>
              <div className="custom-tag-meta">
                <span className="badge">{entry.label}</span>
                <span className="badge muted">{entry.source}</span>
              </div>
              <strong>{entry.currentValue}</strong>
              <p>{entry.formula}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>Recall 公式参数</h3>
            <p className="muted-copy">这些字段写回 `memory_recall_tuning.json`，游戏发布时会同步回 `TalkingWeaponConfig` 与真实 toml。</p>
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
            formula={`layerBudget = max(1, mainCueCount * mainCandidatesPerCue)`}
            stats={<span className="chip">{`cue=4 时 budget ${computePromotionBudget(4, agentStateFile.state.recall_tuning.main_candidates_per_cue)}`}</span>}
            summary="主召回阶段，每个 cue 最多带出多少个提升候选。"
            title="主召回候选预算"
          >
            <NumberField
              label="main_candidates_per_cue"
              max={6}
              min={1}
              onChange={(next) => onAgentRecallTuningChange("main_candidates_per_cue", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.main_candidates_per_cue}
            />
            <FormulaChart
              formatX={(value) => `${Math.round(value)} cues`}
              formatY={(value) => `${Math.round(value)}`}
              samples={promotionBudgetSamples}
              xLabel="mainCueCount"
              yLabel="layerBudget"
            />
          </FormulaCard>

          <FormulaCard
            formula={`cueCountPerMemory = sandboxCuesPerMemory`}
            stats={<span className="chip">{`conscious=3 时最多 ${computeSandboxCueBudget(3, agentStateFile.state.recall_tuning.sandbox_cues_per_memory)} 个 cue`}</span>}
            summary="每条表层记忆能向 sandbox 回灌多少个 associative cue。"
            title="Sandbox Cue 预算"
          >
            <NumberField
              label="sandbox_cues_per_memory"
              max={4}
              min={0}
              onChange={(next) => onAgentRecallTuningChange("sandbox_cues_per_memory", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.sandbox_cues_per_memory}
            />
            <FormulaChart
              formatX={(value) => `${Math.round(value)} conscious`}
              formatY={(value) => `${Math.round(value)}`}
              samples={sandboxCueSamples}
              xLabel="consciousMemoryCount"
              yLabel="max cue count"
            />
          </FormulaCard>

          <FormulaCard
            formula={`consciousCap = consciousMemoryCap\nretentionCap = clamp(8, 32, max(consciousMemoryCap * 4, 8))`}
            stats={
              <>
                <span className="chip">{`consciousCap ${agentStateFile.state.recall_tuning.conscious_memory_cap}`}</span>
                <span className="chip">{`retentionCap ${currentRetentionCap}`}</span>
              </>
            }
            summary="当前一个参数同时决定 conscious 上限，并派生 retained fermentation 的容量上限。"
            title="表层容量"
          >
            <NumberField
              label="conscious_memory_cap"
              max={8}
              min={0}
              onChange={(next) => onAgentRecallTuningChange("conscious_memory_cap", Math.round(next))}
              round
              step={1}
              value={agentStateFile.state.recall_tuning.conscious_memory_cap}
            />
            <FormulaChart
              formatX={(value) => `${Math.round(value)} cap`}
              formatY={(value) => `${Math.round(value)}`}
              samples={consciousCapSamples}
              xLabel="conscious_memory_cap"
              yLabel="retentionCap"
            />
          </FormulaCard>

          <FormulaCard
            formula={`decayFactor = exp(-deltaDays / tagPoolHalfLifeDays)`}
            stats={<span className="chip">{`当前 Δt=${formatShortNumber(currentDeltaDays)} 天 -> ${formatShortNumber(computeTagPoolDecay(currentDeltaDays, memoryRecallTuningFile))}`}</span>}
            summary="控制 tag_pool 的跨轮衰减速度。"
            title="Tag Pool 半衰期"
          >
            <NumberField
              label="tagPoolHalfLifeDays"
              max={30}
              min={0.05}
              onChange={(next) => onMemoryRecallTuningChange({ tagPoolHalfLifeDays: next })}
              step={0.05}
              value={memoryRecallTuningFile.tagPoolHalfLifeDays}
            />
            <FormulaChart
              formatX={(value) => `${formatShortNumber(value)}d`}
              formatY={(value) => formatShortNumber(value)}
              samples={tagPoolDecaySamples}
              xLabel="deltaDays"
              yLabel="decayFactor"
            />
          </FormulaCard>

          <FormulaCard
            formula={`decayFactor = exp(-deltaDays / fermentationHalfLifeDays)`}
            stats={<span className="chip">{`当前 Δt=${formatShortNumber(currentDeltaDays)} 天 -> ${formatShortNumber(computeFermentationDecay(currentDeltaDays, memoryRecallTuningFile))}`}</span>}
            summary="控制 fermentation_buffer 的跨轮保温时长。"
            title="Fermentation 半衰期"
          >
            <NumberField
              label="fermentationHalfLifeDays"
              max={120}
              min={0.1}
              onChange={(next) => onMemoryRecallTuningChange({ fermentationHalfLifeDays: next })}
              step={0.1}
              value={memoryRecallTuningFile.fermentationHalfLifeDays}
            />
            <FormulaChart
              formatX={(value) => `${formatShortNumber(value)}d`}
              formatY={(value) => formatShortNumber(value)}
              samples={fermentationDecaySamples}
              xLabel="deltaDays"
              yLabel="decayFactor"
            />
          </FormulaCard>

          <FormulaCard
            formula={`intervalBonus = clamp(1.0, 2.6,\n  1.0 + min(1.6, log1p(days * intervalBonusSteepness) * intervalBonusScale)\n)`}
            stats={<span className="chip">{`当前 Δt=${formatShortNumber(currentDeltaDays)} 天 -> ${formatShortNumber(computeIntervalBonus(currentDeltaDays, memoryRecallTuningFile))}`}</span>}
            summary="只作用于本轮 visibleContexts 注入 tag_pool 的 injectedScore，不放大旧分数。"
            title="长间隔补偿"
          >
            <div className="form-grid recall-form-grid">
              <NumberField
                label="intervalBonusScale"
                max={10}
                min={0}
                onChange={(next) => onMemoryRecallTuningChange({ intervalBonusScale: next })}
                step={0.05}
                value={memoryRecallTuningFile.intervalBonusScale}
              />
              <NumberField
                label="intervalBonusSteepness"
                max={20}
                min={0.1}
                onChange={(next) => onMemoryRecallTuningChange({ intervalBonusSteepness: next })}
                step={0.1}
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
            <NumberField
              label="synthesizedMemoryHalfLife"
              max={240}
              min={1}
              onChange={(next) => onMemoryRecallTuningChange({ synthesizedMemoryHalfLife: next })}
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
            formula={`effectiveWindow = min(recentRecallWindow, recentRecallWindowCap)\nrecentPenalty = releaseCurve(turnsSincePrompt, effectiveWindow)`}
            stats={
              <>
                <span className="chip">{`effectiveWindow ${effectiveRecentWindow}`}</span>
                <span className="chip">{`turn=0 时 ${formatShortNumber(computeRecentRecallPenalty(0, memoryRecallTuningFile))}`}</span>
              </>
            }
            summary="只在 conscious 显性排序与 implanted fallback 权重里生效；不参与 fermentation 保留公式。"
            title="最近召回抑制"
          >
            <div className="form-grid recall-form-grid">
              <NumberField
                label="recentRecallWindow"
                max={256}
                min={1}
                onChange={(next) => onMemoryRecallTuningChange({ recentRecallWindow: Math.round(next) })}
                round
                step={1}
                value={memoryRecallTuningFile.recentRecallWindow}
              />
              <NumberField
                label="recentRecallWindowCap"
                max={256}
                min={1}
                onChange={(next) => onMemoryRecallTuningChange({ recentRecallWindowCap: Math.round(next) })}
                round
                step={1}
                value={memoryRecallTuningFile.recentRecallWindowCap}
              />
            </div>
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
            <NumberField
              label="promptFillProbabilityScale"
              max={3}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ promptFillProbabilityScale: next })}
              step={0.05}
              value={memoryRecallTuningFile.promptFillProbabilityScale}
            />
            <FormulaChart
              formatX={(value) => formatShortNumber(value)}
              formatY={(value) => formatShortNumber(value)}
              samples={promptFillSamples}
              xLabel="consciousScore"
              yLabel="speakProbability"
            />
          </FormulaCard>

          <FormulaCard
            formula={`desireBonus = desireProfileBonusScale * focusWeight(tag)`}
            stats={<span className="chip">{`focusWeight=1 时 ${formatShortNumber(computeDesireProfileBonus(1, memoryRecallTuningFile))}`}</span>}
            summary="用于 buildFocusProfile，影响 desire tag 在焦点分布里的额外权重。"
            title="渴望 Focus Profile 加权"
          >
            <NumberField
              label="desireProfileBonusScale"
              max={2}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ desireProfileBonusScale: next })}
              step={0.01}
              value={memoryRecallTuningFile.desireProfileBonusScale}
            />
            <FormulaChart
              formatX={(value) => formatShortNumber(value)}
              formatY={(value) => formatShortNumber(value)}
              samples={desireProfileSamples}
              xLabel="focusWeight(tag)"
              yLabel="desireBonus"
            />
          </FormulaCard>

          <FormulaCard
            formula={`desiredFocusBonus = desiredFocusBonusScale * focusWeight(desireTag)`}
            stats={<span className="chip">{`focusWeight=1 时 ${formatShortNumber(computeDesiredFocusBonus(1, memoryRecallTuningFile))}`}</span>}
            summary="用于主召回评分阶段，给携带 desire focus 的记忆追加额外得分。"
            title="渴望记忆评分加成"
          >
            <NumberField
              label="desiredFocusBonusScale"
              max={2}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ desiredFocusBonusScale: next })}
              step={0.01}
              value={memoryRecallTuningFile.desiredFocusBonusScale}
            />
            <FormulaChart
              formatX={(value) => formatShortNumber(value)}
              formatY={(value) => formatShortNumber(value)}
              samples={desiredFocusSamples}
              xLabel="focusWeight(desireTag)"
              yLabel="desiredFocusBonus"
            />
          </FormulaCard>

          <FormulaCard
            formula={`fallbackEntryScore = implantedFallbackEntryScore`}
            stats={<span className="chip">{`当前注入分 ${formatShortNumber(memoryRecallTuningFile.implantedFallbackEntryScore)}`}</span>}
            summary="这不是权重曲线，而是 fallback 记忆被补入 conscious / retained 后的固定注入分。"
            title="植入记忆保底注入分"
          >
            <NumberField
              label="implantedFallbackEntryScore"
              max={3}
              min={0}
              onChange={(next) => onMemoryRecallTuningChange({ implantedFallbackEntryScore: next })}
              step={0.05}
              value={memoryRecallTuningFile.implantedFallbackEntryScore}
            />
            <div className="recall-scalar-meter">
              <div
                className="recall-scalar-meter-fill"
                style={{ width: `${(memoryRecallTuningFile.implantedFallbackEntryScore / 3) * 100}%` }}
              />
            </div>
          </FormulaCard>
        </div>
      </section>

      <div className="two-col runtime-recall-summary">
        <MemoryListBlock emptyText="当前 trace 里没有单独记录 conscious 层。" label="Conscious Memories" memories={consciousMemories} />
        <MemoryListBlock emptyText="当前 trace 里没有 prompt 输出。" label="Prompt Memories" memories={promptMemories} />
      </div>

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
