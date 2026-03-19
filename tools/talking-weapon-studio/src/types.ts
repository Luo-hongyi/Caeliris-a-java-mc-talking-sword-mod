export type FocusTag =
  | "COMBAT"
  | "MOVEMENT"
  | "LABOR"
  | "CREATION"
  | "CREATURE"
  | "SOCIAL"
  | "LANDSCAPE"
  | "HAZARD"
  | "STATUS"
  | "CONSUMABLE"
  | "BLOCK"
  | "TOOL"
  | "COMMON";

export type AgentStateWeightKey =
  | "combat"
  | "movement"
  | "labor"
  | "creation"
  | "creature"
  | "social"
  | "landscape"
  | "hazard"
  | "status"
  | "consumable"
  | "block"
  | "tool";

export type DetailTagKind =
  | "native_specific"
  | "native_generic"
  | "custom_specific"
  | "custom_generic";

export interface CustomTagEntry {
  tag: string;
  kind: Extract<DetailTagKind, "custom_specific" | "custom_generic">;
  label: string;
  aliases?: string[];
  notes?: string;
  group?: string;
  hiddenFromPrompt?: boolean;
}

export interface DescriptionEntry {
  description: string;
  aliases?: string[];
  searchText?: string;
  source?: "default" | "user" | "generated";
}

export interface PersonaEntry {
  id: string;
  enabled?: boolean;
  focus_tags: FocusTag[];
  associated_focus?: FocusTag;
  description: string;
  always_recalled?: boolean;
  detail_tags?: string[];
  notes?: string;
}

export interface ImplantedMemoryEntry {
  id?: string;
  label?: string;
  enabled?: boolean;
  description: string;
  focus_tags?: FocusTag[];
  semantic_time_tag?: string;
  detail_tags?: string[];
  importance?: number;
  occurred_game_time?: number;
  time_label?: string;
  notes?: string;
}

export interface AgentStateCustomRecall {
  seed_entry_count: number;
  tags_per_entry: number;
  memory_pick_per_seed: number;
  max_depth: number;
  branch_per_memory: number;
  final_memory_cap: number;
}

export interface AgentStateFile {
  schemaVersion: number;
  state: {
    primary_emotion: string;
    emotion_intensity: number;
    familiarity: number;
    player_address: string;
    concentration: number;
    wandering_mind: number;
    nostalgia: number;
    association_mode: string;
    custom_recall: AgentStateCustomRecall;
    implanted_memory_safety_net: boolean;
    current_desire_tag?: FocusTag | null;
    desire_description: string;
  };
  persona_state: {
    favorability: number;
    player_profile: string;
  };
  attention_weights: Record<AgentStateWeightKey, number>;
}

export interface LocationRegistryEntry {
  x: number;
  y: number;
  z: number;
  dim: string;
  note?: string;
  horizontalRadius?: number;
  verticalRadius?: number;
  focusTag?: FocusTag | "";
  detailTags?: string[];
}

export interface NativeCatalogEntry {
  tag: string;
  kind: Extract<DetailTagKind, "native_specific" | "native_generic">;
  registryType: string;
  namespace: string;
  path: string;
  sourceMod: string;
  displayName?: string;
  discoveredFrom: string;
  promptVisible?: boolean;
  metadata?: Record<string, string | number | boolean | null>;
}

export interface RuntimeTagViewEntry {
  tag: string;
  kind: DetailTagKind;
  label?: string;
  description?: string;
  relations?: string[];
  registryTypes?: string[];
  sourceMods?: string[];
  promptVisible?: boolean;
}

export interface TagRecord {
  tag: string;
  kind: DetailTagKind;
  label: string;
  description: string;
  aliases: string[];
  relations: string[];
  registryTypes: string[];
  sourceMods: string[];
  promptVisible: boolean;
  iconRelativePath?: string;
  group?: string;
}

export interface RuntimeInteractionOutputEntry {
  type: string;
  text: string;
  timestamp: number;
}

export interface RuntimeInteractionEntry {
  timestamp: number;
  triggerSource: string;
  userPrompt: string;
  hasImage: boolean;
  actionTypes: string[];
  outputs: RuntimeInteractionOutputEntry[];
}

export interface RuntimeRecentInteractionsFile {
  schemaVersion: number;
  generatedAt: string;
  entries: RuntimeInteractionEntry[];
}

export interface RuntimeRecallVisibleContextEntry {
  description: string;
  focusTags: string[];
  detailTags: string[];
  baseImportance: number;
  finalScore: number;
}

export interface RuntimeRecalledMemoryEntry {
  id: string;
  description: string;
  focusTags: string[];
  detailTags: string[];
  semanticTimeTag: string;
  semanticTimeLabel: string;
}

export interface RuntimeRecallTraceFile {
  schemaVersion: number;
  generatedAt: string;
  triggerSource: string;
  userPrompt: string;
  associationMode: string;
  currentDesireTag: string;
  triggerTags: string[];
  traceLines: string[];
  visibleContexts: RuntimeRecallVisibleContextEntry[];
  recalledMemories: RuntimeRecalledMemoryEntry[];
}
