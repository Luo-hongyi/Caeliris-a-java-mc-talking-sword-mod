import type { MemoryRecallTuningFile } from "./types";

const TICKS_PER_DAY = 24000;
const RECENT_RECALL_PENALTY_FLOOR = 0.02;
const RECENT_RECALL_PENALTY_CEIL = 0.86;
const RECENT_RECALL_RELEASE_START = 0.15;
const RECENT_RECALL_RELEASE_END = 0.85;
const RECENT_RECALL_CURVE_EXPONENT = 1.8;
const MAX_INTERVAL_BONUS = 2.6;

export const DEFAULT_MEMORY_RECALL_TUNING_FILE: MemoryRecallTuningFile = {
  schemaVersion: 3,
  generatedAt: "",
  mainCandidatesPerCue: 6,
  sandboxCuesPerMemory: 6,
  consciousMemoryCap: 3,
  retentionMemoryCap: 12,
  recentRecallWindow: 6,
  recentRecallWindowCap: 16,
  implantedFallbackEntryScore: 1.25,
  promptFillProbabilityScale: 1.0,
  tagPoolHalfLifeDays: 0.4,
  tagPoolDecayShape: 1.0,
  tagPoolDropThreshold: 0.035,
  fermentationHalfLifeDays: 1.2,
  fermentationDecayShape: 1.0,
  fermentationDropThreshold: 0.06,
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
    schemaVersion: value.schemaVersion > 0 ? value.schemaVersion : 3,
    generatedAt: typeof value.generatedAt === "string" ? value.generatedAt : "",
    mainCandidatesPerCue: Math.round(clampNumber(value.mainCandidatesPerCue, 1, 32)),
    sandboxCuesPerMemory: Math.round(clampNumber(value.sandboxCuesPerMemory, 1, 32)),
    consciousMemoryCap: Math.round(clampNumber(value.consciousMemoryCap, 1, 16)),
    retentionMemoryCap: Math.round(clampNumber(value.retentionMemoryCap, 1, 16)),
    recentRecallWindowCap: 16,
    recentRecallWindow: Math.round(clampNumber(value.recentRecallWindow, 0, 16)),
    implantedFallbackEntryScore: clampNumber(value.implantedFallbackEntryScore, 0, 3),
    promptFillProbabilityScale: clampNumber(value.promptFillProbabilityScale, 0, 2),
    tagPoolHalfLifeDays: clampNumber(value.tagPoolHalfLifeDays, 0.1, 1),
    tagPoolDecayShape: clampNumber(value.tagPoolDecayShape, 0.25, 4),
    tagPoolDropThreshold: clampNumber(value.tagPoolDropThreshold, 0, 0.2),
    fermentationHalfLifeDays: clampNumber(value.fermentationHalfLifeDays, 0.1, 2),
    fermentationDecayShape: clampNumber(value.fermentationDecayShape, 0.25, 4),
    fermentationDropThreshold: clampNumber(value.fermentationDropThreshold, 0, 0.2),
    intervalBonusScale: clampNumber(value.intervalBonusScale, 0, 2.5),
    intervalBonusSteepness: clampNumber(value.intervalBonusSteepness, 0.25, 8),
    synthesizedMemoryHalfLife: clampNumber(value.synthesizedMemoryHalfLife, 1, 240),
    desireProfileBonusScale: clampNumber(value.desireProfileBonusScale, 0, 2),
    desiredFocusBonusScale: clampNumber(value.desiredFocusBonusScale, 0, 2)
  };
}

export function computeDecayFactor(deltaDays: number, halfLifeDays: number, decayShape: number): number {
  if (deltaDays <= 0 || halfLifeDays <= 0) {
    return 1;
  }
  const ratio = Math.max(0, deltaDays) / Math.max(0.0001, halfLifeDays);
  return Math.exp(-Math.pow(ratio, clampNumber(decayShape, 0.25, 4)));
}

export function computeTagPoolDecay(deltaDays: number, tuning: MemoryRecallTuningFile): number {
  return computeDecayFactor(deltaDays, tuning.tagPoolHalfLifeDays, tuning.tagPoolDecayShape);
}

export function computeFermentationDecay(deltaDays: number, tuning: MemoryRecallTuningFile): number {
  return computeDecayFactor(deltaDays, tuning.fermentationHalfLifeDays, tuning.fermentationDecayShape);
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

export function computeEffectiveRecentRecallWindow(window: number, _cap: number): number {
  return Math.max(0, Math.min(Math.round(window), 16));
}

export function computeRecentRecallPenalty(turnsSincePrompt: number, tuning: MemoryRecallTuningFile): number {
  const effectiveWindow = computeEffectiveRecentRecallWindow(tuning.recentRecallWindow, tuning.recentRecallWindowCap);
  if (effectiveWindow <= 0) {
    return 1;
  }
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

export function computePromotionBudget(mainCandidatesPerCue: number): number {
  return Math.round(clampNumber(mainCandidatesPerCue, 1, 32));
}

export function computeSandboxCueBudget(sandboxCuesPerMemory: number): number {
  return Math.round(clampNumber(sandboxCuesPerMemory, 1, 32));
}

export function computeRetentionCap(retentionMemoryCap: number): number {
  return Math.round(clampNumber(retentionMemoryCap, 1, 16));
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
