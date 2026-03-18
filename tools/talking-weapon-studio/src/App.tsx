import { createContext, useContext, useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import { buildTagRecords, studioSeedData } from "./data";
import {
  describeWorkspaceAccessMode,
  describeWorkspaceDirectoryHandle,
  ensureDirectoryPermission,
  getDesktopBridgeSource,
  isBrowserDirectoryPickerAvailable,
  isDesktopBridgeReady,
  pickDirectoryHandle,
  readFileFromDirectoryPath,
  readJsonFileFromDirectory,
  supportsDirectoryAccessApi,
  writeJsonFileToDirectory
} from "./workspaceBridge";
import { pingGameBridge, publishWorkspaceToGame } from "./gameBridge";
import {
  CustomTagEntry,
  DescriptionEntry,
  DetailTagKind,
  FocusTag,
  ImplantedMemoryEntry,
  NativeCatalogEntry,
  PersonaEntry,
  TagRecord
} from "./types";

type PageId = "catalog" | "relations" | "persona" | "memory";
type CustomTagKind = Extract<DetailTagKind, "custom_specific" | "custom_generic">;
type RelationBatchOperation = "add" | "remove";
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

type PersonaDraft = {
  associated_focus: FocusTag;
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
  occurred_game_time: string;
  notes: string;
};

type MemoryMode =
  | { mode: "list" }
  | { mode: "create"; draft: MemoryDraft }
  | { mode: "edit"; originalIndex: number; draft: MemoryDraft };

type RelationDraft = {
  sourceTag: string;
  linkedTags: string[];
};

type RelationMode =
  | { mode: "list" }
  | { mode: "create"; draft: RelationDraft }
  | { mode: "edit"; originalSourceTag: string; draft: RelationDraft };

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
  customTags: number;
  descriptions: number;
  relations: number;
  personas: number;
  implantedMemories: number;
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
  customTags?: CustomTagsFile | null;
  descriptions?: TagDescriptionsFile | null;
  relations?: TagRelationsFile | null;
  personas?: PersonasFile | null;
  memories?: ImplantedMemoriesFile | null;
};

type WorkspaceManifestWriter = "minecraft" | "studio" | "unknown";

type WorkspaceManifestVersions = {
  snapshot: string;
  runtimeTagView: string;
  customTags: string;
  descriptions: string;
  relations: string;
  personas: string;
  memories: string;
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
  "MOVEMENT",
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

const pages: Array<{ id: PageId; label: string; blurb: string }> = [
  { id: "catalog", label: "Custom Tags", blurb: "管理自定义 tag 的 type、id 与 description。" },
  { id: "relations", label: "Relations", blurb: "管理标签联动引用图、环检测与正则批量操作。" },
  { id: "persona", label: "Persona", blurb: "按 focus 管理人格偏好描述与其 detail tags。" },
  { id: "memory", label: "Memory", blurb: "编辑植入记忆、独立时间语义与标签。" }
];

const EMPTY_CUSTOM_TAG_DRAFT: CustomTagDraft = {
  kind: "custom_specific",
  id: "",
  description: ""
};

const EMPTY_PERSONA_DRAFT: PersonaDraft = {
  associated_focus: "COMMON",
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
  occurred_game_time: "",
  notes: ""
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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
        typeof entry.associated_focus === "string" &&
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
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
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
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
    personas: value.personas
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
    customTags: "missing",
    descriptions: "missing",
    relations: "missing",
    personas: "missing",
    memories: "missing",
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
      customTags: typeof rawVersions.customTags === "string" ? rawVersions.customTags : "missing",
      descriptions: typeof rawVersions.descriptions === "string" ? rawVersions.descriptions : "missing",
      relations: typeof rawVersions.relations === "string" ? rawVersions.relations : "missing",
      personas: typeof rawVersions.personas === "string" ? rawVersions.personas : "missing",
      memories: typeof rawVersions.memories === "string" ? rawVersions.memories : "missing",
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
  }
];

const WORKSPACE_IMPORT_DESCRIPTOR_BY_FILENAME = new Map(
  WORKSPACE_IMPORT_DESCRIPTORS.map((descriptor) => [descriptor.filename, descriptor])
);

