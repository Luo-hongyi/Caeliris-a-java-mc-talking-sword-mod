import { useEffect, useMemo, useState } from "react";
import type {
  ActionTimelineTuningFile,
  RuntimeActionTimelineEntry,
  RuntimeActionTimelineFile
} from "./types";

const INTENT_LABELS: Record<string, string> = {
  BUILD: "建造",
  CRAFT: "制作",
  COMBAT: "战斗",
  EXPLORE: "探索",
  GATHER: "收集",
  SOCIAL: "社交",
  TRAVEL: "旅行"
};

const ACTION_LABELS: Record<string, string> = {
  ACQUIRE_ITEM: "获得物品",
  ATTACK: "攻击",
  BIOME_CHANGE: "群系变化",
  BREAK_BLOCK: "破坏方块",
  CRAFT_ITEM: "合成物品",
  DEATH: "死亡",
  DIM_CHANGE: "维度变化",
  ENTER_MAJOR_STRUCTURE: "进入大型结构",
  ENTER_REGISTERED_LOCATION: "进入已登记地点",
  HURT_BY: "受伤",
  IDLE: "发呆",
  INTERACT_FUNCTION_BLOCK: "交互功能方块",
  INTERACT_NPC: "交互 NPC",
  KILL: "击杀",
  LEAVE_REGISTERED_LOCATION: "离开已登记地点",
  MINE_BLOCK: "挖掘方块",
  OPEN_LOOT_CONTAINER: "打开战利品容器",
  PLACE_BLOCK: "放置方块",
  SET_RESPAWN_POINT: "设置重生点",
  TRADE: "交易",
  TRAVEL: "旅行",
  USE_GATHER_TOOL: "使用采集工具",
  USE_ITEM: "使用物品",
  EXIT_MAJOR_STRUCTURE: "离开大型结构"
};

type RuntimeActionTimelinePageProps = {
  runtimeActionTimeline: RuntimeActionTimelineFile;
  tuningFile: ActionTimelineTuningFile;
  onTuningChange: (patch: Partial<ActionTimelineTuningFile>) => void;
};

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function normalizeWhitelistText(value: string): string[] {
  return Array.from(
    new Set(
      value
        .split(/\r?\n|,/)
        .map((line) => line.trim())
        .filter(Boolean)
    )
  );
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

function formatShortTime(timestamp: number): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return "--:--:--";
  }
  return new Date(timestamp).toLocaleTimeString("zh-CN", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
}

function formatRange(startTimestamp: number, endTimestamp: number): string {
  if (startTimestamp <= 0 || endTimestamp <= 0) {
    return "未知区间";
  }
  return `${formatShortTime(startTimestamp)} - ${formatShortTime(endTimestamp)}`;
}

function formatIntent(intent: string): string {
  if (!intent) {
    return "未判定";
  }
  return INTENT_LABELS[intent] ? `${INTENT_LABELS[intent]} (${intent})` : intent;
}

function formatActionType(actionType: string): string {
  if (!actionType) {
    return "未命名动作";
  }
  return ACTION_LABELS[actionType] ?? actionType;
}

function formatScore(score: number): string {
  return Number.isFinite(score) ? score.toFixed(2) : "0.00";
}

function sortedScores(scores: Record<string, number>): Array<[string, number]> {
  return Object.entries(scores).sort((left, right) => right[1] - left[1]);
}

function TimelineMetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function TimelineEntryCard({ entry }: { entry: RuntimeActionTimelineEntry }) {
  const foldedTargetPreview = entry.foldedTargets.length ? entry.foldedTargets.slice(0, 6) : [];
  const rawIndexSummary =
    entry.rawStartIndex >= 0 && entry.rawEndIndex >= 0
      ? `原始索引 ${entry.rawStartIndex} - ${entry.rawEndIndex}`
      : "这条条目来自 moving window 折叠视图";

  return (
    <article className="action-timeline-entry-card">
      <div className="action-timeline-entry-head">
        <div>
          <div className="custom-tag-meta">
            <span className="badge warn">{formatActionType(entry.type)}</span>
            <span className="badge muted">{`x${entry.count}`}</span>
            {entry.folded ? <span className="badge">压缩</span> : null}
          </div>
          <strong>{entry.target || "(无 target)"}</strong>
        </div>
        <span className="badge muted">{formatRange(entry.firstTimestamp, entry.lastTimestamp)}</span>
      </div>
      <p className="muted-copy">{rawIndexSummary}</p>
      {entry.detailTags.length ? (
        <div className="chip-row compact">
          {entry.detailTags.map((tag) => (
            <span className="chip" key={`${entry.type}-${entry.target}-${tag}`}>
              {tag}
            </span>
          ))}
        </div>
      ) : null}
      {foldedTargetPreview.length ? (
        <div className="chip-row compact">
          {foldedTargetPreview.map((target) => (
            <span className="chip" key={`${entry.type}-${target}`}>
              {target}
            </span>
          ))}
          {entry.foldedTargets.length > foldedTargetPreview.length ? (
            <span className="chip">{`+${entry.foldedTargets.length - foldedTargetPreview.length}`}</span>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}

export function RuntimeActionTimelinePage({
  runtimeActionTimeline,
  tuningFile,
  onTuningChange
}: RuntimeActionTimelinePageProps) {
  const [expandedSegmentIndex, setExpandedSegmentIndex] = useState<number>(-1);
  const segments = runtimeActionTimeline.fixedSegments;
  const displaySegments = useMemo(() => [...segments].sort((left, right) => right.index - left.index), [segments]);
  const segmentsWithBoundaryActions = useMemo(
    () => segments.filter((segment) => segment.boundaryActions.length > 0),
    [segments]
  );
  const totalBoundaryActionCount = useMemo(
    () => segments.reduce((sum, segment) => sum + segment.boundaryActions.length, 0),
    [segments]
  );

  const defaultExpandedSegmentIndex = useMemo(() => {
    if (!segments.length) {
      return -1;
    }
    return segments[segments.length - 1]?.index ?? -1;
  }, [segments]);

  useEffect(() => {
    if (!segments.length) {
      setExpandedSegmentIndex(-1);
      return;
    }

    setExpandedSegmentIndex((current) =>
      current >= 0 && segments.some((segment) => segment.index === current)
        ? current
        : defaultExpandedSegmentIndex
    );
  }, [defaultExpandedSegmentIndex, segments]);

  const movingWindow = runtimeActionTimeline.movingWindow;
  const topMovingEntries = movingWindow.entries.slice(0, 5);
  const majorStructureWhitelistText = tuningFile.majorStructureWhitelist.join("\n");

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Player Actions</p>
          <h2>moving window、fixed segments 与 splitter signals</h2>
          <p className="muted-copy">
            这里聚焦 `playeraction` 包本身的正式输出：近期 prompt 用的 moving window、供 episode 消费的 fixed segments，
            以及每段附带的 observed action types / boundary actions / intent signals。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${runtimeActionTimeline.fixedSegmentCount} 个 fixed segments`}</span>
          <span className="badge">{`${movingWindow.entries.length} 条 moving entries`}</span>
          <span className="badge muted">
            {runtimeActionTimeline.generatedAt
              ? `最近同步 ${formatRuntimeTimestamp(Date.parse(runtimeActionTimeline.generatedAt))}`
              : "尚未读到 runtime 文件"}
          </span>
        </div>
      </div>

      <div className="action-timeline-summary-grid">
        <TimelineMetricCard label="Raw Actions" value={String(runtimeActionTimeline.rawActionCount)} />
        <TimelineMetricCard label="Fixed Segments" value={String(runtimeActionTimeline.fixedSegmentCount)} />
        <TimelineMetricCard label="Moving Entries" value={String(movingWindow.entries.length)} />
        <TimelineMetricCard label="Boundary Actions" value={String(totalBoundaryActionCount)} />
      </div>

      <div className="action-timeline-controls two-col">
        <section className="info-block">
          <h3>Fixed Segment Length</h3>
          <p>控制 Action Archive 的固定切段长度，也是 fixed segment 正式落盘与发送到 episode 的基础窗口。</p>
          <label className="field">
            <span>{`authored ${tuningFile.fixedSegmentSeconds}s · runtime ${runtimeActionTimeline.fixedSegmentSeconds}s`}</span>
            <div className="range-field">
              <input
                max={300}
                min={10}
                onChange={(event) =>
                  onTuningChange({
                    fixedSegmentSeconds: clamp(Number.parseInt(event.target.value, 10), 10, 300)
                  })
                }
                step={10}
                type="range"
                value={tuningFile.fixedSegmentSeconds}
              />
              <output className="range-field-output">{`${tuningFile.fixedSegmentSeconds}s`}</output>
            </div>
          </label>
        </section>

        <section className="info-block">
          <h3>Moving Window Length</h3>
          <p>控制 mov segment 的回看长度。这个窗口以触发 LLM 的时刻为终点，不参与 episode 正式切分。</p>
          <label className="field">
            <span>{`authored ${tuningFile.movingWindowSeconds}s · runtime ${runtimeActionTimeline.movingWindowSeconds}s`}</span>
            <div className="range-field">
              <input
                max={480}
                min={30}
                onChange={(event) =>
                  onTuningChange({
                    movingWindowSeconds: clamp(Number.parseInt(event.target.value, 10), 30, 480)
                  })
                }
                step={10}
                type="range"
                value={tuningFile.movingWindowSeconds}
              />
              <output className="range-field-output">{`${tuningFile.movingWindowSeconds}s`}</output>
            </div>
          </label>
        </section>

        <section className="info-block">
          <h3>Registered Location Leave Buffer</h3>
          <p>已登记地点的离开判定会在 authored 进入半径基础上额外扩张一段距离，用来形成简单滞回，避免边界抖动。</p>
          <label className="field">
            <span>
              {`authored ${tuningFile.registeredLocationLeaveExtraBlocks} 格 · runtime ${runtimeActionTimeline.registeredLocationLeaveExtraBlocks} 格`}
            </span>
            <div className="range-field">
              <input
                max={256}
                min={0}
                onChange={(event) =>
                  onTuningChange({
                    registeredLocationLeaveExtraBlocks: clamp(
                      Number.parseInt(event.target.value, 10),
                      0,
                      256
                    )
                  })
                }
                step={4}
                type="range"
                value={tuningFile.registeredLocationLeaveExtraBlocks}
              />
              <output className="range-field-output">{`${tuningFile.registeredLocationLeaveExtraBlocks} 格`}</output>
            </div>
          </label>
        </section>

        <section className="info-block">
          <h3>Major Structure Enter Radius</h3>
          <p>进入大型结构的探测半径。当前结构边界 tracker 会基于白名单扫描附近结构，并用这个半径判定 enter。</p>
          <label className="field">
            <span>
              {`authored ${tuningFile.majorStructureEnterRadiusBlocks} 格 · runtime ${runtimeActionTimeline.majorStructureEnterRadiusBlocks} 格`}
            </span>
            <div className="range-field">
              <input
                max={256}
                min={0}
                onChange={(event) => {
                  const nextEnterRadius = clamp(Number.parseInt(event.target.value, 10), 0, 256);
                  onTuningChange({
                    majorStructureEnterRadiusBlocks: nextEnterRadius,
                    majorStructureExitRadiusBlocks: Math.max(
                      tuningFile.majorStructureExitRadiusBlocks,
                      nextEnterRadius
                    )
                  });
                }}
                step={4}
                type="range"
                value={tuningFile.majorStructureEnterRadiusBlocks}
              />
              <output className="range-field-output">{`${tuningFile.majorStructureEnterRadiusBlocks} 格`}</output>
            </div>
          </label>
        </section>

        <section className="info-block">
          <h3>Major Structure Exit Radius</h3>
          <p>离开大型结构的判定半径。它必须不小于 enter 半径，用来构成 enter / exit 的滞回区间。</p>
          <label className="field">
            <span>
              {`authored ${tuningFile.majorStructureExitRadiusBlocks} 格 · runtime ${runtimeActionTimeline.majorStructureExitRadiusBlocks} 格`}
            </span>
            <div className="range-field">
              <input
                max={256}
                min={tuningFile.majorStructureEnterRadiusBlocks}
                onChange={(event) =>
                  onTuningChange({
                    majorStructureExitRadiusBlocks: clamp(
                      Number.parseInt(event.target.value, 10),
                      tuningFile.majorStructureEnterRadiusBlocks,
                      256
                    )
                  })
                }
                step={4}
                type="range"
                value={Math.max(
                  tuningFile.majorStructureExitRadiusBlocks,
                  tuningFile.majorStructureEnterRadiusBlocks
                )}
              />
              <output className="range-field-output">{`${Math.max(
                tuningFile.majorStructureExitRadiusBlocks,
                tuningFile.majorStructureEnterRadiusBlocks
              )} 格`}</output>
            </div>
          </label>
        </section>

        <section className="info-block">
          <h3>Major Structure Whitelist</h3>
          <p>一行一个结构 tag。这里只接受 `DetailTag` 字符串，只有命中白名单的结构才会产出 enter / exit 边界动作。</p>
          <label className="field">
            <span>{`当前 ${tuningFile.majorStructureWhitelist.length} 条`}</span>
            <textarea
              onChange={(event) =>
                onTuningChange({
                  majorStructureWhitelist: normalizeWhitelistText(event.target.value)
                })
              }
              placeholder={"native_specific:minecraft:village_plains\nnative_specific:minecraft:ancient_city"}
              rows={8}
              value={majorStructureWhitelistText}
            />
          </label>
        </section>
      </div>

      <div className="two-col action-timeline-overview-grid">
        <section className="info-block">
          <h3>Moving Window</h3>
          <p>{movingWindow.headline || "当前 mov segment 还没有形成明确 headline。"}</p>
          <div className="chip-row compact">
            <span className="chip">{formatRange(movingWindow.startTimestamp, movingWindow.endTimestamp)}</span>
            <span className="chip">{movingWindow.open ? "窗口开启中" : "窗口已闭合"}</span>
            <span className="chip">{`entries ${movingWindow.entries.length}`}</span>
            {movingWindow.dominantIntent ? (
              <span className="chip">{`主意图 ${formatIntent(movingWindow.dominantIntent)} ${formatScore(movingWindow.dominantScore)}`}</span>
            ) : null}
          </div>
          <p>{movingWindow.summary || "当前 mov segment 没有摘要。"}</p>
          {topMovingEntries.length ? (
            <div className="action-timeline-entry-list">
              {topMovingEntries.map((entry, entryIndex) => (
                <TimelineEntryCard
                  entry={entry}
                  key={`moving-entry-${entryIndex}-${entry.type}-${entry.target}`}
                />
              ))}
            </div>
          ) : (
            <p>当前 moving window 还没有形成可展示条目。</p>
          )}
        </section>

        <section className="info-block">
          <h3>Boundary Detection Snapshot</h3>
          <p>这些边界动作仍属于 player-action 运行层，但会被 fixed segment 一并送到 episode splitter 作为候选边界材料。</p>
          <div className="chip-row compact">
            <span className="chip">{`registered leave +${runtimeActionTimeline.registeredLocationLeaveExtraBlocks}`}</span>
            <span className="chip">{`structure enter ${runtimeActionTimeline.majorStructureEnterRadiusBlocks}`}</span>
            <span className="chip">{`structure exit ${runtimeActionTimeline.majorStructureExitRadiusBlocks}`}</span>
            <span className="chip">{`whitelist ${runtimeActionTimeline.majorStructureWhitelist.length}`}</span>
            <span className="chip">{`segments with boundary ${segmentsWithBoundaryActions.length}`}</span>
          </div>
          {runtimeActionTimeline.majorStructureWhitelist.length ? (
            <div className="chip-row compact">
              {runtimeActionTimeline.majorStructureWhitelist.slice(0, 8).map((tag) => (
                <span className="chip" key={`structure-whitelist-${tag}`}>
                  {tag}
                </span>
              ))}
              {runtimeActionTimeline.majorStructureWhitelist.length > 8 ? (
                <span className="chip">{`+${runtimeActionTimeline.majorStructureWhitelist.length - 8}`}</span>
              ) : null}
            </div>
          ) : (
            <p>当前结构白名单为空，因此 major structure tracker 不会产出 enter / exit 信号。</p>
          )}
        </section>
      </div>

      <section className="action-timeline-fixed-sections">
        <div className="section-head">
          <div>
            <h3>Fixed Segments</h3>
            <p className="muted-copy">
              这里展示 fixed segment 的正式产物本身，而不是 downstream 的 episode region。展开后可查看 entries、
              observed action types、boundary actions 与 intent signals。
            </p>
          </div>
        </div>

        {segments.length ? (
          <div className="runtime-entry-list">
            {displaySegments.map((segment) => {
              const expanded = expandedSegmentIndex === segment.index;
              const scorePairs = sortedScores(segment.scores);
              const topScores = scorePairs.slice(0, 4);

              return (
                <article
                  className={expanded ? "runtime-entry-card expanded" : "runtime-entry-card"}
                  key={`fixed-segment-${segment.index}`}
                >
                  <button
                    className="runtime-entry-toggle"
                    onClick={() =>
                      setExpandedSegmentIndex((current) => (current === segment.index ? -1 : segment.index))
                    }
                    type="button"
                  >
                    <div className="runtime-entry-head">
                      <div>
                        <div className="custom-tag-meta">
                          <span className="badge">{`Segment ${segment.index + 1}`}</span>
                          <span className="badge muted">{formatRange(segment.startTimestamp, segment.endTimestamp)}</span>
                          <span className="badge muted">{`actions ${segment.observedActionTypes.length}`}</span>
                          {segment.boundaryActions.length ? (
                            <span className="badge warn">{`boundary ${segment.boundaryActions.length}`}</span>
                          ) : null}
                        </div>
                        <h3>{segment.headline || `Segment ${segment.index + 1}`}</h3>
                      </div>
                      <span className={expanded ? "badge good" : "badge muted"}>{expanded ? "收起" : "展开"}</span>
                    </div>
                    <div className="runtime-summary-lines">
                      <p>{segment.summary || "这一段还没有摘要。"}</p>
                      <p>
                        {segment.dominantIntent
                          ? `主意图: ${formatIntent(segment.dominantIntent)} (${formatScore(segment.dominantScore)})`
                          : "主意图: 未判定"}
                        {segment.runnerUpIntent
                          ? ` · 次意图: ${formatIntent(segment.runnerUpIntent)} (${formatScore(segment.runnerUpScore)})`
                          : ""}
                        {segment.ambiguous ? " · 判定偏模糊" : ""}
                      </p>
                      {segment.boundaryActions.length ? (
                        <p>{`边界动作: ${segment.boundaryActions.join(" / ")}`}</p>
                      ) : null}
                    </div>
                  </button>

                  {expanded ? (
                    <div className="runtime-entry-details">
                      <section className="info-block">
                        <h3>Segment Signals</h3>
                        {segment.observedActionTypes.length ? (
                          <div className="chip-row compact">
                            {segment.observedActionTypes.map((actionType) => (
                              <span className="chip" key={`observed-${segment.index}-${actionType}`}>
                                {formatActionType(actionType)}
                              </span>
                            ))}
                          </div>
                        ) : (
                          <p>当前没有 observed action types。</p>
                        )}
                        {segment.boundaryActions.length ? (
                          <div className="action-timeline-score-list">
                            {segment.boundaryActions.map((action, actionIndex) => (
                              <article className="action-timeline-score-card" key={`boundary-${segment.index}-${actionIndex}`}>
                                <strong>{`Boundary ${actionIndex + 1}`}</strong>
                                <p>{action}</p>
                              </article>
                            ))}
                          </div>
                        ) : (
                          <p>当前没有 boundary actions。</p>
                        )}
                      </section>

                      <section className="info-block">
                        <h3>Intent Scores</h3>
                        <div className="chip-row compact">
                          {topScores.length ? (
                            topScores.map(([intent, score]) => (
                              <span className="chip" key={`score-${segment.index}-${intent}`}>
                                {`${formatIntent(intent)} ${formatScore(score)}`}
                              </span>
                            ))
                          ) : (
                            <span className="chip">无打分</span>
                          )}
                        </div>
                        {scorePairs.length ? (
                          <div className="action-timeline-score-list">
                            {scorePairs.map(([intent, score]) => {
                              const reasons = segment.reasons[intent] ?? [];
                              return (
                                <article className="action-timeline-score-card" key={`reason-${segment.index}-${intent}`}>
                                  <div className="action-timeline-score-head">
                                    <strong>{formatIntent(intent)}</strong>
                                    <span className="badge muted">{formatScore(score)}</span>
                                  </div>
                                  <p>{reasons.length ? reasons.join(" / ") : "当前没有记录具体理由。"}</p>
                                </article>
                              );
                            })}
                          </div>
                        ) : null}
                      </section>

                      <section className="info-block">
                        <h3>Fixed Segment Entries</h3>
                        {segment.entries.length ? (
                          <div className="action-timeline-entry-list">
                            {segment.entries.map((entry, entryIndex) => (
                              <TimelineEntryCard
                                entry={entry}
                                key={`segment-${segment.index}-entry-${entryIndex}-${entry.type}-${entry.target}`}
                              />
                            ))}
                          </div>
                        ) : (
                          <p>这一段没有压缩后的动作条目。</p>
                        )}
                      </section>
                    </div>
                  ) : null}
                </article>
              );
            })}
          </div>
        ) : (
          <div className="empty-state">
            <strong>还没有 fixed segment</strong>
            <p>需要先让游戏端写出 `studio_runtime_action_timeline.json`，或者等待动作记录累计到足够的时间段。</p>
          </div>
        )}
      </section>
    </div>
  );
}
