import { createContext, useContext, useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import { buildTagRecords, studioSeedData } from "./data";
import { DEFAULT_MEMORY_RECALL_TUNING_FILE, sanitizeMemoryRecallTuningFile } from "./memoryRecallModel";
import { RuntimeActionTimelinePage } from "./RuntimeActionTimelinePage";
import { RuntimeRecallGraphPage } from "./RuntimeRecallGraphPage";
import {
  describeWorkspaceAccessMode,
  describeWorkspaceDirectoryHandle,
  ensureDirectoryPermission,
  getDesktopBridgeSource,
  isBrowserDirectoryPickerAvailable,
  isDesktopBridgeReady,
  isDesktopWorkspaceHandle,
  onDesktopWorkspaceChanged,
  pickDirectoryHandle,
  readFileFromDirectoryPath,
  readJsonFileFromDirectory,
  readTextFileFromDirectoryPath,
  startDesktopWorkspaceWatcher,
  stopDesktopWorkspaceWatcher,
  supportsDirectoryAccessApi,
  writeJsonFileToDirectory
} from "./workspaceBridge";
import { pingGameBridge, publishWorkspaceToGame } from "./gameBridge";
import {
  ActionTimelineTuningFile,
  AgentStateRecallTuning,
  AgentStateFile,
  AgentStateWeightKey,
  CustomTagEntry,
  DescriptionEntry,
  DetailTagKind,
  FocusTag,
  ImplantedMemoryEntry,
  ImportanceRegistryFile,
  LocationRegistryEntry,
  MemoryRecallTuningFile,
  NativeCatalogEntry,
  PersonaEntry,
  PromptRegistryFile,
  RuntimeActionSegmentStoreFile,
  RuntimeActionTimelineFile,
  RuntimeRecallTraceFile,
  RuntimeRecentInteractionsFile,
  TagRecord
} from "./types";

type PageId =
  | "catalog"
  | "descriptions"
  | "relations"
  | "persona"
  | "prompts"
  | "importance"
  | "memory"
  | "state"
  | "thoughts"
  | "interactions"
  | "playerActions"
  | "recallGraph"
  | "locations";
type CustomTagKind = Extract<DetailTagKind, "custom_specific" | "custom_generic">;
type RelationBatchOperation = "add" | "remove";
const MEMORY_TICKS_PER_DAY = 24000;
type TagBrowserCategoryId =
  | "all"
  | "custom"
  | "item"
  | "block"
  | "entity"
  | "biome"
  | "structure"
  | "dimension"
  | "fluid"
  | "effect"
  | "enchantment"
  | "generic"
  | "misc";

type CustomTagDraft = {
  kind: CustomTagKind;
  id: string;
  description: string;
};

type CatalogMode =
  | { mode: "list" }
  | { mode: "create"; draft: CustomTagDraft }
  | { mode: "edit"; originalTag: string; draft: CustomTagDraft };

type DescriptionMode =
  | { mode: "list" }
  | { mode: "edit"; tag: string; draft: string };

type PersonaDraft = {
  focus_tags: FocusTag[];
  description: string;
  always_recalled: boolean;
  detail_tags: string[];
};

type PersonaMode =
  | { mode: "list" }
  | { mode: "create"; draft: PersonaDraft }
  | { mode: "edit"; originalId: string; draft: PersonaDraft };

type MemoryDraft = {
  label: string;
  description: string;
  focus_tags: FocusTag[];
  semantic_time_tag: string;
  detail_tags: string[];
  importance: string;
  occurred_day_index: string;
  occurred_time_of_day_tick: string;
  notes: string;
};

type MemoryMode =
  | { mode: "list" }
  | { mode: "create"; draft: MemoryDraft }
  | { mode: "edit"; originalIndex: number; draft: MemoryDraft };

type LocationDraft = {
  name: string;
  x: string;
  y: string;
  z: string;
  dim: string;
  note: string;
  horizontalRadius: string;
  verticalRadius: string;
  focusTag: FocusTag | "";
  detailTags: string[];
};

type LocationMode =
  | { mode: "list" }
  | { mode: "create"; draft: LocationDraft }
  | { mode: "edit"; originalName: string; draft: LocationDraft };

type RelationDraft = {
  sourceTag: string;
  linkedTags: string[];
};

type RelationMode =
  | { mode: "list" }
  | { mode: "create"; draft: RelationDraft }
  | { mode: "edit"; originalSourceTag: string; draft: RelationDraft };

type RuntimeThoughtEntry = {
  id: string;
  interactionTimestamp: number;
  outputTimestamp: number;
  triggerSource: string;
  userPrompt: string;
  hasImage: boolean;
  text: string;
  speechText: string;
  actionTypes: string[];
};

type CustomTagRow = {
  entry: CustomTagEntry;
  id: string;
  description: string;
};

type CustomTagsFile = {
  schemaVersion: number;
  tags: CustomTagEntry[];
};

type TagDescriptionsFile = {
  schemaVersion: number;
  descriptions: Record<string, DescriptionEntry>;
};

type TagRelationsFile = {
  schemaVersion: number;
  relations: Record<string, string[]>;
};

type PersonasFile = {
  schemaVersion: number;
  personas: PersonaEntry[];
};

type ImplantedMemoriesFile = {
  schemaVersion: number;
  memories: ImplantedMemoryEntry[];
};

type AgentStateWorkspaceFile = AgentStateFile;

type NativeCatalogSnapshotFile = {
  schemaVersion: number;
  generatedAt: string;
  generator: {
    modVersion: string;
    minecraftVersion?: string;
    forgeVersion?: string;
  };
  instance: {
    instanceId: string;
    instanceName?: string;
    packVersion?: string;
  };
  entries: NativeCatalogEntry[];
};

type SchemaVersions = {
  importance: number;
  prompts: number;
  customTags: number;
  descriptions: number;
  relations: number;
  personas: number;
  implantedMemories: number;
  agentState: number;
  actionTimelineTuning: number;
  recallTuning: number;
  nativeCatalogSnapshot: number;
};

type ImportFeedback = {
  tone: "neutral" | "good" | "bad";
  message: string;
};

type GameBridgeStatus = {
  state: "checking" | "connected" | "fallback";
  message: string;
};

type WorkspaceDebugInfo = {
  accessMode: string;
  selectedPath: string;
  manifestPresent: "yes" | "no" | "unknown";
  importedParts: string[];
  errorMessage: string;
};

type ImportedWorkspaceData = {
  nativeCatalogSnapshot?: NativeCatalogSnapshotFile | null;
  runtimeInteractions?: RuntimeRecentInteractionsFile | null;
  runtimeActionTimeline?: RuntimeActionTimelineFile | null;
  runtimeActionSegmentStore?: RuntimeActionSegmentStoreFile | null;
  runtimeRecallTrace?: RuntimeRecallTraceFile | null;
  actionTimelineTuning?: ActionTimelineTuningFile | null;
  importance?: ImportanceRegistryFile | null;
  prompts?: PromptRegistryFile | null;
  customTags?: CustomTagsFile | null;
  descriptions?: TagDescriptionsFile | null;
  relations?: TagRelationsFile | null;
  personas?: PersonasFile | null;
  memories?: ImplantedMemoriesFile | null;
  agentState?: AgentStateWorkspaceFile | null;
  memoryRecallTuning?: MemoryRecallTuningFile | null;
  locations?: Record<string, LocationRegistryEntry> | null;
};

type WorkspaceManifestWriter = "minecraft" | "studio" | "unknown";

type WorkspaceManifestVersions = {
  snapshot: string;
  runtimeTagView: string;
  runtimeInteractions: string;
  runtimeActionTimeline: string;
  runtimeActionSegmentStore: string;
  runtimeRecallTrace: string;
  actionTimelineTuning: string;
  memoryRecallTuning: string;
  importance: string;
  prompts: string;
  customTags: string;
  descriptions: string;
  relations: string;
  personas: string;
  memories: string;
  agentState: string;
  locations: string;
  iconsDirectory: string;
};

type StudioWorkspaceManifest = {
  schemaVersion: number;
  generatedAt: string;
  updatedBy: WorkspaceManifestWriter;
  versions: WorkspaceManifestVersions;
};

type WorkspaceImportDescriptor = {
  filename: string;
  versionKey?: keyof WorkspaceManifestVersions;
  normalize: (value: unknown) => unknown | null;
  assign: (importedData: ImportedWorkspaceData, value: unknown) => void;
  describe: (value: unknown) => string;
};

const CUSTOM_TAG_KINDS: DetailTagKind[] = ["custom_specific", "custom_generic"];
const TAG_BROWSER_RESULT_LIMIT = 96;
const TAG_BROWSER_SIDEBAR_PAGE_SIZE = 24;
const DESCRIPTION_PAGE_SIZE = 7;
const TAG_BROWSER_CATEGORY_ORDER: TagBrowserCategoryId[] = [
  "custom",
  "item",
  "block",
  "entity",
  "biome",
  "structure",
  "dimension",
  "fluid",
  "effect",
  "enchantment",
  "generic",
  "misc"
];
const TAG_BROWSER_CATEGORY_LABELS: Record<TagBrowserCategoryId, string> = {
  all: "全部",
  custom: "自定义",
  item: "物品",
  block: "方块",
  entity: "实体",
  biome: "群系",
  structure: "结构",
  dimension: "维度",
  fluid: "流体",
  effect: "效果",
  enchantment: "附魔",
  generic: "泛化",
  misc: "其他"
};
const TAG_BROWSER_ALL_MODS = "__all_mods__";
const TAG_BROWSER_CUSTOM_SOURCE = "__custom_source__";
const IconAssetVersionContext = createContext("seed");

const FOCUS_TAGS: FocusTag[] = [
  "COMBAT",
  "EXPLORE",
  "LABOR",
  "CREATION",
  "CREATURE",
  "SOCIAL",
  "LANDSCAPE",
  "HAZARD",
  "STATUS",
  "CONSUMABLE",
  "BLOCK",
  "TOOL",
  "COMMON"
];

function normalizeFocusTagValue(raw: unknown): FocusTag | null {
  if (typeof raw !== "string") {
    return null;
  }
  const normalized = raw.trim().toUpperCase();
  if (!normalized) {
    return null;
  }
  return FOCUS_TAGS.includes(normalized as FocusTag) ? (normalized as FocusTag) : null;
}

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

const pages: Array<{ id: PageId; label: string; blurb: string }> = [
  { id: "catalog", label: "Custom Tags", blurb: "管理自定义 tag 的 type、id 与 description。" },
  { id: "descriptions", label: "Descriptions", blurb: "统一编辑所有 tag 的自然语言 description。" },
  { id: "relations", label: "Relations", blurb: "管理标签联动引用图、环检测与正则批量操作。" },
  { id: "state", label: "State", blurb: "编辑 agent_state.json，与游戏内 state screen 共用同一份 authored 状态。" },
  { id: "importance", label: "Importance", blurb: "编辑 importance.json，把 base importance 基准接入共享 workspace。" },
  { id: "prompts", label: "Prompts", blurb: "编辑 prompts.json，把触发提示词与系统文案接入共享 workspace。" },
  { id: "thoughts", label: "Thoughts", blurb: "查看每次 inner monologue 的完整记录，而不只看最近一次。" },
  { id: "interactions", label: "Turns", blurb: "按回合查看触发记录、说话输出与 action 列表。" },
  { id: "playerActions", label: "Player Actions", blurb: "查看 moving window、fixed segments、边界动作与 player-action 调参。" },
  { id: "recallGraph", label: "Recall Graph", blurb: "展示最近一次记忆联想的思维导图、触发 tags 与 trace。" },
  { id: "persona", label: "Persona", blurb: "按 focus 管理人格偏好描述与其 detail tags。" },
  { id: "memory", label: "Memory", blurb: "编辑植入记忆、独立时间语义与标签。" },
  { id: "locations", label: "Locations", blurb: "管理 location_registry.json，与游戏内地点注册共用同一份 authored 文件。" }
];

const EMPTY_CUSTOM_TAG_DRAFT: CustomTagDraft = {
  kind: "custom_specific",
  id: "",
  description: ""
};

const EMPTY_PERSONA_DRAFT: PersonaDraft = {
  focus_tags: ["COMMON"],
  description: "",
  always_recalled: false,
  detail_tags: []
};

const EMPTY_MEMORY_DRAFT: MemoryDraft = {
  label: "",
  description: "",
  focus_tags: [],
  semantic_time_tag: "",
  detail_tags: [],
  importance: "",
  occurred_day_index: "",
  occurred_time_of_day_tick: "",
  notes: ""
};

const EMPTY_LOCATION_DRAFT: LocationDraft = {
  name: "",
  x: "0",
  y: "64",
  z: "0",
  dim: "minecraft:overworld",
  note: "",
  horizontalRadius: "24",
  verticalRadius: "16",
  focusTag: "",
  detailTags: []
};

const AGENT_STATE_WEIGHT_KEYS = [
  "combat",
  "explore",
  "labor",
  "creation",
  "creature",
  "social",
  "landscape",
  "hazard",
  "status",
  "consumable",
  "block",
  "tool",
  "common"
] as const;

const PRIMARY_EMOTION_OPTIONS = [
  "NEUTRAL",
  "HAPPY",
  "ANGRY",
  "EXCITED",
  "BORED",
  "FEARFUL",
  "SAD",
  "CURIOUS",
  "ANXIOUS"
] as const;

const PRIMARY_EMOTION_LABELS: Record<string, string> = {
  NEUTRAL: "平静",
  HAPPY: "愉快",
  ANGRY: "愤怒",
  EXCITED: "兴奋",
  BORED: "无聊",
  FEARFUL: "恐惧",
  SAD: "悲伤",
  CURIOUS: "好奇",
  ANXIOUS: "焦虑"
};

const RECALL_PRESET_OPTIONS = ["SEED_FIRST", "FULL_VISIBILITY", "HYBRID", "CUSTOM"] as const;

const RECALL_PRESET_LABELS: Record<string, string> = {
  SEED_FIRST: "种子优先",
  FULL_VISIBILITY: "全量可视",
  HYBRID: "混合",
  CUSTOM: "自定义"
};

const DEFAULT_AGENT_STATE_RECALL_TUNING: AgentStateRecallTuning = {
  main_candidates_per_cue: 6,
  sandbox_cues_per_memory: 6,
  conscious_memory_cap: 3,
  retention_memory_cap: 6
};

const AGENT_STATE_RECALL_TUNING_FIELDS: Array<{
  key: keyof AgentStateRecallTuning;
  label: string;
  min: number;
  max: number;
  note?: string;
}> = [
  {
    key: "main_candidates_per_cue",
    label: "Main Recall Budget",
    min: 1,
    max: 32,
    note: "主召回阶段本轮最多提升多少条候选记忆。"
  },
  {
    key: "sandbox_cues_per_memory",
    label: "Sandbox Cue Budget",
    min: 1,
    max: 32,
    note: "sandbox 联想阶段本轮最多投放多少个联想 cue。"
  },
  {
    key: "conscious_memory_cap",
    label: "Conscious Memory Cap",
    min: 1,
    max: 16,
    note: "最终最多允许多少条记忆进入表层意识。"
  },
  {
    key: "retention_memory_cap",
    label: "Retention Memory Cap",
    min: 1,
    max: 16,
    note: "retained fermentation 层最多保留多少条候选记忆。"
  }
];

const AGENT_STATE_WEIGHT_TO_FOCUS_TAG: Record<AgentStateWeightKey, FocusTag> = {
  combat: "COMBAT",
  explore: "EXPLORE",
  labor: "LABOR",
  creation: "CREATION",
  creature: "CREATURE",
  social: "SOCIAL",
  landscape: "LANDSCAPE",
  hazard: "HAZARD",
  status: "STATUS",
  consumable: "CONSUMABLE",
  block: "BLOCK",
  tool: "TOOL",
  common: "COMMON"
};

function createSeedNativeCatalogSnapshot(): NativeCatalogSnapshotFile {
  return {
    schemaVersion: studioSeedData.schemaVersions.nativeCatalogSnapshot,
    generatedAt: new Date(0).toISOString(),
    generator: {
      modVersion: "studio-seed"
    },
    instance: {
      instanceId: "studio-seed",
      instanceName: "Talking Weapon Studio Seed Data"
    },
    entries: studioSeedData.nativeCatalog
  };
}

function createEmptyRuntimeRecentInteractionsFile(): RuntimeRecentInteractionsFile {
  return {
    schemaVersion: 1,
    generatedAt: "",
    entries: []
  };
}

function createEmptyRuntimeRecallTraceFile(): RuntimeRecallTraceFile {
  return {
    schemaVersion: 5,
    generatedAt: "",
    triggerSource: "",
    userPrompt: "",
    recallPreset: "",
    currentDesireTag: "",
    triggerTags: [],
    traceLines: [],
    visibleContexts: [],
    consciousMemories: [],
    promptMemories: [],
    recalledMemories: [],
    runtimeState: {
      captureMode: "unified-runtime-v1",
      turnCounter: 0,
      currentGameTime: -1,
      previousGameTime: -1,
      deltaGameTime: 0,
      wallClockTime: -1,
      visibleContextCount: 0,
      recalledMemoryCount: 0,
      tagPool: [],
      fermentationBuffer: [],
      consciousMemories: [],
      associationSandbox: {
        active: false,
        cueTags: [],
        sourceMemoryIds: [],
        memoryIds: [],
        hopLimit: 1,
        recallBudget: 0
      }
    },
    timings: {
      totalMillis: 0,
      focusProfileMillis: 0,
      cueBuildMillis: 0,
      candidateCollectionMillis: 0,
      promotionSelectionMillis: 0,
      retainedSelectionMillis: 0,
      consciousSelectionMillis: 0,
      implantedFallbackMillis: 0,
      promptFillMillis: 0,
      sandboxMillis: 0,
      stateCommitMillis: 0,
      resultAssemblyMillis: 0
    }
  };
}

function createEmptyActionTimelineTuningFile(): ActionTimelineTuningFile {
  return {
    schemaVersion: 3,
    generatedAt: "",
    fixedSegmentSeconds: 60,
    movingWindowSeconds: 180,
    registeredLocationLeaveExtraBlocks: 32,
    majorStructureEnterRadiusBlocks: 24,
    majorStructureExitRadiusBlocks: 48,
    majorStructureWhitelist: []
  };
}

function createEmptyRuntimeActionTimelineFile(): RuntimeActionTimelineFile {
  return {
    schemaVersion: 7,
    generatedAt: "",
    fixedSegmentSeconds: 60,
    movingWindowSeconds: 180,
    registeredLocationLeaveExtraBlocks: 32,
    majorStructureEnterRadiusBlocks: 24,
    majorStructureExitRadiusBlocks: 48,
    majorStructureWhitelist: [],
    rawActionCount: 0,
    fixedSegmentCount: 0,
    fixedSegments: [],
    movingWindow: {
      headline: "",
      summary: "",
      dominantIntent: "",
      dominantScore: 0,
      runnerUpIntent: "",
      runnerUpScore: 0,
      ambiguous: false,
      scores: {},
      reasons: {},
      startTimestamp: 0,
      endTimestamp: 0,
      open: false,
      entries: []
    }
  };
}

function createEmptyMemoryRecallTuningFile(): MemoryRecallTuningFile {
  return {
    ...DEFAULT_MEMORY_RECALL_TUNING_FILE
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringArrayOf(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string").map((item) => item.trim()).filter(Boolean)
    : [];
}

function isNativeCatalogEntryArray(value: unknown): value is NativeCatalogEntry[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry) =>
        isRecord(entry) &&
        typeof entry.tag === "string" &&
        typeof entry.kind === "string" &&
        typeof entry.registryType === "string"
    )
  );
}

function isCustomTagEntryArray(value: unknown): value is CustomTagEntry[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry) =>
        isRecord(entry) &&
        typeof entry.tag === "string" &&
        typeof entry.kind === "string" &&
        typeof entry.label === "string"
    )
  );
}

function isPersonaEntryArray(value: unknown): value is PersonaEntry[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry) =>
        isRecord(entry) &&
        typeof entry.id === "string" &&
        (Array.isArray(entry.focus_tags)
          ? entry.focus_tags.every((focusTag) => typeof focusTag === "string")
          : typeof entry.associated_focus === "string") &&
        typeof entry.description === "string"
    )
  );
}

function isImplantedMemoryEntryArray(value: unknown): value is ImplantedMemoryEntry[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry) =>
        isRecord(entry) &&
        typeof entry.description === "string"
    )
  );
}

function normalizePromptRegistryFile(value: unknown): PromptRegistryFile | null {
  if (!isRecord(value)) {
    return null;
  }

  const entries = Object.fromEntries(
    Object.entries(value).filter(
      ([key, entry]) => !key.startsWith("_comment") && typeof entry === "string"
    )
  ) as PromptRegistryFile;

  return entries;
}

function normalizeImportanceRegistryFile(value: unknown): ImportanceRegistryFile | null {
  if (!isRecord(value)) {
    return null;
  }

  const entries = Object.fromEntries(
    Object.entries(value).filter(
      ([key, entry]) => !key.startsWith("_comment") && typeof entry === "number"
    )
  ) as ImportanceRegistryFile;

  return entries;
}

function isDescriptionMap(value: unknown): value is Record<string, DescriptionEntry> {
  return (
    isRecord(value) &&
    Object.values(value).every(
      (entry) => isRecord(entry) && typeof entry.description === "string"
    )
  );
}

function isRelationMap(value: unknown): value is Record<string, string[]> {
  return (
    isRecord(value) &&
    Object.values(value).every(
      (entry) => Array.isArray(entry) && entry.every((item) => typeof item === "string")
    )
  );
}

function normalizeCustomTagsFile(value: unknown): CustomTagsFile | null {
  if (!isRecord(value) || !isCustomTagEntryArray(value.tags)) {
    return null;
  }
  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 2,
    tags: value.tags
  };
}

function normalizeTagDescriptionsFile(value: unknown): TagDescriptionsFile | null {
  if (isRecord(value) && isDescriptionMap(value.descriptions)) {
    return {
      schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
      descriptions: value.descriptions
    };
  }
  if (!isRecord(value)) {
    return null;
  }

  const descriptions = Object.fromEntries(
    Object.entries(value).filter(
      ([key, entry]) => !key.startsWith("_comment") && isRecord(entry) && typeof entry.description === "string"
    )
  ) as Record<string, DescriptionEntry>;

  return Object.keys(descriptions).length > 0
    ? {
        schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
        descriptions
      }
    : null;
}

function normalizeTagRelationsFile(value: unknown): TagRelationsFile | null {
  if (isRecord(value) && isRelationMap(value.relations)) {
    return {
      schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
      relations: value.relations
    };
  }
  if (!isRecord(value)) {
    return null;
  }

  const relations = Object.fromEntries(
    Object.entries(value).filter(
      ([key, entry]) =>
        !key.startsWith("_comment") &&
        Array.isArray(entry) &&
        entry.every((item) => typeof item === "string")
    )
  ) as Record<string, string[]>;

  return Object.keys(relations).length > 0
    ? {
        schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
        relations
      }
    : null;
}

function normalizePersonasFile(value: unknown): PersonasFile | null {
  if (!isRecord(value) || !isPersonaEntryArray(value.personas)) {
    return null;
  }
  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 4,
    personas: value.personas.map((entry) => ({
      ...entry,
      focus_tags: normalizePersonaFocusTags(entry.focus_tags, entry.associated_focus)
    }))
  };
}

function normalizeImplantedMemoriesFile(value: unknown): ImplantedMemoriesFile | null {
  if (isImplantedMemoryEntryArray(value)) {
    return {
      schemaVersion: 1,
      memories: value
    };
  }
  if (!isRecord(value) || !isImplantedMemoryEntryArray(value.memories)) {
    return null;
  }
  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
    memories: value.memories
  };
}

function normalizeAgentStateWorkspaceFile(value: unknown): AgentStateWorkspaceFile | null {
  if (!isRecord(value) || !isRecord(value.state) || !isRecord(value.persona_state) || !isRecord(value.attention_weights)) {
    return null;
  }

  const state = value.state;
  const personaState = value.persona_state;
  const attentionWeights = value.attention_weights;

  if ("association_mode" in state || "custom_recall" in state) {
    return null;
  }

  const currentDesireTag = normalizeFocusTagValue(state.current_desire_tag);

  return {
    schemaVersion: typeof value.schemaVersion === "number" ? Math.max(value.schemaVersion, 4) : 4,
    state: {
      primary_emotion: typeof state.primary_emotion === "string" ? state.primary_emotion : "NEUTRAL",
      emotion_intensity: typeof state.emotion_intensity === "number" ? state.emotion_intensity : 0,
      familiarity: typeof state.familiarity === "number" ? state.familiarity : 0,
      player_address: typeof state.player_address === "string" ? state.player_address : "主人",
      concentration: typeof state.concentration === "number" ? state.concentration : 0.5,
      wandering_mind: typeof state.wandering_mind === "number" ? state.wandering_mind : 0.1,
      nostalgia: typeof state.nostalgia === "number" ? state.nostalgia : 0.5,
      recall_preset: typeof state.recall_preset === "string" ? state.recall_preset : "HYBRID",
      recall_tuning: normalizeAgentStateRecallTuning(state.recall_tuning),
      implanted_memory_safety_net:
        typeof state.implanted_memory_safety_net === "boolean"
          ? state.implanted_memory_safety_net
          : false,
      current_desire_tag: currentDesireTag,
      desire_description: typeof state.desire_description === "string" ? state.desire_description : ""
    },
    persona_state: {
      favorability: typeof personaState.favorability === "number" ? personaState.favorability : 0,
      player_profile: typeof personaState.player_profile === "string" ? personaState.player_profile : ""
    },
    attention_weights: {
      ...(Object.fromEntries(
        AGENT_STATE_WEIGHT_KEYS.map((key) => [key, typeof attentionWeights[key] === "number" ? attentionWeights[key] : 1])
      ) as Record<AgentStateWeightKey, number>),
      crosshair_bonus_enabled:
        typeof attentionWeights.crosshair_bonus_enabled === "boolean"
          ? attentionWeights.crosshair_bonus_enabled
          : true
    }
  };
}

function normalizeLocationRegistryData(value: unknown): Record<string, LocationRegistryEntry> | null {
  if (!isRecord(value)) {
    return null;
  }

  const source =
    isRecord(value.locations)
      ? value.locations
      : value;

  const normalizedEntries = Object.entries(source)
    .filter(([name, rawEntry]) => !name.startsWith("_comment") && name !== "schemaVersion" && isRecord(rawEntry))
    .map(([name, rawEntry]) => {
      const entry = rawEntry as Record<string, unknown>;
      const detailTagsSource = entry.detailTags;
      const detailTags =
        Array.isArray(detailTagsSource)
          ? detailTagsSource.filter((tag): tag is string => typeof tag === "string" && tag.trim().length > 0)
          : typeof detailTagsSource === "string"
            ? detailTagsSource.split(",").map((tag) => tag.trim()).filter(Boolean)
            : [];

      const focusTag = normalizeFocusTagValue(entry.focusTag) ?? "";

      return [
        name.trim().toLowerCase(),
        {
          x: typeof entry.x === "number" ? Math.round(entry.x) : 0,
          y: typeof entry.y === "number" ? Math.round(entry.y) : 64,
          z: typeof entry.z === "number" ? Math.round(entry.z) : 0,
          dim: typeof entry.dim === "string" && entry.dim.trim() ? entry.dim.trim() : "minecraft:overworld",
          note: typeof entry.note === "string" ? entry.note : "",
          horizontalRadius:
            typeof entry.horizontalRadius === "number" && entry.horizontalRadius > 0
              ? Math.round(entry.horizontalRadius)
              : 24,
          verticalRadius:
            typeof entry.verticalRadius === "number" && entry.verticalRadius > 0
              ? Math.round(entry.verticalRadius)
              : 16,
          focusTag,
          detailTags
        } satisfies LocationRegistryEntry
      ] as const;
    });

  return Object.fromEntries(normalizedEntries);
}

function clampNumber(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function normalizeActionTimelineTuningFile(value: unknown): ActionTimelineTuningFile | null {
  if (!isRecord(value)) {
    return null;
  }

  const majorStructureEnterRadiusBlocks =
    typeof value.majorStructureEnterRadiusBlocks === "number"
      ? Math.round(clampNumber(value.majorStructureEnterRadiusBlocks, 0, 256))
      : 24;
  const rawWhitelist = Array.from(new Set(stringArrayOf(value.majorStructureWhitelist)));

  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 6,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    fixedSegmentSeconds:
      typeof value.fixedSegmentSeconds === "number"
        ? Math.round(clampNumber(value.fixedSegmentSeconds, 10, 300))
        : 60,
    movingWindowSeconds:
      typeof value.movingWindowSeconds === "number"
        ? Math.round(clampNumber(value.movingWindowSeconds, 30, 480))
        : 180,
    registeredLocationLeaveExtraBlocks:
      typeof value.registeredLocationLeaveExtraBlocks === "number"
        ? Math.round(clampNumber(value.registeredLocationLeaveExtraBlocks, 0, 256))
        : 32,
    majorStructureEnterRadiusBlocks,
    majorStructureExitRadiusBlocks:
      typeof value.majorStructureExitRadiusBlocks === "number"
        ? Math.round(clampNumber(value.majorStructureExitRadiusBlocks, majorStructureEnterRadiusBlocks, 256))
        : Math.max(48, majorStructureEnterRadiusBlocks),
    majorStructureWhitelist: rawWhitelist
  };
}

function normalizeRuntimeActionTimelineScores(value: unknown): Record<string, number> {
  if (!isRecord(value)) {
    return {};
  }

  return Object.fromEntries(
    Object.entries(value).filter(([, entry]) => typeof entry === "number")
  ) as Record<string, number>;
}

function normalizeRuntimeActionTimelineReasons(value: unknown): Record<string, string[]> {
  if (!isRecord(value)) {
    return {};
  }

  return Object.fromEntries(
    Object.entries(value).map(([key, entry]) => [key, stringArrayOf(entry)])
  );
}

function normalizeRuntimeActionTimelineEntry(value: unknown): RuntimeActionTimelineFile["fixedSegments"][number]["entries"][number] | null {
  if (!isRecord(value)) {
    return null;
  }

  return {
    type: typeof value.type === "string" ? value.type : "",
    target: typeof value.target === "string" ? value.target : "",
    count: typeof value.count === "number" ? value.count : 0,
    firstTimestamp: typeof value.firstTimestamp === "number" ? value.firstTimestamp : 0,
    lastTimestamp: typeof value.lastTimestamp === "number" ? value.lastTimestamp : 0,
    rawStartIndex: typeof value.rawStartIndex === "number" ? value.rawStartIndex : -1,
    rawEndIndex: typeof value.rawEndIndex === "number" ? value.rawEndIndex : -1,
    detailTags: stringArrayOf(value.detailTags),
    folded: value.folded === true,
    foldedTargets: stringArrayOf(value.foldedTargets)
  };
}

