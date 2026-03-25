package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodePendingMark;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeReasonRuntimeConfig;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSegmentPromptDraft;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSplitPoint;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisPromptFormatter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.SegmentIntentSignals;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Episode v2 的 runtime 调试输出。
 * <p>
 * 用于给 Studio / 本地测试查看当前正式 timeline：
 * <ul>
 * <li>fixed segments 视角下的 episode timeline 摘要</li>
 * <li>pending marks / split points / regions</li>
 * <li>本次更新相对于上一份快照的 diff</li>
 * <li>最近一次 synthesis material prompt draft（如有）</li>
 * </ul>
 */
public final class StudioRuntimeEpisodeTimelineFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_episode_timeline.json";
    public static final String VERSION_KEY = "runtimeEpisodeTimeline";
    public static final int SCHEMA_VERSION = 1;
    private static final long MIN_WRITE_INTERVAL_MS = 750L;
    private static final long ASYNC_DEBOUNCE_MS = 100L;
    private static final int MAX_RENDERED_SEGMENTS = 24;
    private static final int MAX_RENDERED_PENDING_MARKS = 24;
    private static final int MAX_RENDERED_SPLIT_POINTS = 24;
    private static final int MAX_RENDERED_REGIONS = 12;
    private static final int MAX_SEGMENT_PROMPT_DRAFTS = 8;

    private static final Object WRITE_LOCK = new Object();
    private static final ScheduledExecutorService WRITE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "TalkingWeapon-StudioEpisodeRuntime");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static long lastWriteAt = 0L;
    private static RuntimeEpisodeTimelineFileModel cachedFile;
    private static ScheduledFuture<?> pendingWrite;
    private static AgentMemory pendingMemory;

    private StudioRuntimeEpisodeTimelineFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeEpisodeTimelineFileModel readOrEmpty() {
        if (cachedFile != null) {
            return cachedFile;
        }
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            cachedFile = new RuntimeEpisodeTimelineFileModel();
            return cachedFile;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeEpisodeTimelineFileModel file = GSON.fromJson(reader, RuntimeEpisodeTimelineFileModel.class);
            if (file == null) {
                file = new RuntimeEpisodeTimelineFileModel();
            }
            file.normalize();
            cachedFile = file;
            return cachedFile;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeEpisodeTimeline] 读取失败：" + e.getMessage());
            cachedFile = new RuntimeEpisodeTimelineFileModel();
            return cachedFile;
        }
    }

    public static void recordSnapshot(AgentMemory memory) {
        if (memory == null) {
            return;
        }
        synchronized (WRITE_LOCK) {
            pendingMemory = memory;
            if (pendingWrite != null) {
                return;
            }
            pendingWrite = WRITE_EXECUTOR.schedule(
                    StudioRuntimeEpisodeTimelineFile::flushPendingSnapshot,
                    nextWriteDelayMs(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public static synchronized void writeCurrentSnapshot(AgentMemory memory) {
        RuntimeEpisodeTimelineFileModel previous = readOrEmpty();
        RuntimeEpisodeTimelineFileModel current = build(memory, previous);
        write(current);
    }

    private static void flushPendingSnapshot() {
        AgentMemory memory;
        synchronized (WRITE_LOCK) {
            memory = pendingMemory;
            pendingMemory = null;
            pendingWrite = null;
        }
        if (memory != null) {
            writeCurrentSnapshot(memory);
        }
        synchronized (WRITE_LOCK) {
            if (pendingMemory != null && pendingWrite == null) {
                pendingWrite = WRITE_EXECUTOR.schedule(
                        StudioRuntimeEpisodeTimelineFile::flushPendingSnapshot,
                        nextWriteDelayMs(),
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private static long nextWriteDelayMs() {
        long elapsed = System.currentTimeMillis() - lastWriteAt;
        long remainingInterval = elapsed >= MIN_WRITE_INTERVAL_MS ? 0L : (MIN_WRITE_INTERVAL_MS - elapsed);
        return Math.max(ASYNC_DEBOUNCE_MS, remainingInterval);
    }

    public static synchronized void recordSynthesisMaterial(EpisodeSynthesisMaterial material) {
        RuntimeEpisodeTimelineFileModel file = readOrEmpty();
        file.generatedAt = OffsetDateTime.now().toString();
        file.latestSynthesisPreview = RuntimeEpisodeSynthesisPreviewModel.fromMaterial(material);
        write(file);
    }

    private static RuntimeEpisodeTimelineFileModel build(
            AgentMemory memory,
            RuntimeEpisodeTimelineFileModel previous) {
        RuntimeEpisodeTimelineFileModel file = new RuntimeEpisodeTimelineFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        file.fallbackQuietWindowSegments = EpisodeRuntimeSettings.getFallbackQuietWindowSegments();
        file.reasonConfigs = createReasonConfigModels(EpisodeRuntimeSettings.snapshotConfigs());
        file.latestSynthesisPreview = previous == null
                ? new RuntimeEpisodeSynthesisPreviewModel()
                : RuntimeEpisodeSynthesisPreviewModel.copyOf(previous.latestSynthesisPreview);

        EpisodeTimeline timeline = memory == null
                ? EpisodeTimeline.empty()
                : memory.getEpisodeTimelineStore().snapshot();
        file.segmentCount = timeline.segmentCount();
        file.pendingMarkCount = timeline.pendingMarks().size();
        file.splitPointCount = timeline.splitPoints().size();
        file.regionCount = timeline.regions().size();

        List<FixedActionSegment> renderedSegments = enrichSegmentsWithCurrentTurns(
                timeline.segments(),
                memory == null ? List.of() : memory.getSTMPool().turnSnapshot());
        List<FixedActionSegment> visibleSegments = tail(renderedSegments, MAX_RENDERED_SEGMENTS);
        int visibleSegmentStartIndex = timeline.segmentCount() - visibleSegments.size();
        int promptDraftStartIndex = Math.max(visibleSegmentStartIndex, timeline.segmentCount() - MAX_SEGMENT_PROMPT_DRAFTS);
        for (int i = 0; i < visibleSegments.size(); i++) {
            int segmentIndex = visibleSegmentStartIndex + i;
            file.segments.add(toSegmentModel(
                    segmentIndex,
                    visibleSegments.get(i),
                    segmentIndex >= promptDraftStartIndex));
        }
        for (EpisodePendingMark pendingMark : tail(timeline.pendingMarks(), MAX_RENDERED_PENDING_MARKS)) {
            if (pendingMark != null) {
                file.pendingMarks.add(RuntimeEpisodePendingMarkModel.from(pendingMark));
            }
        }
        for (EpisodeSplitPoint splitPoint : tail(timeline.splitPoints(), MAX_RENDERED_SPLIT_POINTS)) {
            if (splitPoint != null) {
                file.splitPoints.add(RuntimeEpisodeSplitPointModel.from(splitPoint));
            }
        }
        for (EpisodeRegion region : tail(timeline.regions(), MAX_RENDERED_REGIONS)) {
            if (region != null) {
                file.regions.add(RuntimeEpisodeRegionModel.from(region));
            }
        }

        file.lastUpdate = RuntimeEpisodeUpdateModel.diff(previous, file);
        file.normalize();
        return file;
    }

    private static LinkedHashMap<String, RuntimeEpisodeReasonConfigModel> createReasonConfigModels(
            Map<EpisodeSplitReason, EpisodeReasonRuntimeConfig> configs) {
        LinkedHashMap<String, RuntimeEpisodeReasonConfigModel> models = new LinkedHashMap<>();
        for (EpisodeSplitReason reason : EpisodeSplitReason.values()) {
            EpisodeReasonRuntimeConfig config = configs == null
                    ? EpisodeRuntimeSettings.defaultConfig(reason)
                    : configs.getOrDefault(reason, EpisodeRuntimeSettings.defaultConfig(reason));
            models.put(reason.workspaceKey(), RuntimeEpisodeReasonConfigModel.from(config));
        }
        return models;
    }

    private static RuntimeEpisodeSegmentModel toSegmentModel(
            int index,
            FixedActionSegment segment,
            boolean includePromptDraft) {
        RuntimeEpisodeSegmentModel model = new RuntimeEpisodeSegmentModel();
        model.index = index;
        if (segment == null) {
            return model;
        }

        model.startTimestamp = segment.getStartTimestamp();
        model.endTimestamp = segment.getEndTimestamp();
        model.rawStartIndex = segment.getRawStartIndex();
        model.rawEndIndex = segment.getRawEndIndex();
        model.summary = summarizeFixedEntries(segment.getEntries());
        if (includePromptDraft) {
            model.promptDraft = EpisodeSynthesisPromptFormatter.buildSegmentPromptDraft(index, segment).promptDraft();
        }
        if (segment.getSplitterSignals() != null) {
            model.observedActionTypes = segment.getSplitterSignals().getObservedActionTypes().stream()
                    .map(Enum::name)
                    .toList();
            SegmentIntentSignals intentSignals = segment.getSplitterSignals().getIntentSignals();
            if (intentSignals != null) {
                model.dominantIntent = nameOf(intentSignals.getDominantIntent());
                model.dominantScore = intentSignals.getDominantScore();
                model.runnerUpIntent = nameOf(intentSignals.getRunnerUpIntent());
                model.runnerUpScore = intentSignals.getRunnerUpScore();
                model.ambiguous = intentSignals.isAmbiguous();
            }
        }
        return model;
    }

    private static String summarizeFixedEntries(List<FixedActionSegmentEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "(empty)";
        }
        List<String> summary = new ArrayList<>();
        int limit = Math.min(4, entries.size());
        for (FixedActionSegmentEntry entry : entries) {
            if (entry == null || entry.getType() == null) {
                continue;
            }
            if (entry.getType().name().equals("ACQUIRE_ITEM")) {
                continue;
            }
            String label = entry.getType().name().toLowerCase(Locale.ROOT) + " x" + Math.max(1, entry.getCount());
            if (entry.getTarget() != null && !entry.getTarget().isBlank()) {
                label += " @ " + entry.getTarget().trim();
            }
            summary.add(label);
            if (summary.size() >= limit) {
                break;
            }
        }
        int visibleCount = 0;
        for (FixedActionSegmentEntry entry : entries) {
            if (entry != null && entry.getType() != null && !entry.getType().name().equals("ACQUIRE_ITEM")) {
                visibleCount++;
            }
        }
        if (visibleCount > limit) {
            summary.add("+" + (visibleCount - limit) + " more");
        }
        return summary.isEmpty() ? "(empty)" : String.join(" | ", summary);
    }

    private static List<FixedActionSegment> enrichSegmentsWithCurrentTurns(
            List<FixedActionSegment> segments,
            List<InteractionTurn> turns) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        if (turns == null || turns.isEmpty()) {
            return segments;
        }

        ArrayList<List<InteractionTurn>> turnBuckets = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            turnBuckets.add(new ArrayList<>());
        }

        for (InteractionTurn turn : turns) {
            if (turn == null) {
                continue;
            }
            int bucketIndex = findSegmentBucket(segments, turn.getTimestamp());
            if (bucketIndex >= 0) {
                turnBuckets.get(bucketIndex).add(turn);
            }
        }

        ArrayList<FixedActionSegment> enriched = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            FixedActionSegment segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            List<InteractionTurn> bucket = turnBuckets.get(i);
            if (bucket.isEmpty()) {
                enriched.add(segment);
                continue;
            }

            FixedActionSegmentContext context = segment.getContext();
            ArrayList<InteractionTurn> mergedTurns = new ArrayList<>(context.getInteractionTurns());
            mergedTurns.addAll(bucket);
            mergedTurns.sort(Comparator.comparingLong(InteractionTurn::getTimestamp));
            FixedActionSegmentContext nextContext = context.withMergedSnapshot(
                    context.getMergedContextSnapshot(),
                    List.copyOf(mergedTurns));
            enriched.add(segment.withContext(nextContext));
        }
        return List.copyOf(enriched);
    }

    private static int findSegmentBucket(List<FixedActionSegment> segments, long timestamp) {
        for (int i = 0; i < segments.size(); i++) {
            FixedActionSegment segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            if (timestamp >= segment.getStartTimestamp() && timestamp < segment.getEndTimestamp()) {
                return i;
            }
        }
        return -1;
    }

    private static void write(RuntimeEpisodeTimelineFileModel file) {
        Path path = getPath();
        try {
            file.normalize();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
            cachedFile = file;
            lastWriteAt = System.currentTimeMillis();
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY,
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeEpisodeTimeline] 写入失败：" + e.getMessage());
        }
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private static <T> List<T> tail(List<T> source, int maxCount) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (maxCount <= 0 || source.size() <= maxCount) {
            return source;
        }
        return source.subList(source.size() - maxCount, source.size());
    }

    public static final class RuntimeEpisodeTimelineFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public int fallbackQuietWindowSegments = EpisodeRuntimeSettings.getFallbackQuietWindowSegments();
        public int segmentCount;
        public int pendingMarkCount;
        public int splitPointCount;
        public int regionCount;
        public LinkedHashMap<String, RuntimeEpisodeReasonConfigModel> reasonConfigs = new LinkedHashMap<>();
        public List<RuntimeEpisodeSegmentModel> segments = new ArrayList<>();
        public List<RuntimeEpisodePendingMarkModel> pendingMarks = new ArrayList<>();
        public List<RuntimeEpisodeSplitPointModel> splitPoints = new ArrayList<>();
        public List<RuntimeEpisodeRegionModel> regions = new ArrayList<>();
        public RuntimeEpisodeUpdateModel lastUpdate = new RuntimeEpisodeUpdateModel();
        public RuntimeEpisodeSynthesisPreviewModel latestSynthesisPreview = new RuntimeEpisodeSynthesisPreviewModel();

        private void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            fallbackQuietWindowSegments = Math.max(1, fallbackQuietWindowSegments);
            if (reasonConfigs == null) {
                reasonConfigs = new LinkedHashMap<>();
            }
            if (segments == null) {
                segments = new ArrayList<>();
            }
            if (pendingMarks == null) {
                pendingMarks = new ArrayList<>();
            }
            if (splitPoints == null) {
                splitPoints = new ArrayList<>();
            }
            if (regions == null) {
                regions = new ArrayList<>();
            }
            if (lastUpdate == null) {
                lastUpdate = new RuntimeEpisodeUpdateModel();
            }
            lastUpdate.normalize();
            if (latestSynthesisPreview == null) {
                latestSynthesisPreview = new RuntimeEpisodeSynthesisPreviewModel();
            }
            latestSynthesisPreview.normalize();
        }
    }

    public static final class RuntimeEpisodeReasonConfigModel {
        public boolean highPriority;
        public int confirmDelaySegments;
        public int coverBeforeSegments;
        public int coverAfterSegments;

        public static RuntimeEpisodeReasonConfigModel from(EpisodeReasonRuntimeConfig config) {
            RuntimeEpisodeReasonConfigModel model = new RuntimeEpisodeReasonConfigModel();
            if (config == null) {
                return model;
            }
            model.highPriority = config.highPriority();
            model.confirmDelaySegments = config.confirmDelaySegments();
            model.coverBeforeSegments = config.coverBeforeSegments();
            model.coverAfterSegments = config.coverAfterSegments();
            return model;
        }
    }

    public static final class RuntimeEpisodeSegmentModel {
        public int index;
        public long startTimestamp;
        public long endTimestamp;
        public int rawStartIndex = -1;
        public int rawEndIndex = -1;
        public String summary = "";
        public String promptDraft = "";
        public String dominantIntent = "";
        public double dominantScore;
        public String runnerUpIntent = "";
        public double runnerUpScore;
        public boolean ambiguous;
        public List<String> observedActionTypes = new ArrayList<>();

        String signature() {
            return index + "|" + startTimestamp + "|" + endTimestamp + "|" + rawStartIndex + "|" + rawEndIndex;
        }
    }

    public static final class RuntimeEpisodePendingMarkModel {
        public int detectedAtSegmentIndex;
        public int splitSegmentIndex;
        public long splitTimestamp;
        public String reason = "";
        public int confirmAtSegmentIndex;
        public String ownerSplitterId = "";
        public boolean highPriority;
        public int coverBeforeSegments;
        public int coverAfterSegments;

        public static RuntimeEpisodePendingMarkModel from(EpisodePendingMark pendingMark) {
            RuntimeEpisodePendingMarkModel model = new RuntimeEpisodePendingMarkModel();
            model.detectedAtSegmentIndex = pendingMark.detectedAtSegmentIndex();
            model.splitSegmentIndex = pendingMark.splitSegmentIndex();
            model.splitTimestamp = pendingMark.splitTimestamp();
            model.reason = nameOf(pendingMark.reason());
            model.confirmAtSegmentIndex = pendingMark.confirmAtSegmentIndex();
            model.ownerSplitterId = pendingMark.ownerSplitterId();
            model.highPriority = pendingMark.highPriority();
            model.coverBeforeSegments = pendingMark.coverBeforeSegments();
            model.coverAfterSegments = pendingMark.coverAfterSegments();
            return model;
        }

        String signature() {
            return splitSegmentIndex + "|" + confirmAtSegmentIndex + "|" + reason + "|" + ownerSplitterId;
        }

        String summary() {
            return reason + "@split=" + splitSegmentIndex
                    + " confirm@" + confirmAtSegmentIndex
                    + " owner=" + ownerSplitterId;
        }
    }

    public static final class RuntimeEpisodeSplitPointModel {
        public int splitSegmentIndex;
        public long splitTimestamp;
        public int confirmationSegmentIndex;
        public long confirmationTimestamp;
        public String reason = "";
        public String previousPrimaryIntent = "";
        public String previousSecondaryIntent = "";
        public String nextPrimaryIntent = "";
        public String nextSecondaryIntent = "";

        public static RuntimeEpisodeSplitPointModel from(EpisodeSplitPoint splitPoint) {
            RuntimeEpisodeSplitPointModel model = new RuntimeEpisodeSplitPointModel();
            model.splitSegmentIndex = splitPoint.splitSegmentIndex();
            model.splitTimestamp = splitPoint.splitTimestamp();
            model.confirmationSegmentIndex = splitPoint.confirmationSegmentIndex();
            model.confirmationTimestamp = splitPoint.confirmationTimestamp();
            model.reason = nameOf(splitPoint.reason());
            model.previousPrimaryIntent = nameOf(splitPoint.previousPrimaryIntent());
            model.previousSecondaryIntent = nameOf(splitPoint.previousSecondaryIntent());
            model.nextPrimaryIntent = nameOf(splitPoint.nextPrimaryIntent());
            model.nextSecondaryIntent = nameOf(splitPoint.nextSecondaryIntent());
            return model;
        }

        String signature() {
            return splitSegmentIndex + "|" + splitTimestamp + "|" + reason;
        }

        String summary() {
            return reason + "@split=" + splitSegmentIndex
                    + " confirm@" + confirmationSegmentIndex;
        }
    }

    public static final class RuntimeEpisodeRegionModel {
        public int startSegmentIndex;
        public int endSegmentIndex;
        public long startTimestamp;
        public long endTimestamp;
        public int segmentCount;
        public String primaryIntent = "";
        public double primaryScore;
        public String secondaryIntent = "";
        public double secondaryScore;
        public Map<String, Double> aggregateIntentScores = new LinkedHashMap<>();

        public static RuntimeEpisodeRegionModel from(EpisodeRegion region) {
            RuntimeEpisodeRegionModel model = new RuntimeEpisodeRegionModel();
            model.startSegmentIndex = region.startSegmentIndex();
            model.endSegmentIndex = region.endSegmentIndex();
            model.startTimestamp = region.startTimestamp();
            model.endTimestamp = region.endTimestamp();
            model.segmentCount = region.segmentCount();
            model.primaryIntent = nameOf(region.primaryIntent());
            model.primaryScore = region.primaryScore();
            model.secondaryIntent = nameOf(region.secondaryIntent());
            model.secondaryScore = region.secondaryScore();
            for (PlayerIntent intent : PlayerIntent.values()) {
                double score = region.getScore(intent);
                if (score > 0.0) {
                    model.aggregateIntentScores.put(intent.name(), score);
                }
            }
            return model;
        }

        String signature() {
            return startSegmentIndex + "|" + endSegmentIndex + "|" + startTimestamp + "|" + endTimestamp;
        }

        String summary() {
            return "[" + startSegmentIndex + ".." + endSegmentIndex + "]"
                    + " primary=" + primaryIntent
                    + " " + String.format(Locale.ROOT, "%.2f", primaryScore);
        }
    }

    public static final class RuntimeEpisodeUpdateModel {
        public String updateKind = "";
        public int previousSegmentCount;
        public int currentSegmentCount;
        public List<String> summaryLines = new ArrayList<>();
        public List<String> addedPendingMarks = new ArrayList<>();
        public List<String> removedPendingMarks = new ArrayList<>();
        public List<String> addedSplitPoints = new ArrayList<>();
        public List<String> removedSplitPoints = new ArrayList<>();
        public List<String> addedRegions = new ArrayList<>();
        public List<String> removedRegions = new ArrayList<>();
        public List<String> changedReasonConfigs = new ArrayList<>();

        static RuntimeEpisodeUpdateModel diff(
                RuntimeEpisodeTimelineFileModel previous,
                RuntimeEpisodeTimelineFileModel current) {
            RuntimeEpisodeUpdateModel model = new RuntimeEpisodeUpdateModel();
            int previousSegments = previous == null ? 0 : previous.segmentCount;
            int currentSegments = current == null ? 0 : current.segmentCount;
            model.previousSegmentCount = previousSegments;
            model.currentSegmentCount = currentSegments;
            model.updateKind = resolveUpdateKind(previous, current);
            fillReasonConfigDiff(previous, current, model.changedReasonConfigs);

            fillDiff(
                    previous == null ? List.of() : previous.pendingMarks,
                    current == null ? List.of() : current.pendingMarks,
                    model.addedPendingMarks,
                    model.removedPendingMarks,
                    RuntimeEpisodePendingMarkModel::signature,
                    RuntimeEpisodePendingMarkModel::summary);
            fillDiff(
                    previous == null ? List.of() : previous.splitPoints,
                    current == null ? List.of() : current.splitPoints,
                    model.addedSplitPoints,
                    model.removedSplitPoints,
                    RuntimeEpisodeSplitPointModel::signature,
                    RuntimeEpisodeSplitPointModel::summary);
            fillDiff(
                    previous == null ? List.of() : previous.regions,
                    current == null ? List.of() : current.regions,
                    model.addedRegions,
                    model.removedRegions,
                    RuntimeEpisodeRegionModel::signature,
                    RuntimeEpisodeRegionModel::summary);

            model.summaryLines.add("segments: " + previousSegments + " -> " + currentSegments);
            if (!model.addedPendingMarks.isEmpty()) {
                model.summaryLines.add("added pending: " + String.join(" | ", model.addedPendingMarks));
            }
            if (!model.removedPendingMarks.isEmpty()) {
                model.summaryLines.add("removed pending: " + String.join(" | ", model.removedPendingMarks));
            }
            if (!model.addedSplitPoints.isEmpty()) {
                model.summaryLines.add("confirmed splits: " + String.join(" | ", model.addedSplitPoints));
            }
            if (!model.removedSplitPoints.isEmpty()) {
                model.summaryLines.add("removed splits: " + String.join(" | ", model.removedSplitPoints));
            }
            if (!model.addedRegions.isEmpty()) {
                model.summaryLines.add("added regions: " + String.join(" | ", model.addedRegions));
            }
            if (!model.removedRegions.isEmpty()) {
                model.summaryLines.add("removed regions: " + String.join(" | ", model.removedRegions));
            }
            if (!model.changedReasonConfigs.isEmpty()) {
                model.summaryLines.add("config changes: " + String.join(" | ", model.changedReasonConfigs));
            }
            if (model.summaryLines.size() == 1
                    && model.addedPendingMarks.isEmpty()
                    && model.removedPendingMarks.isEmpty()
                    && model.addedSplitPoints.isEmpty()
                    && model.removedSplitPoints.isEmpty()
                    && model.addedRegions.isEmpty()
                    && model.removedRegions.isEmpty()
                    && model.changedReasonConfigs.isEmpty()) {
                model.summaryLines.add("no structural changes");
            }
            model.normalize();
            return model;
        }

        private static <T> void fillDiff(
                List<T> previous,
                List<T> current,
                List<String> added,
                List<String> removed,
                SignatureFn<T> signatureFn,
                SummaryFn<T> summaryFn) {
            LinkedHashMap<String, String> previousMap = new LinkedHashMap<>();
            LinkedHashMap<String, String> currentMap = new LinkedHashMap<>();
            if (previous != null) {
                for (T item : previous) {
                    if (item == null) {
                        continue;
                    }
                    previousMap.put(signatureFn.signature(item), summaryFn.summary(item));
                }
            }
            if (current != null) {
                for (T item : current) {
                    if (item == null) {
                        continue;
                    }
                    currentMap.put(signatureFn.signature(item), summaryFn.summary(item));
                }
            }
            for (Map.Entry<String, String> entry : currentMap.entrySet()) {
                if (!previousMap.containsKey(entry.getKey())) {
                    added.add(entry.getValue());
                }
            }
            for (Map.Entry<String, String> entry : previousMap.entrySet()) {
                if (!currentMap.containsKey(entry.getKey())) {
                    removed.add(entry.getValue());
                }
            }
        }

        private static void fillReasonConfigDiff(
                RuntimeEpisodeTimelineFileModel previous,
                RuntimeEpisodeTimelineFileModel current,
                List<String> changedReasonConfigs) {
            if (changedReasonConfigs == null || current == null) {
                return;
            }
            int previousFallback = previous == null ? 0 : previous.fallbackQuietWindowSegments;
            if (previous != null && previousFallback != current.fallbackQuietWindowSegments) {
                changedReasonConfigs.add("fallbackQuietWindowSegments="
                        + previousFallback + "->" + current.fallbackQuietWindowSegments);
            }
            Map<String, RuntimeEpisodeReasonConfigModel> previousConfigs =
                    previous == null || previous.reasonConfigs == null ? Map.of() : previous.reasonConfigs;
            Map<String, RuntimeEpisodeReasonConfigModel> currentConfigs =
                    current.reasonConfigs == null ? Map.of() : current.reasonConfigs;
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            keys.addAll(previousConfigs.keySet());
            keys.addAll(currentConfigs.keySet());
            for (String key : keys) {
                RuntimeEpisodeReasonConfigModel left = previousConfigs.get(key);
                RuntimeEpisodeReasonConfigModel right = currentConfigs.get(key);
                if (sameReasonConfig(left, right)) {
                    continue;
                }
                changedReasonConfigs.add((key == null ? "(unknown)" : key) + "="
                        + describeReasonConfig(left) + "->" + describeReasonConfig(right));
            }
        }

        private static boolean sameReasonConfig(
                RuntimeEpisodeReasonConfigModel left,
                RuntimeEpisodeReasonConfigModel right) {
            if (left == right) {
                return true;
            }
            if (left == null || right == null) {
                return false;
            }
            return left.highPriority == right.highPriority
                    && left.confirmDelaySegments == right.confirmDelaySegments
                    && left.coverBeforeSegments == right.coverBeforeSegments
                    && left.coverAfterSegments == right.coverAfterSegments;
        }

        private static String describeReasonConfig(RuntimeEpisodeReasonConfigModel model) {
            if (model == null) {
                return "(missing)";
            }
            return (model.highPriority ? "high" : "low")
                    + "/d" + model.confirmDelaySegments
                    + "/c" + model.coverBeforeSegments
                    + ":" + model.coverAfterSegments;
        }

        private static String resolveUpdateKind(
                RuntimeEpisodeTimelineFileModel previous,
                RuntimeEpisodeTimelineFileModel current) {
            int previousSegments = previous == null ? 0 : previous.segmentCount;
            int currentSegments = current == null ? 0 : current.segmentCount;
            if (previousSegments == 0 && currentSegments == 0) {
                return "empty_snapshot";
            }
            if (previousSegments == 0) {
                return "initial_snapshot";
            }
            if (currentSegments == 0) {
                return "clear";
            }
            if (hasSegmentPrefix(previous, current) && currentSegments >= previousSegments) {
                return currentSegments > previousSegments ? "append" : "refresh";
            }
            return "replace";
        }

        private static boolean hasSegmentPrefix(
                RuntimeEpisodeTimelineFileModel previous,
                RuntimeEpisodeTimelineFileModel current) {
            if (previous == null || current == null) {
                return false;
            }
            if (previous.segments.size() > current.segments.size()) {
                return false;
            }
            for (int i = 0; i < previous.segments.size(); i++) {
                RuntimeEpisodeSegmentModel left = previous.segments.get(i);
                RuntimeEpisodeSegmentModel right = current.segments.get(i);
                if (left == null || right == null) {
                    if (left != right) {
                        return false;
                    }
                    continue;
                }
                if (!left.signature().equals(right.signature())) {
                    return false;
                }
            }
            return true;
        }

        private void normalize() {
            if (updateKind == null) {
                updateKind = "";
            }
            if (summaryLines == null) {
                summaryLines = new ArrayList<>();
            }
            if (addedPendingMarks == null) {
                addedPendingMarks = new ArrayList<>();
            }
            if (removedPendingMarks == null) {
                removedPendingMarks = new ArrayList<>();
            }
            if (addedSplitPoints == null) {
                addedSplitPoints = new ArrayList<>();
            }
            if (removedSplitPoints == null) {
                removedSplitPoints = new ArrayList<>();
            }
            if (addedRegions == null) {
                addedRegions = new ArrayList<>();
            }
            if (removedRegions == null) {
                removedRegions = new ArrayList<>();
            }
            if (changedReasonConfigs == null) {
                changedReasonConfigs = new ArrayList<>();
            }
        }
    }

    public static final class RuntimeEpisodeSynthesisPreviewModel {
        public boolean available;
        public int regionStartSegmentIndex;
        public int regionEndSegmentIndex;
        public long regionStartTimestamp;
        public long regionEndTimestamp;
        public String regionPromptDraft = "";
        public List<RuntimeEpisodeSegmentPromptDraftModel> segmentDrafts = new ArrayList<>();

        static RuntimeEpisodeSynthesisPreviewModel fromMaterial(EpisodeSynthesisMaterial material) {
            RuntimeEpisodeSynthesisPreviewModel model = new RuntimeEpisodeSynthesisPreviewModel();
            if (material == null) {
                return model;
            }
            model.available = true;
            model.regionStartSegmentIndex = material.signal().regionStartSegmentIndex();
            model.regionEndSegmentIndex = material.signal().regionEndSegmentIndex();
            model.regionStartTimestamp = material.signal().regionStartTimestamp();
            model.regionEndTimestamp = material.signal().regionEndTimestamp();
            List<EpisodeSegmentPromptDraft> segmentPromptDrafts = material.buildSegmentPromptDrafts();
            model.regionPromptDraft = material.buildRegionPromptDraft();
            for (EpisodeSegmentPromptDraft draft : segmentPromptDrafts) {
                model.segmentDrafts.add(RuntimeEpisodeSegmentPromptDraftModel.from(draft));
            }
            return model;
        }

        static RuntimeEpisodeSynthesisPreviewModel copyOf(RuntimeEpisodeSynthesisPreviewModel source) {
            RuntimeEpisodeSynthesisPreviewModel copy = new RuntimeEpisodeSynthesisPreviewModel();
            if (source == null) {
                return copy;
            }
            copy.available = source.available;
            copy.regionStartSegmentIndex = source.regionStartSegmentIndex;
            copy.regionEndSegmentIndex = source.regionEndSegmentIndex;
            copy.regionStartTimestamp = source.regionStartTimestamp;
            copy.regionEndTimestamp = source.regionEndTimestamp;
            copy.regionPromptDraft = source.regionPromptDraft != null ? source.regionPromptDraft : "";
            copy.segmentDrafts = source.segmentDrafts == null ? new ArrayList<>() : new ArrayList<>(source.segmentDrafts);
            return copy;
        }

        private void normalize() {
            if (regionPromptDraft == null) {
                regionPromptDraft = "";
            }
            if (segmentDrafts == null) {
                segmentDrafts = new ArrayList<>();
            }
        }
    }

    public static final class RuntimeEpisodeSegmentPromptDraftModel {
        public int segmentIndex;
        public long startTimestamp;
        public long endTimestamp;
        public List<String> timelineLines = new ArrayList<>();
        public String promptDraft = "";

        static RuntimeEpisodeSegmentPromptDraftModel from(EpisodeSegmentPromptDraft draft) {
            RuntimeEpisodeSegmentPromptDraftModel model = new RuntimeEpisodeSegmentPromptDraftModel();
            if (draft == null) {
                return model;
            }
            model.segmentIndex = draft.segmentIndex();
            model.startTimestamp = draft.startTimestamp();
            model.endTimestamp = draft.endTimestamp();
            model.timelineLines = new ArrayList<>(draft.timelineLines());
            model.promptDraft = draft.promptDraft();
            return model;
        }
    }

    @FunctionalInterface
    private interface SignatureFn<T> {
        String signature(T value);
    }

    @FunctionalInterface
    private interface SummaryFn<T> {
        String summary(T value);
    }
}