function hasManifestVersionChanged(
  previousManifest: StudioWorkspaceManifest | null,
  nextManifest: StudioWorkspaceManifest
): boolean {
  if (!previousManifest) {
    return true;
  }
  const keys = Object.keys(nextManifest.versions) as Array<keyof WorkspaceManifestVersions>;
  return keys.some((key) => previousManifest.versions[key] !== nextManifest.versions[key]);
}

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

    if (relativePath.startsWith("native_catalog_icons/") && relativePath.toLowerCase().endsWith(".png")) {
      workspaceFiles.set(relativePath, file);
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

function createStudioId(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}

function buildMemoryDraft(memory?: ImplantedMemoryEntry): MemoryDraft {
  if (!memory) {
    return { ...EMPTY_MEMORY_DRAFT };
  }

  return {
    label: memory.label ?? "",
    description: memory.description,
    focus_tags: [...(memory.focus_tags ?? [])],
    semantic_time_tag: memory.semantic_time_tag ?? "",
    detail_tags: [...(memory.detail_tags ?? [])],
    importance: memory.importance !== undefined ? String(memory.importance) : "",
    occurred_game_time: memory.occurred_game_time !== undefined ? String(memory.occurred_game_time) : "",
    notes: memory.notes ?? ""
  };
}

function buildMemoryEntryFromDraft(
  draft: MemoryDraft,
  baseMemory?: ImplantedMemoryEntry,
  derivedTimeLabel?: string
): ImplantedMemoryEntry {
  const nextMemory: ImplantedMemoryEntry = {
    ...(baseMemory ?? {}),
    label: draft.label.trim() || undefined,
    description: draft.description.trim(),
    focus_tags: draft.focus_tags.length ? draft.focus_tags : undefined,
    semantic_time_tag: draft.semantic_time_tag.trim() || undefined,
    detail_tags: draft.detail_tags.length ? draft.detail_tags : undefined,
    importance: draft.importance.trim() ? Number.parseFloat(draft.importance) : undefined,
    occurred_game_time: draft.occurred_game_time.trim()
      ? Number.parseInt(draft.occurred_game_time, 10)
      : undefined,
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

function formatFocusTagLabel(focusTag: FocusTag): string {
  return `${FOCUS_TAG_LABELS[focusTag]} (${focusTag})`;
}

function formatFocusTagChip(focusTag: FocusTag): string {
  return formatFocusTagLabel(focusTag);
}

function formatFocusTagItems(focusTags: FocusTag[] | undefined): string[] {
  return (focusTags ?? []).map((focusTag) => formatFocusTagChip(focusTag));
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
  const [relationQuery, setRelationQuery] = useState("");
  const [personaQuery, setPersonaQuery] = useState("");
  const [personaFocusFilter, setPersonaFocusFilter] = useState<"all" | FocusTag>("all");
  const [memoryQuery, setMemoryQuery] = useState("");
  const [nativeCatalogSnapshot, setNativeCatalogSnapshot] = useState<NativeCatalogSnapshotFile>(
    createSeedNativeCatalogSnapshot()
  );
  const [customTags, setCustomTags] = useState<CustomTagEntry[]>(studioSeedData.customTags.tags);
  const [descriptions, setDescriptions] = useState<Record<string, DescriptionEntry>>(
    studioSeedData.descriptions.descriptions
  );
  const [relations, setRelations] = useState<Record<string, string[]>>(
    normalizeRelationMap(studioSeedData.relations.relations)
  );
  const [personas, setPersonas] = useState<PersonaEntry[]>(studioSeedData.personas.personas);
  const [memories, setMemories] = useState<ImplantedMemoryEntry[]>(studioSeedData.implantedMemories.memories);
  const [schemaVersions, setSchemaVersions] = useState<SchemaVersions>(studioSeedData.schemaVersions);
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
  const [relationFeedback, setRelationFeedback] = useState<ImportFeedback>({
    tone: "neutral",
    message: "联动标签默认仅用于程序侧扩展引用，不会直接写入 LLM Prompt；图必须保持无环。"
  });
  const [catalogMode, setCatalogMode] = useState<CatalogMode>({ mode: "list" });
  const [relationMode, setRelationMode] = useState<RelationMode>({ mode: "list" });
  const [personaMode, setPersonaMode] = useState<PersonaMode>({ mode: "list" });
  const [memoryMode, setMemoryMode] = useState<MemoryMode>({ mode: "list" });

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

  const filteredPersonas = useMemo(() => {
    const normalizedQuery = personaQuery.trim().toLowerCase();
    const base = [...personas]
      .filter((persona) => personaFocusFilter === "all" || persona.associated_focus === personaFocusFilter)
      .sort((left, right) => {
      const rank = focusTagRank(left.associated_focus) - focusTagRank(right.associated_focus);
      if (rank !== 0) {
        return rank;
      }

      return left.description.localeCompare(right.description, "zh-Hans");
      });

    if (!normalizedQuery) {
      return base;
    }

    return base.filter((persona) =>
      [
        persona.associated_focus,
        FOCUS_TAG_LABELS[persona.associated_focus],
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

    setCatalogMode((currentMode) =>
      currentMode.mode === "edit" && currentMode.originalTag === tag ? { mode: "list" } : currentMode
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
        associated_focus: entry.associated_focus,
        description: entry.description,
        always_recalled: entry.always_recalled ?? false,
        detail_tags: [...(entry.detail_tags ?? [])]
      }
    });
  };

  const closePersonaEditor = () => {
    setPersonaMode({ mode: "list" });
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

    const nextEntry: PersonaEntry = {
      id: personaMode.mode === "edit" ? personaMode.originalId : createStudioId("persona"),
      enabled: true,
      associated_focus: personaMode.draft.associated_focus,
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
      return [...filtered, nextEntry].sort((left, right) => {
        const rank = focusTagRank(left.associated_focus) - focusTagRank(right.associated_focus);
        if (rank !== 0) {
          return rank;
        }

        return left.description.localeCompare(right.description, "zh-Hans");
      });
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

  const editableWorkspaceFiles: Array<{
    filename: string;
    versionKey: Extract<keyof WorkspaceManifestVersions, "customTags" | "descriptions" | "relations" | "personas" | "memories">;
    build: () => CustomTagsFile | TagDescriptionsFile | TagRelationsFile | PersonasFile | ImplantedMemoriesFile;
  }> = [
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
    }
  ];

  const resetEditorsAfterImport = () => {
    setCatalogMode({ mode: "list" });
    setRelationMode({ mode: "list" });
    setPersonaMode({ mode: "list" });
    setMemoryMode({ mode: "list" });
  };

  const getPendingEditorLabel = (): string | null => {
    if (catalogMode.mode !== "list") {
      return "Custom Tag";
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
    const { importedData, importedParts } = await loadWorkspaceDataFromDirectory(directoryHandle, manifest);

    setWorkspaceImportedFiles(new Map());
    setWorkspaceManifest(manifest);
    setIconAssetVersion(manifest?.versions.iconsDirectory ?? "missing");
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
    if (!workspaceDirectoryHandle) {
      return;
    }

    let cancelled = false;
    let polling = false;

    const pollWorkspaceManifest = async () => {
      if (polling) {
        return;
      }
      polling = true;
      try {
        const manifestRaw = await readJsonFileFromDirectory(workspaceDirectoryHandle, "studio_workspace.manifest.json");
        if (cancelled || manifestRaw === null) {
          return;
        }

        const nextManifest = normalizeWorkspaceManifest(manifestRaw);
        if (!nextManifest) {
          return;
        }

        if (hasManifestVersionChanged(workspaceManifest, nextManifest)) {
          await refreshWorkspaceFromManifest(workspaceDirectoryHandle, workspaceManifest, nextManifest);
          if (!cancelled) {
            setWorkspaceManifest(nextManifest);
          }
        }
      } catch (error) {
        if (!cancelled) {
          const message = error instanceof Error ? error.message : String(error);
          setImportFeedback({
            tone: "bad",
            message: `工作区自动同步失败：${message}`
          });
        }
      } finally {
        polling = false;
      }
    };

    const intervalId = window.setInterval(() => {
      void pollWorkspaceManifest();
    }, 1500);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [workspaceDirectoryHandle, workspaceManifest]);

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
          message: "未连接到游戏内 HTTP bridge，当前使用工作区 manifest 轮询。"
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

  const applyPartialWorkspaceRefresh = (importedData: ImportedWorkspaceData) => {
    applyWorkspaceDataPatch(importedData);
  };

  const refreshWorkspaceFromManifest = async (
    directoryHandle: FileSystemDirectoryHandle,
    previousManifest: StudioWorkspaceManifest | null,
    nextManifest: StudioWorkspaceManifest
  ) => {
    const { importedData, importedParts: changedParts } = await loadWorkspaceDataFromDirectory(
      directoryHandle,
      nextManifest,
      previousManifest
    );

    if (!previousManifest || previousManifest.versions.iconsDirectory !== nextManifest.versions.iconsDirectory) {
      setIconAssetVersion(nextManifest.versions.iconsDirectory);
    }

    if (changedParts.length) {
      applyPartialWorkspaceRefresh(importedData);
      setImportFeedback({
        tone: "good",
        message: `已同步工作区变化：${changedParts.join(" / ")}。`
      });
    }
  };

  const exportWorkspace = async () => {
    try {
      const pendingEditorLabel = getPendingEditorLabel();
      if (pendingEditorLabel) {
        setImportFeedback({
          tone: "bad",
          message: `当前有未保存的 ${pendingEditorLabel} 草稿。请先点击当前编辑页的“保存”，再导出工作区。`
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
      const exportedMessage = `已导出工作区到 ${workspaceDirectoryName || directoryHandle.name}。`;

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
          message: "未连接到游戏内 HTTP bridge，当前使用工作区 manifest 轮询。"
        });
        setImportFeedback({
          tone: "good",
          message: `${exportedMessage} 未能直接通知游戏，已保留 manifest 兜底同步：${message}`
        });
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setImportFeedback({
        tone: "bad",
        message: `导出工作区失败：${message}`
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
                <h2>工作区导入 / 导出</h2>
                <p className="muted-copy">
                  目标路径：<code>{workspacePathHint}</code>
                </p>
              </div>
            </div>
            <div className="workspace-meta">
              <span className="badge muted">
                {workspaceDirectoryName ? `已选择目录：${workspaceDirectoryName}` : "尚未选择工作区目录"}
              </span>
              <span className="badge">由模组导出的 `studio_workspace` 文件夹</span>
              {workspaceDirectoryHandle ? <span className="badge good">自动同步中</span> : null}
              <span className={gameBridgeStatus.state === "connected" ? "badge good" : "badge muted"}>
                {gameBridgeStatus.state === "connected" ? "HTTP Bridge 已连接" : "Manifest Fallback"}
              </span>
            </div>
            <p className="muted-copy">
              导入会读取该目录下已有的 schema 文件；导出只会回写 `custom_tags.json`、
              `tag_descriptions.json`、`tag_relations.json`、`personas.json` 与
              `implanted_memories.json`，不会覆盖模组生成的 snapshot 与图标文件。若游戏内 HTTP bridge 在线，
              导出后会立即请求热重载；否则退回 manifest 轮询同步。
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
                导出工作区
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
    return (
      <div className="panel page-panel catalog-editor">
        <div className="panel-head">
          <div>
            <p className="eyebrow">{mode.mode === "create" ? "Create Custom Tag" : "Edit Custom Tag"}</p>
            <h2>{mode.mode === "create" ? "新增 custom tag" : "编辑 custom tag"}</h2>
            <p className="muted-copy">这里仅编辑 `type`、`id` 与 `description`，其他字段暂不暴露。</p>
          </div>
          <button className="action-button quiet" onClick={onCancel} type="button">
            返回列表
          </button>
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
        onAddDetailTag={onAddDetailTag}
        onRemoveDetailTag={onRemoveDetailTag}
        onCancel={onCancel}
        onSave={onSave}
      />
    );
  }

  const grouped = FOCUS_TAGS.map((focusTag) => ({
    focusTag,
    entries: personas.filter((entry) => entry.associated_focus === focusTag)
  })).filter((group) => group.entries.length > 0);

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Persona Entries</p>
          <h2>人格偏好管理</h2>
          <p className="muted-copy">以 focus tag 分组，每条人格偏好由自然语言描述和 detail tags 组成。</p>
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
                      <span className="badge">{formatFocusTagLabel(entry.associated_focus)}</span>
                      {entry.always_recalled ? <span className="badge warn">Always Remembered</span> : null}
                    </div>
                    <p className="persona-description">{entry.description}</p>
                    {entry.always_recalled ? (
                      <p className="always-recalled-note">
                        {entry.associated_focus === "COMMON"
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
  onAddDetailTag: (tag: string) => void;
  onRemoveDetailTag: (tag: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  const isCommonFocus = mode.draft.associated_focus === "COMMON";
  const hiddenDetailTagCount = mode.draft.detail_tags.length;

  return (
    <div className="panel page-panel catalog-editor">
      <div className="panel-head">
        <div>
          <p className="eyebrow">{mode.mode === "create" ? "Create Persona Entry" : "Edit Persona Entry"}</p>
          <h2>{mode.mode === "create" ? "新增 persona" : "编辑 persona"}</h2>
          <p className="muted-copy">每条 persona 由一个 focus、一个 description，以及若干 detail tags 组成。</p>
        </div>
        <button className="action-button quiet" onClick={onCancel} type="button">
          返回列表
        </button>
      </div>

      <div className="form-grid">
        <label className="field">
          <span>Focus Tag</span>
          <select
            value={mode.draft.associated_focus}
            onChange={(event) => onDraftChange({ associated_focus: event.target.value as FocusTag })}
          >
            {FOCUS_TAGS.map((focusTag) => (
              <option key={focusTag} value={focusTag}>
                {formatFocusTagLabel(focusTag)}
              </option>
            ))}
          </select>
        </label>
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
              : "这条 persona 将在当前注意力领域下的所有情况下触发。"}
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
        <button className="action-button quiet" onClick={onCancel} type="button">
          返回列表
        </button>
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
          <span>Occurred Game Time</span>
          <input
            type="number"
            value={mode.draft.occurred_game_time}
            onChange={(event) => onDraftChange({ occurred_game_time: event.target.value })}
            placeholder="可选；一般不建议手填，优先使用上方语义时间锚"
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
        <h3>时间 Custom Tag</h3>
        <p className="muted-copy">植入记忆的时间语义会参与记忆之间的联想边，但会保持为独立字段，不并入普通 detail tag 列表。</p>
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
