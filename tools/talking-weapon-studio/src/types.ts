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
  | "tool"
  | "common";

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
  occurred_day_index?: number;
  occurred_time_of_day_tick?: number;
  occurred_game_time?: number;
  time_label?: string;
  notes?: string;
}

export interface AgentStateRecallTuning {
  main_candidates_per_cue: number;
  sandbox_cues_per_memory: number;
  conscious_memory_cap: number;
  retention_memory_cap: number;
}

export interface PromptRegistryFile {
  [key: string]: string;
}

export interface ImportanceRegistryFile {
  [key: string]: number;
}

export interface MemoryRecallTuningFile {
  schemaVersion: number;
  generatedAt: string;
  mainCandidatesPerCue: number;
  sandboxCuesPerMemory: number;
  consciousMemoryCap: number;
  retentionMemoryCap: number;
  recentRecallWindow: number;
  recentRecallWindowCap: number;
  implantedFallbackEntryScore: number;
  promptFillProbabilityScale: number;
  tagPoolHalfLifeDays: number;
  tagPoolDecayShape: number;
  tagPoolDropThreshold: number;
  tagPoolReheatPulseGain: number;
  tagPoolReheatDecayPerHit: number;
  tagPoolReheatFloor: number;
  fermentationHalfLifeDays: number;
  fermentationDecayShape: number;
  fermentationDropThreshold: number;
  intervalBonusScale: number;
  intervalBonusSteepness: number;
  synthesizedMemoryHalfLife: number;
  desireProfileBonusScale: number;
  desiredFocusBonusScale: number;
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
    recall_preset: string;
    recall_tuning: AgentStateRecallTuning;
    implanted_memory_safety_net: boolean;
    current_desire_tag?: FocusTag | null;
    desire_description: string;
  };
  persona_state: {
    favorability: number;
    player_profile: string;
  };
  attention_weights: Record<AgentStateWeightKey, number> & {
    crosshair_bonus_enabled: boolean;
  };
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

export interface RuntimeRecallTagPoolEntry {
  tag: string;
  score: number;
  sourceCount: number;
  focusTags: string[];
  lastInjectedGameTime: number;
  lastMatchedGameTime: number;
  reheatGain: number;
  newlyInjectedThisTurn: boolean;
  rawInjectedScoreThisTurn: number;
  intervalBonusThisTurn: number;
  reheatGainAppliedThisTurn: number;
  injectedScoreThisTurn: number;
}

export interface RuntimeRecallFermentationEntry {
  memoryId: string;
  description: string;
  score: number;
  sourceType: string;
  focusTags: string[];
  supportTags: string[];
  associativeTags: string[];
  lastUpdatedGameTime: number;
  lastConsciousGameTime: number;
  enteredConsciousThisTurn: boolean;
  cueBoostThisTurn: number;
}

export interface RuntimeRecallConsciousEntry {
  memoryId: string;
  description: string;
  score: number;
  sourceType: string;
  focusTags: string[];
  supportTags: string[];
  associativeTags: string[];
}

export interface RuntimeRecallAssociationSandbox {
  active: boolean;
  cueTags: string[];
  sourceMemoryIds: string[];
  memoryIds: string[];
  hopLimit: number;
  recallBudget: number;
}

export interface RuntimeRecallRuntimeState {
  captureMode: string;
  turnCounter: number;
  currentGameTime: number;
  previousGameTime: number;
  deltaGameTime: number;
  wallClockTime: number;
  visibleContextCount: number;
  recalledMemoryCount: number;
  tagPool: RuntimeRecallTagPoolEntry[];
  fermentationBuffer: RuntimeRecallFermentationEntry[];
  consciousMemories: RuntimeRecallConsciousEntry[];
  associationSandbox: RuntimeRecallAssociationSandbox;
}

export interface RuntimeRecallTimings {
  totalMillis: number;
  focusProfileMillis: number;
  cueBuildMillis: number;
  candidateCollectionMillis: number;
  promotionSelectionMillis: number;
  retainedSelectionMillis: number;
  consciousSelectionMillis: number;
  implantedFallbackMillis: number;
  promptFillMillis: number;
  sandboxMillis: number;
  stateCommitMillis: number;
  resultAssemblyMillis: number;
}

export interface RuntimeRecallTraceFile {
  schemaVersion: number;
  generatedAt: string;
  triggerSource: string;
  userPrompt: string;
  recallPreset: string;
  currentDesireTag: string;
  triggerTags: string[];
  traceLines: string[];
  visibleContexts: RuntimeRecallVisibleContextEntry[];
  consciousMemories: RuntimeRecalledMemoryEntry[];
  promptMemories: RuntimeRecalledMemoryEntry[];
  recalledMemories: RuntimeRecalledMemoryEntry[];
  runtimeState: RuntimeRecallRuntimeState;
  timings: RuntimeRecallTimings;
}
