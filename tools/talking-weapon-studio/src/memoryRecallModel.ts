import type { MemoryRecallTuningFile } from "./types";

const TICKS_PER_DAY = 24000;
const RECENT_RECALL_PENALTY_FLOOR = 0.02;
const RECENT_RECALL_PENALTY_CEIL = 0.86;
const RECENT_RECALL_RELEASE_START = 0.15;
const RECENT_RECALL_RELEASE_END = 0.85;
const RECENT_RECALL_CURVE_EXPONENT = 1.8;
const MAX_INTERVAL_BONUS = 2.6;

export const DEFAULT_MEMORY_RECALL_TUNING_FILE: MemoryRecallTuningFile = {
  schemaVersion: 1,
  generatedAt: "",
  mainCandidatesPerCue: 1,
  sandboxCuesPerMemory: 2,
  consciousMemoryCap: 3,
  recentRecallWindow: 6,
  recentRecallWindowCap: 20,
  implantedFallbackEntryScore: 1.25,
  promptFillProbabilityScale: 1.0,
  tagPoolHalfLifeDays: 0.4,
  fermentationHalfLifeDays: 2.5,
  intervalBonusScale: 0.95,
  intervalBonusSteepness: 4.0,
  synthesizedMemoryHalfLife: 63.0,
  desireProfileBonusScale: 0.25,
  desiredFocusBonusScale: 0.18
};

