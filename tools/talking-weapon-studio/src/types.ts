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
  associated_focus: FocusTag;
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
