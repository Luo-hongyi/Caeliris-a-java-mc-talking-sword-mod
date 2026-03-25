import { useMemo, useState } from "react";
import type { RuntimeEpisodeTimelineFile } from "./types";

const INTENT_LABELS: Record<string, string> = {
  BUILD: "建造",
  CRAFT: "制作",
  COMBAT: "战斗",
  EXPLORE: "探索",
  GATHER: "收集",
  SOCIAL: "社交",
  TRAVEL: "旅行"
};

const REASON_LABELS: Record<string, string> = {
  INTENT_SHIFT: "意图偏移",
  DEATH: "死亡",
  FALLBACK_QUIET: "兜底静默"
};

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

function formatReason(reason: string): string {
  if (!reason) {
    return "未命名原因";
  }
  return REASON_LABELS[reason] ? `${REASON_LABELS[reason]} (${reason})` : reason;
}

function formatScore(score: number): string {
  return Number.isFinite(score) ? score.toFixed(2) : "0.00";
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export function RuntimeEpisodeTimelinePage({
  runtimeEpisodeTimeline,
  onClearRuntime
}: {
  runtimeEpisodeTimeline: RuntimeEpisodeTimelineFile;
  onClearRuntime: () => Promise<void>;
}) {
  const [expandedSegmentIndex, setExpandedSegmentIndex] = useState<number>(-1);
  const [clearing, setClearing] = useState(false);
  const displaySegments = useMemo(
    () => [...runtimeEpisodeTimeline.segments].sort((left, right) => right.index - left.index),
    [runtimeEpisodeTimeline.segments]
  );

  const latestRegion = runtimeEpisodeTimeline.regions[runtimeEpisodeTimeline.regions.length - 1] ?? null;
  const latestPreview = runtimeEpisodeTimeline.latestSynthesisPreview;

  const handleClearRuntime = async () => {
    if (clearing) {
      return;
    }
    setClearing(true);
    try {
      await onClearRuntime();
    } finally {
      setClearing(false);
    }
  };

  return (
    <div className="panel page-panel">
      <div className="panel-head">
        <div>
          <p className="eyebrow">Episode</p>
          <h2>episode timeline、splitter 状态与 synthesis preview</h2>
          <p className="muted-copy">
            这里展示 episode 子系统的正式 runtime 输出：segments / pending marks / confirmed split points /
            regions，以及最近一次 episode synthesis 预览草稿。
          </p>
        </div>
        <div className="stacked-badges">
          <span className="badge">{`${runtimeEpisodeTimeline.segmentCount} 个 segments`}</span>
          <span className="badge">{`${runtimeEpisodeTimeline.splitPointCount} 个 confirmed splits`}</span>
          <span className="badge muted">
            {runtimeEpisodeTimeline.generatedAt
              ? `最近同步 ${formatRuntimeTimestamp(Date.parse(runtimeEpisodeTimeline.generatedAt))}`
              : "尚未读到 runtime 文件"}
          </span>
          <button className="secondary-button danger-button" disabled={clearing} onClick={() => void handleClearRuntime()} type="button">
            {clearing ? "清理中..." : "清空 Episode Runtime"}
          </button>
        </div>
      </div>

      <div className="action-timeline-summary-grid">
        <MetricCard label="Segments" value={String(runtimeEpisodeTimeline.segmentCount)} />
        <MetricCard label="Pending Marks" value={String(runtimeEpisodeTimeline.pendingMarkCount)} />
        <MetricCard label="Split Points" value={String(runtimeEpisodeTimeline.splitPointCount)} />
        <MetricCard label="Regions" value={String(runtimeEpisodeTimeline.regionCount)} />
      </div>

      <div className="action-timeline-controls two-col">
        <section className="info-block">
          <h3>Last Update</h3>
          <p>这是当前 snapshot 相对于上一份 runtime 文件的结构 diff，用来判断这一次追加 segment 后 episode 内部发生了什么。</p>
          <div className="chip-row compact">
            <span className="chip">{`updateKind ${runtimeEpisodeTimeline.lastUpdate.updateKind || "unknown"}`}</span>
            <span className="chip">{`segments ${runtimeEpisodeTimeline.lastUpdate.previousSegmentCount} -> ${runtimeEpisodeTimeline.lastUpdate.currentSegmentCount}`}</span>
          </div>
          <div className="runtime-summary-lines">
            {runtimeEpisodeTimeline.lastUpdate.summaryLines.length ? (
              runtimeEpisodeTimeline.lastUpdate.summaryLines.map((line, index) => <p key={`episode-update-${index}`}>{line}</p>)
            ) : (
              <p>当前还没有结构更新摘要。</p>
            )}
          </div>
        </section>

        <section className="info-block">
          <h3>Latest Closed Region</h3>
          {latestRegion ? (
            <>
              <p>{`最新 region 覆盖 segment ${latestRegion.startSegmentIndex} - ${latestRegion.endSegmentIndex}，共 ${latestRegion.segmentCount} 段。`}</p>
              <div className="chip-row compact">
                <span className="chip">{`primary ${formatIntent(latestRegion.primaryIntent)} ${formatScore(latestRegion.primaryScore)}`}</span>
                {latestRegion.secondaryIntent ? (
                  <span className="chip">{`secondary ${formatIntent(latestRegion.secondaryIntent)} ${formatScore(latestRegion.secondaryScore)}`}</span>
                ) : null}
                <span className="chip">{formatRange(latestRegion.startTimestamp, latestRegion.endTimestamp)}</span>
              </div>
            </>
          ) : (
            <p>当前还没有已闭合 region。</p>
          )}
        </section>
      </div>

      <section className="info-block">
        <h3>Reason Configs</h3>
        <p>这里显示每个 splitter 当前实际生效的延迟、优先级和 cover 范围，便于核对 workspace authored tuning 是否真的进了运行时。</p>
        <div className="chip-row compact">
          <span className="chip">{`fallback quiet window ${runtimeEpisodeTimeline.fallbackQuietWindowSegments} segments`}</span>
        </div>
        <div className="action-timeline-entry-list">
          {Object.entries(runtimeEpisodeTimeline.reasonConfigs).map(([reason, config]) => (
            <article className="action-timeline-entry-card" key={reason}>
              <div className="action-timeline-entry-head">
                <strong>{formatReason(reason)}</strong>
                <div className="custom-tag-meta">
                  <span className={`badge ${config.highPriority ? "warn" : "muted"}`}>
                    {config.highPriority ? "high priority" : "low priority"}
                  </span>
                  <span className="badge muted">{`delay ${config.confirmDelaySegments}`}</span>
                  <span className="badge muted">{`cover ${config.coverBeforeSegments}:${config.coverAfterSegments}`}</span>
                </div>
              </div>
            </article>
          ))}
        </div>
      </section>

      <div className="action-timeline-controls two-col">
        <section className="info-block">
          <h3>Pending Marks</h3>
          <div className="action-timeline-entry-list">
            {runtimeEpisodeTimeline.pendingMarks.length ? (
              runtimeEpisodeTimeline.pendingMarks.map((mark, index) => (
                <article className="action-timeline-entry-card" key={`pending-${index}-${mark.splitSegmentIndex}-${mark.reason}`}>
                  <div className="action-timeline-entry-head">
                    <strong>{formatReason(mark.reason)}</strong>
                    <span className="badge muted">{`split ${mark.splitSegmentIndex} -> confirm ${mark.confirmAtSegmentIndex}`}</span>
                  </div>
                  <p className="muted-copy">{`detected at segment ${mark.detectedAtSegmentIndex} · owner ${mark.ownerSplitterId || "unknown"}`}</p>
                  <div className="chip-row compact">
                    <span className={`chip ${mark.highPriority ? "warn" : ""}`}>{mark.highPriority ? "high priority" : "low priority"}</span>
                    <span className="chip">{`cover ${mark.coverBeforeSegments}:${mark.coverAfterSegments}`}</span>
                    <span className="chip">{formatRuntimeTimestamp(mark.splitTimestamp)}</span>
                  </div>
                </article>
              ))
            ) : (
              <p className="muted-copy">当前没有待确认 pending mark。</p>
            )}
          </div>
        </section>

        <section className="info-block">
          <h3>Confirmed Split Points</h3>
          <div className="action-timeline-entry-list">
            {runtimeEpisodeTimeline.splitPoints.length ? (
              runtimeEpisodeTimeline.splitPoints.map((splitPoint, index) => (
                <article className="action-timeline-entry-card" key={`split-${index}-${splitPoint.splitSegmentIndex}-${splitPoint.reason}`}>
                  <div className="action-timeline-entry-head">
                    <strong>{formatReason(splitPoint.reason)}</strong>
                    <span className="badge muted">{`split ${splitPoint.splitSegmentIndex} @ confirm ${splitPoint.confirmationSegmentIndex}`}</span>
                  </div>
                  <p className="muted-copy">{`${formatIntent(splitPoint.previousPrimaryIntent)} -> ${formatIntent(splitPoint.nextPrimaryIntent)}`}</p>
                  <div className="chip-row compact">
                    {splitPoint.previousSecondaryIntent ? (
                      <span className="chip">{`prev secondary ${formatIntent(splitPoint.previousSecondaryIntent)}`}</span>
                    ) : null}
                    {splitPoint.nextSecondaryIntent ? (
                      <span className="chip">{`next secondary ${formatIntent(splitPoint.nextSecondaryIntent)}`}</span>
                    ) : null}
                    <span className="chip">{formatRuntimeTimestamp(splitPoint.confirmationTimestamp)}</span>
                  </div>
                </article>
              ))
            ) : (
              <p className="muted-copy">当前还没有 confirmed split point。</p>
            )}
          </div>
        </section>
      </div>

      <section className="info-block">
        <h3>Regions</h3>
        <div className="action-timeline-entry-list">
          {runtimeEpisodeTimeline.regions.length ? (
            runtimeEpisodeTimeline.regions.map((region, index) => (
              <article className="action-timeline-entry-card" key={`region-${index}-${region.startSegmentIndex}-${region.endSegmentIndex}`}>
                <div className="action-timeline-entry-head">
                  <strong>{`segment ${region.startSegmentIndex} - ${region.endSegmentIndex}`}</strong>
                  <span className="badge muted">{formatRange(region.startTimestamp, region.endTimestamp)}</span>
                </div>
                <p className="muted-copy">{`primary ${formatIntent(region.primaryIntent)} ${formatScore(region.primaryScore)} · secondary ${formatIntent(region.secondaryIntent)} ${formatScore(region.secondaryScore)}`}</p>
                <div className="chip-row compact">
                  <span className="chip">{`${region.segmentCount} segments`}</span>
                  {Object.entries(region.aggregateIntentScores)
                    .sort((left, right) => right[1] - left[1])
                    .slice(0, 5)
                    .map(([intent, score]) => (
                      <span className="chip" key={`${region.startSegmentIndex}-${intent}`}>
                        {`${formatIntent(intent)} ${formatScore(score)}`}
                      </span>
                    ))}
                </div>
              </article>
            ))
          ) : (
            <p className="muted-copy">当前还没有闭合 region。</p>
          )}
        </div>
      </section>

      <section className="info-block">
        <h3>Segments</h3>
        <p>这里展示 episode timeline 当前持有的固定 segments。展开后可以对照这一段的动作摘要、intent 倾向和 observed action types。</p>
        <div className="action-timeline-entry-list">
          {displaySegments.length ? (
            displaySegments.map((segment) => {
              const expanded = expandedSegmentIndex === segment.index;
              return (
                <article className="action-timeline-entry-card" key={`episode-segment-${segment.index}`}>
                  <button
                    className="segment-toggle"
                    onClick={() => setExpandedSegmentIndex((current) => (current === segment.index ? -1 : segment.index))}
                    type="button"
                  >
                    <div className="action-timeline-entry-head">
                      <div>
                        <div className="custom-tag-meta">
                          <span className="badge">{`Segment ${segment.index}`}</span>
                          {segment.ambiguous ? <span className="badge warn">ambiguous</span> : null}
                        </div>
                        <strong>{segment.summary || "这一段还没有动作摘要。"}</strong>
                      </div>
                      <span className="badge muted">{expanded ? "收起" : "展开"}</span>
                    </div>
                    <p className="muted-copy">{formatRange(segment.startTimestamp, segment.endTimestamp)}</p>
                  </button>
                  {expanded ? (
                    <div className="runtime-summary-lines">
                      <p>{`dominant: ${formatIntent(segment.dominantIntent)} ${formatScore(segment.dominantScore)}`}</p>
                      <p>{`runner-up: ${formatIntent(segment.runnerUpIntent)} ${formatScore(segment.runnerUpScore)}`}</p>
                      <p>{`raw index: ${segment.rawStartIndex} - ${segment.rawEndIndex}`}</p>
                      <p>
                        {segment.observedActionTypes.length
                          ? `observed actions: ${segment.observedActionTypes.join(" | ")}`
                          : "observed actions: (none)"}
                      </p>
                      {segment.promptDraft ? (
                        <pre className="runtime-prompt-draft">{segment.promptDraft}</pre>
                      ) : (
                        <p>segment prompt draft: (empty)</p>
                      )}
                    </div>
                  ) : null}
                </article>
              );
            })
          ) : (
            <p className="muted-copy">当前还没有 episode segment。</p>
          )}
        </div>
      </section>

      <section className="info-block">
        <h3>Latest Synthesis Preview</h3>
        <p>这里显式区分两层：每个 segment 各自的 prompt draft，以及最后合成出的 region prompt draft。</p>
        {latestPreview.available ? (
          <>
            <div className="chip-row compact">
              <span className="chip">{`region ${latestPreview.regionStartSegmentIndex} - ${latestPreview.regionEndSegmentIndex}`}</span>
              <span className="chip">{formatRange(latestPreview.regionStartTimestamp, latestPreview.regionEndTimestamp)}</span>
            </div>
            <div className="action-timeline-entry-list">
              {latestPreview.segmentDrafts.map((draft) => (
                <article className="action-timeline-entry-card" key={`preview-draft-${draft.segmentIndex}`}>
                  <div className="action-timeline-entry-head">
                    <strong>{`Segment ${draft.segmentIndex}`}</strong>
                    <span className="badge muted">{formatRange(draft.startTimestamp, draft.endTimestamp)}</span>
                  </div>
                  <pre className="runtime-prompt-draft">{draft.promptDraft || "(empty draft)"}</pre>
                </article>
              ))}
            </div>
            <h4>Region Prompt Draft</h4>
            <pre className="runtime-prompt-draft">{latestPreview.regionPromptDraft || "(empty draft)"}</pre>
          </>
        ) : (
          <p className="muted-copy">当前还没有 synthesis preview。通常要等至少一个 region 闭合并触发 episode synthesis task。</p>
        )}
      </section>
    </div>
  );
}