export function clampNumber(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function clamp01(value: number): number {
  return clampNumber(value, 0, 1);
}

export function sanitizeMemoryRecallTuningFile(value: MemoryRecallTuningFile): MemoryRecallTuningFile {
  return {
    schemaVersion: value.schemaVersion > 0 ? value.schemaVersion : 1,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    mainCandidatesPerCue: Math.round(clampNumber(value.mainCandidatesPerCue, 1, 6)),
    sandboxCuesPerMemory: Math.round(clampNumber(value.sandboxCuesPerMemory, 0, 4)),
    consciousMemoryCap: Math.round(clampNumber(value.consciousMemoryCap, 0, 8)),
    recentRecallWindowCap: Math.round(clampNumber(value.recentRecallWindowCap, 1, 256)),
    recentRecallWindow: Math.round(
      clampNumber(value.recentRecallWindow, 1, Math.round(clampNumber(value.recentRecallWindowCap, 1, 256)))
    ),
    implantedFallbackEntryScore: clampNumber(value.implantedFallbackEntryScore, 0, 3),
    promptFillProbabilityScale: clampNumber(value.promptFillProbabilityScale, 0, 3),
    tagPoolHalfLifeDays: clampNumber(value.tagPoolHalfLifeDays, 0.05, 30),
    fermentationHalfLifeDays: clampNumber(value.fermentationHalfLifeDays, 0.1, 120),
    intervalBonusScale: clampNumber(value.intervalBonusScale, 0, 10),
    intervalBonusSteepness: clampNumber(value.intervalBonusSteepness, 0.1, 20),
    synthesizedMemoryHalfLife: clampNumber(value.synthesizedMemoryHalfLife, 1, 240),
    desireProfileBonusScale: clampNumber(value.desireProfileBonusScale, 0, 2),
    desiredFocusBonusScale: clampNumber(value.desiredFocusBonusScale, 0, 2)
  };
}

export function computeDecayFactor(deltaDays: number, halfLifeDays: number): number {
  if (deltaDays <= 0 || halfLifeDays <= 0) {
    return 1;
  }
  return Math.exp(-deltaDays / halfLifeDays);
}

export function computeTagPoolDecay(deltaDays: number, tuning: MemoryRecallTuningFile): number {
  return computeDecayFactor(deltaDays, tuning.tagPoolHalfLifeDays);
}

export function computeFermentationDecay(deltaDays: number, tuning: MemoryRecallTuningFile): number {
  return computeDecayFactor(deltaDays, tuning.fermentationHalfLifeDays);
}

export function computeIntervalBonus(deltaDays: number, tuning: MemoryRecallTuningFile): number {
  if (deltaDays <= 0) {
    return 1;
  }
  const bonus =
    1
    + Math.min(
      MAX_INTERVAL_BONUS - 1,
      Math.log1p(deltaDays * tuning.intervalBonusSteepness) * tuning.intervalBonusScale
    );
  return clampNumber(bonus, 1, MAX_INTERVAL_BONUS);
}

export function computeSynthesizedAgeFactor(ageDays: number, tuning: MemoryRecallTuningFile): number {
  const decay = Math.exp(-Math.max(0, ageDays) / Math.max(1, tuning.synthesizedMemoryHalfLife));
  return clampNumber(decay, 0.15, 1);
}

export function computeEffectiveRecentRecallWindow(window: number, cap: number): number {
  return Math.max(1, Math.min(Math.round(window), Math.round(cap)));
}

export function computeRecentRecallPenalty(turnsSincePrompt: number, tuning: MemoryRecallTuningFile): number {
  const effectiveWindow = computeEffectiveRecentRecallWindow(tuning.recentRecallWindow, tuning.recentRecallWindowCap);
  if (turnsSincePrompt >= effectiveWindow) {
    return 1;
  }

  const remainingCooldown = Math.max(1, effectiveWindow - Math.max(0, turnsSincePrompt));
  const releaseStep =
    effectiveWindow <= 1
      ? 0
      : clampNumber(effectiveWindow - remainingCooldown, 0, effectiveWindow - 1);
  const ratio =
    effectiveWindow <= 1
      ? 0
      : clamp01(releaseStep / Math.max(1, effectiveWindow - 1));
  const t = clamp01(
    (ratio - RECENT_RECALL_RELEASE_START)
    / Math.max(0.0001, RECENT_RECALL_RELEASE_END - RECENT_RECALL_RELEASE_START)
  );

  return RECENT_RECALL_PENALTY_FLOOR
    + (RECENT_RECALL_PENALTY_CEIL - RECENT_RECALL_PENALTY_FLOOR) * Math.pow(t, RECENT_RECALL_CURVE_EXPONENT);
}

export function computePromptFillProbability(consciousScore: number, tuning: MemoryRecallTuningFile): number {
  const normalized = clamp01(consciousScore / 2.4);
  const rawProbability = 0.08 + normalized * 0.84;
  return clampNumber(rawProbability * tuning.promptFillProbabilityScale, 0, 1);
}

export function computeTemperatureExponent(wanderingMind: number): number {
  return 2.6 - clamp01(wanderingMind) * 2;
}

export function applyTemperature(score: number, wanderingMind: number): number {
  return Math.pow(Math.max(0.0001, score), computeTemperatureExponent(wanderingMind));
}

export function computePromotionBudget(cueCount: number, mainCandidatesPerCue: number): number {
  return Math.max(1, cueCount * Math.max(1, Math.round(mainCandidatesPerCue)));
}

export function computeSandboxCueBudget(consciousMemoryCount: number, sandboxCuesPerMemory: number): number {
  return Math.max(0, consciousMemoryCount * Math.max(0, Math.round(sandboxCuesPerMemory)));
}

export function computeRetentionCap(consciousMemoryCap: number): number {
  return clampNumber(Math.max(consciousMemoryCap * 4, 8), 8, 32);
}

export function computeSandboxRecallBudget(consciousMemoryCount: number): number {
  return Math.max(1, Math.min(4, consciousMemoryCount));
}

export function computeDesireProfileBonus(focusWeight: number, tuning: MemoryRecallTuningFile): number {
  return clampNumber(focusWeight, 0, 1) * tuning.desireProfileBonusScale;
}

export function computeDesiredFocusBonus(focusWeight: number, tuning: MemoryRecallTuningFile): number {
  return clampNumber(focusWeight, 0, 1) * tuning.desiredFocusBonusScale;
}

export function buildSampleSeries(
  maxX: number,
  sampleCount: number,
  sample: (x: number) => number
): Array<{ x: number; y: number }> {
  const safeMaxX = Math.max(0.0001, maxX);
  const safeCount = Math.max(2, sampleCount);
  return Array.from({ length: safeCount }, (_, index) => {
    const x = safeMaxX * (index / (safeCount - 1));
    return {
      x,
      y: sample(x)
    };
  });
}

export function gameTicksToDays(gameTicks: number): number {
  return Math.max(0, gameTicks) / TICKS_PER_DAY;
}