function normalizeRuntimeActionTimelineIntentCarrier(value: unknown): {
  headline: string;
  summary: string;
  dominantIntent: string;
  dominantScore: number;
  runnerUpIntent: string;
  runnerUpScore: number;
  ambiguous: boolean;
  scores: Record<string, number>;
  reasons: Record<string, string[]>;
} {
  if (!isRecord(value)) {
    return {
      headline: "",
      summary: "",
      dominantIntent: "",
      dominantScore: 0,
      runnerUpIntent: "",
      runnerUpScore: 0,
      ambiguous: false,
      scores: {},
      reasons: {}
    };
  }

  return {
    headline: typeof value.headline === "string" ? value.headline : "",
    summary: typeof value.summary === "string" ? value.summary : "",
    dominantIntent: typeof value.dominantIntent === "string" ? value.dominantIntent : "",
    dominantScore: typeof value.dominantScore === "number" ? value.dominantScore : 0,
    runnerUpIntent: typeof value.runnerUpIntent === "string" ? value.runnerUpIntent : "",
    runnerUpScore: typeof value.runnerUpScore === "number" ? value.runnerUpScore : 0,
    ambiguous: value.ambiguous === true,
    scores: normalizeRuntimeActionTimelineScores(value.scores),
    reasons: normalizeRuntimeActionTimelineReasons(value.reasons)
  };
}

function normalizeRuntimeActionTimelineFixedSegment(value: unknown): RuntimeActionTimelineFile["fixedSegments"][number] | null {
  if (!isRecord(value)) {
    return null;
  }

  const carrier = normalizeRuntimeActionTimelineIntentCarrier(value);
  return {
    ...carrier,
    index: typeof value.index === "number" ? value.index : -1,
    segmentId: typeof value.segmentId === "number" ? value.segmentId : -1,
    startTimestamp: typeof value.startTimestamp === "number" ? value.startTimestamp : 0,
    endTimestamp: typeof value.endTimestamp === "number" ? value.endTimestamp : 0,
    rawStartIndex: typeof value.rawStartIndex === "number" ? value.rawStartIndex : -1,
    rawEndIndex: typeof value.rawEndIndex === "number" ? value.rawEndIndex : -1,
    observedActionTypes: stringArrayOf(value.observedActionTypes),
    boundaryActions: stringArrayOf(value.boundaryActions),
    entries: Array.isArray(value.entries)
      ? value.entries
          .map((entry) => normalizeRuntimeActionTimelineEntry(entry))
          .filter((entry): entry is NonNullable<typeof entry> => entry !== null)
      : []
  };
}

function normalizeRuntimeActionTimelineMovingWindow(value: unknown): RuntimeActionTimelineFile["movingWindow"] {
  const fallback = createEmptyRuntimeActionTimelineFile().movingWindow;
  if (!isRecord(value)) {
    return fallback;
  }

  const carrier = normalizeRuntimeActionTimelineIntentCarrier(value);
  return {
    ...carrier,
    startTimestamp: typeof value.startTimestamp === "number" ? value.startTimestamp : 0,
    endTimestamp: typeof value.endTimestamp === "number" ? value.endTimestamp : 0,
    open: value.open === true,
    entries: Array.isArray(value.entries)
      ? value.entries
          .map((entry) => normalizeRuntimeActionTimelineEntry(entry))
          .filter((entry): entry is NonNullable<typeof entry> => entry !== null)
      : []
  };
}

function normalizeRuntimeActionTimelineFile(value: unknown): RuntimeActionTimelineFile | null {
  if (!isRecord(value)) {
    return null;
  }

  const majorStructureEnterRadiusBlocks =
    typeof value.majorStructureEnterRadiusBlocks === "number"
      ? Math.round(clampNumber(value.majorStructureEnterRadiusBlocks, 0, 256))
      : 24;

  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 7,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    fixedSegmentSeconds:
      typeof value.fixedSegmentSeconds === "number"
        ? Math.round(clampNumber(value.fixedSegmentSeconds, 10, 300))
        : 60,
    movingWindowSeconds:
      typeof value.movingWindowSeconds === "number"
        ? Math.round(clampNumber(value.movingWindowSeconds, 30, 480))
        : 180,
    registeredLocationLeaveExtraBlocks:
      typeof value.registeredLocationLeaveExtraBlocks === "number"
        ? Math.round(clampNumber(value.registeredLocationLeaveExtraBlocks, 0, 256))
        : 32,
    majorStructureEnterRadiusBlocks,
    majorStructureExitRadiusBlocks:
      typeof value.majorStructureExitRadiusBlocks === "number"
        ? Math.round(clampNumber(value.majorStructureExitRadiusBlocks, majorStructureEnterRadiusBlocks, 256))
        : Math.max(48, majorStructureEnterRadiusBlocks),
    majorStructureWhitelist: Array.from(new Set(stringArrayOf(value.majorStructureWhitelist))),
    rawActionCount: typeof value.rawActionCount === "number" ? value.rawActionCount : 0,
    fixedSegmentCount: typeof value.fixedSegmentCount === "number" ? value.fixedSegmentCount : 0,
    fixedSegments: [],
    movingWindow: normalizeRuntimeActionTimelineMovingWindow(value.movingWindow)
  };
}

function normalizeRuntimeActionSegmentStoreFile(value: unknown): RuntimeActionSegmentStoreFile | null {
  if (!isRecord(value)) {
    return null;
  }

  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 2,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    sequenceToken: typeof value.sequenceToken === "string" ? value.sequenceToken : "",
    fixedSegmentCount: typeof value.fixedSegmentCount === "number" ? value.fixedSegmentCount : 0,
    dataFilename: typeof value.dataFilename === "string" ? value.dataFilename : "",
    fixedSegments: []
  };
}

function parseRuntimeActionSegmentStoreData(
  text: string | null | undefined
): RuntimeActionTimelineFile["fixedSegments"] {
  if (!text) {
    return [];
  }

  const segments: RuntimeActionTimelineFile["fixedSegments"] = [];
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      const normalized = normalizeRuntimeActionTimelineFixedSegment(parsed);
      if (normalized) {
        segments.push(normalized);
      }
    } catch {
      continue;
    }
  }
  return segments;
}

function hydrateRuntimeActionTimeline(
  timeline: RuntimeActionTimelineFile,
  segmentStore?: RuntimeActionSegmentStoreFile | null,
  currentTimeline?: RuntimeActionTimelineFile | null
): RuntimeActionTimelineFile {
  const preferredSegments = segmentStore
    ? segmentStore.fixedSegments
    : currentTimeline?.fixedSegments ?? [];

  return {
    ...timeline,
    fixedSegmentCount: Math.max(timeline.fixedSegmentCount, preferredSegments.length),
    fixedSegments: preferredSegments
  };
}

function normalizeAgentStateRecallTuning(value: unknown): AgentStateRecallTuning {
  const source = isRecord(value) ? value : {};

  return {
    main_candidates_per_cue:
      typeof source.main_candidates_per_cue === "number"
        ? Math.round(clampNumber(source.main_candidates_per_cue, 1, 32))
        : DEFAULT_AGENT_STATE_RECALL_TUNING.main_candidates_per_cue,
    sandbox_cues_per_memory:
      typeof source.sandbox_cues_per_memory === "number"
        ? Math.round(clampNumber(source.sandbox_cues_per_memory, 1, 32))
        : DEFAULT_AGENT_STATE_RECALL_TUNING.sandbox_cues_per_memory,
    conscious_memory_cap:
      typeof source.conscious_memory_cap === "number"
        ? Math.round(clampNumber(source.conscious_memory_cap, 1, 16))
        : DEFAULT_AGENT_STATE_RECALL_TUNING.conscious_memory_cap,
    retention_memory_cap:
      typeof source.retention_memory_cap === "number"
        ? Math.round(clampNumber(source.retention_memory_cap, 1, 16))
        : DEFAULT_AGENT_STATE_RECALL_TUNING.retention_memory_cap
  };
}

function sanitizeAgentStateFile(file: AgentStateWorkspaceFile): AgentStateWorkspaceFile {
  const currentDesireTag = normalizeFocusTagValue(file.state.current_desire_tag);

  return {
    schemaVersion: file.schemaVersion > 0 ? Math.max(file.schemaVersion, 4) : 4,
    state: {
      primary_emotion:
        typeof file.state.primary_emotion === "string" && file.state.primary_emotion.trim()
          ? file.state.primary_emotion.trim().toUpperCase()
          : "NEUTRAL",
      emotion_intensity: Math.round(clampNumber(file.state.emotion_intensity, 0, 100)),
      familiarity: Math.max(0, Math.round(file.state.familiarity)),
      player_address:
        typeof file.state.player_address === "string" && file.state.player_address.trim()
          ? file.state.player_address.trim()
          : "主人",
      concentration: clampNumber(file.state.concentration, 0, 1),
      wandering_mind: clampNumber(file.state.wandering_mind, 0, 1),
      nostalgia: clampNumber(file.state.nostalgia, 0, 2),
      recall_preset:
        typeof file.state.recall_preset === "string" && file.state.recall_preset.trim()
          ? file.state.recall_preset.trim().toUpperCase()
          : "HYBRID",
      recall_tuning: normalizeAgentStateRecallTuning(file.state.recall_tuning),
      implanted_memory_safety_net: Boolean(file.state.implanted_memory_safety_net),
      current_desire_tag: currentDesireTag,
      desire_description: typeof file.state.desire_description === "string" ? file.state.desire_description : ""
    },
    persona_state: {
      favorability: Math.round(clampNumber(file.persona_state.favorability, -100, 100)),
      player_profile: typeof file.persona_state.player_profile === "string" ? file.persona_state.player_profile : ""
    },
    attention_weights: {
      ...(Object.fromEntries(
        AGENT_STATE_WEIGHT_KEYS.map((key) => [key, clampNumber(file.attention_weights[key], 0, 10)])
      ) as Record<AgentStateWeightKey, number>),
      crosshair_bonus_enabled:
        typeof file.attention_weights.crosshair_bonus_enabled === "boolean"
          ? file.attention_weights.crosshair_bonus_enabled
          : true
    }
  };
}

function normalizeNativeCatalogSnapshotFile(value: unknown): NativeCatalogSnapshotFile | null {
  if (!isRecord(value) || !isNativeCatalogEntryArray(value.entries)) {
    return null;
  }
  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : new Date().toISOString(),
    generator: isRecord(value.generator)
      ? {
          modVersion: typeof value.generator.modVersion === "string" ? value.generator.modVersion : "unknown",
          minecraftVersion:
            typeof value.generator.minecraftVersion === "string" ? value.generator.minecraftVersion : undefined,
          forgeVersion: typeof value.generator.forgeVersion === "string" ? value.generator.forgeVersion : undefined
        }
      : { modVersion: "unknown" },
    instance: isRecord(value.instance)
      ? {
          instanceId: typeof value.instance.instanceId === "string" ? value.instance.instanceId : "imported-instance",
          instanceName: typeof value.instance.instanceName === "string" ? value.instance.instanceName : undefined,
          packVersion: typeof value.instance.packVersion === "string" ? value.instance.packVersion : undefined
        }
      : { instanceId: "imported-instance" },
    entries: value.entries
  };
}

function normalizeRuntimeRecentInteractionsFile(value: unknown): RuntimeRecentInteractionsFile | null {
  if (!isRecord(value) || !Array.isArray(value.entries)) {
    return null;
  }

  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    entries: value.entries
      .filter(isRecord)
      .map((entry) => ({
        timestamp: typeof entry.timestamp === "number" ? entry.timestamp : 0,
        triggerSource: typeof entry.triggerSource === "string" ? entry.triggerSource : "",
        userPrompt: typeof entry.userPrompt === "string" ? entry.userPrompt : "",
        hasImage: entry.hasImage === true,
        actionTypes: stringArrayOf(entry.actionTypes),
        outputs: Array.isArray(entry.outputs)
          ? entry.outputs
              .filter(isRecord)
              .map((output) => ({
                type: typeof output.type === "string" ? output.type : "",
                text: typeof output.text === "string" ? output.text : "",
                timestamp: typeof output.timestamp === "number" ? output.timestamp : 0
              }))
          : []
      }))
  };
}

function normalizeMemoryRecallTuningFile(value: unknown): MemoryRecallTuningFile | null {
  if (!isRecord(value)) {
    return null;
  }

  return sanitizeMemoryRecallTuningFile({
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 4,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    mainCandidatesPerCue:
      typeof value.mainCandidatesPerCue === "number"
        ? value.mainCandidatesPerCue
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.mainCandidatesPerCue,
    sandboxCuesPerMemory:
      typeof value.sandboxCuesPerMemory === "number"
        ? value.sandboxCuesPerMemory
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.sandboxCuesPerMemory,
    consciousMemoryCap:
      typeof value.consciousMemoryCap === "number"
        ? value.consciousMemoryCap
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.consciousMemoryCap,
    retentionMemoryCap:
      typeof value.retentionMemoryCap === "number"
        ? value.retentionMemoryCap
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.retentionMemoryCap,
    recentRecallWindow:
      typeof value.recentRecallWindow === "number"
        ? value.recentRecallWindow
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.recentRecallWindow,
    recentRecallWindowCap:
      typeof value.recentRecallWindowCap === "number"
        ? value.recentRecallWindowCap
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.recentRecallWindowCap,
    implantedFallbackEntryScore:
      typeof value.implantedFallbackEntryScore === "number"
        ? value.implantedFallbackEntryScore
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.implantedFallbackEntryScore,
    promptFillProbabilityScale:
      typeof value.promptFillProbabilityScale === "number"
        ? value.promptFillProbabilityScale
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.promptFillProbabilityScale,
    tagPoolHalfLifeDays:
      typeof value.tagPoolHalfLifeDays === "number"
        ? value.tagPoolHalfLifeDays
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.tagPoolHalfLifeDays,
    tagPoolDecayShape:
      typeof value.tagPoolDecayShape === "number"
        ? value.tagPoolDecayShape
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.tagPoolDecayShape,
    tagPoolDropThreshold:
      typeof value.tagPoolDropThreshold === "number"
        ? value.tagPoolDropThreshold
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.tagPoolDropThreshold,
    tagPoolReheatPulseGain:
      typeof value.tagPoolReheatPulseGain === "number"
        ? value.tagPoolReheatPulseGain
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.tagPoolReheatPulseGain,
    tagPoolReheatDecayPerHit:
      typeof value.tagPoolReheatDecayPerHit === "number"
        ? value.tagPoolReheatDecayPerHit
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.tagPoolReheatDecayPerHit,
    tagPoolReheatFloor:
      typeof value.tagPoolReheatFloor === "number"
        ? value.tagPoolReheatFloor
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.tagPoolReheatFloor,
    fermentationHalfLifeDays:
      typeof value.fermentationHalfLifeDays === "number"
        ? value.fermentationHalfLifeDays
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.fermentationHalfLifeDays,
    fermentationDecayShape:
      typeof value.fermentationDecayShape === "number"
        ? value.fermentationDecayShape
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.fermentationDecayShape,
    fermentationDropThreshold:
      typeof value.fermentationDropThreshold === "number"
        ? value.fermentationDropThreshold
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.fermentationDropThreshold,
    intervalBonusScale:
      typeof value.intervalBonusScale === "number"
        ? value.intervalBonusScale
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.intervalBonusScale,
    intervalBonusSteepness:
      typeof value.intervalBonusSteepness === "number"
        ? value.intervalBonusSteepness
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.intervalBonusSteepness,
    synthesizedMemoryHalfLife:
      typeof value.synthesizedMemoryHalfLife === "number"
        ? value.synthesizedMemoryHalfLife
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.synthesizedMemoryHalfLife,
    desireProfileBonusScale:
      typeof value.desireProfileBonusScale === "number"
        ? value.desireProfileBonusScale
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.desireProfileBonusScale,
    desiredFocusBonusScale:
      typeof value.desiredFocusBonusScale === "number"
        ? value.desiredFocusBonusScale
        : DEFAULT_MEMORY_RECALL_TUNING_FILE.desiredFocusBonusScale
  });
}

function normalizeRuntimeRecallTraceFile(value: unknown): RuntimeRecallTraceFile | null {
  if (!isRecord(value)) {
    return null;
  }

  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 5,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    triggerSource: typeof value.triggerSource === "string" ? value.triggerSource : "",
    userPrompt: typeof value.userPrompt === "string" ? value.userPrompt : "",
    recallPreset: typeof value.recallPreset === "string" ? value.recallPreset : "",
    currentDesireTag: typeof value.currentDesireTag === "string" ? value.currentDesireTag : "",
    triggerTags: stringArrayOf(value.triggerTags),
    traceLines: stringArrayOf(value.traceLines),
    visibleContexts: Array.isArray(value.visibleContexts)
      ? value.visibleContexts
          .filter(isRecord)
          .map((entry) => ({
            description: typeof entry.description === "string" ? entry.description : "",
            focusTags: stringArrayOf(entry.focusTags),
            detailTags: stringArrayOf(entry.detailTags),
            baseImportance: typeof entry.baseImportance === "number" ? entry.baseImportance : 0,
            finalScore: typeof entry.finalScore === "number" ? entry.finalScore : 0
          }))
      : [],
    consciousMemories: Array.isArray(value.consciousMemories)
      ? value.consciousMemories
          .filter(isRecord)
          .map((entry) => ({
            id: typeof entry.id === "string" ? entry.id : "",
            description: typeof entry.description === "string" ? entry.description : "",
            focusTags: stringArrayOf(entry.focusTags),
            detailTags: stringArrayOf(entry.detailTags),
            semanticTimeTag: typeof entry.semanticTimeTag === "string" ? entry.semanticTimeTag : "",
            semanticTimeLabel: typeof entry.semanticTimeLabel === "string" ? entry.semanticTimeLabel : ""
          }))
      : [],
    promptMemories: Array.isArray(value.promptMemories)
      ? value.promptMemories
          .filter(isRecord)
          .map((entry) => ({
            id: typeof entry.id === "string" ? entry.id : "",
            description: typeof entry.description === "string" ? entry.description : "",
            focusTags: stringArrayOf(entry.focusTags),
            detailTags: stringArrayOf(entry.detailTags),
            semanticTimeTag: typeof entry.semanticTimeTag === "string" ? entry.semanticTimeTag : "",
            semanticTimeLabel: typeof entry.semanticTimeLabel === "string" ? entry.semanticTimeLabel : ""
          }))
      : [],
    recalledMemories: Array.isArray(value.recalledMemories)
      ? value.recalledMemories
          .filter(isRecord)
          .map((entry) => ({
            id: typeof entry.id === "string" ? entry.id : "",
            description: typeof entry.description === "string" ? entry.description : "",
            focusTags: stringArrayOf(entry.focusTags),
            detailTags: stringArrayOf(entry.detailTags),
            semanticTimeTag: typeof entry.semanticTimeTag === "string" ? entry.semanticTimeTag : "",
            semanticTimeLabel: typeof entry.semanticTimeLabel === "string" ? entry.semanticTimeLabel : ""
          }))
      : [],
    runtimeState: normalizeRuntimeRecallRuntimeState(value.runtimeState),
    timings: normalizeRuntimeRecallTimings(value.timings)
  };
}

function normalizeRuntimeRecallTimings(value: unknown): RuntimeRecallTraceFile["timings"] {
  const empty = createEmptyRuntimeRecallTraceFile().timings;
  if (!isRecord(value)) {
    return empty;
  }

  return {
    totalMillis: typeof value.totalMillis === "number" ? value.totalMillis : 0,
    focusProfileMillis: typeof value.focusProfileMillis === "number" ? value.focusProfileMillis : 0,
    cueBuildMillis: typeof value.cueBuildMillis === "number" ? value.cueBuildMillis : 0,
    candidateCollectionMillis: typeof value.candidateCollectionMillis === "number" ? value.candidateCollectionMillis : 0,
    promotionSelectionMillis: typeof value.promotionSelectionMillis === "number" ? value.promotionSelectionMillis : 0,
    retainedSelectionMillis: typeof value.retainedSelectionMillis === "number" ? value.retainedSelectionMillis : 0,
    consciousSelectionMillis: typeof value.consciousSelectionMillis === "number" ? value.consciousSelectionMillis : 0,
    implantedFallbackMillis: typeof value.implantedFallbackMillis === "number" ? value.implantedFallbackMillis : 0,
    promptFillMillis: typeof value.promptFillMillis === "number" ? value.promptFillMillis : 0,
    sandboxMillis: typeof value.sandboxMillis === "number" ? value.sandboxMillis : 0,
    stateCommitMillis: typeof value.stateCommitMillis === "number" ? value.stateCommitMillis : 0,
    resultAssemblyMillis: typeof value.resultAssemblyMillis === "number" ? value.resultAssemblyMillis : 0
  };
}

function normalizeRuntimeRecallRuntimeState(value: unknown): RuntimeRecallTraceFile["runtimeState"] {
  if (!isRecord(value)) {
    return createEmptyRuntimeRecallTraceFile().runtimeState;
  }

  return {
    captureMode: typeof value.captureMode === "string" ? value.captureMode : "unified-runtime-v1",
    turnCounter: typeof value.turnCounter === "number" ? value.turnCounter : 0,
    currentGameTime: typeof value.currentGameTime === "number" ? value.currentGameTime : -1,
    previousGameTime: typeof value.previousGameTime === "number" ? value.previousGameTime : -1,
    deltaGameTime: typeof value.deltaGameTime === "number" ? value.deltaGameTime : 0,
    wallClockTime: typeof value.wallClockTime === "number" ? value.wallClockTime : -1,
    visibleContextCount: typeof value.visibleContextCount === "number" ? value.visibleContextCount : 0,
    recalledMemoryCount: typeof value.recalledMemoryCount === "number" ? value.recalledMemoryCount : 0,
    tagPool: Array.isArray(value.tagPool)
      ? value.tagPool
          .filter(isRecord)
          .map((entry) => ({
            tag: typeof entry.tag === "string" ? entry.tag : "",
            score: typeof entry.score === "number" ? entry.score : 0,
            sourceCount: typeof entry.sourceCount === "number" ? entry.sourceCount : 0,
            focusTags: stringArrayOf(entry.focusTags),
            lastInjectedGameTime:
              typeof entry.lastInjectedGameTime === "number" ? entry.lastInjectedGameTime : -1,
            lastMatchedGameTime:
              typeof entry.lastMatchedGameTime === "number" ? entry.lastMatchedGameTime : -1,
            reheatGain:
              typeof entry.reheatGain === "number" ? entry.reheatGain : 1,
            newlyInjectedThisTurn: entry.newlyInjectedThisTurn === true,
            rawInjectedScoreThisTurn:
              typeof entry.rawInjectedScoreThisTurn === "number" ? entry.rawInjectedScoreThisTurn : 0,
            intervalBonusThisTurn:
              typeof entry.intervalBonusThisTurn === "number" ? entry.intervalBonusThisTurn : 0,
            reheatGainAppliedThisTurn:
              typeof entry.reheatGainAppliedThisTurn === "number" ? entry.reheatGainAppliedThisTurn : 0,
            injectedScoreThisTurn:
              typeof entry.injectedScoreThisTurn === "number" ? entry.injectedScoreThisTurn : 0
          }))
      : [],
    fermentationBuffer: Array.isArray(value.fermentationBuffer)
      ? value.fermentationBuffer
          .filter(isRecord)
          .map((entry) => ({
            memoryId: typeof entry.memoryId === "string" ? entry.memoryId : "",
            description: typeof entry.description === "string" ? entry.description : "",
            score: typeof entry.score === "number" ? entry.score : 0,
            sourceType: typeof entry.sourceType === "string" ? entry.sourceType : "",
            focusTags: stringArrayOf(entry.focusTags),
            supportTags: stringArrayOf(entry.supportTags),
            associativeTags: stringArrayOf(entry.associativeTags),
            lastUpdatedGameTime:
              typeof entry.lastUpdatedGameTime === "number" ? entry.lastUpdatedGameTime : -1,
            lastConsciousGameTime:
              typeof entry.lastConsciousGameTime === "number" ? entry.lastConsciousGameTime : -1,
            enteredConsciousThisTurn: entry.enteredConsciousThisTurn === true,
            cueBoostThisTurn:
              typeof entry.cueBoostThisTurn === "number" ? entry.cueBoostThisTurn : 0
          }))
      : [],
    consciousMemories: Array.isArray(value.consciousMemories)
      ? value.consciousMemories
          .filter(isRecord)
          .map((entry) => ({
            memoryId: typeof entry.memoryId === "string" ? entry.memoryId : "",
            description: typeof entry.description === "string" ? entry.description : "",
            score: typeof entry.score === "number" ? entry.score : 0,
            sourceType: typeof entry.sourceType === "string" ? entry.sourceType : "",
            focusTags: stringArrayOf(entry.focusTags),
            supportTags: stringArrayOf(entry.supportTags),
            associativeTags: stringArrayOf(entry.associativeTags)
          }))
      : [],
    associationSandbox: isRecord(value.associationSandbox)
      ? {
          active: value.associationSandbox.active === true,
          cueTags: stringArrayOf(value.associationSandbox.cueTags),
          sourceMemoryIds: stringArrayOf(value.associationSandbox.sourceMemoryIds),
          memoryIds: stringArrayOf(value.associationSandbox.memoryIds),
          hopLimit: typeof value.associationSandbox.hopLimit === "number" ? value.associationSandbox.hopLimit : 1,
          recallBudget:
            typeof value.associationSandbox.recallBudget === "number" ? value.associationSandbox.recallBudget : 0
        }
      : {
          active: false,
          cueTags: [],
          sourceMemoryIds: [],
          memoryIds: [],
          hopLimit: 1,
          recallBudget: 0
        }
  };
}

function basenameOfPath(path: string): string {
  const normalized = path.replace(/\\/g, "/");
  const segments = normalized.split("/");
  return segments[segments.length - 1] ?? path;
}

function normalizeWorkspaceRelativePath(path: string): string {
  const normalized = path.replace(/\\/g, "/");
  const segments = normalized.split("/").filter(Boolean);
  if (segments.length <= 1) {
    return segments[0] ?? "";
  }
  return segments.slice(1).join("/");
}

function createEmptyWorkspaceManifestVersions(): WorkspaceManifestVersions {
  return {
    snapshot: "missing",
    runtimeTagView: "missing",
    runtimeInteractions: "missing",
    runtimeActionTimeline: "missing",
    runtimeActionSegmentStore: "missing",
    runtimeRecallTrace: "missing",
    actionTimelineTuning: "missing",
    memoryRecallTuning: "missing",
    importance: "missing",
    prompts: "missing",
    customTags: "missing",
    descriptions: "missing",
    relations: "missing",
    personas: "missing",
    memories: "missing",
    agentState: "missing",
    locations: "missing",
    iconsDirectory: "missing"
  };
}

function normalizeWorkspaceManifestWriter(value: unknown): WorkspaceManifestWriter {
  return value === "minecraft" || value === "studio" ? value : "unknown";
}

function normalizeWorkspaceManifest(value: unknown): StudioWorkspaceManifest | null {
  if (!isRecord(value)) {
    return null;
  }

  const rawVersions = isRecord(value.versions) ? value.versions : {};
  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : new Date().toISOString(),
    updatedBy: normalizeWorkspaceManifestWriter(value.updatedBy),
    versions: {
      snapshot: typeof rawVersions.snapshot === "string" ? rawVersions.snapshot : "missing",
      runtimeTagView: typeof rawVersions.runtimeTagView === "string" ? rawVersions.runtimeTagView : "missing",
      runtimeInteractions:
        typeof rawVersions.runtimeInteractions === "string" ? rawVersions.runtimeInteractions : "missing",
      runtimeActionTimeline:
        typeof rawVersions.runtimeActionTimeline === "string" ? rawVersions.runtimeActionTimeline : "missing",
      runtimeActionSegmentStore:
        typeof rawVersions.runtimeActionSegmentStore === "string" ? rawVersions.runtimeActionSegmentStore : "missing",
      runtimeRecallTrace:
        typeof rawVersions.runtimeRecallTrace === "string" ? rawVersions.runtimeRecallTrace : "missing",
      actionTimelineTuning:
        typeof rawVersions.actionTimelineTuning === "string" ? rawVersions.actionTimelineTuning : "missing",
      memoryRecallTuning:
        typeof rawVersions.memoryRecallTuning === "string" ? rawVersions.memoryRecallTuning : "missing",
      importance: typeof rawVersions.importance === "string" ? rawVersions.importance : "missing",
      prompts: typeof rawVersions.prompts === "string" ? rawVersions.prompts : "missing",
      customTags: typeof rawVersions.customTags === "string" ? rawVersions.customTags : "missing",
      descriptions: typeof rawVersions.descriptions === "string" ? rawVersions.descriptions : "missing",
      relations: typeof rawVersions.relations === "string" ? rawVersions.relations : "missing",
      personas: typeof rawVersions.personas === "string" ? rawVersions.personas : "missing",
      memories: typeof rawVersions.memories === "string" ? rawVersions.memories : "missing",
      agentState: typeof rawVersions.agentState === "string" ? rawVersions.agentState : "missing",
      locations: typeof rawVersions.locations === "string" ? rawVersions.locations : "missing",
      iconsDirectory: typeof rawVersions.iconsDirectory === "string" ? rawVersions.iconsDirectory : "missing"
    }
  };
}

