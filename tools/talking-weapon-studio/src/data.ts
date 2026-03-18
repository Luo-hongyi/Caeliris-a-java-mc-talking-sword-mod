import agentStateExample from "../examples/agent_state.example.json";
import customTagsExample from "../examples/custom_tags.example.json";
import implantedMemoriesExample from "../examples/implanted_memories.example.json";
import locationRegistryExample from "../examples/location_registry.example.json";
import personasExample from "../examples/personas.example.json";
import tagDescriptionsExample from "../examples/tag_descriptions.example.json";
import tagRelationsExample from "../examples/tag_relations.example.json";
import {
  AgentStateFile,
  CustomTagEntry,
  DescriptionEntry,
  FocusTag,
  ImplantedMemoryEntry,
  LocationRegistryEntry,
  NativeCatalogEntry,
  PersonaEntry,
  TagRecord
} from "./types";

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

function normalizeSeedLocationRegistry(
  value: unknown
): Record<string, LocationRegistryEntry> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return {};
  }

  const normalized: Record<string, LocationRegistryEntry> = {};
  for (const [name, rawEntry] of Object.entries(value)) {
    if (name.startsWith("_comment")) {
      continue;
    }
    if (typeof rawEntry !== "object" || rawEntry === null || Array.isArray(rawEntry)) {
      continue;
    }

    const entry = rawEntry as Record<string, unknown>;
    const detailTagsSource = entry.detailTags;
    const detailTags =
      Array.isArray(detailTagsSource)
        ? detailTagsSource.filter((tag): tag is string => typeof tag === "string" && tag.trim().length > 0)
        : typeof detailTagsSource === "string"
          ? detailTagsSource.split(",").map((tag) => tag.trim()).filter(Boolean)
          : [];

    normalized[name.toLowerCase()] = {
      x: typeof entry.x === "number" ? entry.x : 0,
      y: typeof entry.y === "number" ? entry.y : 64,
      z: typeof entry.z === "number" ? entry.z : 0,
      dim: typeof entry.dim === "string" && entry.dim.trim() ? entry.dim : "minecraft:overworld",
      note: typeof entry.note === "string" ? entry.note : "",
      focusTag:
        typeof entry.focusTag === "string" &&
        ["COMBAT", "MOVEMENT", "LABOR", "CREATION", "CREATURE", "SOCIAL", "LANDSCAPE", "HAZARD", "STATUS", "CONSUMABLE", "BLOCK", "TOOL", "COMMON"].includes(entry.focusTag)
          ? (entry.focusTag as FocusTag)
          : "",
      detailTags
    };
  }
  return normalized;
}

const customTags = customTagsExample as CustomTagsFile;
const descriptions = tagDescriptionsExample as TagDescriptionsFile;
const relations = tagRelationsExample as TagRelationsFile;
const personas = personasExample as PersonasFile;
const implantedMemories = implantedMemoriesExample as ImplantedMemoriesFile;
const agentState = agentStateExample as AgentStateWorkspaceFile;
const locations = normalizeSeedLocationRegistry(locationRegistryExample);

const syntheticNativeCatalog: NativeCatalogEntry[] = [
  {
    tag: "native_specific:minecraft:mineshaft",
    kind: "native_specific",
    registryType: "structure",
    namespace: "minecraft",
    path: "mineshaft",
    sourceMod: "minecraft",
    displayName: "废弃矿井",
    discoveredFrom: "registry",
    promptVisible: true
  },
  {
    tag: "native_specific:minecraft:lava",
    kind: "native_specific",
    registryType: "fluid",
    namespace: "minecraft",
    path: "lava",
    sourceMod: "minecraft",
    displayName: "岩浆",
    discoveredFrom: "registry",
    promptVisible: true
  },
  {
    tag: "native_generic:minecraft:is_overworld",
    kind: "native_generic",
    registryType: "dimension",
    namespace: "minecraft",
    path: "is_overworld",
    sourceMod: "minecraft",
    displayName: "主世界语义",
    discoveredFrom: "manual_seed",
    promptVisible: false
  }
];

export function buildTagRecords(
  nativeEntries: NativeCatalogEntry[],
  customEntries: CustomTagEntry[],
  descriptionMap: Record<string, DescriptionEntry>,
  relationMap: Record<string, string[]>
): TagRecord[] {
  const map = new Map<string, TagRecord>();

  const ensure = (tag: string, kind: TagRecord["kind"]): TagRecord => {
    let record = map.get(tag);
    if (!record) {
      record = {
        tag,
        kind,
        label: tag.split(":").slice(1).join(":"),
        description: "",
        aliases: [],
        relations: [],
        registryTypes: [],
        sourceMods: [],
        promptVisible: true
      };
      map.set(tag, record);
    }
    return record;
  };

  for (const entry of nativeEntries) {
    const record = ensure(entry.tag, entry.kind);
    record.label = entry.displayName ?? entry.path;
    record.registryTypes = Array.from(new Set([...record.registryTypes, entry.registryType]));
    record.sourceMods = Array.from(new Set([...record.sourceMods, entry.sourceMod]));
    record.promptVisible = entry.promptVisible ?? record.promptVisible;
    if (typeof entry.metadata?.iconRelativePath === "string") {
      record.iconRelativePath = entry.metadata.iconRelativePath;
    }
  }

  for (const entry of customEntries) {
    const record = ensure(entry.tag, entry.kind);
    record.label = entry.label;
    record.aliases = entry.aliases ?? [];
    record.group = entry.group;
    record.promptVisible = !(entry.hiddenFromPrompt ?? false);
  }

  for (const [tag, entry] of Object.entries(descriptionMap)) {
    const kind = tag.split(":")[0] as TagRecord["kind"];
    const record = ensure(tag, kind);
    record.description = entry.description;
    record.aliases = Array.from(new Set([...(record.aliases ?? []), ...(entry.aliases ?? [])]));
  }

  for (const [tag, linked] of Object.entries(relationMap)) {
    const kind = tag.split(":")[0] as TagRecord["kind"];
    const record = ensure(tag, kind);
    record.relations = linked;
    for (const related of linked) {
      const relatedKind = related.split(":")[0] as TagRecord["kind"];
      ensure(related, relatedKind);
    }
  }

  return Array.from(map.values()).sort((a, b) => a.label.localeCompare(b.label, "zh-Hans"));
}

export const studioSeedData = {
  customTags,
  descriptions,
  relations,
  personas,
  implantedMemories,
  agentState,
  locations,
  nativeCatalog: syntheticNativeCatalog,
  tagRecords: buildTagRecords(
    syntheticNativeCatalog,
    customTags.tags,
    descriptions.descriptions,
    relations.relations
  ),
  schemaVersions: {
    customTags: customTags.schemaVersion,
    descriptions: descriptions.schemaVersion,
    relations: relations.schemaVersion,
    personas: personas.schemaVersion,
    implantedMemories: implantedMemories.schemaVersion,
    agentState: agentState.schemaVersion,
    nativeCatalogSnapshot: 1
  }
};