const WORKSPACE_IMPORT_DESCRIPTORS: WorkspaceImportDescriptor[] = [
  {
    filename: "native_catalog.snapshot.json",
    versionKey: "snapshot",
    normalize: normalizeNativeCatalogSnapshotFile,
    assign: (importedData, value) => {
      importedData.nativeCatalogSnapshot = value as NativeCatalogSnapshotFile;
    },
    describe: (value) => `snapshot ${(value as NativeCatalogSnapshotFile).entries.length} 条`
  },
  {
    filename: "studio_runtime_recent_interactions.json",
    versionKey: "runtimeInteractions",
    normalize: normalizeRuntimeRecentInteractionsFile,
    assign: (importedData, value) => {
      importedData.runtimeInteractions = value as RuntimeRecentInteractionsFile;
    },
    describe: (value) => `turns ${(value as RuntimeRecentInteractionsFile).entries.length} 条`
  },
  {
    filename: "studio_runtime_action_timeline.json",
    versionKey: "runtimeActionTimeline",
    normalize: normalizeRuntimeActionTimelineFile,
    assign: (importedData, value) => {
      importedData.runtimeActionTimeline = value as RuntimeActionTimelineFile;
    },
    describe: (value) => `timeline ${(value as RuntimeActionTimelineFile).fixedSegmentCount} 个 fixed segments`
  },
  {
    filename: "studio_runtime_action_segments.json",
    versionKey: "runtimeActionSegmentStore",
    normalize: normalizeRuntimeActionSegmentStoreFile,
    assign: (importedData, value) => {
      importedData.runtimeActionSegmentStore = value as RuntimeActionSegmentStoreFile;
    },
    describe: (value) => `segment store ${(value as RuntimeActionSegmentStoreFile).fixedSegmentCount} 个 fixed segments`
  },
  {
    filename: "action_timeline_tuning.json",
    versionKey: "actionTimelineTuning",
    normalize: normalizeActionTimelineTuningFile,
    assign: (importedData, value) => {
      importedData.actionTimelineTuning = value as ActionTimelineTuningFile;
    },
    describe: (_value) => "action timeline tuning 1 份"
  },
  {
    filename: "studio_runtime_recall_trace.json",
    versionKey: "runtimeRecallTrace",
    normalize: normalizeRuntimeRecallTraceFile,
    assign: (importedData, value) => {
      importedData.runtimeRecallTrace = value as RuntimeRecallTraceFile;
    },
    describe: (value) => `recall ${((value as RuntimeRecallTraceFile).promptMemories.length || (value as RuntimeRecallTraceFile).recalledMemories.length)} 条 prompt 记忆`
  },
  {
    filename: "memory_recall_tuning.json",
    versionKey: "memoryRecallTuning",
    normalize: normalizeMemoryRecallTuningFile,
    assign: (importedData, value) => {
      importedData.memoryRecallTuning = value as MemoryRecallTuningFile;
    },
    describe: (_value) => "recall tuning 1 份"
  },
  {
    filename: "importance.json",
    versionKey: "importance",
    normalize: normalizeImportanceRegistryFile,
    assign: (importedData, value) => {
      importedData.importance = value as ImportanceRegistryFile;
    },
    describe: (value) => `importance ${Object.keys(value as ImportanceRegistryFile).length} 条`
  },
  {
    filename: "prompts.json",
    versionKey: "prompts",
    normalize: normalizePromptRegistryFile,
    assign: (importedData, value) => {
      importedData.prompts = value as PromptRegistryFile;
    },
    describe: (value) => `prompts ${Object.keys(value as PromptRegistryFile).length} 条`
  },
  {
    filename: "custom_tags.json",
    versionKey: "customTags",
    normalize: normalizeCustomTagsFile,
    assign: (importedData, value) => {
      importedData.customTags = value as CustomTagsFile;
    },
    describe: (value) => `custom tags ${(value as CustomTagsFile).tags.length} 条`
  },
  {
    filename: "tag_descriptions.json",
    versionKey: "descriptions",
    normalize: normalizeTagDescriptionsFile,
    assign: (importedData, value) => {
      importedData.descriptions = value as TagDescriptionsFile;
    },
    describe: (value) => `descriptions ${Object.keys((value as TagDescriptionsFile).descriptions).length} 条`
  },
  {
    filename: "tag_relations.json",
    versionKey: "relations",
    normalize: normalizeTagRelationsFile,
    assign: (importedData, value) => {
      importedData.relations = value as TagRelationsFile;
    },
    describe: (value) => `relations ${Object.keys((value as TagRelationsFile).relations).length} 个`
  },
  {
    filename: "personas.json",
    versionKey: "personas",
    normalize: normalizePersonasFile,
    assign: (importedData, value) => {
      importedData.personas = value as PersonasFile;
    },
    describe: (value) => `personas ${(value as PersonasFile).personas.length} 条`
  },
  {
    filename: "implanted_memories.json",
    versionKey: "memories",
    normalize: normalizeImplantedMemoriesFile,
    assign: (importedData, value) => {
      importedData.memories = value as ImplantedMemoriesFile;
    },
    describe: (value) => `memories ${(value as ImplantedMemoriesFile).memories.length} 条`
  },
  {
    filename: "agent_state.json",
    versionKey: "agentState",
    normalize: normalizeAgentStateWorkspaceFile,
    assign: (importedData, value) => {
      importedData.agentState = value as AgentStateWorkspaceFile;
    },
    describe: (_value) => "agent state 1 份"
  },
  {
    filename: "location_registry.json",
    versionKey: "locations",
    normalize: normalizeLocationRegistryData,
    assign: (importedData, value) => {
      importedData.locations = value as Record<string, LocationRegistryEntry>;
    },
    describe: (value) => `locations ${Object.keys(value as Record<string, LocationRegistryEntry>).length} 条`
  }
];

const WORKSPACE_IMPORT_DESCRIPTOR_BY_FILENAME = new Map(
  WORKSPACE_IMPORT_DESCRIPTORS.map((descriptor) => [descriptor.filename, descriptor])
);

async function readWorkspaceManifestFromDirectory(
  handle: FileSystemDirectoryHandle
): Promise<StudioWorkspaceManifest | null> {
  const manifestRaw = await readJsonFileFromDirectory(handle, "studio_workspace.manifest.json");
  return manifestRaw !== null ? normalizeWorkspaceManifest(manifestRaw) : null;
}

async function loadWorkspaceDataFromDirectory(
  handle: FileSystemDirectoryHandle,
  nextManifest: StudioWorkspaceManifest | null,
  previousManifest?: StudioWorkspaceManifest | null
): Promise<{ importedData: ImportedWorkspaceData; importedParts: string[] }> {
  const importedData: ImportedWorkspaceData = {};
  const importedParts: string[] = [];
  const previousVersions = previousManifest?.versions ?? createEmptyWorkspaceManifestVersions();

  for (const descriptor of WORKSPACE_IMPORT_DESCRIPTORS) {
    if (
      nextManifest &&
      previousManifest &&
      descriptor.versionKey &&
      previousVersions[descriptor.versionKey] === nextManifest.versions[descriptor.versionKey]
    ) {
      continue;
    }

    const raw = await readJsonFileFromDirectory(handle, descriptor.filename);
    if (raw === null) {
      continue;
    }

    const normalized = descriptor.normalize(raw);
    if (!normalized) {
      throw new Error(`${descriptor.filename} 结构无效`);
    }

    descriptor.assign(importedData, normalized);
    importedParts.push(descriptor.describe(normalized));
  }

  if (importedData.runtimeActionSegmentStore?.dataFilename) {
    const rawData = await readTextFileFromDirectoryPath(handle, importedData.runtimeActionSegmentStore.dataFilename);
    if (rawData !== null) {
      importedData.runtimeActionSegmentStore = {
        ...importedData.runtimeActionSegmentStore,
        fixedSegments: parseRuntimeActionSegmentStoreData(rawData)
      };
    }
  }

  return { importedData, importedParts };
}

async function loadWorkspaceDataFromFolderFiles(
  files: File[]
): Promise<{
  importedData: ImportedWorkspaceData;
  importedParts: string[];
  ignoredFiles: string[];
  workspaceFiles: Map<string, File>;
}> {
  const importedData: ImportedWorkspaceData = {};
  const importedParts: string[] = [];
  const ignoredFiles: string[] = [];
  const workspaceFiles = new Map<string, File>();

  for (const file of files) {
    const relativePath = normalizeWorkspaceRelativePath(file.webkitRelativePath || file.name);
    const basename = basenameOfPath(relativePath);
    workspaceFiles.set(relativePath, file);

    if (relativePath.startsWith("native_catalog_icons/") && relativePath.toLowerCase().endsWith(".png")) {
      continue;
    }
    if (!basename.toLowerCase().endsWith(".json")) {
      continue;
    }

    const descriptor = WORKSPACE_IMPORT_DESCRIPTOR_BY_FILENAME.get(basename);
    if (!descriptor) {
      ignoredFiles.push(basename);
      continue;
    }

    const parsed = JSON.parse(await file.text()) as unknown;
    const normalized = descriptor.normalize(parsed);
    if (!normalized) {
      throw new Error(`${basename} 结构无效`);
    }

    descriptor.assign(importedData, normalized);
    importedParts.push(descriptor.describe(normalized));
  }

  if (importedData.runtimeActionSegmentStore?.dataFilename) {
    const normalizedDataPath = normalizeWorkspaceRelativePath(importedData.runtimeActionSegmentStore.dataFilename);
    const dataFile = workspaceFiles.get(normalizedDataPath)
      ?? workspaceFiles.get(basenameOfPath(normalizedDataPath));
    if (dataFile) {
      importedData.runtimeActionSegmentStore = {
        ...importedData.runtimeActionSegmentStore,
        fixedSegments: parseRuntimeActionSegmentStoreData(await dataFile.text())
      };
    }
  }

  return { importedData, importedParts, ignoredFiles, workspaceFiles };
}

function buildNextWorkspaceManifest(
  currentManifest: StudioWorkspaceManifest | null,
  updatedBy: WorkspaceManifestWriter,
  versionUpdates: Partial<WorkspaceManifestVersions>
): StudioWorkspaceManifest {
  return {
    schemaVersion: currentManifest?.schemaVersion ?? 1,
    generatedAt: new Date().toISOString(),
    updatedBy,
    versions: {
      ...(currentManifest?.versions ?? createEmptyWorkspaceManifestVersions()),
      ...versionUpdates
    }
  };
}

function extractCustomTagId(tag: string): string {
  const separatorIndex = tag.indexOf(":");
  return separatorIndex === -1 ? tag : tag.slice(separatorIndex + 1);
}

function buildCustomTagValue(kind: CustomTagKind, id: string): string {
  return `${kind}:${id.trim()}`;
}

function buildSuggestedCustomTagDescription(kind: CustomTagKind, id: string): string {
  const normalizedId = id.trim() || "new_tag";
  const humanizedId = normalizedId
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();

  if (!humanizedId) {
    return kind === "custom_specific" ? "一个具体的自定义对象或概念。" : "一类抽象的自定义概念。";
  }

  return kind === "custom_specific"
    ? `指向“${humanizedId}”这一具体对象、地点、人物或概念。`
    : `表示与“${humanizedId}”相关的一类抽象概念或泛化倾向。`;
}

function replaceTagInList(list: string[] | undefined, oldTag: string, newTag: string): string[] | undefined {
  if (!list) {
    return undefined;
  }

  return Array.from(new Set(list.map((entry) => (entry === oldTag ? newTag : entry))));
}

function removeTagFromList(list: string[] | undefined, tag: string): string[] | undefined {
  if (!list) {
    return undefined;
  }

  return list.filter((entry) => entry !== tag);
}

function renameTagInRelations(
  relationMap: Record<string, string[]>,
  oldTag: string,
  newTag: string
): Record<string, string[]> {
  const next = new Map<string, string[]>();

  for (const [source, targets] of Object.entries(relationMap)) {
    const normalizedSource = source === oldTag ? newTag : source;
    const normalizedTargets = Array.from(new Set(targets.map((target) => (target === oldTag ? newTag : target))));
    const existing = next.get(normalizedSource) ?? [];
    next.set(normalizedSource, Array.from(new Set([...existing, ...normalizedTargets])));
  }

  return Object.fromEntries(next.entries());
}

function deleteTagFromRelations(
  relationMap: Record<string, string[]>,
  tag: string
): Record<string, string[]> {
  const nextEntries = Object.entries(relationMap)
    .filter(([source]) => source !== tag)
    .map(([source, targets]) => [source, targets.filter((target) => target !== tag)] as const)
    .filter(([, targets]) => targets.length > 0);

  return Object.fromEntries(nextEntries);
}

function normalizeRelationTargets(targets: string[]): string[] {
  return Array.from(new Set(targets.map((entry) => entry.trim()).filter(Boolean))).sort((left, right) =>
    left.localeCompare(right, "zh-Hans")
  );
}

function normalizeRelationMap(relationMap: Record<string, string[]>): Record<string, string[]> {
  const nextEntries = Object.entries(relationMap)
    .map(([source, targets]) => [source.trim(), normalizeRelationTargets(targets)] as const)
    .filter(([source, targets]) => source && targets.length > 0)
    .sort((left, right) => left[0].localeCompare(right[0], "zh-Hans"));

  return Object.fromEntries(nextEntries);
}

function upsertRelationEntry(
  relationMap: Record<string, string[]>,
  previousSourceTag: string | undefined,
  nextSourceTag: string,
  linkedTags: string[]
): Record<string, string[]> {
  const next = { ...relationMap };
  if (previousSourceTag && previousSourceTag !== nextSourceTag) {
    delete next[previousSourceTag];
  }

  const normalizedTargets = normalizeRelationTargets(linkedTags);
  if (normalizedTargets.length) {
    next[nextSourceTag] = normalizedTargets;
  } else {
    delete next[nextSourceTag];
  }

  return normalizeRelationMap(next);
}

function applyRelationBatchUpdate(
  relationMap: Record<string, string[]>,
  matchedSourceTags: string[],
  operation: RelationBatchOperation,
  linkedTags: string[]
): Record<string, string[]> {
  const normalizedLinkedTags = normalizeRelationTargets(linkedTags);
  if (!matchedSourceTags.length || !normalizedLinkedTags.length) {
    return normalizeRelationMap(relationMap);
  }

  const next = { ...relationMap };
  for (const sourceTag of matchedSourceTags) {
    const currentTargets = normalizeRelationTargets(next[sourceTag] ?? []);
    const targetSet = new Set(currentTargets);

    if (operation === "add") {
      for (const linkedTag of normalizedLinkedTags) {
        targetSet.add(linkedTag);
      }
    } else {
      for (const linkedTag of normalizedLinkedTags) {
        targetSet.delete(linkedTag);
      }
    }

    const updatedTargets = normalizeRelationTargets(Array.from(targetSet));
    if (updatedTargets.length) {
      next[sourceTag] = updatedTargets;
    } else {
      delete next[sourceTag];
    }
  }

  return normalizeRelationMap(next);
}

function canonicalizeCycle(cycleNodes: string[]): string {
  if (!cycleNodes.length) {
    return "";
  }

  let best = cycleNodes;
  for (let index = 1; index < cycleNodes.length; index += 1) {
    const rotated = [...cycleNodes.slice(index), ...cycleNodes.slice(0, index)];
    if (rotated.join("->") < best.join("->")) {
      best = rotated;
    }
  }

  return best.join("->");
}

function detectRelationCycles(relationMap: Record<string, string[]>): string[][] {
  const adjacency = new Map<string, string[]>(
    Object.entries(normalizeRelationMap(relationMap)).map(([source, targets]) => [source, targets])
  );
  const visitState = new Map<string, "visiting" | "done">();
  const stack: string[] = [];
  const stackIndex = new Map<string, number>();
  const cycles: string[][] = [];
  const seenCycles = new Set<string>();

  function dfs(node: string) {
    visitState.set(node, "visiting");
    stackIndex.set(node, stack.length);
    stack.push(node);

    for (const next of adjacency.get(node) ?? []) {
      if (!adjacency.has(next)) {
        continue;
      }

      const state = visitState.get(next);
      if (!state) {
        dfs(next);
        continue;
      }

      if (state === "visiting") {
        const startIndex = stackIndex.get(next);
        if (startIndex === undefined) {
          continue;
        }

        const cycleNodes = stack.slice(startIndex);
        const cycleKey = canonicalizeCycle(cycleNodes);
        if (!seenCycles.has(cycleKey)) {
          seenCycles.add(cycleKey);
          cycles.push([...cycleNodes, cycleNodes[0]]);
        }
      }
    }

    stack.pop();
    stackIndex.delete(node);
    visitState.set(node, "done");
  }

  for (const node of adjacency.keys()) {
    if (!visitState.has(node)) {
      dfs(node);
    }
  }

  return cycles;
}

function cycleNodeSetOf(cycles: string[][]): Set<string> {
  return new Set(cycles.flatMap((cycle) => cycle.slice(0, -1)));
}

function renameTagInPersonas(personas: PersonaEntry[], oldTag: string, newTag: string): PersonaEntry[] {
  return personas.map((persona) => ({
    ...persona,
    detail_tags: replaceTagInList(persona.detail_tags, oldTag, newTag)
  }));
}

function removeTagFromPersonas(personas: PersonaEntry[], tag: string): PersonaEntry[] {
  return personas.map((persona) => ({
    ...persona,
    detail_tags: removeTagFromList(persona.detail_tags, tag)
  }));
}

function renameTagInMemories(memories: ImplantedMemoryEntry[], oldTag: string, newTag: string): ImplantedMemoryEntry[] {
  return memories.map((memory) => ({
    ...memory,
    detail_tags: replaceTagInList(memory.detail_tags, oldTag, newTag)
  }));
}

function removeTagFromMemories(memories: ImplantedMemoryEntry[], tag: string): ImplantedMemoryEntry[] {
  return memories.map((memory) => ({
    ...memory,
    detail_tags: removeTagFromList(memory.detail_tags, tag)
  }));
}

function renameTagInLocations(
  locations: Record<string, LocationRegistryEntry>,
  oldTag: string,
  newTag: string
): Record<string, LocationRegistryEntry> {
  return Object.fromEntries(
    Object.entries(locations).map(([name, entry]) => [
      name,
      {
        ...entry,
        detailTags: replaceTagInList(entry.detailTags, oldTag, newTag) ?? []
      }
    ])
  );
}

function removeTagFromLocations(
  locations: Record<string, LocationRegistryEntry>,
  tag: string
): Record<string, LocationRegistryEntry> {
  return Object.fromEntries(
    Object.entries(locations).map(([name, entry]) => [
      name,
      {
        ...entry,
        detailTags: removeTagFromList(entry.detailTags, tag) ?? []
      }
    ])
  );
}

function createStudioId(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}

function buildMemoryDraft(memory?: ImplantedMemoryEntry): MemoryDraft {
  if (!memory) {
    return { ...EMPTY_MEMORY_DRAFT };
  }

  const legacyOccurredGameTime = memory.occurred_game_time;
  const occurredDayIndex =
    memory.occurred_day_index !== undefined
      ? memory.occurred_day_index
      : legacyOccurredGameTime !== undefined && legacyOccurredGameTime >= 0
        ? Math.floor(legacyOccurredGameTime / MEMORY_TICKS_PER_DAY) + 1
        : undefined;
  const occurredTimeOfDayTick =
    memory.occurred_time_of_day_tick !== undefined
      ? normalizeStudioTimeOfDayTick(memory.occurred_time_of_day_tick)
      : legacyOccurredGameTime !== undefined && legacyOccurredGameTime >= 0
        ? normalizeStudioTimeOfDayTick(legacyOccurredGameTime)
        : undefined;

  return {
    label: memory.label ?? "",
    description: memory.description,
    focus_tags: [...(memory.focus_tags ?? [])],
    semantic_time_tag: memory.semantic_time_tag ?? "",
    detail_tags: [...(memory.detail_tags ?? [])],
    importance: memory.importance !== undefined ? String(memory.importance) : "",
    occurred_day_index: occurredDayIndex !== undefined && occurredDayIndex > 0 ? String(occurredDayIndex) : "",
    occurred_time_of_day_tick:
      occurredTimeOfDayTick !== undefined && occurredTimeOfDayTick >= 0 ? String(occurredTimeOfDayTick) : "",
    notes: memory.notes ?? ""
  };
}

function buildLocationDraft(name?: string, entry?: LocationRegistryEntry): LocationDraft {
  if (!name || !entry) {
    return { ...EMPTY_LOCATION_DRAFT };
  }

  return {
    name,
    x: String(entry.x),
    y: String(entry.y),
    z: String(entry.z),
    dim: entry.dim,
    note: entry.note ?? "",
    horizontalRadius: String(entry.horizontalRadius ?? 24),
    verticalRadius: String(entry.verticalRadius ?? 16),
    focusTag: entry.focusTag ?? "",
    detailTags: [...(entry.detailTags ?? [])]
  };
}

function buildLocationRegistryJson(
  locations: Record<string, LocationRegistryEntry>
): Record<string, unknown> {
  const entries = Object.entries(locations)
    .filter(([name]) => Boolean(name.trim()))
    .sort((left, right) => left[0].localeCompare(right[0], "zh-Hans"));

  const root: Record<string, unknown> = {
    _comment: "关键位置注册表。格式：\"地点名\" -> {x, y, z, dim, note, horizontalRadius, verticalRadius}",
    _comment2: "可通过游戏内命令、Studio 或直接编辑此文件更新。",
    _comment3: "horizontalRadius / verticalRadius 为该地点各自的命中范围；focusTag 为关联的 FocusTag 名称（可选），detailTags 为逗号分隔的 DetailTag 列表（可选）。"
  };

  for (const [name, entry] of entries) {
    root[name] = {
      x: Math.round(entry.x),
      y: Math.round(entry.y),
      z: Math.round(entry.z),
      dim: entry.dim.trim() || "minecraft:overworld",
      note: entry.note ?? "",
      horizontalRadius: Math.max(1, Math.round(entry.horizontalRadius ?? 24)),
      verticalRadius: Math.max(1, Math.round(entry.verticalRadius ?? 16)),
      focusTag: entry.focusTag ?? "",
      detailTags: (entry.detailTags ?? []).join(", ")
    };
  }

  return root;
}

function buildMemoryEntryFromDraft(
  draft: MemoryDraft,
  baseMemory?: ImplantedMemoryEntry,
  derivedTimeLabel?: string
): ImplantedMemoryEntry {
  const occurredDayIndex = parseOptionalInteger(draft.occurred_day_index);
  const occurredTimeOfDayTick = parseOptionalInteger(draft.occurred_time_of_day_tick);
  const hasAbsoluteTimeAnchor =
    occurredDayIndex !== undefined &&
    occurredDayIndex > 0 &&
    occurredTimeOfDayTick !== undefined &&
    occurredTimeOfDayTick >= 0;

  const nextMemory: ImplantedMemoryEntry = {
    ...(baseMemory ?? {}),
    label: draft.label.trim() || undefined,
    description: draft.description.trim(),
    focus_tags: draft.focus_tags.length ? draft.focus_tags : undefined,
    semantic_time_tag: draft.semantic_time_tag.trim() || undefined,
    detail_tags: draft.detail_tags.length ? draft.detail_tags : undefined,
    importance: draft.importance.trim() ? Number.parseFloat(draft.importance) : undefined,
    occurred_day_index: hasAbsoluteTimeAnchor ? occurredDayIndex : undefined,
    occurred_time_of_day_tick: hasAbsoluteTimeAnchor
      ? normalizeStudioTimeOfDayTick(occurredTimeOfDayTick)
      : undefined,
    occurred_game_time: undefined,
    time_label: derivedTimeLabel,
    notes: draft.notes.trim() || undefined
  };

  if (!nextMemory.description) {
    nextMemory.description = "";
  }

  return nextMemory;
}

function focusTagRank(focusTag: FocusTag): number {
  const index = FOCUS_TAGS.indexOf(focusTag);
  return index === -1 ? FOCUS_TAGS.length : index;
}

function parseOptionalInteger(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (!trimmed) {
    return undefined;
  }
  const parsed = Number.parseInt(trimmed, 10);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function normalizeStudioTimeOfDayTick(rawTick: number): number {
  const normalized = rawTick % MEMORY_TICKS_PER_DAY;
  return normalized >= 0 ? normalized : normalized + MEMORY_TICKS_PER_DAY;
}

function normalizePersonaFocusTags(focusTags: FocusTag[] | undefined, fallback?: FocusTag): FocusTag[] {
  const source = Array.isArray(focusTags) && focusTags.length ? focusTags : fallback ? [fallback] : [];
  return Array.from(
    new Set(
      source
        .map((focusTag) => normalizeFocusTagValue(focusTag))
        .filter((focusTag): focusTag is FocusTag => focusTag !== null)
    )
  );
}

function getPersonaFocusTags(persona: PersonaEntry): FocusTag[] {
  return normalizePersonaFocusTags(persona.focus_tags, persona.associated_focus);
}

function getPrimaryPersonaFocusTag(persona: PersonaEntry): FocusTag {
  return getPersonaFocusTags(persona)[0] ?? "COMMON";
}

function personaHasFocus(persona: PersonaEntry, focusTag: FocusTag): boolean {
  return getPersonaFocusTags(persona).includes(focusTag);
}

function comparePersonaEntries(left: PersonaEntry, right: PersonaEntry): number {
  const rank = focusTagRank(getPrimaryPersonaFocusTag(left)) - focusTagRank(getPrimaryPersonaFocusTag(right));
  if (rank !== 0) {
    return rank;
  }
  return left.description.localeCompare(right.description, "zh-Hans");
}

function formatFocusTagLabel(focusTag: FocusTag): string {
  return `${FOCUS_TAG_LABELS[focusTag]} (${focusTag})`;
}

function formatPrimaryEmotionLabel(emotion: string): string {
  return `${PRIMARY_EMOTION_LABELS[emotion] ?? emotion} (${emotion})`;
}

function formatRecallPresetLabel(mode: string): string {
  return `${RECALL_PRESET_LABELS[mode] ?? mode} (${mode})`;
}

function formatAgentStateWeightLabel(weightKey: AgentStateWeightKey): string {
  return formatFocusTagLabel(AGENT_STATE_WEIGHT_TO_FOCUS_TAG[weightKey]);
}

function formatAgentStateWeightShortLabel(weightKey: AgentStateWeightKey): string {
  return FOCUS_TAG_LABELS[AGENT_STATE_WEIGHT_TO_FOCUS_TAG[weightKey]];
}

function formatFocusTagChip(focusTag: FocusTag): string {
  return formatFocusTagLabel(focusTag);
}

function polarPoint(center: number, radius: number, angleInTurns: number): { x: number; y: number } {
  const angle = angleInTurns * Math.PI * 2 - Math.PI / 2;
  return {
    x: center + Math.cos(angle) * radius,
    y: center + Math.sin(angle) * radius
  };
}

function formatFocusTagItems(focusTags: FocusTag[] | undefined): string[] {
  return (focusTags ?? []).map((focusTag) => formatFocusTagChip(focusTag));
}

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
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
}

function formatShortRuntimeTimestamp(timestamp: number): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return "未知时间";
  }
  return new Date(timestamp).toLocaleTimeString("zh-CN", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
}

function shortActionType(actionType: string): string {
  switch ((actionType ?? "").trim().toLowerCase()) {
    case "speech":
      return "说话";
    case "inner_monologue":
      return "心声";
    case "attack":
      return "攻击";
    case "kill":
      return "击杀";
    case "hurt_by":
      return "受伤";
    case "death":
      return "死亡";
    case "mine_block":
      return "挖掘";
    case "place_block":
      return "放置";
    case "eat":
      return "进食";
    case "use_item":
      return "使用物品";
    case "travel":
      return "移动";
    case "biome_change":
      return "群系变化";
    case "dim_change":
      return "维度变化";
    case "trade":
      return "交易";
    case "interact":
      return "交互";
    case "favorability_update":
      return "好感变化";
    case "emotion_update":
      return "情绪变化";
    default:
      return abbreviateText(
        actionType
          .toLowerCase()
          .replace(/_/g, " ")
          .trim() || "未知",
        12,
        "未知"
      );
  }
}

function formatOutputTypeLabel(type: string): string {
  switch ((type ?? "").trim().toLowerCase()) {
    case "speech":
      return "说话";
    case "inner_monologue":
      return "内心";
    default:
      return type || "输出";
  }
}

function getSpeechOutputText(entry: RuntimeRecentInteractionsFile["entries"][number]): string {
  const speechOutput = entry.outputs.find((output) => output.type === "speech" && output.text.trim());
  return speechOutput?.text.trim() ?? "";
}

function buildInteractionTriggerLine(entry: RuntimeRecentInteractionsFile["entries"][number]): string {
  const imageMark = entry.hasImage ? " [+image]" : "";
  const prompt = entry.userPrompt.trim() ? entry.userPrompt.trim() : "(无提示词)";
  return `${formatShortRuntimeTimestamp(entry.timestamp)}  触发: ${prompt}${imageMark}`;
}

function buildInteractionSpeechLine(entry: RuntimeRecentInteractionsFile["entries"][number]): string {
  const speechText = getSpeechOutputText(entry);
  return `说话: ${speechText || "(沉默)"}`;
}

function buildInteractionActionLine(entry: RuntimeRecentInteractionsFile["entries"][number]): string {
  const shortTypes = Array.from(new Set(entry.actionTypes.map((actionType) => shortActionType(actionType))));
  return `动作: ${shortTypes.length ? shortTypes.join(" / ") : "无"}`;
}

function primaryRegistryTypeOf(record: TagRecord): string | undefined {
  return record.registryTypes[0];
}

function tagBrowserCategoryOf(record: TagRecord): TagBrowserCategoryId {
  if (record.kind === "custom_specific" || record.kind === "custom_generic") {
    return "custom";
  }

  const primaryRegistryType = primaryRegistryTypeOf(record);
  switch (primaryRegistryType) {
    case "item":
      return "item";
    case "block":
      return "block";
    case "entity_type":
      return "entity";
    case "biome":
      return "biome";
    case "structure":
      return "structure";
    case "dimension":
      return "dimension";
    case "fluid":
      return "fluid";
    case "mob_effect":
      return "effect";
    case "enchantment":
      return "enchantment";
    default:
      return record.kind === "native_generic" ? "generic" : "misc";
  }
}

function tagBrowserCategoryLabel(categoryId: TagBrowserCategoryId): string {
  return TAG_BROWSER_CATEGORY_LABELS[categoryId];
}

function tagPlaceholderGlyph(record: TagRecord): string {
  const trimmedLabel = record.label.trim();
  if (trimmedLabel) {
    return trimmedLabel[0]?.toUpperCase() ?? "#";
  }

  if (record.kind === "custom_specific" || record.kind === "custom_generic") {
    return "C";
  }

  return "N";
}

function isCustomTagRecord(record: TagRecord): boolean {
  return record.kind === "custom_specific" || record.kind === "custom_generic";
}

function tagRecordOrFallback(tagRecords: TagRecord[], tag: string): TagRecord {
  const existing = findTagRecord(tagRecords, tag);
  if (existing) {
    return existing;
  }

  return {
    tag,
    kind: tag.split(":")[0] as DetailTagKind,
    label: tag.split(":").slice(1).join(":") || tag,
    description: "",
    aliases: [],
    relations: [],
    registryTypes: [],
    sourceMods: [],
    promptVisible: true
  };
}

function findTagRecord(tagRecords: TagRecord[], tag: string | undefined): TagRecord | undefined {
  if (!tag) {
    return undefined;
  }

  return tagRecords.find((record) => record.tag === tag);
}

function deriveMemoryTimeLabel(tagRecords: TagRecord[], semanticTimeTag: string): string | undefined {
  const record = findTagRecord(tagRecords, semanticTimeTag);
  if (!record) {
    return semanticTimeTag || undefined;
  }

  return record.description || record.label || record.tag;
}

function tokenizeTagRecord(record: TagRecord): string {
  return [
    record.tag,
    record.label,
    record.description,
    record.aliases.join(" "),
    record.relations.join(" "),
    record.registryTypes.join(" "),
    record.sourceMods.join(" "),
    record.group ?? ""
  ]
    .join(" ")
    .toLowerCase();
}

function filterTagRecords(tagRecords: TagRecord[], query: string, excludedTags: string[] = []): TagRecord[] {
  const normalizedQuery = query.trim().toLowerCase();
  const excluded = new Set(excludedTags);
  const base = tagRecords.filter((record) => !excluded.has(record.tag));

  if (!normalizedQuery) {
    return base;
  }

  return base.filter((record) => tokenizeTagRecord(record).includes(normalizedQuery));
}

function App() {
  const folderImportInputRef = useRef<HTMLInputElement | null>(null);
  const [page, setPage] = useState<PageId>("catalog");
  const [customQuery, setCustomQuery] = useState("");
  const [descriptionQuery, setDescriptionQuery] = useState("");
  const [relationQuery, setRelationQuery] = useState("");
  const [personaQuery, setPersonaQuery] = useState("");
  const [personaFocusFilter, setPersonaFocusFilter] = useState<"all" | FocusTag>("all");
  const [memoryQuery, setMemoryQuery] = useState("");
  const [locationQuery, setLocationQuery] = useState("");
  const [importanceQuery, setImportanceQuery] = useState("");
  const [promptQuery, setPromptQuery] = useState("");
  const [newImportanceKey, setNewImportanceKey] = useState("");
  const [newImportanceValue, setNewImportanceValue] = useState("0.50");
  const [newPromptKey, setNewPromptKey] = useState("");
  const [newPromptValue, setNewPromptValue] = useState("");
  const [nativeCatalogSnapshot, setNativeCatalogSnapshot] = useState<NativeCatalogSnapshotFile>(
    createSeedNativeCatalogSnapshot()
  );
  const [runtimeInteractions, setRuntimeInteractions] = useState<RuntimeRecentInteractionsFile>(
    createEmptyRuntimeRecentInteractionsFile()
  );
  const [runtimeActionTimeline, setRuntimeActionTimeline] = useState<RuntimeActionTimelineFile>(
    createEmptyRuntimeActionTimelineFile()
  );
  const [runtimeRecallTrace, setRuntimeRecallTrace] = useState<RuntimeRecallTraceFile>(
    createEmptyRuntimeRecallTraceFile()
  );
  const [actionTimelineTuningFile, setActionTimelineTuningFile] = useState<ActionTimelineTuningFile>(
    createEmptyActionTimelineTuningFile()
  );
  const [memoryRecallTuningFile, setMemoryRecallTuningFile] = useState<MemoryRecallTuningFile>(
    createEmptyMemoryRecallTuningFile()
  );
  const [importanceFile, setImportanceFile] = useState<ImportanceRegistryFile>({});
  const [promptsFile, setPromptsFile] = useState<PromptRegistryFile>({});
  const [customTags, setCustomTags] = useState<CustomTagEntry[]>(studioSeedData.customTags.tags);
  const [descriptions, setDescriptions] = useState<Record<string, DescriptionEntry>>(
    studioSeedData.descriptions.descriptions
  );
  const [relations, setRelations] = useState<Record<string, string[]>>(
    normalizeRelationMap(studioSeedData.relations.relations)
  );
  const [personas, setPersonas] = useState<PersonaEntry[]>(studioSeedData.personas.personas);
  const [memories, setMemories] = useState<ImplantedMemoryEntry[]>(studioSeedData.implantedMemories.memories);
  const [agentStateFile, setAgentStateFile] = useState<AgentStateWorkspaceFile>(sanitizeAgentStateFile(studioSeedData.agentState));
  const [locations, setLocations] = useState<Record<string, LocationRegistryEntry>>(studioSeedData.locations);
  const [schemaVersions, setSchemaVersions] = useState<SchemaVersions>({
    ...studioSeedData.schemaVersions,
    actionTimelineTuning: Math.max((studioSeedData.schemaVersions as Partial<SchemaVersions>).actionTimelineTuning ?? 1, 3),
    agentState: Math.max(studioSeedData.schemaVersions.agentState ?? 1, 4),
    importance: 1,
    prompts: 1,
    recallTuning: 1
  });
  const [workspaceDirectoryHandle, setWorkspaceDirectoryHandle] = useState<FileSystemDirectoryHandle | null>(null);
  const [workspaceDirectoryName, setWorkspaceDirectoryName] = useState("");
  const [workspaceImportedFiles, setWorkspaceImportedFiles] = useState<Map<string, File>>(() => new Map());
  const [workspaceManifest, setWorkspaceManifest] = useState<StudioWorkspaceManifest | null>(null);
  const [iconAssetVersion, setIconAssetVersion] = useState("seed");
  const [gameBridgeStatus, setGameBridgeStatus] = useState<GameBridgeStatus>({
    state: "checking",
    message: "正在探测游戏内 HTTP bridge。"
  });
  const [workspaceDebugInfo, setWorkspaceDebugInfo] = useState<WorkspaceDebugInfo>({
    accessMode: describeWorkspaceAccessMode(),
    selectedPath: "未选择",
    manifestPresent: "unknown",
    importedParts: [],
    errorMessage: ""
  });
  const [importFeedback, setImportFeedback] = useState<ImportFeedback>({
    tone: "neutral",
    message: "工作区目录对应 Minecraft 的 config/talking_weapon/studio_workspace/。"
  });
  const workspaceDirectoryRequestRef = useRef<Promise<FileSystemDirectoryHandle | null> | null>(null);
  const workspaceWatcherRefreshRef = useRef(false);
  const [relationFeedback, setRelationFeedback] = useState<ImportFeedback>({
    tone: "neutral",
    message: "联动标签默认仅用于程序侧扩展引用，不会直接写入 LLM Prompt；图必须保持无环。"
  });
  const [catalogMode, setCatalogMode] = useState<CatalogMode>({ mode: "list" });
  const [descriptionMode, setDescriptionMode] = useState<DescriptionMode>({ mode: "list" });
  const [relationMode, setRelationMode] = useState<RelationMode>({ mode: "list" });
  const [personaMode, setPersonaMode] = useState<PersonaMode>({ mode: "list" });
  const [memoryMode, setMemoryMode] = useState<MemoryMode>({ mode: "list" });
  const [locationMode, setLocationMode] = useState<LocationMode>({ mode: "list" });

  useEffect(() => {
    console.log("[TW Studio] renderer env", {
      accessMode: describeWorkspaceAccessMode(),
      desktopBridgeReady: isDesktopBridgeReady(),
      desktopBridgeSource: getDesktopBridgeSource(),
      showDirectoryPicker: isBrowserDirectoryPickerAvailable()
    });
  }, []);

  const tagRecords = useMemo(
    () => buildTagRecords(nativeCatalogSnapshot.entries, customTags, descriptions, relations),
    [customTags, descriptions, nativeCatalogSnapshot.entries, relations]
  );
  const relationCycles = useMemo(() => detectRelationCycles(relations), [relations]);
  const runtimeInteractionEntries = useMemo(
    () => [...runtimeInteractions.entries].sort((left, right) => right.timestamp - left.timestamp),
    [runtimeInteractions.entries]
  );
  const runtimeThoughtEntries = useMemo<RuntimeThoughtEntry[]>(() => {
    const thoughts: RuntimeThoughtEntry[] = [];
    for (const entry of runtimeInteractionEntries) {
      const speechText = getSpeechOutputText(entry);
      [...entry.outputs]
        .sort((left, right) => right.timestamp - left.timestamp)
        .forEach((output, index) => {
          if (output.type !== "inner_monologue" || !output.text.trim()) {
            return;
          }
          thoughts.push({
            id: `${entry.timestamp}-${output.timestamp}-${index}`,
            interactionTimestamp: entry.timestamp,
            outputTimestamp: output.timestamp,
            triggerSource: entry.triggerSource,
            userPrompt: entry.userPrompt,
            hasImage: entry.hasImage,
            text: output.text.trim(),
            speechText,
            actionTypes: entry.actionTypes
          });
        });
    }
    return thoughts.sort((left, right) => right.outputTimestamp - left.outputTimestamp);
  }, [runtimeInteractionEntries]);

  const customTagRows = useMemo(() => {
    const normalizedQuery = customQuery.trim().toLowerCase();
    const rows = customTags
      .map((entry) => ({
        entry,
        id: extractCustomTagId(entry.tag),
        description: descriptions[entry.tag]?.description ?? ""
      }))
      .sort((left, right) => left.entry.tag.localeCompare(right.entry.tag, "zh-Hans"));

    if (!normalizedQuery) {
      return rows;
    }

    return rows.filter((row) =>
      [row.entry.tag, row.id, row.description]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery)
    );
  }, [customQuery, customTags, descriptions]);

  const filteredDescriptionRows = useMemo(() => {
    const normalizedQuery = descriptionQuery.trim().toLowerCase();
    const rows = [...tagRecords]
      .sort((left, right) => left.label.localeCompare(right.label, "zh-Hans"))
      .map((record) => ({
        record,
        description: descriptions[record.tag]?.description ?? ""
      }));

    if (!normalizedQuery) {
      return rows;
    }

    return rows.filter(({ record, description }) =>
      [
        record.tag,
        record.label,
        record.description,
        description,
        record.kind,
        ...record.registryTypes,
        ...record.sourceMods
      ]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery)
    );
  }, [descriptionQuery, descriptions, tagRecords]);

  const filteredPersonas = useMemo(() => {
    const normalizedQuery = personaQuery.trim().toLowerCase();
    const base = [...personas]
      .filter((persona) => personaFocusFilter === "all" || personaHasFocus(persona, personaFocusFilter))
      .sort(comparePersonaEntries);

    if (!normalizedQuery) {
      return base;
    }

    return base.filter((persona) =>
      [
        ...getPersonaFocusTags(persona),
        ...getPersonaFocusTags(persona).map((focusTag) => FOCUS_TAG_LABELS[focusTag]),
        persona.description,
        ...(persona.detail_tags ?? [])
      ]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery)
    );
  }, [personaFocusFilter, personaQuery, personas]);

  const filteredMemories = useMemo(() => {
    const normalizedQuery = memoryQuery.trim().toLowerCase();
    const base = memories.map((memory, index) => ({ memory, index }));

    if (!normalizedQuery) {
      return base;
    }

    return base.filter(({ memory }) =>
      [
        memory.label ?? "",
        memory.description,
        ...(memory.focus_tags ?? []).map((focusTag) => `${focusTag} ${FOCUS_TAG_LABELS[focusTag]}`),
        memory.semantic_time_tag ?? "",
        ...(memory.detail_tags ?? []),
        memory.time_label ?? ""
      ]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery)
    );
  }, [memoryQuery, memories]);

  const filteredLocations = useMemo(() => {
    const normalizedQuery = locationQuery.trim().toLowerCase();
    const rows = Object.entries(locations)
      .sort((left, right) => left[0].localeCompare(right[0], "zh-Hans"))
      .map(([name, entry]) => ({ name, entry }));

    if (!normalizedQuery) {
      return rows;
    }

    return rows.filter(({ name, entry }) =>
      [
        name,
        entry.dim,
        entry.note ?? "",
        entry.focusTag ?? "",
        ...(entry.detailTags ?? [])
      ]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery)
    );
  }, [locationQuery, locations]);

  const schemaSummary = Object.entries(schemaVersions)
    .map(([key, version]) => `${key}:v${version}`)
    .join(" | ");

  const updateCatalogDraft = (patch: Partial<CustomTagDraft>) => {
    setCatalogMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          ...patch
        }
      };
    });
  };

  const openCreateTagEditor = () => {
    setCatalogMode({
      mode: "create",
      draft: { ...EMPTY_CUSTOM_TAG_DRAFT }
    });
  };

  const openEditTagEditor = (entry: CustomTagEntry) => {
    setCatalogMode({
      mode: "edit",
      originalTag: entry.tag,
      draft: {
        kind: entry.kind,
        id: extractCustomTagId(entry.tag),
        description: descriptions[entry.tag]?.description ?? ""
      }
    });
  };

  const closeTagEditor = () => {
    setCatalogMode({ mode: "list" });
  };

  const openEditDescriptionEditor = (tag: string) => {
    setDescriptionMode({
      mode: "edit",
      tag,
      draft: descriptions[tag]?.description ?? ""
    });
  };

  const closeDescriptionEditor = () => {
    setDescriptionMode({ mode: "list" });
  };

  const updateDescriptionDraft = (nextDescription: string) => {
    setDescriptionMode((currentMode) =>
      currentMode.mode === "list"
        ? currentMode
        : {
            ...currentMode,
            draft: nextDescription
          }
    );
  };

  const saveDescription = () => {
    if (descriptionMode.mode === "list") {
      return;
    }

    const normalizedDescription = descriptionMode.draft.trim();
    setDescriptions((currentDescriptions) => {
      const nextDescriptions = { ...currentDescriptions };
      if (!normalizedDescription) {
        delete nextDescriptions[descriptionMode.tag];
        return nextDescriptions;
      }

      nextDescriptions[descriptionMode.tag] = {
        ...(currentDescriptions[descriptionMode.tag] ?? {}),
        description: normalizedDescription,
        source: "user"
      };
      return nextDescriptions;
    });
    setDescriptionMode({ mode: "list" });
  };

  const saveCustomTag = () => {
    if (catalogMode.mode === "list") {
      return;
    }

    const normalizedId = catalogMode.draft.id.trim();
    if (!normalizedId) {
      return;
    }

    const nextTag = buildCustomTagValue(catalogMode.draft.kind, normalizedId);
    const previousTag = catalogMode.mode === "edit" ? catalogMode.originalTag : undefined;
    const previousEntry =
      catalogMode.mode === "edit" ? customTags.find((entry) => entry.tag === catalogMode.originalTag) : undefined;

    const nextEntry: CustomTagEntry = {
      ...(previousEntry ?? {}),
      tag: nextTag,
      kind: catalogMode.draft.kind,
      label: previousEntry?.label ?? normalizedId
    };

    setCustomTags((currentTags) => {
      const filtered = currentTags.filter((entry) => entry.tag !== previousTag && entry.tag !== nextTag);
      return [...filtered, nextEntry].sort((left, right) => left.tag.localeCompare(right.tag, "zh-Hans"));
    });

    setDescriptions((currentDescriptions) => {
      const nextDescriptions = { ...currentDescriptions };
      const previousDescription = previousTag ? currentDescriptions[previousTag] : undefined;

      if (previousTag && previousTag !== nextTag) {
        delete nextDescriptions[previousTag];
      }

      nextDescriptions[nextTag] = {
        ...(previousDescription ?? currentDescriptions[nextTag]),
        description: catalogMode.draft.description.trim(),
        source: "user"
      };

      return nextDescriptions;
    });

    if (previousTag && previousTag !== nextTag) {
      setRelations((currentRelations) =>
        normalizeRelationMap(renameTagInRelations(currentRelations, previousTag, nextTag))
      );
      setPersonas((currentPersonas) => renameTagInPersonas(currentPersonas, previousTag, nextTag));
      setMemories((currentMemories) => renameTagInMemories(currentMemories, previousTag, nextTag));
      setLocations((currentLocations) => renameTagInLocations(currentLocations, previousTag, nextTag));
    }

    setCatalogMode({ mode: "list" });
  };

  const deleteCustomTag = (tag: string) => {
    setCustomTags((currentTags) => currentTags.filter((entry) => entry.tag !== tag));
    setDescriptions((currentDescriptions) => {
      const nextDescriptions = { ...currentDescriptions };
      delete nextDescriptions[tag];
      return nextDescriptions;
    });
    setRelations((currentRelations) => normalizeRelationMap(deleteTagFromRelations(currentRelations, tag)));
    setPersonas((currentPersonas) => removeTagFromPersonas(currentPersonas, tag));
    setMemories((currentMemories) => removeTagFromMemories(currentMemories, tag));
    setLocations((currentLocations) => removeTagFromLocations(currentLocations, tag));

    setCatalogMode((currentMode) =>
      currentMode.mode === "edit" && currentMode.originalTag === tag ? { mode: "list" } : currentMode
    );
    setDescriptionMode((currentMode) =>
      currentMode.mode === "edit" && currentMode.tag === tag ? { mode: "list" } : currentMode
    );
  };

  const updateRelationDraft = (patch: Partial<RelationDraft>) => {
    setRelationMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          ...patch
        }
      };
    });
  };

  const openEditRelationEditor = (sourceTag: string) => {
    setRelationMode({
      mode: "edit",
      originalSourceTag: sourceTag,
      draft: {
        sourceTag,
        linkedTags: [...(relations[sourceTag] ?? [])]
      }
    });
    setRelationFeedback({
      tone: "neutral",
      message: "编辑 source tag 的联动列表。linked tags 默认作为内部引用，不会直接进入 LLM Prompt。"
    });
  };

  const closeRelationEditor = () => {
    setRelationMode({ mode: "list" });
  };

  const setRelationSourceTag = (tag: string) => {
    updateRelationDraft({ sourceTag: tag });
  };

  const addRelationLinkedTag = (tag: string) => {
    setRelationMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          linkedTags: Array.from(new Set([...currentMode.draft.linkedTags, tag]))
        }
      };
    });
  };

  const removeRelationLinkedTag = (tag: string) => {
    setRelationMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          linkedTags: currentMode.draft.linkedTags.filter((entry) => entry !== tag)
        }
      };
    });
  };

  const saveRelation = () => {
    if (relationMode.mode === "list") {
      return;
    }

    const sourceTag = relationMode.draft.sourceTag.trim();
    const linkedTags = normalizeRelationTargets(relationMode.draft.linkedTags);
    if (!sourceTag) {
      setRelationFeedback({
        tone: "bad",
        message: "保存失败：必须先选择 source tag。"
      });
      return;
    }

    if (!linkedTags.length) {
      setRelationFeedback({
        tone: "bad",
        message: "保存失败：至少要选择一个 linked tag。"
      });
      return;
    }

    const nextRelations = upsertRelationEntry(
      relations,
      relationMode.mode === "edit" ? relationMode.originalSourceTag : undefined,
      sourceTag,
      linkedTags
    );
    const nextCycles = detectRelationCycles(nextRelations);
    if (nextCycles.length) {
      setRelationFeedback({
        tone: "bad",
        message: `保存失败：relations 图出现环 ${nextCycles[0].join(" -> ")}`
      });
      return;
    }

    setRelations(nextRelations);
    setRelationMode({ mode: "list" });
    setRelationFeedback({
      tone: "good",
      message: `已保存 ${sourceTag} 的联动关系，共 ${linkedTags.length} 个 linked tags。`
    });
  };

  const applyRelationWorkspaceUpdate = (
    sourceTags: string[],
    operation: RelationBatchOperation,
    linkedTags: string[]
  ) => {
    const normalizedSourceTags = Array.from(new Set(sourceTags.map((tag) => tag.trim()).filter(Boolean))).sort(
      (left, right) => left.localeCompare(right, "zh-Hans")
    );
    const normalizedLinkedTags = normalizeRelationTargets(linkedTags);

    if (!normalizedSourceTags.length) {
      setRelationFeedback({
        tone: "bad",
        message: "批量操作失败：工作区中至少要有一个 source tag。"
      });
      return;
    }

    if (!normalizedLinkedTags.length) {
      setRelationFeedback({
        tone: "bad",
        message: "批量操作失败：右侧至少要选择一个关联 tag。"
      });
      return;
    }

    const nextRelations = applyRelationBatchUpdate(relations, normalizedSourceTags, operation, normalizedLinkedTags);
    const nextCycles = detectRelationCycles(nextRelations);
    if (nextCycles.length) {
      setRelationFeedback({
        tone: "bad",
        message: `批量操作失败：会引入环 ${nextCycles[0].join(" -> ")}`
      });
      return;
    }

    setRelations(nextRelations);
    setRelationFeedback({
      tone: "good",
      message: `已对工作区内 ${normalizedSourceTags.length} 个 source tags 执行批量${
        operation === "add" ? "增加" : "移除"
      }。`
    });
  };

  const updatePersonaDraft = (patch: Partial<PersonaDraft>) => {
    setPersonaMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          ...patch
        }
      };
    });
  };

  const openCreatePersonaEditor = () => {
    setPersonaMode({
      mode: "create",
      draft: { ...EMPTY_PERSONA_DRAFT }
    });
  };

  const openEditPersonaEditor = (entry: PersonaEntry) => {
    setPersonaMode({
      mode: "edit",
      originalId: entry.id,
      draft: {
        focus_tags: getPersonaFocusTags(entry),
        description: entry.description,
        always_recalled: entry.always_recalled ?? false,
        detail_tags: [...(entry.detail_tags ?? [])]
      }
    });
  };

  const closePersonaEditor = () => {
    setPersonaMode({ mode: "list" });
  };

  const togglePersonaFocusTag = (focusTag: FocusTag) => {
    setPersonaMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      const exists = currentMode.draft.focus_tags.includes(focusTag);
      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          focus_tags: exists
            ? currentMode.draft.focus_tags.filter((entry) => entry !== focusTag)
            : [...currentMode.draft.focus_tags, focusTag]
        }
      };
    });
  };

  const addPersonaDetailTag = (tag: string) => {
    setPersonaMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          detail_tags: Array.from(new Set([...currentMode.draft.detail_tags, tag]))
        }
      };
    });
  };

  const removePersonaDetailTag = (tag: string) => {
    setPersonaMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          detail_tags: currentMode.draft.detail_tags.filter((entry) => entry !== tag)
        }
      };
    });
  };

  const savePersona = () => {
    if (personaMode.mode === "list") {
      return;
    }

    const normalizedDescription = personaMode.draft.description.trim();
    if (!normalizedDescription) {
      return;
    }
    const normalizedFocusTags = normalizePersonaFocusTags(personaMode.draft.focus_tags);
    if (!normalizedFocusTags.length) {
      return;
    }

    const nextEntry: PersonaEntry = {
      id: personaMode.mode === "edit" ? personaMode.originalId : createStudioId("persona"),
      enabled: true,
      focus_tags: normalizedFocusTags,
      description: normalizedDescription,
      always_recalled: personaMode.draft.always_recalled,
      detail_tags: personaMode.draft.detail_tags,
      notes:
        personaMode.mode === "edit"
          ? personas.find((entry) => entry.id === personaMode.originalId)?.notes
          : undefined
    };

    setPersonas((currentPersonas) => {
      const filtered = currentPersonas.filter((entry) => entry.id !== nextEntry.id);
      return [...filtered, nextEntry].sort(comparePersonaEntries);
    });
    setPersonaMode({ mode: "list" });
  };

  const deletePersona = (id: string) => {
    setPersonas((currentPersonas) => currentPersonas.filter((entry) => entry.id !== id));
    setPersonaMode((currentMode) =>
      currentMode.mode === "edit" && currentMode.originalId === id ? { mode: "list" } : currentMode
    );
  };

  const updateMemoryDraft = (patch: Partial<MemoryDraft>) => {
    setMemoryMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          ...patch
        }
      };
    });
  };

  const openCreateMemoryEditor = () => {
    setMemoryMode({
      mode: "create",
      draft: { ...EMPTY_MEMORY_DRAFT }
    });
  };

  const openEditMemoryEditor = (index: number) => {
    setMemoryMode({
      mode: "edit",
      originalIndex: index,
      draft: buildMemoryDraft(memories[index])
    });
  };

  const closeMemoryEditor = () => {
    setMemoryMode({ mode: "list" });
  };

  const toggleMemoryFocusTag = (focusTag: FocusTag) => {
    setMemoryMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      const hasFocus = currentMode.draft.focus_tags.includes(focusTag);
      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          focus_tags: hasFocus
            ? currentMode.draft.focus_tags.filter((entry) => entry !== focusTag)
            : [...currentMode.draft.focus_tags, focusTag]
        }
      };
    });
  };

  const addMemoryDetailTag = (tag: string) => {
    setMemoryMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          detail_tags: Array.from(new Set([...currentMode.draft.detail_tags, tag]))
        }
      };
    });
  };

  const removeMemoryDetailTag = (tag: string) => {
    setMemoryMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          detail_tags: currentMode.draft.detail_tags.filter((entry) => entry !== tag)
        }
      };
    });
  };

  const saveMemory = () => {
    if (memoryMode.mode === "list") {
      return;
    }

    const normalizedDescription = memoryMode.draft.description.trim();
    if (!normalizedDescription) {
      return;
    }

    const baseMemory = memoryMode.mode === "edit" ? memories[memoryMode.originalIndex] : undefined;
    const derivedTimeLabel = deriveMemoryTimeLabel(tagRecords, memoryMode.draft.semantic_time_tag.trim());
    const nextMemory = buildMemoryEntryFromDraft(
      {
        ...memoryMode.draft,
        description: normalizedDescription
      },
      baseMemory,
      derivedTimeLabel
    );

    setMemories((currentMemories) => {
      if (memoryMode.mode === "edit") {
        return currentMemories.map((memory, index) =>
          index === memoryMode.originalIndex ? nextMemory : memory
        );
      }

      return [...currentMemories, nextMemory];
    });
    setMemoryMode({ mode: "list" });
  };

  const deleteMemory = (index: number) => {
    setMemories((currentMemories) => currentMemories.filter((_, currentIndex) => currentIndex !== index));
    setMemoryMode((currentMode) =>
      currentMode.mode === "edit" && currentMode.originalIndex === index ? { mode: "list" } : currentMode
    );
  };

  const updateLocationDraft = (patch: Partial<LocationDraft>) => {
    setLocationMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          ...patch
        }
      };
    });
  };

  const openCreateLocationEditor = () => {
    setLocationMode({
      mode: "create",
      draft: { ...EMPTY_LOCATION_DRAFT }
    });
  };

  const openEditLocationEditor = (name: string) => {
    setLocationMode({
      mode: "edit",
      originalName: name,
      draft: buildLocationDraft(name, locations[name])
    });
  };

  const closeLocationEditor = () => {
    setLocationMode({ mode: "list" });
  };

  const addLocationDetailTag = (tag: string) => {
    setLocationMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          detailTags: Array.from(new Set([...currentMode.draft.detailTags, tag]))
        }
      };
    });
  };

  const removeLocationDetailTag = (tag: string) => {
    setLocationMode((currentMode) => {
      if (currentMode.mode === "list") {
        return currentMode;
      }

      return {
        ...currentMode,
        draft: {
          ...currentMode.draft,
          detailTags: currentMode.draft.detailTags.filter((entry) => entry !== tag)
        }
      };
    });
  };

  const saveLocation = () => {
    if (locationMode.mode === "list") {
      return;
    }

    const normalizedName = locationMode.draft.name.trim().toLowerCase();
    if (!normalizedName) {
      return;
    }

    const x = Number.parseInt(locationMode.draft.x, 10);
    const y = Number.parseInt(locationMode.draft.y, 10);
    const z = Number.parseInt(locationMode.draft.z, 10);
    const horizontalRadius = Number.parseInt(locationMode.draft.horizontalRadius, 10);
    const verticalRadius = Number.parseInt(locationMode.draft.verticalRadius, 10);
    const nextEntry: LocationRegistryEntry = {
      x: Number.isFinite(x) ? x : 0,
      y: Number.isFinite(y) ? y : 64,
      z: Number.isFinite(z) ? z : 0,
      dim: locationMode.draft.dim.trim() || "minecraft:overworld",
      note: locationMode.draft.note.trim(),
      horizontalRadius: Number.isFinite(horizontalRadius) && horizontalRadius > 0 ? horizontalRadius : 24,
      verticalRadius: Number.isFinite(verticalRadius) && verticalRadius > 0 ? verticalRadius : 16,
      focusTag: locationMode.draft.focusTag,
      detailTags: locationMode.draft.detailTags
    };

    setLocations((currentLocations) => {
      const nextLocations = { ...currentLocations };
      if (locationMode.mode === "edit" && locationMode.originalName !== normalizedName) {
        delete nextLocations[locationMode.originalName];
      }
      nextLocations[normalizedName] = nextEntry;
      return Object.fromEntries(
        Object.entries(nextLocations).sort((left, right) => left[0].localeCompare(right[0], "zh-Hans"))
      );
    });
    setLocationMode({ mode: "list" });
  };

  const deleteLocation = (name: string) => {
    setLocations((currentLocations) => {
      const nextLocations = { ...currentLocations };
      delete nextLocations[name];
      return nextLocations;
    });
    setLocationMode((currentMode) =>
      currentMode.mode === "edit" && currentMode.originalName === name ? { mode: "list" } : currentMode
    );
  };

  const updateAgentRuntimeState = (patch: Partial<AgentStateWorkspaceFile["state"]>) => {
    setAgentStateFile((currentFile) => ({
      ...currentFile,
      state: {
        ...currentFile.state,
        ...patch
      }
    }));
  };

  const updateAgentPersonaState = (patch: Partial<AgentStateWorkspaceFile["persona_state"]>) => {
    setAgentStateFile((currentFile) => ({
      ...currentFile,
      persona_state: {
        ...currentFile.persona_state,
        ...patch
      }
    }));
  };

  const updateAgentAttentionWeight = (weightKey: AgentStateWeightKey, nextValue: number) => {
    setAgentStateFile((currentFile) => ({
      ...currentFile,
      attention_weights: {
        ...currentFile.attention_weights,
        [weightKey]: nextValue
      }
    }));
  };

  const updateMemoryRecallTuning = (patch: Partial<MemoryRecallTuningFile>) => {
    setMemoryRecallTuningFile((currentFile) => sanitizeMemoryRecallTuningFile({
      ...currentFile,
      ...patch
    }));
  };

  const updateActionTimelineTuning = (patch: Partial<ActionTimelineTuningFile>) => {
    setActionTimelineTuningFile((currentFile) =>
      normalizeActionTimelineTuningFile({
        ...currentFile,
        ...patch
      }) ?? currentFile
    );
  };

  const updateImportanceEntry = (key: string, nextValue: number) => {
    const normalizedKey = key.trim();
    if (!normalizedKey) {
      return;
    }
    setImportanceFile((currentFile) => ({
      ...currentFile,
      [normalizedKey]: clampNumber(nextValue, 0, 1)
    }));
  };

  const removeImportanceEntry = (key: string) => {
    setImportanceFile((currentFile) => {
      const next = { ...currentFile };
      delete next[key];
      return next;
    });
  };

  const addImportanceEntry = () => {
    const normalizedKey = newImportanceKey.trim();
    const parsedValue = Number.parseFloat(newImportanceValue);
    if (!normalizedKey || !Number.isFinite(parsedValue)) {
      return;
    }
    setImportanceFile((currentFile) => ({
      ...currentFile,
      [normalizedKey]: clampNumber(parsedValue, 0, 1)
    }));
    setNewImportanceKey("");
  };

  const updatePromptEntry = (key: string, nextValue: string) => {
    const normalizedKey = key.trim();
    if (!normalizedKey) {
      return;
    }
    setPromptsFile((currentFile) => ({
      ...currentFile,
      [normalizedKey]: nextValue
    }));
  };

  const removePromptEntry = (key: string) => {
    setPromptsFile((currentFile) => {
      const next = { ...currentFile };
      delete next[key];
      return next;
    });
  };

  const addPromptEntry = () => {
    const normalizedKey = newPromptKey.trim();
    if (!normalizedKey) {
      return;
    }
    setPromptsFile((currentFile) => ({
      ...currentFile,
      [normalizedKey]: newPromptValue
    }));
    setNewPromptKey("");
  };

  const filteredImportanceEntries = useMemo(() => {
    const normalizedQuery = importanceQuery.trim().toLowerCase();
    return Object.entries(importanceFile)
      .filter(([key]) => !normalizedQuery || key.toLowerCase().includes(normalizedQuery))
      .sort((left, right) => left[0].localeCompare(right[0], "zh-CN"));
  }, [importanceFile, importanceQuery]);

  const filteredPromptEntries = useMemo(() => {
    const normalizedQuery = promptQuery.trim().toLowerCase();
    return Object.entries(promptsFile)
      .filter(([key, value]) =>
        !normalizedQuery
        || key.toLowerCase().includes(normalizedQuery)
        || value.toLowerCase().includes(normalizedQuery))
      .sort((left, right) => left[0].localeCompare(right[0], "zh-CN"));
  }, [promptQuery, promptsFile]);

  const buildImportanceFile = (): ImportanceRegistryFile =>
    Object.fromEntries(
      Object.entries(importanceFile)
        .filter(([key, value]) => key.trim().length > 0 && Number.isFinite(value))
        .sort((left, right) => left[0].localeCompare(right[0], "zh-CN"))
        .map(([key, value]) => [key.trim(), clampNumber(value, 0, 1)])
    );

  const buildPromptsFile = (): PromptRegistryFile =>
    Object.fromEntries(
      Object.entries(promptsFile)
        .filter(([key, value]) => key.trim().length > 0 && typeof value === "string")
        .sort((left, right) => left[0].localeCompare(right[0], "zh-CN"))
        .map(([key, value]) => [key.trim(), value])
    );

  const buildCustomTagsFile = (): CustomTagsFile => ({
    schemaVersion: schemaVersions.customTags,
    tags: customTags
  });

  const buildTagDescriptionsFile = (): TagDescriptionsFile => ({
    schemaVersion: schemaVersions.descriptions,
    descriptions
  });

  const buildTagRelationsFile = (): TagRelationsFile => ({
    schemaVersion: schemaVersions.relations,
    relations: normalizeRelationMap(relations)
  });

  const buildPersonasFile = (): PersonasFile => ({
    schemaVersion: schemaVersions.personas,
    personas
  });

  const buildImplantedMemoriesFile = (): ImplantedMemoriesFile => ({
    schemaVersion: schemaVersions.implantedMemories,
    memories
  });

  const buildAgentStateFile = (): AgentStateWorkspaceFile => ({
    ...sanitizeAgentStateFile(agentStateFile),
    schemaVersion: schemaVersions.agentState
  });

  const buildMemoryRecallTuningFile = (): MemoryRecallTuningFile => ({
    ...sanitizeMemoryRecallTuningFile(memoryRecallTuningFile),
    schemaVersion: schemaVersions.recallTuning
  });

  const buildActionTimelineTuningFile = (): ActionTimelineTuningFile => ({
    ...(normalizeActionTimelineTuningFile(actionTimelineTuningFile) ?? createEmptyActionTimelineTuningFile()),
    schemaVersion: schemaVersions.actionTimelineTuning
  });

  const buildLocationRegistryFile = (): Record<string, unknown> => buildLocationRegistryJson(locations);

  const editableWorkspaceFiles: Array<{
    filename: string;
    versionKey: Extract<keyof WorkspaceManifestVersions, "actionTimelineTuning" | "memoryRecallTuning" | "importance" | "prompts" | "customTags" | "descriptions" | "relations" | "personas" | "memories" | "agentState" | "locations">;
    build: () => unknown;
  }> = [
    {
      filename: "action_timeline_tuning.json",
      versionKey: "actionTimelineTuning",
      build: buildActionTimelineTuningFile
    },
    {
      filename: "memory_recall_tuning.json",
      versionKey: "memoryRecallTuning",
      build: buildMemoryRecallTuningFile
    },
    {
      filename: "importance.json",
      versionKey: "importance",
      build: buildImportanceFile
    },
    {
      filename: "prompts.json",
      versionKey: "prompts",
      build: buildPromptsFile
    },
    {
      filename: "custom_tags.json",
      versionKey: "customTags",
      build: buildCustomTagsFile
    },
    {
      filename: "tag_descriptions.json",
      versionKey: "descriptions",
      build: buildTagDescriptionsFile
    },
    {
      filename: "tag_relations.json",
      versionKey: "relations",
      build: buildTagRelationsFile
    },
    {
      filename: "personas.json",
      versionKey: "personas",
      build: buildPersonasFile
    },
    {
      filename: "implanted_memories.json",
      versionKey: "memories",
      build: buildImplantedMemoriesFile
    },
    {
      filename: "agent_state.json",
      versionKey: "agentState",
      build: buildAgentStateFile
    },
    {
      filename: "location_registry.json",
      versionKey: "locations",
      build: buildLocationRegistryFile
    }
  ];

  const resetEditorsAfterImport = () => {
    setCatalogMode({ mode: "list" });
    setDescriptionMode({ mode: "list" });
    setRelationMode({ mode: "list" });
    setPersonaMode({ mode: "list" });
    setMemoryMode({ mode: "list" });
    setLocationMode({ mode: "list" });
  };

  const getPendingEditorLabel = (): string | null => {
    if (catalogMode.mode !== "list") {
      return "Custom Tag";
    }
    if (descriptionMode.mode !== "list") {
      return "Description";
    }
    if (relationMode.mode !== "list") {
      return "Relation";
    }
    if (personaMode.mode !== "list") {
      return "Persona";
    }
    if (memoryMode.mode !== "list") {
      return "Memory";
    }
    if (locationMode.mode !== "list") {
      return "Location";
    }
    return null;
  };

  const workspacePathHint = ".minecraft/config/talking_weapon/studio_workspace/";

  const applyWorkspaceDataPatch = (importedData: ImportedWorkspaceData) => {
    if (importedData.nativeCatalogSnapshot) {
      setNativeCatalogSnapshot(importedData.nativeCatalogSnapshot);
      setSchemaVersions((current) => ({
        ...current,
        nativeCatalogSnapshot: importedData.nativeCatalogSnapshot?.schemaVersion ?? current.nativeCatalogSnapshot
      }));
    }
    if (importedData.runtimeInteractions) {
      setRuntimeInteractions(importedData.runtimeInteractions);
    }
    if (importedData.runtimeActionTimeline || importedData.runtimeActionSegmentStore) {
      const baseTimeline = importedData.runtimeActionTimeline ?? runtimeActionTimeline;
      setRuntimeActionTimeline(
        hydrateRuntimeActionTimeline(baseTimeline, importedData.runtimeActionSegmentStore, runtimeActionTimeline)
      );
    }
    if (importedData.actionTimelineTuning) {
      setActionTimelineTuningFile(importedData.actionTimelineTuning);
      setSchemaVersions((current) => ({
        ...current,
        actionTimelineTuning: importedData.actionTimelineTuning?.schemaVersion ?? current.actionTimelineTuning
      }));
    }
    if (importedData.runtimeRecallTrace) {
      setRuntimeRecallTrace(importedData.runtimeRecallTrace);
    }
    if (importedData.memoryRecallTuning) {
      setMemoryRecallTuningFile(sanitizeMemoryRecallTuningFile(importedData.memoryRecallTuning));
      setSchemaVersions((current) => ({
        ...current,
        recallTuning: importedData.memoryRecallTuning?.schemaVersion ?? current.recallTuning
      }));
    }
    if (importedData.importance) {
      setImportanceFile(importedData.importance);
    }
    if (importedData.prompts) {
      setPromptsFile(importedData.prompts);
    }
    if (importedData.customTags) {
      setCustomTags(importedData.customTags.tags);
      setSchemaVersions((current) => ({
        ...current,
        customTags: importedData.customTags?.schemaVersion ?? current.customTags
      }));
    }
    if (importedData.descriptions) {
      setDescriptions(importedData.descriptions.descriptions);
      setSchemaVersions((current) => ({
        ...current,
        descriptions: importedData.descriptions?.schemaVersion ?? current.descriptions
      }));
    }
    if (importedData.relations) {
      setRelations(normalizeRelationMap(importedData.relations.relations));
      setSchemaVersions((current) => ({
        ...current,
        relations: importedData.relations?.schemaVersion ?? current.relations
      }));
    }
    if (importedData.personas) {
      setPersonas(importedData.personas.personas);
      setSchemaVersions((current) => ({
        ...current,
        personas: importedData.personas?.schemaVersion ?? current.personas
      }));
    }
    if (importedData.memories) {
      setMemories(importedData.memories.memories);
      setSchemaVersions((current) => ({
        ...current,
        implantedMemories: importedData.memories?.schemaVersion ?? current.implantedMemories
      }));
    }
    if (importedData.agentState) {
      setAgentStateFile(sanitizeAgentStateFile(importedData.agentState));
      setSchemaVersions((current) => ({
        ...current,
        agentState: Math.max(importedData.agentState?.schemaVersion ?? current.agentState, 4)
      }));
    }
    if (importedData.locations) {
      setLocations(importedData.locations);
    }
  };

  const applyImportedWorkspaceData = (
    importedData: ImportedWorkspaceData,
    importedParts: string[],
    ignoredFiles: string[] = []
  ) => {
    if (!importedParts.length) {
      setImportFeedback({
        tone: "bad",
        message: "工作区中没有可导入的数据文件。至少需要包含 snapshot 或各个 schema JSON。"
      });
      return;
    }

    applyWorkspaceDataPatch(importedData);
    resetEditorsAfterImport();
    setImportFeedback({
      tone: "good",
      message: `已导入工作区：${importedParts.join(" / ")}${
        ignoredFiles.length ? `。已忽略 ${ignoredFiles.length} 个当前前端未使用的文件。` : "。"
      }`
    });
  };

  const requestWorkspaceDirectory = async (mode: "read" | "readwrite"): Promise<FileSystemDirectoryHandle | null> => {
    const inflightRequest = workspaceDirectoryRequestRef.current;
    if (inflightRequest) {
      return await inflightRequest;
    }

    const runRequest = async (): Promise<FileSystemDirectoryHandle | null> => {
    const existingHandle = workspaceDirectoryHandle;
    if (existingHandle && (await ensureDirectoryPermission(existingHandle, mode))) {
      return existingHandle;
    }

      let pickedHandle: FileSystemDirectoryHandle | null;
      try {
        pickedHandle = await pickDirectoryHandle(mode);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        if (message.includes("File picker already active")) {
          setImportFeedback({
            tone: "bad",
            message: "目录选择窗口已经打开。请先完成或关闭当前文件夹选择，再重试。"
          });
          return null;
        }
        throw error;
      }

    if (!pickedHandle) {
      return null;
    }

    if (!(await ensureDirectoryPermission(pickedHandle, mode))) {
      setImportFeedback({
        tone: "bad",
        message: "未获得工作区目录访问权限。"
      });
      return null;
    }

    setWorkspaceDirectoryHandle(pickedHandle);
    setWorkspaceDirectoryName(pickedHandle.name);
    setWorkspaceDebugInfo({
      accessMode: describeWorkspaceAccessMode(),
      selectedPath: describeWorkspaceDirectoryHandle(pickedHandle),
      manifestPresent: "unknown",
      importedParts: [],
      errorMessage: ""
    });
    return pickedHandle;
    };

    const requestPromise = runRequest().finally(() => {
      if (workspaceDirectoryRequestRef.current === requestPromise) {
        workspaceDirectoryRequestRef.current = null;
      }
    });
    workspaceDirectoryRequestRef.current = requestPromise;
    return await requestPromise;
  };

  const importWorkspaceFromDirectoryHandle = async (directoryHandle: FileSystemDirectoryHandle) => {
    const manifest = await readWorkspaceManifestFromDirectory(directoryHandle);
    const { importedData, importedParts } = await loadWorkspaceDataFromDirectory(directoryHandle, null);

    setWorkspaceImportedFiles(new Map());
    setWorkspaceManifest(manifest);
    setIconAssetVersion(`desktop-import:${Date.now()}`);
    setWorkspaceDebugInfo({
      accessMode: describeWorkspaceAccessMode(),
      selectedPath: describeWorkspaceDirectoryHandle(directoryHandle),
      manifestPresent: manifest ? "yes" : "no",
      importedParts,
      errorMessage: ""
    });
    applyImportedWorkspaceData(importedData, importedParts);
  };

  useEffect(() => {
    if (!workspaceDirectoryHandle || !isDesktopWorkspaceHandle(workspaceDirectoryHandle)) {
      void stopDesktopWorkspaceWatcher();
      return;
    }

    let cancelled = false;
    let removeListener: (() => void) | null = null;

    const connectWatcher = async () => {
      try {
        const started = await startDesktopWorkspaceWatcher(workspaceDirectoryHandle);
        if (cancelled || !started) {
          return;
        }

        removeListener = onDesktopWorkspaceChanged(async (_payload) => {
          if (cancelled || workspaceWatcherRefreshRef.current) {
            return;
          }
          workspaceWatcherRefreshRef.current = true;
          try {
            await importWorkspaceFromDirectoryHandle(workspaceDirectoryHandle);
            if (!cancelled) {
              setImportFeedback({
                tone: "good",
                message: "已通过桌面 watcher 自动同步工作区变化。"
              });
            }
          } catch (error) {
            if (!cancelled) {
              const message = error instanceof Error ? error.message : String(error);
              setImportFeedback({
                tone: "bad",
                message: `工作区 watcher 同步失败：${message}`
              });
            }
          } finally {
            workspaceWatcherRefreshRef.current = false;
          }
        });
      } catch (error) {
        if (!cancelled) {
          const message = error instanceof Error ? error.message : String(error);
          setImportFeedback({
            tone: "bad",
            message: `启动桌面 watcher 失败：${message}`
          });
        }
      }
    };

    void connectWatcher();

    return () => {
      cancelled = true;
      if (removeListener) {
        removeListener();
      }
      void stopDesktopWorkspaceWatcher();
    };
  }, [workspaceDirectoryHandle]);

  useEffect(() => {
    let cancelled = false;

    const probeBridge = async () => {
      try {
        const health = await pingGameBridge();
        if (cancelled) {
          return;
        }

        setGameBridgeStatus({
          state: "connected",
          message: `游戏已连接：${health.workspaceRoot}`
        });
      } catch (_error) {
        if (cancelled) {
          return;
        }
        setGameBridgeStatus({
          state: "fallback",
          message: "未连接到游戏内 HTTP bridge，当前只能本地编辑工作区，无法自动通知游戏热重载。"
        });
      }
    };

    void probeBridge();
    const intervalId = window.setInterval(() => {
      void probeBridge();
    }, 5000);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, []);

  const importWorkspace = async () => {
    try {
      if (supportsDirectoryAccessApi()) {
        const directoryHandle = await requestWorkspaceDirectory("read");
        if (!directoryHandle) {
          return;
        }
        await importWorkspaceFromDirectoryHandle(directoryHandle);
        return;
      }

      folderImportInputRef.current?.click();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setWorkspaceDebugInfo({
        accessMode: describeWorkspaceAccessMode(),
        selectedPath: describeWorkspaceDirectoryHandle(workspaceDirectoryHandle),
        manifestPresent: "unknown",
        importedParts: [],
        errorMessage: message
      });
      setImportFeedback({
        tone: "bad",
        message: `导入工作区失败：${message}`
      });
    }
  };

  const exportWorkspace = async () => {
    try {
      const pendingEditorLabel = getPendingEditorLabel();
      if (pendingEditorLabel) {
        setImportFeedback({
          tone: "bad",
          message: `当前有未保存的 ${pendingEditorLabel} 草稿。请先点击当前编辑页的“保存”，再保存工作区。`
        });
        return;
      }

      if (!supportsDirectoryAccessApi()) {
        setImportFeedback({
          tone: "bad",
          message: "当前浏览器不支持直接写入工作区目录。请使用 Chromium 内核浏览器打开 Studio。"
        });
        return;
      }

      const directoryHandle = await requestWorkspaceDirectory("readwrite");
      if (!directoryHandle) {
        return;
      }

      const manifestBeforeWrite = workspaceManifest ?? (await readWorkspaceManifestFromDirectory(directoryHandle));
      const versionToken = Date.now().toString();
      const updatedVersions: Partial<WorkspaceManifestVersions> = {};

      for (const descriptor of editableWorkspaceFiles) {
        await writeJsonFileToDirectory(directoryHandle, descriptor.filename, descriptor.build());
        updatedVersions[descriptor.versionKey] = versionToken;
      }

      const nextManifest = buildNextWorkspaceManifest(manifestBeforeWrite, "studio", updatedVersions);
      await writeJsonFileToDirectory(directoryHandle, "studio_workspace.manifest.json", nextManifest);
      setWorkspaceManifest(nextManifest);
      const exportedMessage = `已保存工作区到 ${workspaceDirectoryName || directoryHandle.name}。`;

      try {
        const publishResult = await publishWorkspaceToGame();
        setGameBridgeStatus({
          state: "connected",
          message: "游戏已连接：HTTP bridge 热重载可用。"
        });
        setImportFeedback({
          tone: "good",
          message: `${exportedMessage} 已通知游戏热重载，共应用 ${publishResult.publishedFiles.length} 个文件。`
        });
      } catch (publishError) {
        const message = publishError instanceof Error ? publishError.message : String(publishError);
        setGameBridgeStatus({
          state: "fallback",
          message: "未连接到游戏内 HTTP bridge，当前只能本地编辑工作区，无法自动通知游戏热重载。"
        });
        setImportFeedback({
          tone: "good",
          message: `${exportedMessage} 未能直接通知游戏热重载：${message}`
        });
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setImportFeedback({
        tone: "bad",
        message: `保存工作区失败：${message}`
      });
    }
  };

  const handleFolderImportChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";

    if (!files.length) {
      return;
    }

    const nextWorkspaceFiles = new Map<string, File>();
    const selectedFolderName = files[0]?.webkitRelativePath
      ? files[0].webkitRelativePath.replace(/\\/g, "/").split("/").filter(Boolean)[0] ?? ""
      : "";

    try {
      const { importedData, importedParts, ignoredFiles, workspaceFiles } = await loadWorkspaceDataFromFolderFiles(files);

      workspaceFiles.forEach((file, key) => {
        nextWorkspaceFiles.set(key, file);
      });
      setWorkspaceImportedFiles(nextWorkspaceFiles);
      setWorkspaceManifest(null);
      setIconAssetVersion(`folder-import:${Date.now()}`);
      setWorkspaceDirectoryHandle(null);
      setWorkspaceDirectoryName(selectedFolderName);
      setWorkspaceDebugInfo({
        accessMode: describeWorkspaceAccessMode(),
        selectedPath: selectedFolderName || "folder-upload",
        manifestPresent: files.some((file) => basenameOfPath(normalizeWorkspaceRelativePath(file.webkitRelativePath || file.name)) === "studio_workspace.manifest.json")
          ? "yes"
          : "no",
        importedParts,
        errorMessage: ""
      });
      applyImportedWorkspaceData(importedData, importedParts, ignoredFiles);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setWorkspaceDebugInfo({
        accessMode: describeWorkspaceAccessMode(),
        selectedPath: selectedFolderName || "folder-upload",
        manifestPresent: "unknown",
        importedParts: [],
        errorMessage: message
      });
      setImportFeedback({
        tone: "bad",
        message: `导入文件夹失败：${message}`
      });
    }
  };

  return (
    <IconAssetVersionContext.Provider value={iconAssetVersion}>
    <div className="studio-shell">
      <div className="studio-aurora" />
      <input
        {...({ webkitdirectory: "", directory: "" } as Record<string, string>)}
        ref={folderImportInputRef}
        className="hidden-import-input"
        multiple
        onChange={handleFolderImportChange}
        type="file"
      />
      <header className="hero">
        <div className="hero-intro">
          <p className="eyebrow">Talking Weapon Studio</p>
          <h1>语义编辑器工作台</h1>
          <p className="hero-copy">
            Studio 现在只围绕 Minecraft 工作区目录工作。模组负责导出 snapshot、icons 与运行时视图，
            前端只读取并回写可编辑的 schema 文件。
          </p>
        </div>
        <div className="hero-support">
          <section className="hero-metrics">
            <MetricCard label="Tag Records" value={String(tagRecords.length)} />
            <MetricCard label="Custom Tags" value={String(customTags.length)} />
            <MetricCard label="Persona Entries" value={String(personas.length)} />
            <MetricCard label="Memories" value={String(memories.length)} />
          </section>
          <section className="panel io-panel">
            <div className="panel-head io-head">
              <div>
                <p className="eyebrow">Workspace</p>
                <h2>工作区读取 / 保存</h2>
                <p className="muted-copy">
                  目标路径：<code>{workspacePathHint}</code>
                </p>
              </div>
            </div>
            <div className="workspace-meta">
              <span className="badge muted">
                {workspaceDirectoryName ? `已选择目录：${workspaceDirectoryName}` : "尚未选择工作区目录"}
              </span>
              <span className="badge">游戏与 Studio 共用的 `studio_workspace` 真源文件夹</span>
              {workspaceDirectoryHandle ? <span className="badge good">自动同步中</span> : null}
              <span className={gameBridgeStatus.state === "connected" ? "badge good" : "badge muted"}>
                {gameBridgeStatus.state === "connected" ? "HTTP Bridge 已连接" : "未连接游戏热重载"}
              </span>
            </div>
            <p className="muted-copy">
              读取会加载该目录下已有的 schema 文件；保存只会回写 `custom_tags.json`、
              `action_timeline_tuning.json`、`tag_descriptions.json`、`tag_relations.json`、`personas.json`、
              `implanted_memories.json`、`agent_state.json` 与 `location_registry.json`。这些 authored 文件本身就是游戏运行时真源；保存不会覆盖模组生成的
              snapshot 与图标文件。若游戏内 HTTP bridge 在线，保存后会立即请求热重载；否则退回 manifest 轮询同步。
            </p>
            <p className="muted-copy">{gameBridgeStatus.message}</p>
            <div className="workspace-debug">
              <p className="muted-copy">目录访问模式：{workspaceDebugInfo.accessMode}</p>
              <p className="muted-copy">
                Desktop bridge ready: {isDesktopBridgeReady() ? "yes" : "no"} / source: {getDesktopBridgeSource()}
              </p>
              <p className="muted-copy">
                showDirectoryPicker: {isBrowserDirectoryPickerAvailable() ? "yes" : "no"}
              </p>
              <p className="muted-copy">当前工作区句柄：{workspaceDebugInfo.selectedPath}</p>
              <p className="muted-copy">
                Manifest 检测：
                {workspaceDebugInfo.manifestPresent === "yes"
                  ? " 已找到 studio_workspace.manifest.json"
                  : workspaceDebugInfo.manifestPresent === "no"
                    ? " 未找到 studio_workspace.manifest.json"
                    : " 尚未检测"}
              </p>
              <p className="muted-copy">
                最近一次导入读取：
                {workspaceDebugInfo.importedParts.length
                  ? ` ${workspaceDebugInfo.importedParts.join(" / ")}`
                  : " 暂无"}
              </p>
              {workspaceDebugInfo.errorMessage ? (
                <p className="muted-copy">最近一次导入错误：{workspaceDebugInfo.errorMessage}</p>
              ) : null}
            </div>
            <div className="workspace-actions">
              <button className="action-button primary" onClick={importWorkspace} type="button">
                导入工作区
              </button>
              <button className="action-button quiet" onClick={exportWorkspace} type="button">
                保存
              </button>
            </div>
            <p className={`io-feedback ${importFeedback.tone}`}>{importFeedback.message}</p>
          </section>
        </div>
      </header>

      <nav className="page-switcher">
        {pages.map((entry) => (
          <button
            key={entry.id}
            className={entry.id === page ? "page-chip active" : "page-chip"}
            onClick={() => setPage(entry.id)}
            type="button"
          >
            <span>{entry.label}</span>
            <small>{entry.blurb}</small>
          </button>
        ))}
      </nav>

      <main className="workspace">
        {page === "catalog" ? (
          <CustomTagCatalogPage
            rows={customTagRows}
            query={customQuery}
            schemaSummary={schemaSummary}
            mode={catalogMode}
            onQueryChange={setCustomQuery}
            onCreate={openCreateTagEditor}
            onEdit={openEditTagEditor}
            onDelete={deleteCustomTag}
            onDraftChange={updateCatalogDraft}
            onCancel={closeTagEditor}
            onSave={saveCustomTag}
          />
        ) : null}
        {page === "descriptions" ? (
          <DescriptionCatalogPage
            rows={filteredDescriptionRows}
            query={descriptionQuery}
            mode={descriptionMode}
            tagRecords={tagRecords}
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onQueryChange={setDescriptionQuery}
            onEdit={openEditDescriptionEditor}
            onDraftChange={updateDescriptionDraft}
            onCancel={closeDescriptionEditor}
            onSave={saveDescription}
          />
        ) : null}
        {page === "relations" ? (
          <RelationsPage
            relations={relations}
            relationCycles={relationCycles}
            query={relationQuery}
            mode={relationMode}
            feedback={relationFeedback}
            tags={tagRecords}
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onQueryChange={setRelationQuery}
            onEdit={openEditRelationEditor}
            onSourceTagChange={setRelationSourceTag}
            onAddLinkedTag={addRelationLinkedTag}
            onRemoveLinkedTag={removeRelationLinkedTag}
            onCancel={closeRelationEditor}
            onSave={saveRelation}
            onWorkspaceApply={applyRelationWorkspaceUpdate}
          />
        ) : null}
        {page === "state" ? (
          <AgentStatePage
            agentStateFile={agentStateFile}
            onStateChange={updateAgentRuntimeState}
            onPersonaStateChange={updateAgentPersonaState}
            onAttentionWeightChange={updateAgentAttentionWeight}
            onCrosshairBonusEnabledChange={(nextValue) =>
              setAgentStateFile((currentFile) => ({
                ...currentFile,
                attention_weights: {
                  ...currentFile.attention_weights,
                  crosshair_bonus_enabled: nextValue
                }
              }))
            }
          />
        ) : null}
        {page === "importance" ? (
          <ImportancePage
            entries={filteredImportanceEntries}
            query={importanceQuery}
            newKey={newImportanceKey}
            newValue={newImportanceValue}
            onQueryChange={setImportanceQuery}
            onNewKeyChange={setNewImportanceKey}
            onNewValueChange={setNewImportanceValue}
            onAdd={addImportanceEntry}
            onUpdate={updateImportanceEntry}
            onDelete={removeImportanceEntry}
          />
        ) : null}
        {page === "prompts" ? (
          <PromptsPage
            entries={filteredPromptEntries}
            query={promptQuery}
            newKey={newPromptKey}
            newValue={newPromptValue}
            onQueryChange={setPromptQuery}
            onNewKeyChange={setNewPromptKey}
            onNewValueChange={setNewPromptValue}
            onAdd={addPromptEntry}
            onUpdate={updatePromptEntry}
            onDelete={removePromptEntry}
          />
        ) : null}
        {page === "thoughts" ? (
          <RuntimeThoughtsPage
            generatedAt={runtimeInteractions.generatedAt}
            thoughts={runtimeThoughtEntries}
          />
        ) : null}
        {page === "interactions" ? (
          <RuntimeInteractionsPage
            generatedAt={runtimeInteractions.generatedAt}
            entries={runtimeInteractionEntries}
          />
        ) : null}
        {page === "playerActions" ? (
          <RuntimeActionTimelinePage
            runtimeActionTimeline={runtimeActionTimeline}
            tuningFile={actionTimelineTuningFile}
            onTuningChange={updateActionTimelineTuning}
          />
        ) : null}
        {page === "recallGraph" ? (
          <RuntimeRecallGraphPage
            agentStateFile={agentStateFile}
            memoryRecallTuningFile={memoryRecallTuningFile}
            onAgentRecallTuningChange={(key, value) => updateAgentRuntimeState({
              recall_tuning: {
                ...agentStateFile.state.recall_tuning,
                [key]: value
              }
            })}
            onAgentStateChange={updateAgentRuntimeState}
            onMemoryRecallTuningChange={updateMemoryRecallTuning}
            recallTrace={runtimeRecallTrace}
          />
        ) : null}
        {page === "persona" ? (
          <PersonaPage
            personas={filteredPersonas}
            query={personaQuery}
            focusFilter={personaFocusFilter}
            mode={personaMode}
            tags={tagRecords}
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onQueryChange={setPersonaQuery}
            onFocusFilterChange={setPersonaFocusFilter}
            onCreate={openCreatePersonaEditor}
            onEdit={openEditPersonaEditor}
            onDelete={deletePersona}
            onDraftChange={updatePersonaDraft}
            onToggleFocusTag={togglePersonaFocusTag}
            onAddDetailTag={addPersonaDetailTag}
            onRemoveDetailTag={removePersonaDetailTag}
            onCancel={closePersonaEditor}
            onSave={savePersona}
          />
        ) : null}
        {page === "memory" ? (
          <MemoryPage
            memories={filteredMemories}
            query={memoryQuery}
            mode={memoryMode}
            tags={tagRecords}
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onQueryChange={setMemoryQuery}
            onCreate={openCreateMemoryEditor}
            onEdit={openEditMemoryEditor}
            onDelete={deleteMemory}
            onDraftChange={updateMemoryDraft}
            onToggleFocusTag={toggleMemoryFocusTag}
            onAddDetailTag={addMemoryDetailTag}
            onRemoveDetailTag={removeMemoryDetailTag}
            onCancel={closeMemoryEditor}
            onSave={saveMemory}
          />
        ) : null}
        {page === "locations" ? (
          <LocationRegistryPage
            locations={filteredLocations}
            query={locationQuery}
            mode={locationMode}
            tags={tagRecords}
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onQueryChange={setLocationQuery}
            onCreate={openCreateLocationEditor}
            onEdit={openEditLocationEditor}
            onDelete={deleteLocation}
            onDraftChange={updateLocationDraft}
            onAddDetailTag={addLocationDetailTag}
            onRemoveDetailTag={removeLocationDetailTag}
            onCancel={closeLocationEditor}
            onSave={saveLocation}
          />
        ) : null}
      </main>
    </div>
    </IconAssetVersionContext.Provider>
  );
}

function CustomTagCatalogPage({
  rows,
  query,
  schemaSummary,
  mode,
  onQueryChange,
  onCreate,
  onEdit,
  onDelete,
  onDraftChange,
  onCancel,
  onSave
}: {
  rows: CustomTagRow[];
  query: string;
  schemaSummary: string;
  mode: CatalogMode;
  onQueryChange: (next: string) => void;
  onCreate: () => void;
  onEdit: (entry: CustomTagEntry) => void;
  onDelete: (tag: string) => void;
  onDraftChange: (patch: Partial<CustomTagDraft>) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  if (mode.mode !== "list") {
    const applySuggestedDescription = () => {
      onDraftChange({
        description: buildSuggestedCustomTagDescription(mode.draft.kind, mode.draft.id)
      });
    };

    return (
      <div className="panel page-panel catalog-editor">
        <div className="panel-head">
          <div>
            <p className="eyebrow">{mode.mode === "create" ? "Create Custom Tag" : "Edit Custom Tag"}</p>
            <h2>{mode.mode === "create" ? "新增 custom tag" : "编辑 custom tag"}</h2>
            <p className="muted-copy">这里仅编辑 `type`、`id` 与 `description`，其他字段暂不暴露。</p>
          </div>
          <div className="row-actions">
            <button className="action-button quiet" onClick={applySuggestedDescription} type="button">
              自动补全 Description
            </button>
            <button className="action-button quiet" onClick={onCancel} type="button">
              返回列表
            </button>
          </div>
        </div>

        <div className="form-grid">
          <label className="field">
            <span>Type</span>
            <select
              value={mode.draft.kind}
              onChange={(event) => onDraftChange({ kind: event.target.value as CustomTagKind })}
            >
              <option value="custom_specific">custom_specific</option>
              <option value="custom_generic">custom_generic</option>
            </select>
          </label>
          <label className="field">
            <span>ID</span>
            <input
              value={mode.draft.id}
              onChange={(event) => onDraftChange({ id: event.target.value })}
              placeholder="例如 homeland 或 亚尔夫海姆"
            />
          </label>
          <label className="field full">
            <span>Description</span>
            <textarea
              rows={8}
              value={mode.draft.description}
              onChange={(event) => onDraftChange({ description: event.target.value })}
              placeholder="写给玩家和 LLM 的自然语言描述"
            />
          </label>
        </div>

        <section className="info-block">
          <h3>Preview</h3>
          <div className="chip-row">
            <span className="chip">{buildCustomTagValue(mode.draft.kind, mode.draft.id || "new_tag")}</span>
          </div>
          <p>{mode.draft.description || "还没有 description。保存后会同时写入 custom_tags 和 tag_descriptions 两层状态。"}</p>
        </section>

        <div className="editor-actions">
          <button className="action-button quiet" onClick={onCancel} type="button">
            取消
          </button>
          <button className="action-button primary" onClick={onSave} type="button">
            保存
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Custom Tag Catalog</p>
          <h2>Custom Tag 管理</h2>
          <p className="muted-copy">第一页只处理 custom tag 本体及其 description，不混入 native tag 浏览。</p>
        </div>
        <span className="badge">{schemaSummary}</span>
      </div>

      <div className="catalog-toolbar">
        <label className="field grow">
          <span>检索</span>
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="按 tag id 或 description 搜索"
          />
        </label>
        <button className="action-button primary" onClick={onCreate} type="button">
          增加
        </button>
      </div>

      <div className="custom-tag-list">
        {rows.map((row) => (
          <article className="custom-tag-row" key={row.entry.tag}>
            <div className="custom-tag-main">
              <div className="custom-tag-meta">
                <span className="badge">{row.entry.kind}</span>
                <code className="tag-id">{row.id}</code>
              </div>
              <p className="custom-tag-description">{row.description || "暂无 description"}</p>
            </div>
            <div className="row-actions">
              <button className="action-button quiet" onClick={() => onEdit(row.entry)} type="button">
                编辑
              </button>
              <button className="action-button danger" onClick={() => onDelete(row.entry.tag)} type="button">
                删除
              </button>
            </div>
          </article>
        ))}
        {!rows.length ? (
          <div className="empty-state">
            <strong>没有匹配结果</strong>
            <p>当前检索条件下没有 custom tag。</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

function DescriptionCatalogPage({
  rows,
  query,
  mode,
  tagRecords,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onQueryChange,
  onEdit,
  onDraftChange,
  onCancel,
  onSave
}: {
  rows: Array<{ record: TagRecord; description: string }>;
  query: string;
  mode: DescriptionMode;
  tagRecords: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onQueryChange: (next: string) => void;
  onEdit: (tag: string) => void;
  onDraftChange: (nextDescription: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  const [page, setPage] = useState(1);
  const pageCount = Math.max(1, Math.ceil(rows.length / DESCRIPTION_PAGE_SIZE));
  const pageStart = (page - 1) * DESCRIPTION_PAGE_SIZE;
  const visibleRows = rows.slice(pageStart, pageStart + DESCRIPTION_PAGE_SIZE);

  useEffect(() => {
    setPage(1);
  }, [query]);

  useEffect(() => {
    if (page > pageCount) {
      setPage(pageCount);
    }
  }, [page, pageCount]);

  if (mode.mode !== "list") {
    const record = tagRecordOrFallback(tagRecords, mode.tag);
    return (
      <div className="panel page-panel catalog-editor">
        <div className="panel-head">
          <div>
            <p className="eyebrow">Edit Description</p>
            <h2>编辑 tag description</h2>
            <p className="muted-copy">这里直接维护 `tag_descriptions.json`，可为 native 与 custom tags 补充自然语言描述。</p>
          </div>
          <div className="row-actions">
            <button className="action-button quiet" onClick={onCancel} type="button">
              返回列表
            </button>
            <button className="action-button primary" onClick={onSave} type="button">
              保存
            </button>
          </div>
        </div>

        <section className="info-block">
          <div className="relation-source-preview">
            <TagCardIcon
              record={record}
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
            />
            <div className="relation-source-copy">
              <div className="custom-tag-meta">
                <span className="badge">{record.kind}</span>
                {record.registryTypes[0] ? <span className="badge muted">{record.registryTypes[0]}</span> : null}
                {record.sourceMods[0] ? <span className="badge muted">{record.sourceMods[0]}</span> : null}
              </div>
              <strong>{record.label}</strong>
              <code>{record.tag}</code>
              <p className="tag-browser-description">
                {record.description || "当前记录还没有 description。"}
              </p>
            </div>
          </div>
        </section>

        <label className="field full">
          <span>Description</span>
          <textarea
            rows={10}
            value={mode.draft}
            onChange={(event) => onDraftChange(event.target.value)}
            placeholder="写给玩家和 LLM 的自然语言描述。若清空并保存，则会删除这一条 description。"
          />
        </label>

        <div className="editor-actions">
          <button className="action-button quiet" onClick={onCancel} type="button">
            取消
          </button>
          <button className="action-button primary" onClick={onSave} type="button">
            保存
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">All Tag Descriptions</p>
          <h2>Tag Description 管理</h2>
          <p className="muted-copy">这里统一检索并编辑所有 tag 的自然语言描述，不再分 custom / native。</p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${rows.length} 条`}</span>
          <span className="badge muted">{`第 ${page} / ${pageCount} 页`}</span>
        </div>
      </div>

      <div className="catalog-toolbar">
        <label className="field grow">
          <span>检索</span>
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="按 tag、label、description、registry type 或 mod 搜索"
          />
        </label>
      </div>

      <div className="custom-tag-list">
        {visibleRows.map(({ record, description }) => (
          <article className="custom-tag-row description-row" key={record.tag}>
            <TagCardIcon
              record={record}
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
            />
            <div className="custom-tag-main">
              <div className="custom-tag-meta">
                <span className="badge">{record.kind}</span>
                {record.registryTypes[0] ? <span className="badge muted">{record.registryTypes[0]}</span> : null}
                {record.sourceMods[0] ? <span className="badge muted">{record.sourceMods[0]}</span> : null}
              </div>
              <strong>{record.label}</strong>
              <code>{record.tag}</code>
              <p className="custom-tag-description">{description || "暂无 description"}</p>
            </div>
            <div className="row-actions">
              <button className="action-button quiet" onClick={() => onEdit(record.tag)} type="button">
                编辑
              </button>
            </div>
          </article>
        ))}
        {!visibleRows.length ? (
          <div className="empty-state">
            <strong>没有匹配结果</strong>
            <p>当前检索条件下没有 tag description 可编辑项。</p>
          </div>
        ) : null}
      </div>

      {rows.length > DESCRIPTION_PAGE_SIZE ? (
        <div className="tag-browser-pagination">
          <span className="badge muted">{`当前显示 ${pageStart + 1}-${Math.min(rows.length, pageStart + DESCRIPTION_PAGE_SIZE)} / ${rows.length}`}</span>
          <div className="tag-browser-pagination-actions">
            <button
              className="action-button quiet"
              disabled={page <= 1}
              onClick={() => setPage((current) => Math.max(1, current - 1))}
              type="button"
            >
              上一页
            </button>
            <button
              className="action-button quiet"
              disabled={page >= pageCount}
              onClick={() => setPage((current) => Math.min(pageCount, current + 1))}
              type="button"
            >
              下一页
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function RelationsPage({
  relations,
  relationCycles,
  query,
  mode,
  feedback,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onQueryChange,
  onEdit,
  onSourceTagChange,
  onAddLinkedTag,
  onRemoveLinkedTag,
  onCancel,
  onSave,
  onWorkspaceApply
}: {
  relations: Record<string, string[]>;
  relationCycles: string[][];
  query: string;
  mode: RelationMode;
  feedback: ImportFeedback;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onQueryChange: (next: string) => void;
  onEdit: (sourceTag: string) => void;
  onSourceTagChange: (tag: string) => void;
  onAddLinkedTag: (tag: string) => void;
  onRemoveLinkedTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
  onWorkspaceApply: (sourceTags: string[], operation: RelationBatchOperation, linkedTags: string[]) => void;
}) {
  const [activeSidebar, setActiveSidebar] = useState<"source" | "linked">("source");
  const [workspaceSourceTags, setWorkspaceSourceTags] = useState<string[]>([]);
  const [activeWorkspaceSourceTag, setActiveWorkspaceSourceTag] = useState("");

  const cycleNodes = useMemo(() => cycleNodeSetOf(relationCycles), [relationCycles]);
  const totalEdgeCount = useMemo(
    () => Object.values(relations).reduce((sum, linkedTags) => sum + linkedTags.length, 0),
    [relations]
  );
  const existingRelationSourceTags = useMemo(
    () => Object.keys(relations).sort((left, right) => left.localeCompare(right, "zh-Hans")),
    [relations]
  );

  const workspaceEntries = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    const entries = workspaceSourceTags.map((sourceTag) => {
      const sourceRecord = tagRecordOrFallback(tags, sourceTag);
      const linkedRecords = (relations[sourceTag] ?? []).map((linkedTag) => tagRecordOrFallback(tags, linkedTag));
      return {
        sourceTag,
        sourceRecord,
        linkedRecords
      };
    });

    if (!normalizedQuery) {
      return entries;
    }

    return entries.filter((entry) =>
      [
        entry.sourceTag,
        entry.sourceRecord.description,
        ...entry.linkedRecords.flatMap((record) => [record.tag, record.description])
      ]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery)
    );
  }, [query, relations, tags, workspaceSourceTags]);

  useEffect(() => {
    if (activeWorkspaceSourceTag && workspaceSourceTags.includes(activeWorkspaceSourceTag)) {
      return;
    }
    setActiveWorkspaceSourceTag("");
  }, [activeWorkspaceSourceTag, workspaceSourceTags]);

  const singleEditMode = Boolean(activeWorkspaceSourceTag);
  const workspaceTargetSourceTags = singleEditMode ? [activeWorkspaceSourceTag] : workspaceSourceTags;
  const relationShellColumns =
    activeSidebar === "source"
      ? "minmax(300px, 360px) minmax(0, 1fr)"
      : "minmax(0, 1fr) minmax(300px, 360px)";

  const applyTagFromRightSidebar = (linkedTag: string) => {
    onWorkspaceApply(workspaceTargetSourceTags, "add", [linkedTag]);
  };

  const applyTagsFromRightSidebar = (linkedTags: string[]) => {
    onWorkspaceApply(workspaceTargetSourceTags, "add", linkedTags);
  };

  const removeLinkedTagFromWorkspace = (linkedTag: string) => {
    onWorkspaceApply(workspaceTargetSourceTags, "remove", [linkedTag]);
  };

  if (mode.mode !== "list") {
    return (
      <RelationEditor
        mode={mode}
        feedback={feedback}
        tags={tags}
        workspaceDirectoryHandle={workspaceDirectoryHandle}
        workspaceImportedFiles={workspaceImportedFiles}
        onSourceTagChange={onSourceTagChange}
        onAddLinkedTag={onAddLinkedTag}
        onRemoveLinkedTag={onRemoveLinkedTag}
        onCancel={onCancel}
        onSave={onSave}
      />
    );
  }

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Tag Relations Workspace</p>
          <h2>标签联动工作区</h2>
          <p className="muted-copy">
            左侧把 source tags 加入工作区，右侧挑选要批量施加的关联 tags，中间工作区负责统一增删联动。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${existingRelationSourceTags.length} 个 source`}</span>
          <span className="badge muted">{`${totalEdgeCount} 条边`}</span>
          <span className={relationCycles.length ? "badge warn" : "badge good"}>
            {relationCycles.length ? `环 ${relationCycles.length}` : "无环"}
          </span>
        </div>
      </div>

      <p className={`io-feedback ${feedback.tone}`}>{feedback.message}</p>

      {relationCycles.length ? (
        <section className="info-block relation-cycle-block">
          <h3>环检测结果</h3>
          <p className="muted-copy">只要图中存在环，relations 的批量操作与保存都会被阻止。</p>
          <div className="cycle-list">
            {relationCycles.slice(0, 8).map((cycle) => (
              <code key={cycle.join("->")}>{cycle.join(" -> ")}</code>
            ))}
          </div>
        </section>
      ) : null}

      <div
        className="relations-shell"
        style={{
          gridTemplateColumns: relationShellColumns
        }}
      >
        {activeSidebar === "source" ? (
          <aside className="panel relations-sidebar-panel">
            <div className="relations-sidebar-head">
              <div>
                <p className="eyebrow">Source</p>
                <h3>左侧编辑标签</h3>
              </div>
            </div>
            <TagBrowser
              tags={tags}
              excludedTags={workspaceSourceTags}
              title="Source Tag Browser"
              description="点击任意 tag，将它加入中央工作区作为待编辑的 source tag。"
              searchPlaceholder="搜索要加入工作区的 source tag"
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
              variant="sidebar"
              onAddAllTags={(tagIds) => {
                setWorkspaceSourceTags((current) => Array.from(new Set([...current, ...tagIds])));
              }}
              onSelectTag={(tag) => {
                setWorkspaceSourceTags((current) => Array.from(new Set([...current, tag])));
              }}
            />
          </aside>
        ) : null}

        <section className="relations-workspace">
          <div className="panel relation-workspace-panel">
            <div className="section-head">
              <div>
                <h3>中央工作区</h3>
                <p className="muted-copy">
                  {singleEditMode
                    ? "当前为单独编辑模式。右侧点击会直接加到被勾选的 source tag 上，列表里的删除也只影响这一条。"
                    : "当前为批量修改模式。右侧点击会直接加到工作区内全部 source tags 上，列表里的删除也会联动全部。"}
                </p>
              </div>
              <div className="relation-workspace-head-actions">
                <div className="ios-segmented-control" role="tablist" aria-label="Relations Sidebar Switcher">
                  <button
                    aria-selected={activeSidebar === "source"}
                    className={activeSidebar === "source" ? "ios-segmented-button active" : "ios-segmented-button"}
                    onClick={() => setActiveSidebar("source")}
                    type="button"
                  >
                    工作区标签选择
                  </button>
                  <button
                    aria-selected={activeSidebar === "linked"}
                    className={activeSidebar === "linked" ? "ios-segmented-button active" : "ios-segmented-button"}
                    onClick={() => setActiveSidebar("linked")}
                    type="button"
                  >
                    关联标签选择
                  </button>
                </div>
                <div className="workspace-actions">
                <button
                  className="action-button quiet"
                  disabled={!singleEditMode}
                  onClick={() => {
                    if (singleEditMode && activeWorkspaceSourceTag) {
                      onEdit(activeWorkspaceSourceTag);
                    }
                  }}
                  type="button"
                >
                  单条编辑
                </button>
                </div>
              </div>
            </div>

            <div className="relation-workspace-toolbar">
              <label className="field grow">
                <span>过滤工作区</span>
                <input
                  value={query}
                  onChange={(event) => onQueryChange(event.target.value)}
                  placeholder="按 source tag、简介或其附带 tags 过滤工作区"
                />
              </label>
              <button
                className="action-button quiet"
                onClick={() => {
                  setWorkspaceSourceTags(existingRelationSourceTags);
                  setActiveWorkspaceSourceTag("");
                }}
                type="button"
              >
                载入已有 Source
              </button>
              <button
                className="action-button quiet"
                onClick={() => {
                  setWorkspaceSourceTags([]);
                  setActiveWorkspaceSourceTag("");
                }}
                type="button"
              >
                清空工作区
              </button>
            </div>

            <section className="info-block relation-apply-panel">
              <div className="section-head">
                <div>
                  <h3>当前操作模式</h3>
                  <p className="muted-copy">
                    {singleEditMode
                      ? `已勾选 ${activeWorkspaceSourceTag}，右侧点击将直接新增到这一个 source tag。`
                      : "当前未勾选任何 source tag，右侧点击将直接新增到工作区内全部 source tags。"}
                  </p>
                </div>
                <span className={singleEditMode ? "badge warn" : "badge"}>
                  {singleEditMode ? "单独编辑模式" : "批量修改模式"}
                </span>
              </div>
              <p className="muted-copy">
                中央列表内每个关联 tag 都带删除按钮。未勾选时删除会联动全部；勾选后只删当前条目。
              </p>
            </section>

            <div className="relation-workspace-list">
              {workspaceEntries.length ? (
                workspaceEntries.map((entry) => (
                  <article
                    className={
                      entry.sourceTag === activeWorkspaceSourceTag
                        ? "relation-workspace-row active"
                        : "relation-workspace-row"
                    }
                    key={entry.sourceTag}
                  >
                    <TagCardIcon
                      record={entry.sourceRecord}
                      workspaceDirectoryHandle={workspaceDirectoryHandle}
                      workspaceImportedFiles={workspaceImportedFiles}
                    />
                    <div className="relation-workspace-main">
                      <label className="relation-row-selector">
                        <input
                          checked={entry.sourceTag === activeWorkspaceSourceTag}
                          onChange={(event) => {
                            setActiveWorkspaceSourceTag(event.target.checked ? entry.sourceTag : "");
                          }}
                          type="checkbox"
                        />
                        <span>单独编辑这条</span>
                      </label>
                      <div className="custom-tag-meta">
                        <span className="badge">{entry.sourceRecord.kind}</span>
                        <span className="badge muted">{entry.linkedRecords.length} linked</span>
                        {cycleNodes.has(entry.sourceTag) ? <span className="badge warn">Cycle</span> : null}
                      </div>
                      <code>{entry.sourceTag}</code>
                      <p className="tag-browser-description">
                        {entry.sourceRecord.description || "该 tag 暂无 description。"}
                      </p>
                      <RelationInlineTagStrip
                        records={entry.linkedRecords}
                        emptyLabel="当前还没有关联 tags。"
                        removable={!singleEditMode || entry.sourceTag === activeWorkspaceSourceTag}
                        onRemoveTag={removeLinkedTagFromWorkspace}
                      />
                    </div>
                    <div className="row-actions">
                      <button
                        className="action-button quiet"
                        onClick={(event) => {
                          event.stopPropagation();
                          setWorkspaceSourceTags((current) =>
                            current.filter((sourceTag) => sourceTag !== entry.sourceTag)
                          );
                          if (entry.sourceTag === activeWorkspaceSourceTag) {
                            setActiveWorkspaceSourceTag("");
                          }
                        }}
                        type="button"
                      >
                        移出工作区
                      </button>
                    </div>
                  </article>
                ))
              ) : (
                <div className="empty-state">
                  <strong>工作区还是空的</strong>
                  <p>从左侧侧边栏挑选 source tag 放进来后，这里才会展开批量编辑列表。</p>
                </div>
              )}
            </div>
          </div>
        </section>

        {activeSidebar === "linked" ? (
          <aside className="panel relations-sidebar-panel">
            <div className="relations-sidebar-head">
              <div>
                <p className="eyebrow">Linked</p>
                <h3>右侧关联标签</h3>
              </div>
            </div>
            <TagBrowser
              tags={tags}
              title="Linked Tag Browser"
              description={
                singleEditMode
                  ? "点击任意 tag，会直接加到当前勾选的 source tag 上。"
                  : "点击任意 tag，会直接加到工作区内全部 source tags 上。"
              }
              searchPlaceholder="搜索要直接关联的 linked tag"
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
              variant="sidebar"
              onAddAllTags={applyTagsFromRightSidebar}
              onSelectTag={applyTagFromRightSidebar}
            />
          </aside>
        ) : null}
      </div>
    </div>
  );
}

function RelationEditor({
  mode,
  feedback,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onSourceTagChange,
  onAddLinkedTag,
  onRemoveLinkedTag,
  onCancel,
  onSave
}: {
  mode: Exclude<RelationMode, { mode: "list" }>;
  feedback: ImportFeedback;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onSourceTagChange: (tag: string) => void;
  onAddLinkedTag: (tag: string) => void;
  onRemoveLinkedTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  const sourceRecord = mode.draft.sourceTag ? tagRecordOrFallback(tags, mode.draft.sourceTag) : null;

  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">{mode.mode === "create" ? "Create Relation Entry" : "Edit Relation Entry"}</p>
          <h2>{mode.mode === "create" ? "新增标签联动" : "编辑标签联动"}</h2>
          <p className="muted-copy">
            这里维护 `tag_relations.json` 的一对多有向图。linked tags 默认仅作为内部联想边，不直接显示给 LLM。
          </p>
        </div>
        <button className="action-button quiet" onClick={onCancel} type="button">
          返回列表
        </button>
      </div>

      <p className={`io-feedback ${feedback.tone}`}>{feedback.message}</p>

      <section className="info-block">
        <h3>Source Tag</h3>
        {sourceRecord ? (
          <div className="relation-source-preview">
            <TagCardIcon
              record={sourceRecord}
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
            />
            <div className="relation-source-copy">
              <div className="custom-tag-meta">
                <span className="badge">{sourceRecord.kind}</span>
                <span className="badge muted">source</span>
              </div>
              <strong>{sourceRecord.label}</strong>
              <code>{sourceRecord.tag}</code>
              <p className="tag-browser-description">
                {sourceRecord.description || "该 source tag 暂无 description。"}
              </p>
              <div className="row-actions">
                <button className="action-button quiet" onClick={() => onSourceTagChange("")} type="button">
                  清除 Source
                </button>
              </div>
            </div>
          </div>
        ) : (
          <p className="muted-copy">还没有选择 source tag。</p>
        )}
      </section>

      <section className="info-block tag-picker">
        <TagBrowser
          tags={tags}
          excludedTags={mode.draft.sourceTag ? [mode.draft.sourceTag] : []}
          title="Source Tag Browser"
          description="点击卡片即可将其设为当前 relation 的 source tag。"
          searchPlaceholder="搜索 source tag"
          workspaceDirectoryHandle={workspaceDirectoryHandle}
          workspaceImportedFiles={workspaceImportedFiles}
          onSelectTag={onSourceTagChange}
        />
      </section>

      <div className="two-col persona-editor-grid">
        <section className="info-block">
          <h3>Linked Tags</h3>
          <p className="muted-copy">这些 linked tags 默认视为内部联动引用，不直接进入 LLM Prompt。</p>
          {mode.draft.linkedTags.length ? (
            <SelectedTagGrid
              tagIds={mode.draft.linkedTags}
              tags={tags}
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
              onRemoveTag={onRemoveLinkedTag}
            />
          ) : (
            <p className="muted-copy">还没有 linked tags。</p>
          )}
        </section>

        <section className="info-block tag-picker">
          <TagBrowser
            tags={tags}
            excludedTags={[
              ...mode.draft.linkedTags,
              ...(mode.draft.sourceTag ? [mode.draft.sourceTag] : [])
            ]}
            title="Linked Tag Browser"
            description="点击卡片即可加入当前 source tag 的联动引用列表。"
            searchPlaceholder="搜索 linked tags"
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onSelectTag={onAddLinkedTag}
          />
        </section>
      </div>

      <section className="info-block">
        <h3>草稿预览</h3>
        <div className="chip-row">
          <span className="chip">{mode.draft.sourceTag || "未选择 source"}</span>
          <span className="chip">{`${mode.draft.linkedTags.length} 个 linked tags`}</span>
        </div>
        <p>
          保存前会自动做环检测；如果形成 <code>A {"->"} B {"->"} C {"->"} A</code> 这类回路，将阻止写入。
        </p>
      </section>

      <div className="editor-actions">
        <button className="action-button quiet" onClick={onCancel} type="button">
          取消
        </button>
        <button className="action-button primary" onClick={onSave} type="button">
          保存
        </button>
      </div>
    </div>
  );
}

function RelationInlineTagStrip({
  records,
  emptyLabel,
  removable = false,
  onRemoveTag
}: {
  records: TagRecord[];
  emptyLabel: string;
  removable?: boolean;
  onRemoveTag?: (tag: string) => void;
}) {
  if (!records.length) {
    return <p className="muted-copy">{emptyLabel}</p>;
  }

  return (
    <div className="relation-inline-tag-strip">
      {records.map((record) => (
        <article className="relation-inline-tag" key={record.tag}>
          <div className="relation-inline-tag-head">
            <code>{record.tag}</code>
            {removable && onRemoveTag ? (
              <button
                className="action-button danger relation-inline-tag-remove"
                onClick={() => onRemoveTag(record.tag)}
                type="button"
              >
                移除
              </button>
            ) : null}
          </div>
          <p>{record.description || "暂无 description"}</p>
        </article>
      ))}
    </div>
  );
}

function PersonaPage({
  personas,
  query,
  focusFilter,
  mode,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onQueryChange,
  onFocusFilterChange,
  onCreate,
  onEdit,
  onDelete,
  onDraftChange,
  onToggleFocusTag,
  onAddDetailTag,
  onRemoveDetailTag,
  onCancel,
  onSave
}: {
  personas: PersonaEntry[];
  query: string;
  focusFilter: "all" | FocusTag;
  mode: PersonaMode;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onQueryChange: (next: string) => void;
  onFocusFilterChange: (next: "all" | FocusTag) => void;
  onCreate: () => void;
  onEdit: (entry: PersonaEntry) => void;
  onDelete: (id: string) => void;
  onDraftChange: (patch: Partial<PersonaDraft>) => void;
  onToggleFocusTag: (focusTag: FocusTag) => void;
  onAddDetailTag: (tag: string) => void;
  onRemoveDetailTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  if (mode.mode !== "list") {
    return (
      <PersonaEditor
        mode={mode}
        tags={tags}
        workspaceDirectoryHandle={workspaceDirectoryHandle}
        workspaceImportedFiles={workspaceImportedFiles}
        onDraftChange={onDraftChange}
        onToggleFocusTag={onToggleFocusTag}
        onAddDetailTag={onAddDetailTag}
        onRemoveDetailTag={onRemoveDetailTag}
        onCancel={onCancel}
        onSave={onSave}
      />
    );
  }

  const groupedFocusTags = focusFilter === "all" ? FOCUS_TAGS : [focusFilter];
  const grouped = groupedFocusTags.map((focusTag) => ({
    focusTag,
    entries: personas.filter((entry) => personaHasFocus(entry, focusTag))
  })).filter((group) => group.entries.length > 0);

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Persona Entries</p>
          <h2>人格偏好管理</h2>
          <p className="muted-copy">以 focus tag 分组；多 focus persona 会同时出现在多个分组里。</p>
        </div>
      </div>

      <div className="catalog-toolbar">
        <label className="field grow">
          <span>检索</span>
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="按 focus、description 或 detail tag 搜索"
          />
        </label>
        <label className="field toolbar-select">
          <span>Focus 分类</span>
          <select
            value={focusFilter}
            onChange={(event) => onFocusFilterChange(event.target.value as "all" | FocusTag)}
          >
            <option value="all">全部 Focus</option>
            {FOCUS_TAGS.map((focusTag) => (
              <option key={focusTag} value={focusTag}>
                {formatFocusTagLabel(focusTag)}
              </option>
            ))}
          </select>
        </label>
        <button className="action-button primary" onClick={onCreate} type="button">
          增加
        </button>
      </div>

      <div className="persona-groups">
        {grouped.map((group) => (
          <section className="persona-group" key={group.focusTag}>
            <div className="section-head">
              <h3>{formatFocusTagLabel(group.focusTag)}</h3>
              <span className="badge">{group.entries.length}</span>
            </div>
            <div className="custom-tag-list">
              {group.entries.map((entry) => (
                <article className="custom-tag-row persona-row" key={entry.id}>
                  <div className="custom-tag-main">
                    <div className="custom-tag-meta">
                      {getPersonaFocusTags(entry).map((focusTag) => (
                        <span className="badge" key={`${entry.id}-${focusTag}`}>
                          {formatFocusTagLabel(focusTag)}
                        </span>
                      ))}
                      {entry.always_recalled ? <span className="badge warn">Always Remembered</span> : null}
                    </div>
                    <p className="persona-description">{entry.description}</p>
                    {entry.always_recalled ? (
                      <p className="always-recalled-note">
                        {getPersonaFocusTags(entry).includes("COMMON")
                          ? "将在所有情况下触发。"
                          : "将在当前注意力领域下持续触发。"}
                      </p>
                    ) : (
                      <ChipList items={entry.detail_tags ?? []} emptyLabel="无 detail tags" compact />
                    )}
                  </div>
                  <div className="row-actions">
                    <button className="action-button quiet" onClick={() => onEdit(entry)} type="button">
                      编辑
                    </button>
                    <button className="action-button danger" onClick={() => onDelete(entry.id)} type="button">
                      删除
                    </button>
                  </div>
                </article>
              ))}
            </div>
          </section>
        ))}
        {!grouped.length ? (
          <div className="empty-state">
            <strong>没有匹配结果</strong>
            <p>当前检索条件下没有 persona entry。</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

function PersonaEditor({
  mode,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onDraftChange,
  onToggleFocusTag,
  onAddDetailTag,
  onRemoveDetailTag,
  onCancel,
  onSave
}: {
  mode: Exclude<PersonaMode, { mode: "list" }>;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onDraftChange: (patch: Partial<PersonaDraft>) => void;
  onToggleFocusTag: (focusTag: FocusTag) => void;
  onAddDetailTag: (tag: string) => void;
  onRemoveDetailTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  const selectedFocusTags = normalizePersonaFocusTags(mode.draft.focus_tags);
  const isCommonFocus = selectedFocusTags.includes("COMMON");
  const hiddenDetailTagCount = mode.draft.detail_tags.length;
  const addAllDetailTags = (tagIds: string[]) => {
    onDraftChange({
      detail_tags: Array.from(new Set([...mode.draft.detail_tags, ...tagIds]))
    });
  };

  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">{mode.mode === "create" ? "Create Persona Entry" : "Edit Persona Entry"}</p>
          <h2>{mode.mode === "create" ? "新增 persona" : "编辑 persona"}</h2>
          <p className="muted-copy">每条 persona 由多个 focus、一个 description，以及若干 detail tags 组成。</p>
        </div>
        <div className="row-actions">
          <button className="action-button quiet" onClick={onCancel} type="button">
            返回列表
          </button>
          <button className="action-button primary" onClick={onSave} type="button">
            保存
          </button>
        </div>
      </div>

      <div className="form-grid">
        <section className="info-block full">
          <h3>Focus Tags</h3>
          <div className="focus-picker">
            {FOCUS_TAGS.map((focusTag) => {
              const selected = selectedFocusTags.includes(focusTag);
              return (
                <button
                  className={selected ? "focus-chip active" : "focus-chip"}
                  key={focusTag}
                  onClick={() => onToggleFocusTag(focusTag)}
                  type="button"
                >
                  {formatFocusTagLabel(focusTag)}
                </button>
              );
            })}
          </div>
        </section>
        <label className="field full">
          <span>Description</span>
          <textarea
            rows={6}
            value={mode.draft.description}
            onChange={(event) => onDraftChange({ description: event.target.value })}
            placeholder="例如：厌恶毫无必要的鲁莽冲锋，会本能地警惕高危环境。"
          />
        </label>
        <label className="toggle-field full">
          <input
            type="checkbox"
            checked={mode.draft.always_recalled}
            onChange={(event) => onDraftChange({ always_recalled: event.target.checked })}
          />
          <div>
            <strong>总是记住这条 persona</strong>
            <p>过多启用这类 persona 会导致 LLM token 爆炸，只应对极少数核心人格偏好使用。</p>
            {!isCommonFocus ? <p>仅在当前注意力领域生效，若全局生效请移动至 Common focus。</p> : null}
          </div>
        </label>
      </div>

      {mode.draft.always_recalled ? (
        <section className="info-block always-recalled-panel">
          <h3>触发行为</h3>
          <p>
            {isCommonFocus
              ? "这条 persona 将在所有情况下触发。"
              : "这条 persona 将在所选注意力领域下的所有情况下触发。"}
          </p>
          {hiddenDetailTagCount > 0 ? (
            <p>已隐藏 {hiddenDetailTagCount} 个 Detail Tags，取消勾选后会重新显示，不会被删除。</p>
          ) : (
            <p>当前没有附带的 Detail Tags；取消勾选后仍可继续添加。</p>
          )}
        </section>
      ) : (
        <div className="two-col persona-editor-grid">
          <section className="info-block">
            <h3>已附带的 Detail Tags</h3>
            {mode.draft.detail_tags.length ? (
              <SelectedTagGrid
                tagIds={mode.draft.detail_tags}
                tags={tags}
                workspaceDirectoryHandle={workspaceDirectoryHandle}
                workspaceImportedFiles={workspaceImportedFiles}
                onRemoveTag={onRemoveDetailTag}
              />
            ) : (
              <p className="muted-copy">还没有 detail tag。</p>
            )}
          </section>

          <section className="info-block tag-picker">
            <TagBrowser
              tags={tags}
              excludedTags={mode.draft.detail_tags}
              title="Tag Browser"
              description="按类似创造模式的分类浏览 tag，点击卡片即可加入当前 persona。"
              searchPlaceholder="搜索 tag id、描述、别名、来源"
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
              onAddAllTags={addAllDetailTags}
              onSelectTag={onAddDetailTag}
            />
          </section>
        </div>
      )}

      <div className="editor-actions">
        <button className="action-button quiet" onClick={onCancel} type="button">
          取消
        </button>
        <button className="action-button primary" onClick={onSave} type="button">
          保存
        </button>
      </div>
    </div>
  );
}

function LocationRegistryPage({
  locations,
  query,
  mode,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onQueryChange,
  onCreate,
  onEdit,
  onDelete,
  onDraftChange,
  onAddDetailTag,
  onRemoveDetailTag,
  onCancel,
  onSave
}: {
  locations: Array<{ name: string; entry: LocationRegistryEntry }>;
  query: string;
  mode: LocationMode;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onQueryChange: (next: string) => void;
  onCreate: () => void;
  onEdit: (name: string) => void;
  onDelete: (name: string) => void;
  onDraftChange: (patch: Partial<LocationDraft>) => void;
  onAddDetailTag: (tag: string) => void;
  onRemoveDetailTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  if (mode.mode !== "list") {
    return (
      <LocationRegistryEditor
        mode={mode}
        tags={tags}
        workspaceDirectoryHandle={workspaceDirectoryHandle}
        workspaceImportedFiles={workspaceImportedFiles}
        onDraftChange={onDraftChange}
        onAddDetailTag={onAddDetailTag}
        onRemoveDetailTag={onRemoveDetailTag}
        onCancel={onCancel}
        onSave={onSave}
      />
    );
  }

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Location Registry</p>
          <h2>地点注册</h2>
          <p className="muted-copy">这里直接管理 `location_registry.json`。游戏内地点注册界面与 Studio 共用同一份 authored 文件。</p>
        </div>
        <span className="badge">{`${locations.length} 个地点`}</span>
      </div>

      <div className="catalog-toolbar">
        <label className="field grow">
          <span>检索</span>
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="按名称、维度、备注、focus 或 detail tag 搜索"
          />
        </label>
        <button className="action-button primary" onClick={onCreate} type="button">
          增加
        </button>
      </div>

      <div className="custom-tag-list">
        {locations.map(({ name, entry }) => (
          <article className="custom-tag-row" key={name}>
            <div className="custom-tag-main">
              <div className="custom-tag-meta">
                <span className="badge">Location</span>
                <span className="badge muted">{entry.dim}</span>
                {entry.focusTag ? <span className="badge">{formatFocusTagLabel(entry.focusTag)}</span> : null}
              </div>
              <strong>{name}</strong>
              <p className="custom-tag-description">
                {`(${entry.x}, ${entry.y}, ${entry.z})`}
                {entry.note ? ` — ${entry.note}` : ""}
              </p>
              <p className="custom-tag-description">
                {`判定范围: 水平 ${entry.horizontalRadius ?? 24} / 垂直 ${entry.verticalRadius ?? 16}`}
              </p>
              <ChipList
                items={(entry.detailTags ?? []).map((tag) => tagRecordOrFallback(tags, tag).label)}
                emptyLabel="无 detail tags"
                compact
              />
            </div>
            <div className="row-actions">
              <button className="action-button quiet" onClick={() => onEdit(name)} type="button">
                编辑
              </button>
              <button className="action-button danger" onClick={() => onDelete(name)} type="button">
                删除
              </button>
            </div>
          </article>
        ))}
        {!locations.length ? (
          <div className="empty-state">
            <strong>没有匹配结果</strong>
            <p>当前检索条件下没有地点注册项。</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

function LocationRegistryEditor({
  mode,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onDraftChange,
  onAddDetailTag,
  onRemoveDetailTag,
  onCancel,
  onSave
}: {
  mode: Exclude<LocationMode, { mode: "list" }>;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onDraftChange: (patch: Partial<LocationDraft>) => void;
  onAddDetailTag: (tag: string) => void;
  onRemoveDetailTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">{mode.mode === "create" ? "Create Location" : "Edit Location"}</p>
          <h2>{mode.mode === "create" ? "新增地点" : "编辑地点"}</h2>
          <p className="muted-copy">地点名会作为 <code>custom_specific:&lt;地点名&gt;</code> 的值参与位置语义解释，因此建议保持简洁稳定。</p>
        </div>
        <div className="row-actions">
          <button className="action-button quiet" onClick={onCancel} type="button">
            返回列表
          </button>
          <button className="action-button primary" onClick={onSave} type="button">
            保存
          </button>
        </div>
      </div>

      <div className="form-grid">
        <label className="field">
          <span>Name</span>
          <input
            value={mode.draft.name}
            onChange={(event) => onDraftChange({ name: event.target.value })}
            placeholder="例如 home / forge_room"
          />
        </label>
        <label className="field">
          <span>Dimension</span>
          <input
            value={mode.draft.dim}
            onChange={(event) => onDraftChange({ dim: event.target.value })}
            placeholder="minecraft:overworld"
          />
        </label>
        <label className="field">
          <span>X</span>
          <input
            value={mode.draft.x}
            onChange={(event) => onDraftChange({ x: event.target.value })}
            type="number"
          />
        </label>
        <label className="field">
          <span>Y</span>
          <input
            value={mode.draft.y}
            onChange={(event) => onDraftChange({ y: event.target.value })}
            type="number"
          />
        </label>
        <label className="field">
          <span>Z</span>
          <input
            value={mode.draft.z}
            onChange={(event) => onDraftChange({ z: event.target.value })}
            type="number"
          />
        </label>
        <label className="field">
          <span>Horizontal Radius</span>
          <input
            min={1}
            value={mode.draft.horizontalRadius}
            onChange={(event) => onDraftChange({ horizontalRadius: event.target.value })}
            type="number"
          />
        </label>
        <label className="field">
          <span>Vertical Radius</span>
          <input
            min={1}
            value={mode.draft.verticalRadius}
            onChange={(event) => onDraftChange({ verticalRadius: event.target.value })}
            type="number"
          />
        </label>
        <label className="field">
          <span>Focus Tag</span>
          <select
            value={mode.draft.focusTag}
            onChange={(event) => onDraftChange({ focusTag: event.target.value as FocusTag | "" })}
          >
            <option value="">无</option>
            {FOCUS_TAGS.map((focusTag) => (
              <option key={focusTag} value={focusTag}>
                {formatFocusTagLabel(focusTag)}
              </option>
            ))}
          </select>
        </label>
        <label className="field full">
          <span>Note</span>
          <textarea
            rows={4}
            value={mode.draft.note}
            onChange={(event) => onDraftChange({ note: event.target.value })}
            placeholder="写给玩家和 LLM 的地点备注"
          />
        </label>
      </div>

      <p className="muted-copy">
        每个地点都可以有独立的判定范围。只有玩家进入该范围时，这个地点才会被当作“当前登记地点”写入常驻语义，并生成对应的
        <code>custom_specific:&lt;地点名&gt;</code> 上下文标签。
      </p>

      <div className="two-col persona-editor-grid">
        <section className="info-block">
          <h3>已附带的 Detail Tags</h3>
          {mode.draft.detailTags.length ? (
            <SelectedTagGrid
              tagIds={mode.draft.detailTags}
              tags={tags}
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
              onRemoveTag={onRemoveDetailTag}
            />
          ) : (
            <p className="muted-copy">还没有 detail tag。</p>
          )}
        </section>

        <section className="info-block tag-picker">
          <TagBrowser
            tags={tags}
            excludedTags={mode.draft.detailTags}
            title="Tag Browser"
            description="点击卡片即可把 detail tag 加到这个地点上。"
            searchPlaceholder="搜索 detail tag"
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onSelectTag={onAddDetailTag}
          />
        </section>
      </div>

      <div className="editor-actions">
        <button className="action-button quiet" onClick={onCancel} type="button">
          取消
        </button>
        <button className="action-button primary" onClick={onSave} type="button">
          保存
        </button>
      </div>
    </div>
  );
}

function MemoryPage({
  memories,
  query,
  mode,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onQueryChange,
  onCreate,
  onEdit,
  onDelete,
  onDraftChange,
  onToggleFocusTag,
  onAddDetailTag,
  onRemoveDetailTag,
  onCancel,
  onSave
}: {
  memories: Array<{ memory: ImplantedMemoryEntry; index: number }>;
  query: string;
  mode: MemoryMode;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onQueryChange: (next: string) => void;
  onCreate: () => void;
  onEdit: (index: number) => void;
  onDelete: (index: number) => void;
  onDraftChange: (patch: Partial<MemoryDraft>) => void;
  onToggleFocusTag: (focusTag: FocusTag) => void;
  onAddDetailTag: (tag: string) => void;
  onRemoveDetailTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  if (mode.mode !== "list") {
    return (
      <MemoryEditor
        mode={mode}
        tags={tags}
        workspaceDirectoryHandle={workspaceDirectoryHandle}
        workspaceImportedFiles={workspaceImportedFiles}
        onDraftChange={onDraftChange}
        onToggleFocusTag={onToggleFocusTag}
        onAddDetailTag={onAddDetailTag}
        onRemoveDetailTag={onRemoveDetailTag}
        onCancel={onCancel}
        onSave={onSave}
      />
    );
  }

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Implanted Memories</p>
          <h2>植入记忆管理</h2>
          <p className="muted-copy">先在列表里浏览和检索记忆，再进入单独编辑页修改完整内容。</p>
        </div>
      </div>

      <div className="catalog-toolbar">
        <label className="field grow">
          <span>检索</span>
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="按标题、描述、focus、detail tag 或时间锚点搜索"
          />
        </label>
        <button className="action-button primary" onClick={onCreate} type="button">
          增加
        </button>
      </div>

      <div className="custom-tag-list">
        {memories.map(({ memory, index }) => (
          <article className="custom-tag-row memory-row" key={memory.id ?? `memory-${index}`}>
            <div className="custom-tag-main">
              <div className="custom-tag-meta">
                <span className="badge">Memory</span>
                {memory.importance !== undefined ? (
                  <span className="badge">{`重要度 ${memory.importance}`}</span>
                ) : null}
                {memory.time_label ? <span className="badge muted">{memory.time_label}</span> : null}
              </div>
              <strong className="memory-title">{memory.label ?? `Memory ${index + 1}`}</strong>
              <p className="custom-tag-description">{memory.description}</p>
              <ChipList items={formatFocusTagItems(memory.focus_tags)} emptyLabel="无 focus tags" compact />
            </div>
            <div className="row-actions">
              <button className="action-button quiet" onClick={() => onEdit(index)} type="button">
                编辑
              </button>
              <button className="action-button danger" onClick={() => onDelete(index)} type="button">
                删除
              </button>
            </div>
          </article>
        ))}
        {!memories.length ? (
          <div className="empty-state">
            <strong>没有匹配结果</strong>
            <p>当前检索条件下没有 implanted memory。</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

function MemoryEditor({
  mode,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onDraftChange,
  onToggleFocusTag,
  onAddDetailTag,
  onRemoveDetailTag,
  onCancel,
  onSave
}: {
  mode: Exclude<MemoryMode, { mode: "list" }>;
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onDraftChange: (patch: Partial<MemoryDraft>) => void;
  onToggleFocusTag: (focusTag: FocusTag) => void;
  onAddDetailTag: (tag: string) => void;
  onRemoveDetailTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  const selectedTimeTagRecord = findTagRecord(tags, mode.draft.semantic_time_tag);
  const [timeTagBrowserExpanded, setTimeTagBrowserExpanded] = useState(false);
  const addAllDetailTags = (tagIds: string[]) => {
    onDraftChange({
      detail_tags: Array.from(new Set([...mode.draft.detail_tags, ...tagIds]))
    });
  };

  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">{mode.mode === "create" ? "Create Memory" : "Edit Memory"}</p>
          <h2>{mode.mode === "create" ? "新增植入记忆" : "编辑植入记忆"}</h2>
          <p className="muted-copy">在单独编辑页中维护记忆文本、focus tags、detail tags 与语义时间锚。</p>
          <p className="muted-copy">植入记忆不再编辑维度、群系、天气，只保留更适合叙事的时间锚。</p>
          <p className="muted-copy">该时间锚会参与记忆联想，但不会混入普通 detail tag 线索。</p>
        </div>
        <div className="row-actions">
          <button className="action-button quiet" onClick={onCancel} type="button">
            返回列表
          </button>
          <button className="action-button primary" onClick={onSave} type="button">
            保存
          </button>
        </div>
      </div>

      <div className="form-grid">
        <label className="field">
          <span>Label</span>
          <input
            value={mode.draft.label}
            onChange={(event) => onDraftChange({ label: event.target.value })}
            placeholder="可选的编辑器标题"
          />
        </label>
        <label className="field">
          <span>Importance</span>
          <input
            type="number"
            min={0}
            max={1}
            step={0.01}
            value={mode.draft.importance}
            onChange={(event) => onDraftChange({ importance: event.target.value })}
            placeholder="0.0 - 1.0"
          />
        </label>
        <label className="field full">
          <span>Description</span>
          <textarea
            rows={6}
            value={mode.draft.description}
            onChange={(event) => onDraftChange({ description: event.target.value })}
            placeholder="写入长期记忆的实际内容"
          />
        </label>
        <label className="field">
          <span>Occurred Day Index</span>
          <input
            type="number"
            min={1}
            value={mode.draft.occurred_day_index}
            onChange={(event) => onDraftChange({ occurred_day_index: event.target.value })}
            placeholder="可选；如需绝对时间锚点，请与右侧字段成对填写"
          />
        </label>
        <label className="field">
          <span>Occurred Time Of Day Tick</span>
          <input
            type="number"
            min={0}
            max={23999}
            value={mode.draft.occurred_time_of_day_tick}
            onChange={(event) => onDraftChange({ occurred_time_of_day_tick: event.target.value })}
            placeholder="0 - 23999；留空则视为无绝对时间锚点"
          />
        </label>
        <label className="field full">
          <span>Notes</span>
          <textarea
            rows={3}
            value={mode.draft.notes}
            onChange={(event) => onDraftChange({ notes: event.target.value })}
            placeholder="仅供编辑器使用的备注"
          />
        </label>
      </div>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>时间 Custom Tag</h3>
            <p className="muted-copy">植入记忆的时间语义会参与记忆之间的联想边，但会保持为独立字段，不并入普通 detail tag 列表。</p>
          </div>
          <button
            className="action-button quiet"
            onClick={() => setTimeTagBrowserExpanded((current) => !current)}
            type="button"
          >
            {timeTagBrowserExpanded ? "收起时间 Tag Browser" : "展开时间 Tag Browser"}
          </button>
        </div>
        {selectedTimeTagRecord ? (
          <div className="selected-time-tag">
            <div className="custom-tag-meta">
              <span className="badge warn">Semantic Time</span>
              <span className="badge">{selectedTimeTagRecord.kind}</span>
            </div>
            <strong>{selectedTimeTagRecord.label}</strong>
            <code>{selectedTimeTagRecord.tag}</code>
            <p>{selectedTimeTagRecord.description || "该 custom tag 暂无 description。"}</p>
            <button
              className="action-button quiet"
              onClick={() => onDraftChange({ semantic_time_tag: "" })}
              type="button"
            >
              清除时间 Tag
            </button>
          </div>
        ) : (
          <p className="muted-copy">尚未选择时间 custom tag。</p>
        )}
        {timeTagBrowserExpanded ? (
          <TagBrowser
            tags={tags}
            allowedKinds={CUSTOM_TAG_KINDS}
            excludedTags={mode.draft.semantic_time_tag ? [mode.draft.semantic_time_tag] : []}
            title="时间 Tag Browser"
            description="这里只浏览 custom tag，用于挑选语义时间锚。"
            searchPlaceholder="搜索时间相关的 custom tag"
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            initialCategory="custom"
            onSelectTag={(tag) => onDraftChange({ semantic_time_tag: tag })}
          />
        ) : (
          <p className="muted-copy">时间 tag 浏览器当前已折叠。</p>
        )}
      </section>

      <section className="info-block">
        <h3>Focus Tags</h3>
        <div className="focus-picker">
          {FOCUS_TAGS.map((focusTag) => {
            const selected = mode.draft.focus_tags.includes(focusTag);
            return (
              <button
                className={selected ? "focus-chip active" : "focus-chip"}
                key={focusTag}
                onClick={() => onToggleFocusTag(focusTag)}
                type="button"
              >
                {formatFocusTagLabel(focusTag)}
              </button>
            );
          })}
        </div>
      </section>

      <div className="two-col persona-editor-grid">
        <section className="info-block">
          <h3>已附带的 Detail Tags</h3>
          {mode.draft.detail_tags.length ? (
            <SelectedTagGrid
              tagIds={mode.draft.detail_tags}
              tags={tags}
              workspaceDirectoryHandle={workspaceDirectoryHandle}
              workspaceImportedFiles={workspaceImportedFiles}
              onRemoveTag={onRemoveDetailTag}
            />
          ) : (
            <p className="muted-copy">还没有 detail tag。</p>
          )}
        </section>

        <section className="info-block tag-picker">
          <TagBrowser
            tags={tags}
            excludedTags={mode.draft.detail_tags}
            title="Tag Browser"
            description="按分类浏览和检索 detail tag，点击卡片即可加入当前记忆。"
            searchPlaceholder="搜索 tag id、描述、别名、来源"
            workspaceDirectoryHandle={workspaceDirectoryHandle}
            workspaceImportedFiles={workspaceImportedFiles}
            onAddAllTags={addAllDetailTags}
            onSelectTag={onAddDetailTag}
          />
        </section>
      </div>

      <div className="editor-actions">
        <button className="action-button quiet" onClick={onCancel} type="button">
          取消
        </button>
        <button className="action-button primary" onClick={onSave} type="button">
          保存
        </button>
      </div>
    </div>
  );
}

function AgentStatePage({
  agentStateFile,
  onStateChange,
  onPersonaStateChange,
  onAttentionWeightChange,
  onCrosshairBonusEnabledChange
}: {
  agentStateFile: AgentStateWorkspaceFile;
  onStateChange: (patch: Partial<AgentStateWorkspaceFile["state"]>) => void;
  onPersonaStateChange: (patch: Partial<AgentStateWorkspaceFile["persona_state"]>) => void;
  onAttentionWeightChange: (weightKey: AgentStateWeightKey, nextValue: number) => void;
  onCrosshairBonusEnabledChange: (nextValue: boolean) => void;
}) {
  const strongestAttention = useMemo(() => {
    return [...AGENT_STATE_WEIGHT_KEYS]
      .map((weightKey) => ({
        weightKey,
        value: agentStateFile.attention_weights[weightKey]
      }))
      .sort((left, right) => right.value - left.value)[0];
  }, [agentStateFile.attention_weights]);

  const weakestAttention = useMemo(() => {
    return [...AGENT_STATE_WEIGHT_KEYS]
      .map((weightKey) => ({
        weightKey,
        value: agentStateFile.attention_weights[weightKey]
      }))
      .sort((left, right) => left.value - right.value)[0];
  }, [agentStateFile.attention_weights]);

  const recallTuningSummary = useMemo(() => {
    const tuning = agentStateFile.state.recall_tuning;
    return `main=${tuning.main_candidates_per_cue}, sandbox=${tuning.sandbox_cues_per_memory}, conscious=${tuning.conscious_memory_cap}, retention=${tuning.retention_memory_cap}`;
  }, [agentStateFile.state.recall_tuning]);

  const recallPresetDescription = useMemo(() => {
    switch (agentStateFile.state.recall_preset) {
      case "SEED_FIRST":
        return "Seed First 会更严格地压低弱 cue 的影响，只有少数强 cue 能主导召回，命中更稀疏但也更像被偶然触发。";
      case "FULL_VISIBILITY":
        return "Full Visibility 会尽量让当前可见 cue 全部参与主召回，反馈最强，最容易命中玩家手工编辑的记忆。";
      case "CUSTOM":
        return "自定义预设会读取下方 recall tuning 参数。当前统一模型始终先维护 tag pool，再做主召回、发酵层筛选、表层意识筛选和 sandbox 联想。";
      case "HYBRID":
      default:
        return "Hybrid 是默认模式。主召回相对克制，但允许表层记忆在 sandbox 中带出更多联想 cue，兼顾命中率和稳定性。";
    }
  }, [agentStateFile.state.recall_preset]);

  const updateRecallTuning = (key: keyof AgentStateRecallTuning, nextValue: number) => {
    onStateChange({
      recall_tuning: {
        ...agentStateFile.state.recall_tuning,
        [key]: nextValue
      }
    });
  };

  const promptPreview = useMemo(() => {
    const currentDesireText = agentStateFile.state.current_desire_tag
      ? `, desire=${agentStateFile.state.current_desire_tag}`
      : "";
    const desireDescriptionText = agentStateFile.state.desire_description.trim()
      ? `, desireDescription=${agentStateFile.state.desire_description.trim()}`
      : "";
    const playerProfileText = agentStateFile.persona_state.player_profile.trim()
      ? `\n对持有者的看法: ${agentStateFile.persona_state.player_profile.trim()}`
      : "";

    return [
      `[AgentState] emotion=${agentStateFile.state.primary_emotion} (intensity=${agentStateFile.state.emotion_intensity}/100), familiarity=${agentStateFile.state.familiarity}, concentration=${agentStateFile.state.concentration.toFixed(2)}, wandering=${agentStateFile.state.wandering_mind.toFixed(2)}, nostalgia=${agentStateFile.state.nostalgia.toFixed(2)}, recallPreset=${agentStateFile.state.recall_preset}, 对玩家称呼=${agentStateFile.state.player_address}${currentDesireText}${desireDescriptionText}`,
      `[AgentPersona]`,
      `好感度: ${agentStateFile.persona_state.favorability}/100${playerProfileText}`
    ].join("\n");
  }, [agentStateFile]);

  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Shared Agent State</p>
          <h2>Agent State / Persona State</h2>
          <p className="muted-copy">
            本页直接编辑 `agent_state.json`。游戏内 State Screen 与 Studio 共享这一份 authored 状态文件。
          </p>
          <p className="muted-copy">
            此处修改会立即写入 Studio 当前草稿；点击顶部工作区区域里的“保存”后，才会真正回写文件并触发热重载。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{formatPrimaryEmotionLabel(agentStateFile.state.primary_emotion)}</span>
          <span className="badge muted">{formatRecallPresetLabel(agentStateFile.state.recall_preset)}</span>
          <span className={agentStateFile.persona_state.favorability >= 0 ? "badge good" : "badge warn"}>
            {`Favorability ${agentStateFile.persona_state.favorability}`}
          </span>
        </div>
      </div>

      <div className="two-col state-overview-grid">
        <section className="info-block">
          <h3>当前概览</h3>
          <div className="chip-row">
            <span className="chip">{`称呼：${agentStateFile.state.player_address}`}</span>
            <span className="chip">{`熟悉度：${agentStateFile.state.familiarity}`}</span>
            <span className="chip">{`专注度：${agentStateFile.state.concentration.toFixed(2)}`}</span>
            <span className="chip">{`随想值：${agentStateFile.state.wandering_mind.toFixed(2)}`}</span>
            <span className="chip">{`忆旧值（预留）：${agentStateFile.state.nostalgia.toFixed(2)}`}</span>
            <span className="chip">{`植入保底：${agentStateFile.state.implanted_memory_safety_net ? "开启" : "关闭"}`}</span>
            <span className="chip">{`准心加分：${agentStateFile.attention_weights.crosshair_bonus_enabled ? "开启" : "关闭"}`}</span>
            {agentStateFile.state.recall_preset === "CUSTOM" ? (
              <span className="chip">{`Recall Tuning：${recallTuningSummary}`}</span>
            ) : null}
            <span className="chip">
              {agentStateFile.state.current_desire_tag
                ? `当前渴望：${formatFocusTagLabel(agentStateFile.state.current_desire_tag)}`
                : "当前渴望：无"}
            </span>
          </div>
          <p>
            当前最强注意力权重是 {formatAgentStateWeightLabel(strongestAttention.weightKey)}，
            值为 {strongestAttention.value.toFixed(2)}；最弱的是{" "}
            {formatAgentStateWeightLabel(weakestAttention.weightKey)}，
            值为 {weakestAttention.value.toFixed(2)}。
          </p>
        </section>

        <section className="info-block">
          <h3>Prompt 预览</h3>
          <pre className="state-preview">{promptPreview}</pre>
        </section>
      </div>

      <section className="info-block">
        <h3>核心状态</h3>
        <div className="form-grid">
          <label className="field">
            <span>Primary Emotion</span>
            <select
              value={agentStateFile.state.primary_emotion}
              onChange={(event) => onStateChange({ primary_emotion: event.target.value })}
            >
              {PRIMARY_EMOTION_OPTIONS.map((emotion) => (
                <option key={emotion} value={emotion}>
                  {formatPrimaryEmotionLabel(emotion)}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span>Player Address</span>
            <input
              type="text"
              value={agentStateFile.state.player_address}
              onChange={(event) => onStateChange({ player_address: event.target.value })}
              placeholder="例如 主人 / 朋友 / 锻造者"
            />
          </label>

          <label className="field">
            <span>Emotion Intensity</span>
            <div className="range-field">
              <input
                max={100}
                min={0}
                step={1}
                type="range"
                value={agentStateFile.state.emotion_intensity}
                onChange={(event) => onStateChange({ emotion_intensity: Number.parseInt(event.target.value, 10) })}
              />
              <strong className="range-field-output">{agentStateFile.state.emotion_intensity}</strong>
            </div>
          </label>

          <label className="field">
            <span>Favorability</span>
            <div className="range-field">
              <input
                max={100}
                min={-100}
                step={1}
                type="range"
                value={agentStateFile.persona_state.favorability}
                onChange={(event) =>
                  onPersonaStateChange({ favorability: Number.parseInt(event.target.value, 10) })
                }
              />
              <strong className="range-field-output">{agentStateFile.persona_state.favorability}</strong>
            </div>
            <input
              max={100}
              min={-100}
              step={1}
              type="number"
              value={agentStateFile.persona_state.favorability}
              onChange={(event) => {
                const nextValue = Number.parseInt(event.target.value, 10);
                onPersonaStateChange({
                  favorability: Number.isFinite(nextValue)
                    ? Math.max(-100, Math.min(100, nextValue))
                    : agentStateFile.persona_state.favorability
                });
              }}
            />
          </label>

          <label className="field">
            <span>Familiarity</span>
            <input
              min={0}
              step={1}
              type="number"
              value={agentStateFile.state.familiarity}
              onChange={(event) => {
                const nextValue = Number.parseInt(event.target.value, 10);
                onStateChange({
                  familiarity: Number.isFinite(nextValue) ? nextValue : agentStateFile.state.familiarity
                });
              }}
            />
          </label>

          <label className="field">
            <span>Recall Preset</span>
            <select
              value={agentStateFile.state.recall_preset}
              onChange={(event) => onStateChange({ recall_preset: event.target.value })}
            >
              {RECALL_PRESET_OPTIONS.map((mode) => (
                <option key={mode} value={mode}>
                  {formatRecallPresetLabel(mode)}
                </option>
              ))}
            </select>
            <p className="muted-copy">{recallPresetDescription}</p>
          </label>

          {agentStateFile.state.recall_preset === "CUSTOM" ? (
            <div className="field full">
              <span>Recall Tuning</span>
              <p className="muted-copy">
                这组数值会写入 <code>agent_state.json.state.recall_tuning</code>，游戏内 State Screen 与 Studio 共用。
              </p>
              <div className="form-grid state-form-grid">
                {AGENT_STATE_RECALL_TUNING_FIELDS.map((field) => (
                  <label key={field.key} className="field">
                    <span>{field.label}</span>
                    <input
                      max={field.max}
                      min={field.min}
                      step={1}
                      type="number"
                      value={agentStateFile.state.recall_tuning[field.key]}
                      onChange={(event) => {
                        const nextValue = Number.parseInt(event.target.value, 10);
                        updateRecallTuning(
                          field.key,
                          Number.isFinite(nextValue)
                            ? Math.round(clampNumber(nextValue, field.min, field.max))
                            : agentStateFile.state.recall_tuning[field.key]
                        );
                      }}
                    />
                    {field.note ? <p className="muted-copy">{field.note}</p> : null}
                  </label>
                ))}
              </div>
            </div>
          ) : null}

          <label className="field">
            <span>Concentration</span>
            <div className="range-field">
              <input
                max={1}
                min={0}
                step={0.01}
                type="range"
                value={agentStateFile.state.concentration}
                onChange={(event) => onStateChange({ concentration: Number.parseFloat(event.target.value) })}
              />
              <strong className="range-field-output">{agentStateFile.state.concentration.toFixed(2)}</strong>
            </div>
          </label>

          <label className="field">
            <span>Wandering Mind</span>
            <div className="range-field">
              <input
                max={1}
                min={0}
                step={0.01}
                type="range"
                value={agentStateFile.state.wandering_mind}
                onChange={(event) => onStateChange({ wandering_mind: Number.parseFloat(event.target.value) })}
              />
              <strong className="range-field-output">{agentStateFile.state.wandering_mind.toFixed(2)}</strong>
            </div>
          </label>

          <label className="field">
            <span>Nostalgia (Reserved)</span>
            <div className="range-field">
              <input
                max={2}
                min={0}
                step={0.01}
                type="range"
                value={agentStateFile.state.nostalgia}
                onChange={(event) => onStateChange({ nostalgia: Number.parseFloat(event.target.value) })}
              />
              <strong className="range-field-output">{agentStateFile.state.nostalgia.toFixed(2)}</strong>
            </div>
            <p className="muted-copy">当前为预留参数，暂未接入 recall 公式。</p>
          </label>

          <label className="field full">
            <span>Implanted Memory Safety Net</span>
            <label className="checkbox-field">
              <input
                type="checkbox"
                checked={agentStateFile.state.implanted_memory_safety_net}
                onChange={(event) => onStateChange({ implanted_memory_safety_net: event.target.checked })}
              />
              <span>若本轮正常召回未命中 `occurredGameTime &lt; 0` 的植入记忆，则额外执行一次轻量补捞。</span>
            </label>
            <p className="muted-copy">这个开关已与 nostalgia 解耦。关闭后，系统将完全依赖正常 seed recall，不再做植入记忆保底。</p>
          </label>
        </div>
      </section>

      <div className="two-col state-detail-grid">
        <section className="info-block">
          <h3>当前渴望</h3>
          <div className="form-grid state-form-grid">
            <label className="field">
              <span>Focus Tag</span>
              <select
                value={agentStateFile.state.current_desire_tag ?? ""}
                onChange={(event) =>
                  onStateChange({
                    current_desire_tag: event.target.value ? (event.target.value as FocusTag) : null
                  })
                }
              >
                <option value="">无</option>
                {FOCUS_TAGS.map((focusTag) => (
                  <option key={focusTag} value={focusTag}>
                    {formatFocusTagLabel(focusTag)}
                  </option>
                ))}
              </select>
            </label>

            <label className="field full">
              <span>Desire Description</span>
              <textarea
                rows={5}
                value={agentStateFile.state.desire_description}
                onChange={(event) => onStateChange({ desire_description: event.target.value })}
                placeholder="例如：想再次经历被认真锻造、被赋予意义的时刻。"
              />
            </label>
          </div>
        </section>

        <section className="info-block">
          <h3>玩家画像</h3>
          <p className="muted-copy">这一段是剑对持有者的长期主观印象，属于 Persona 的动态部分，但与 state 一起共存在 `agent_state.json`。</p>
          <label className="field">
            <span>Player Profile</span>
            <textarea
              rows={7}
              value={agentStateFile.persona_state.player_profile}
              onChange={(event) => onPersonaStateChange({ player_profile: event.target.value })}
              placeholder="例如：他做事有些莽撞，但并不轻慢我；更多时候，是把我当真正的同伴。"
            />
          </label>
        </section>
      </div>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>注意力权重</h3>
            <p className="muted-copy">
              这里对应游戏内 State Screen 暴露的 attention weights。它们会直接影响当前上下文下 focus 召回的偏置。
            </p>
          </div>
          <span className="badge">{`${AGENT_STATE_WEIGHT_KEYS.length} 项`}</span>
        </div>

        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={agentStateFile.attention_weights.crosshair_bonus_enabled}
            onChange={(event) => onCrosshairBonusEnabledChange(event.target.checked)}
          />
          <span>启用准心额外加分。开启后，`crosshairTarget = true` 的可见上下文会叠加独立加分。</span>
        </label>

        <div className="two-col state-weight-layout">
          <AttentionWeightRadarChart weights={agentStateFile.attention_weights} />

          <div className="state-weight-grid">
            {AGENT_STATE_WEIGHT_KEYS.map((weightKey) => (
              <label className="field state-weight-card" key={weightKey}>
                <span>{formatAgentStateWeightLabel(weightKey)}</span>
                <div className="range-field">
                  <input
                    max={10}
                    min={0}
                    step={0.05}
                    type="range"
                    value={agentStateFile.attention_weights[weightKey]}
                    onChange={(event) => {
                      const nextValue = Number.parseFloat(event.target.value);
                      onAttentionWeightChange(
                        weightKey,
                        Number.isFinite(nextValue) ? nextValue : agentStateFile.attention_weights[weightKey]
                      );
                    }}
                  />
                  <strong className="range-field-output">
                    {agentStateFile.attention_weights[weightKey].toFixed(2)}
                  </strong>
                </div>
                <input
                  max={10}
                  min={0}
                  step={0.05}
                  type="number"
                  value={agentStateFile.attention_weights[weightKey]}
                  onChange={(event) => {
                    const nextValue = Number.parseFloat(event.target.value);
                    onAttentionWeightChange(
                      weightKey,
                      Number.isFinite(nextValue) ? nextValue : agentStateFile.attention_weights[weightKey]
                    );
                  }}
                />
              </label>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

function ImportancePage({
  entries,
  query,
  newKey,
  newValue,
  onQueryChange,
  onNewKeyChange,
  onNewValueChange,
  onAdd,
  onUpdate,
  onDelete
}: {
  entries: Array<[string, number]>;
  query: string;
  newKey: string;
  newValue: string;
  onQueryChange: (value: string) => void;
  onNewKeyChange: (value: string) => void;
  onNewValueChange: (value: string) => void;
  onAdd: () => void;
  onUpdate: (key: string, nextValue: number) => void;
  onDelete: (key: string) => void;
}) {
  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Base Importance</p>
          <h2>Importance Registry</h2>
          <p className="muted-copy">本页直接编辑 `importance.json`。游戏端 collector 的 base importance 基准现在与 Studio 共用同一份 workspace authored 文件。</p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${entries.length} 条`}</span>
          <span className="badge muted">workspace true source</span>
        </div>
      </div>

      <section className="info-block">
        <div className="form-grid state-form-grid">
          <label className="field">
            <span>搜索 key</span>
            <input value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder="例如 base:health_critical" />
          </label>
          <label className="field">
            <span>新增 key</span>
            <input value={newKey} onChange={(event) => onNewKeyChange(event.target.value)} placeholder="例如 look:entity" />
          </label>
          <label className="field">
            <span>新增 value</span>
            <input value={newValue} onChange={(event) => onNewValueChange(event.target.value)} type="number" min={0} max={1} step={0.01} />
          </label>
        </div>
        <div className="row-actions">
          <button className="action-button primary" onClick={onAdd} type="button">新增 importance</button>
        </div>
      </section>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>Workspace Entries</h3>
            <p className="muted-copy">value 范围按 0.0 到 1.0 处理。这里改的是 collector 注入 `baseImportance` 时的 authored 基准值。</p>
          </div>
        </div>
        <div className="form-grid">
          {entries.map(([key, value]) => (
            <div className="field full" key={key}>
              <span>{key}</span>
              <div className="row-actions">
                <input
                  type="number"
                  min={0}
                  max={1}
                  step={0.01}
                  value={value}
                  onChange={(event) => onUpdate(key, Number.parseFloat(event.target.value))}
                />
                <button className="action-button quiet" onClick={() => onDelete(key)} type="button">删除</button>
              </div>
            </div>
          ))}
          {!entries.length ? <p className="muted-copy">当前没有匹配的 importance 项。</p> : null}
        </div>
      </section>
    </div>
  );
}

function PromptsPage({
  entries,
  query,
  newKey,
  newValue,
  onQueryChange,
  onNewKeyChange,
  onNewValueChange,
  onAdd,
  onUpdate,
  onDelete
}: {
  entries: Array<[string, string]>;
  query: string;
  newKey: string;
  newValue: string;
  onQueryChange: (value: string) => void;
  onNewKeyChange: (value: string) => void;
  onNewValueChange: (value: string) => void;
  onAdd: () => void;
  onUpdate: (key: string, nextValue: string) => void;
  onDelete: (key: string) => void;
}) {
  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Prompt Registry</p>
          <h2>Prompts</h2>
          <p className="muted-copy">本页直接编辑 `prompts.json`。触发 user message、event instruction 和系统文案现在与 Studio 共用同一份 workspace authored 文件。</p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${entries.length} 条`}</span>
          <span className="badge muted">workspace true source</span>
        </div>
      </div>

      <section className="info-block">
        <div className="form-grid state-form-grid">
          <label className="field">
            <span>搜索 key / value</span>
            <input value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder="例如 trigger.right_click" />
          </label>
          <label className="field">
            <span>新增 key</span>
            <input value={newKey} onChange={(event) => onNewKeyChange(event.target.value)} placeholder="例如 trigger.right_click.event_instruction" />
          </label>
          <label className="field full">
            <span>新增文本</span>
            <textarea rows={3} value={newValue} onChange={(event) => onNewValueChange(event.target.value)} />
          </label>
        </div>
        <div className="row-actions">
          <button className="action-button primary" onClick={onAdd} type="button">新增 prompt</button>
        </div>
      </section>

      <section className="info-block">
        <div className="section-head">
          <div>
            <h3>Workspace Entries</h3>
            <p className="muted-copy">这里直接编辑 PromptRegistry 的真实键值。删除后，运行时会回退到代码里的默认文案。</p>
          </div>
        </div>
        <div className="form-grid">
          {entries.map(([key, value]) => (
            <label className="field full" key={key}>
              <span>{key}</span>
              <textarea rows={4} value={value} onChange={(event) => onUpdate(key, event.target.value)} />
              <div className="row-actions">
                <button className="action-button quiet" onClick={() => onDelete(key)} type="button">删除</button>
              </div>
            </label>
          ))}
          {!entries.length ? <p className="muted-copy">当前没有匹配的 prompt 项。</p> : null}
        </div>
      </section>
    </div>
  );
}

function AttentionWeightRadarChart({
  weights
}: {
  weights: AgentStateWorkspaceFile["attention_weights"];
}) {
  const size = 460;
  const center = size / 2;
  const radius = 138;
  const labelRadius = 186;
  const ringCount = 5;
  const maxValue = 10;

  const axes = useMemo(
    () =>
      AGENT_STATE_WEIGHT_KEYS.map((weightKey, index) => {
        const turn = index / AGENT_STATE_WEIGHT_KEYS.length;
        const axisPoint = polarPoint(center, radius, turn);
        const labelPoint = polarPoint(center, labelRadius, turn);
        const valuePoint = polarPoint(center, radius * (weights[weightKey] / maxValue), turn);
        const textAnchor: "start" | "middle" | "end" =
          labelPoint.x < center - 12 ? "end" : labelPoint.x > center + 12 ? "start" : "middle";

        return {
          weightKey,
          axisPoint,
          labelPoint,
          valuePoint,
          textAnchor
        };
      }),
    [center, labelRadius, radius, weights]
  );

  const ringPolygons = useMemo(
    () =>
      Array.from({ length: ringCount }, (_, index) => {
        const factor = (index + 1) / ringCount;
        return AGENT_STATE_WEIGHT_KEYS.map((_, axisIndex) => {
          const point = polarPoint(center, radius * factor, axisIndex / AGENT_STATE_WEIGHT_KEYS.length);
          return `${point.x},${point.y}`;
        }).join(" ");
      }),
    [center, radius]
  );

  const dataPolygon = axes.map((axis) => `${axis.valuePoint.x},${axis.valuePoint.y}`).join(" ");

  return (
    <div className="state-radar-card">
      <div className="section-head">
        <div>
          <h3>权重雷达图</h3>
          <p className="muted-copy">半径越长，说明当前焦点越容易在召回中被放大。量程固定为 0.00 到 10.00。</p>
        </div>
        <span className="badge muted">Radar</span>
      </div>

      <div className="state-radar-shell">
        <svg
          aria-label="Attention weights radar chart"
          className="state-radar-chart"
          role="img"
          viewBox={`0 0 ${size} ${size}`}
        >
          {ringPolygons.map((points, index) => (
            <polygon
              className="state-radar-ring"
              key={`ring-${index + 1}`}
              points={points}
            />
          ))}

          {axes.map((axis) => (
            <line
              className="state-radar-axis"
              key={`axis-${axis.weightKey}`}
              x1={center}
              x2={axis.axisPoint.x}
              y1={center}
              y2={axis.axisPoint.y}
            />
          ))}

          <polygon className="state-radar-shape" points={dataPolygon} />

          {axes.map((axis) => (
            <circle
              className="state-radar-point"
              cx={axis.valuePoint.x}
              cy={axis.valuePoint.y}
              key={`point-${axis.weightKey}`}
              r={4.5}
            />
          ))}

          {Array.from({ length: ringCount }, (_, index) => {
            const value = ((index + 1) * maxValue) / ringCount;
            return (
              <text
                className="state-radar-tick"
                key={`tick-${value}`}
                x={center + 8}
                y={center - (radius * (index + 1)) / ringCount}
              >
                {value.toFixed(1)}
              </text>
            );
          })}

          {axes.map((axis) => (
            <g key={`label-${axis.weightKey}`}>
              <text
                className="state-radar-label"
                textAnchor={axis.textAnchor}
                x={axis.labelPoint.x}
                y={axis.labelPoint.y - 2}
              >
                {formatAgentStateWeightShortLabel(axis.weightKey)}
              </text>
              <text
                className="state-radar-value"
                textAnchor={axis.textAnchor}
                x={axis.labelPoint.x}
                y={axis.labelPoint.y + 14}
              >
                {weights[axis.weightKey].toFixed(2)}
              </text>
            </g>
          ))}
        </svg>
      </div>
    </div>
  );
}

function RuntimeThoughtsPage({
  generatedAt,
  thoughts
}: {
  generatedAt: string;
  thoughts: RuntimeThoughtEntry[];
}) {
  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Inner Monologue Archive</p>
          <h2>完整心路历程</h2>
          <p className="muted-copy">
            这里把 runtime 交互文件中的每一条 `inner_monologue` 都拆成独立 entry，
            方便逐条追踪剑灵在每次触发里的内心活动。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${thoughts.length} 条心声`}</span>
          <span className="badge muted">
            {generatedAt ? `最近同步 ${formatRuntimeTimestamp(Date.parse(generatedAt))}` : "尚未读到 runtime 文件"}
          </span>
        </div>
      </div>

      {thoughts.length ? (
        <div className="runtime-entry-list">
          {thoughts.map((thought) => (
            <article className="runtime-entry-card" key={thought.id}>
              <div className="runtime-entry-head">
                <div>
                  <div className="custom-tag-meta">
                    <span className="badge warn">Inner Monologue</span>
                    {thought.triggerSource ? <span className="badge muted">{thought.triggerSource}</span> : null}
                    {thought.hasImage ? <span className="badge muted">+image</span> : null}
                  </div>
                  <h3>{formatRuntimeTimestamp(thought.outputTimestamp || thought.interactionTimestamp)}</h3>
                </div>
                <span className="badge">{buildInteractionActionLine({
                  timestamp: thought.interactionTimestamp,
                  triggerSource: thought.triggerSource,
                  userPrompt: thought.userPrompt,
                  hasImage: thought.hasImage,
                  actionTypes: thought.actionTypes,
                  outputs: []
                }).replace(/^动作: /, "")}</span>
              </div>

              <p className="runtime-entry-trigger">{`触发: ${abbreviateText(thought.userPrompt, 120, "(无提示词)")}`}</p>
              <div className="runtime-thought-body">{thought.text}</div>

              <div className="runtime-entry-footer">
                <p className="muted-copy">
                  对外说话：{thought.speechText ? abbreviateText(thought.speechText, 120) : "(沉默)"}
                </p>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="empty-state">
          <strong>还没有 inner monologue 记录</strong>
          <p>需要先让游戏端完成一次交互回合，并把 runtime 文件写进共享 workspace。</p>
        </div>
      )}
    </div>
  );
}

function RuntimeInteractionsPage({
  generatedAt,
  entries
}: {
  generatedAt: string;
  entries: RuntimeRecentInteractionsFile["entries"];
}) {
  const [expandedEntryId, setExpandedEntryId] = useState<string>("");

  useEffect(() => {
    if (!entries.length) {
      setExpandedEntryId("");
      return;
    }
    setExpandedEntryId((current) => current || `${entries[0]?.timestamp ?? 0}-0`);
  }, [entries]);

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Runtime Turns</p>
          <h2>触发记录列表</h2>
          <p className="muted-copy">
            封面沿用游戏内的极简摘要，只显示触发、说话和动作概览；展开后可以查看完整输出与 action 列表。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${entries.length} 个回合`}</span>
          <span className="badge muted">
            {generatedAt ? `最近同步 ${formatRuntimeTimestamp(Date.parse(generatedAt))}` : "尚未读到 runtime 文件"}
          </span>
        </div>
      </div>

      {entries.length ? (
        <div className="runtime-entry-list">
          {entries.map((entry, index) => {
            const entryId = `${entry.timestamp}-${index}`;
            const expanded = expandedEntryId === entryId;
            const uniqueActionTypes = Array.from(new Set(entry.actionTypes));
            const sortedOutputs = [...entry.outputs].sort((left, right) => left.timestamp - right.timestamp);

            return (
              <article className={expanded ? "runtime-entry-card expanded" : "runtime-entry-card"} key={entryId}>
                <button
                  className="runtime-entry-toggle"
                  onClick={() => setExpandedEntryId((current) => (current === entryId ? "" : entryId))}
                  type="button"
                >
                  <div className="runtime-entry-head">
                    <div>
                      <div className="custom-tag-meta">
                        <span className="badge">{formatRuntimeTimestamp(entry.timestamp)}</span>
                        {entry.triggerSource ? <span className="badge muted">{entry.triggerSource}</span> : null}
                        {entry.hasImage ? <span className="badge muted">+image</span> : null}
                      </div>
                      <h3>{buildInteractionTriggerLine(entry)}</h3>
                    </div>
                    <span className={expanded ? "badge good" : "badge muted"}>{expanded ? "收起" : "展开"}</span>
                  </div>
                  <div className="runtime-summary-lines">
                    <p>{buildInteractionSpeechLine(entry)}</p>
                    <p>{buildInteractionActionLine(entry)}</p>
                  </div>
                </button>

                {expanded ? (
                  <div className="runtime-entry-details">
                    <section className="info-block">
                      <h3>Action 列表</h3>
                      <div className="chip-row compact">
                        {uniqueActionTypes.length ? (
                          uniqueActionTypes.map((actionType) => (
                            <span className="chip" key={actionType}>
                              {shortActionType(actionType)}
                            </span>
                          ))
                        ) : (
                          <span className="chip">无</span>
                        )}
                      </div>
                    </section>

                    <section className="info-block">
                      <h3>输出明细</h3>
                      <div className="runtime-output-list">
                        {sortedOutputs.length ? (
                          sortedOutputs.map((output, outputIndex) => (
                            <article className="runtime-output-card" key={`${entryId}-${output.timestamp}-${outputIndex}`}>
                              <div className="runtime-output-head">
                                <div className="custom-tag-meta">
                                  <span className="badge warn">{formatOutputTypeLabel(output.type)}</span>
                                  <span className="badge muted">{formatShortRuntimeTimestamp(output.timestamp)}</span>
                                </div>
                              </div>
                              <div className="runtime-output-body">{output.text.trim() || "(空输出)"}</div>
                            </article>
                          ))
                        ) : (
                          <p className="muted-copy">这一回合没有记录到输出文本。</p>
                        )}
                      </div>
                    </section>
                  </div>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : (
        <div className="empty-state">
          <strong>还没有触发记录</strong>
          <p>需要先让游戏端写出 `studio_runtime_recent_interactions.json`。</p>
        </div>
      )}
    </div>
  );
}

function TagBrowser({
  tags,
  excludedTags = [],
  allowedKinds,
  title,
  description,
  searchPlaceholder,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  initialCategory = "all",
  variant = "default",
  onAddAllTags,
  onSelectTag
}: {
  tags: TagRecord[];
  excludedTags?: string[];
  allowedKinds?: DetailTagKind[];
  title: string;
  description: string;
  searchPlaceholder: string;
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  initialCategory?: TagBrowserCategoryId;
  variant?: "default" | "sidebar";
  onAddAllTags?: (tags: string[]) => void;
  onSelectTag: (tag: string) => void;
}) {
  const [query, setQuery] = useState("");
  const [activeCategory, setActiveCategory] = useState<TagBrowserCategoryId>(initialCategory);
  const [activeSourceMod, setActiveSourceMod] = useState(TAG_BROWSER_ALL_MODS);
  const [page, setPage] = useState(1);

  const baseRecords = useMemo(() => {
    const excluded = new Set(excludedTags);
    return tags.filter(
      (record) => !excluded.has(record.tag) && (!allowedKinds || allowedKinds.includes(record.kind))
    );
  }, [allowedKinds, excludedTags, tags]);

  const sourceModOptions = useMemo(() => {
    const modCounts = new Map<string, number>();
    let customCount = 0;

    for (const record of baseRecords) {
      if (isCustomTagRecord(record) && !record.sourceMods.length) {
        customCount += 1;
      }

      for (const sourceMod of record.sourceMods) {
        modCounts.set(sourceMod, (modCounts.get(sourceMod) ?? 0) + 1);
      }
    }

    const next = [
      {
        id: TAG_BROWSER_ALL_MODS,
        label: "全部模组",
        count: baseRecords.length
      }
    ];

    if (customCount > 0) {
      next.push({
        id: TAG_BROWSER_CUSTOM_SOURCE,
        label: "Custom",
        count: customCount
      });
    }

    const sortedMods = Array.from(modCounts.entries()).sort((left, right) => left[0].localeCompare(right[0], "en"));
    for (const [sourceMod, count] of sortedMods) {
      next.push({
        id: sourceMod,
        label: sourceMod,
        count
      });
    }

    return next;
  }, [baseRecords]);

  useEffect(() => {
    if (!sourceModOptions.some((entry) => entry.id === activeSourceMod)) {
      setActiveSourceMod(TAG_BROWSER_ALL_MODS);
    }
  }, [activeSourceMod, sourceModOptions]);

  const sourceFilteredRecords = useMemo(() => {
    if (activeSourceMod === TAG_BROWSER_ALL_MODS) {
      return baseRecords;
    }

    if (activeSourceMod === TAG_BROWSER_CUSTOM_SOURCE) {
      return baseRecords.filter((record) => isCustomTagRecord(record) && !record.sourceMods.length);
    }

    return baseRecords.filter((record) => record.sourceMods.includes(activeSourceMod));
  }, [activeSourceMod, baseRecords]);

  const categoryCounts = useMemo(() => {
    const counts = new Map<TagBrowserCategoryId, number>();
    for (const record of sourceFilteredRecords) {
      const categoryId = tagBrowserCategoryOf(record);
      counts.set(categoryId, (counts.get(categoryId) ?? 0) + 1);
    }
    return counts;
  }, [sourceFilteredRecords]);

  const categories = useMemo(() => {
    const next: Array<{ id: TagBrowserCategoryId; count: number }> = [
      { id: "all", count: sourceFilteredRecords.length }
    ];

    for (const categoryId of TAG_BROWSER_CATEGORY_ORDER) {
      const count = categoryCounts.get(categoryId) ?? 0;
      if (count > 0) {
        next.push({ id: categoryId, count });
      }
    }

    return next;
  }, [categoryCounts, sourceFilteredRecords.length]);

  useEffect(() => {
    if (!categories.some((entry) => entry.id === activeCategory)) {
      setActiveCategory(categories[0]?.id ?? "all");
    }
  }, [activeCategory, categories]);

  const filteredRecords = useMemo(() => {
    const searched = filterTagRecords(sourceFilteredRecords, query);
    if (activeCategory === "all") {
      return searched;
    }
    return searched.filter((record) => tagBrowserCategoryOf(record) === activeCategory);
  }, [activeCategory, query, sourceFilteredRecords]);

  const addAllRecordTags = filteredRecords.map((record) => record.tag);
  const pageSize = variant === "sidebar" ? TAG_BROWSER_SIDEBAR_PAGE_SIZE : TAG_BROWSER_RESULT_LIMIT;
  const pageCount = Math.max(1, Math.ceil(filteredRecords.length / pageSize));

  useEffect(() => {
    setPage(1);
  }, [query, activeCategory, activeSourceMod, variant]);

  useEffect(() => {
    if (page > pageCount) {
      setPage(pageCount);
    }
  }, [page, pageCount]);

  const pageStart = (page - 1) * pageSize;
  const visibleRecords = filteredRecords.slice(pageStart, pageStart + pageSize);

  return (
    <div className={variant === "sidebar" ? "tag-browser tag-browser-sidebar" : "tag-browser"}>
      <div className="section-head tag-browser-head">
        <div>
          <h3>{title}</h3>
          <p className="muted-copy">{description}</p>
        </div>
        <div className="tag-browser-head-actions">
          {onAddAllTags ? (
            <button
              className="action-button quiet"
              disabled={!addAllRecordTags.length}
              onClick={() => onAddAllTags(addAllRecordTags)}
              type="button"
            >
              全部加入
            </button>
          ) : null}
          <span className="badge">{`${filteredRecords.length} / ${sourceFilteredRecords.length}`}</span>
        </div>
      </div>

      <div className="tag-browser-toolbar">
        <label className="field grow">
          <span>Tag Search</span>
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={searchPlaceholder}
          />
        </label>
        <label className="field tag-browser-mod-filter">
          <span>模组过滤</span>
          <select
            value={activeSourceMod}
            onChange={(event) => setActiveSourceMod(event.target.value)}
          >
            {sourceModOptions.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {`${entry.label} (${entry.count})`}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="tag-browser-categories" role="tablist" aria-label={`${title} 分类`}>
        {categories.map((category) => (
          <button
            key={category.id}
            className={category.id === activeCategory ? "tag-browser-tab active" : "tag-browser-tab"}
            onClick={() => setActiveCategory(category.id)}
            type="button"
          >
            <span>{tagBrowserCategoryLabel(category.id)}</span>
            <strong>{category.count}</strong>
          </button>
        ))}
      </div>

      <p className="muted-copy">点击卡片即可添加，已选 tag 会自动从浏览器中隐藏。</p>

      {visibleRecords.length ? (
        <div className="tag-browser-grid">
          {visibleRecords.map((record) => {
            const primaryRegistryType = primaryRegistryTypeOf(record);
            return (
              <button
                className="tag-browser-card"
                key={record.tag}
                onClick={() => onSelectTag(record.tag)}
                type="button"
              >
                <TagCardIcon
                  record={record}
                  workspaceDirectoryHandle={workspaceDirectoryHandle}
                  workspaceImportedFiles={workspaceImportedFiles}
                />
                <div className="tag-browser-card-main">
                  <div className="custom-tag-meta">
                    <span className="badge">{record.kind}</span>
                    {primaryRegistryType ? <span className="badge muted">{primaryRegistryType}</span> : null}
                    {record.sourceMods[0] ? <span className="badge muted">{record.sourceMods[0]}</span> : null}
                  </div>
                  <strong>{record.label}</strong>
                  <code>{record.tag}</code>
                  <p className="tag-browser-description">
                    {record.description || "该 tag 暂无 description。"}
                  </p>
                </div>
              </button>
            );
          })}
        </div>
      ) : (
        <div className="empty-state">
          <strong>没有可显示的 tag</strong>
          <p>当前分类和检索条件下没有更多可添加的条目。</p>
        </div>
      )}

      {filteredRecords.length > pageSize ? (
        variant === "sidebar" ? (
          <div className="tag-browser-pagination">
            <span className="badge muted">{`第 ${page} / ${pageCount} 页`}</span>
            <div className="tag-browser-pagination-actions">
              <button
                className="action-button quiet"
                disabled={page <= 1}
                onClick={() => setPage((current) => Math.max(1, current - 1))}
                type="button"
              >
                上一页
              </button>
              <button
                className="action-button quiet"
                disabled={page >= pageCount}
                onClick={() => setPage((current) => Math.min(pageCount, current + 1))}
                type="button"
              >
                下一页
              </button>
            </div>
          </div>
        ) : (
          <p className="muted-copy">{`结果过多，当前仅显示前 ${TAG_BROWSER_RESULT_LIMIT} 条，请继续缩小检索范围。`}</p>
        )
      ) : null}
    </div>
  );
}

function TagCardIcon({
  record,
  workspaceDirectoryHandle,
  workspaceImportedFiles
}: {
  record: TagRecord;
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
}) {
  const iconAssetVersion = useContext(IconAssetVersionContext);
  const [iconUrl, setIconUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let nextObjectUrl: string | null = null;
    setIconUrl(null);

    async function loadIcon() {
      if (!record.iconRelativePath) {
        setIconUrl(null);
        return;
      }

      try {
        const importedFile = workspaceImportedFiles.get(record.iconRelativePath) ?? null;
        const file =
          importedFile ?? (workspaceDirectoryHandle
            ? await readFileFromDirectoryPath(workspaceDirectoryHandle, record.iconRelativePath)
            : null);

        if (!file || cancelled) {
          setIconUrl(null);
          return;
        }

        nextObjectUrl = URL.createObjectURL(file);
        setIconUrl(nextObjectUrl);
      } catch {
        if (!cancelled) {
          setIconUrl(null);
        }
      }
    }

    void loadIcon();

    return () => {
      cancelled = true;
      if (nextObjectUrl) {
        URL.revokeObjectURL(nextObjectUrl);
      }
    };
  }, [iconAssetVersion, record.iconRelativePath, workspaceDirectoryHandle, workspaceImportedFiles]);

  return (
    <div className="tag-card-icon">
      {iconUrl ? (
        <img alt={record.label} className="tag-card-image" src={iconUrl} />
      ) : (
        <div className="tag-card-fallback">{tagPlaceholderGlyph(record)}</div>
      )}
    </div>
  );
}

function SelectedTagGrid({
  tagIds,
  tags,
  workspaceDirectoryHandle,
  workspaceImportedFiles,
  onRemoveTag
}: {
  tagIds: string[];
  tags: TagRecord[];
  workspaceDirectoryHandle: FileSystemDirectoryHandle | null;
  workspaceImportedFiles: Map<string, File>;
  onRemoveTag: (tag: string) => void;
}) {
  const records = useMemo(
    () =>
      tagIds.map((tag) => {
        const existing = findTagRecord(tags, tag);
        if (existing) {
          return existing;
        }

        return {
          tag,
          kind: tag.split(":")[0] as DetailTagKind,
          label: tag.split(":").slice(1).join(":") || tag,
          description: "",
          aliases: [],
          relations: [],
          registryTypes: [],
          sourceMods: [],
          promptVisible: true
        } satisfies TagRecord;
      }),
    [tagIds, tags]
  );

  const [activeCategory, setActiveCategory] = useState<TagBrowserCategoryId>("all");

  const categoryCounts = useMemo(() => {
    const counts = new Map<TagBrowserCategoryId, number>();
    for (const record of records) {
      const categoryId = tagBrowserCategoryOf(record);
      counts.set(categoryId, (counts.get(categoryId) ?? 0) + 1);
    }
    return counts;
  }, [records]);

  const categories = useMemo(() => {
    const next: Array<{ id: TagBrowserCategoryId; count: number }> = [
      { id: "all", count: records.length }
    ];

    for (const categoryId of TAG_BROWSER_CATEGORY_ORDER) {
      const count = categoryCounts.get(categoryId) ?? 0;
      if (count > 0) {
        next.push({ id: categoryId, count });
      }
    }

    return next;
  }, [categoryCounts, records.length]);

  useEffect(() => {
    if (!categories.some((entry) => entry.id === activeCategory)) {
      setActiveCategory("all");
    }
  }, [activeCategory, categories]);

  const visibleRecords = useMemo(() => {
    if (activeCategory === "all") {
      return records;
    }

    return records.filter((record) => tagBrowserCategoryOf(record) === activeCategory);
  }, [activeCategory, records]);

  return (
    <div className="selected-tag-grid">
      {categories.length > 1 ? (
        <div className="tag-browser-categories" role="tablist" aria-label="已附带的 Detail Tags 分类">
          {categories.map((category) => (
            <button
              key={category.id}
              className={category.id === activeCategory ? "tag-browser-tab active" : "tag-browser-tab"}
              onClick={() => setActiveCategory(category.id)}
              type="button"
            >
              <span>{tagBrowserCategoryLabel(category.id)}</span>
              <strong>{category.count}</strong>
            </button>
          ))}
        </div>
      ) : null}

      <div className="tag-browser-grid">
        {visibleRecords.map((record) => {
          const primaryRegistryType = primaryRegistryTypeOf(record);
          return (
            <article className="tag-browser-card selected-tag-card" key={record.tag}>
              <TagCardIcon
                record={record}
                workspaceDirectoryHandle={workspaceDirectoryHandle}
                workspaceImportedFiles={workspaceImportedFiles}
              />
              <div className="tag-browser-card-main">
                <div className="selected-tag-head">
                  <div className="custom-tag-meta">
                    <span className="badge">{record.kind}</span>
                    {primaryRegistryType ? <span className="badge muted">{primaryRegistryType}</span> : null}
                    {record.sourceMods[0] ? <span className="badge muted">{record.sourceMods[0]}</span> : null}
                  </div>
                  <button
                    className="action-button danger selected-tag-remove"
                    onClick={() => onRemoveTag(record.tag)}
                    type="button"
                  >
                    移除
                  </button>
                </div>
                <strong>{record.label}</strong>
                <code>{record.tag}</code>
                <p className="tag-browser-description">
                  {record.description || "该 tag 暂无 description。"}
                </p>
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
}

function ChipList({
  items,
  emptyLabel,
  compact = false
}: {
  items: string[];
  emptyLabel: string;
  compact?: boolean;
}) {
  if (!items.length) {
    return <p className="muted-copy">{emptyLabel}</p>;
  }

  return (
    <div className={compact ? "chip-row compact" : "chip-row"}>
      {items.map((item) => (
        <span className="chip" key={item}>
          {item}
        </span>
      ))}
    </div>
  );
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export default App;
