package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionTimelineRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.MovingActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.MovingActionSegmentEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.PlayerIntentScorer;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.SegmentIntentSignals;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 将 player-action 正式输出写入 Studio workspace。
 */
public final class StudioRuntimeActionTimelineFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_action_timeline.json";
    public static final String VERSION_KEY = "runtimeActionTimeline";
    public static final int SCHEMA_VERSION = 3;
    private static final long MIN_WRITE_INTERVAL_MS = 750L;
    private static final long ASYNC_DEBOUNCE_MS = 100L;
    private static final int MAX_RENDERED_FIXED_SEGMENTS = 24;
    private static final int MAX_RENDERED_FIXED_ENTRIES_PER_SEGMENT = 20;
    private static final int MAX_RENDERED_MOVING_ENTRIES = 20;
    private static final int MAX_RENDERED_DETAIL_TAGS_PER_ENTRY = 8;

    private static final Object WRITE_LOCK = new Object();
    private static final ScheduledExecutorService WRITE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "TalkingWeapon-StudioActionRuntime");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static long lastWriteAt = 0L;
    private static ScheduledFuture<?> pendingWrite;
    private static AgentMemory pendingMemory;

    private StudioRuntimeActionTimelineFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeActionTimelineFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return new RuntimeActionTimelineFileModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeActionTimelineFileModel file = GSON.fromJson(reader, RuntimeActionTimelineFileModel.class);
            return file != null ? file : new RuntimeActionTimelineFileModel();
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionTimeline] 读取失败：" + e.getMessage());
            return new RuntimeActionTimelineFileModel();
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
                    StudioRuntimeActionTimelineFile::flushPendingSnapshot,
                    nextWriteDelayMs(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public static synchronized void writeCurrentSnapshot(AgentMemory memory) {
        RuntimeActionTimelineFileModel file = build(memory);
        Path path = getPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
            lastWriteAt = System.currentTimeMillis();
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY,
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionTimeline] 写入失败：" + e.getMessage());
        }
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
                        StudioRuntimeActionTimelineFile::flushPendingSnapshot,
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

    private static RuntimeActionTimelineFileModel build(AgentMemory memory) {
        RuntimeActionTimelineFileModel file = new RuntimeActionTimelineFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        file.fixedSegmentSeconds = ActionTimelineRuntimeSettings.getFixedSegmentSeconds();
        file.movingWindowSeconds = ActionTimelineRuntimeSettings.getMovingWindowSeconds();
        file.registeredLocationLeaveExtraBlocks = ActionTimelineRuntimeSettings.getRegisteredLocationLeaveExtraBlocks();
        file.majorStructureEnterRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureEnterRadiusBlocks();
        file.majorStructureExitRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureExitRadiusBlocks();
        file.majorStructureWhitelist = new ArrayList<>(ActionTimelineRuntimeSettings.getMajorStructureWhitelist());

        if (memory == null) {
            return file;
        }

        List<FixedActionSegment> archiveFixedSegments =
                memory.getActionArchive().snapshotFixedSegments(ActionTimelineRuntimeSettings.getFixedSegmentMs());
        List<FixedActionSegment> fixedSegments = tail(archiveFixedSegments, MAX_RENDERED_FIXED_SEGMENTS);
        int visibleSegmentStartIndex = archiveFixedSegments.size() - fixedSegments.size();
        List<SegmentIntentSignals> analyses = fixedSegments.stream()
                .map(StudioRuntimeActionTimelineFile::intentSignalsOf)
                .toList();
        MovingActionSegment movingWindow = memory.getMovingActionBuffer().snapshotPromptWindowSegment();
        SegmentIntentSignals movingAnalysis = movingWindow == null || movingWindow.getEntries().isEmpty()
                ? null
                : PlayerIntentScorer.analyze(movingWindow);

        file.rawActionCount = memory.getActionArchive().size();
        file.fixedSegmentCount = archiveFixedSegments.size();

        int count = Math.max(fixedSegments.size(), analyses.size());
        for (int i = 0; i < count; i++) {
            FixedActionSegment segment = i < fixedSegments.size() ? fixedSegments.get(i) : null;
            SegmentIntentSignals analysis = i < analyses.size() ? analyses.get(i) : null;
            file.fixedSegments.add(toFixedSegmentModel(
                    visibleSegmentStartIndex + i,
                    segment,
                    analysis));
        }

        file.movingWindow = toMovingWindowModel(movingWindow, movingAnalysis);
        return file;
    }

    private static RuntimeFixedSegmentModel toFixedSegmentModel(
            int index,
            FixedActionSegment segment,
            SegmentIntentSignals analysis) {
        RuntimeFixedSegmentModel model = new RuntimeFixedSegmentModel();
        model.index = index;
        if (segment != null) {
            model.startTimestamp = segment.getStartTimestamp();
            model.endTimestamp = segment.getEndTimestamp();
            model.rawStartIndex = segment.getRawStartIndex();
            model.rawEndIndex = segment.getRawEndIndex();
            model.entries = toFixedEntries(segment.getEntries());
            model.summary = summarizeFixedEntries(segment.getEntries());
            if (segment.getSplitterSignals() != null) {
                model.observedActionTypes = segment.getSplitterSignals().getObservedActionTypes().stream()
                        .map(Enum::name)
                        .toList();
            }
            model.boundaryActions = summarizeBoundaryActions(segment);
        }
        fillIntentFields(model, analysis);
        return model;
    }

    private static RuntimeMovingWindowModel toMovingWindowModel(
            MovingActionSegment segment,
            SegmentIntentSignals analysis) {
        RuntimeMovingWindowModel model = new RuntimeMovingWindowModel();
        if (segment == null) {
            return model;
        }
        model.startTimestamp = segment.getStartTimestamp();
        model.endTimestamp = segment.getEndTimestamp();
        model.open = segment.isOpen();
        model.entries = toMovingEntries(segment.getEntries());
        model.summary = summarizeMovingEntries(segment.getEntries());
        fillIntentFields(model, analysis);
        return model;
    }

    private static void fillIntentFields(RuntimeIntentCarrier model, SegmentIntentSignals analysis) {
        if (model == null) {
            return;
        }
        model.setHeadline(formatIntentHeadline(analysis));
        if (analysis == null) {
            return;
        }
        model.setDominantIntent(nameOf(analysis.getDominantIntent()));
        model.setDominantScore(analysis.getDominantScore());
        model.setRunnerUpIntent(nameOf(analysis.getRunnerUpIntent()));
        model.setRunnerUpScore(analysis.getRunnerUpScore());
        model.setAmbiguous(analysis.isAmbiguous());

        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> reasons = new LinkedHashMap<>();
        for (PlayerIntent intent : analysis.getRankedIntents()) {
            if (intent == null) {
                continue;
            }
            scores.put(intent.name(), analysis.getScore(intent));
            reasons.put(intent.name(), new ArrayList<>(analysis.getReasons(intent)));
        }
        model.setScores(scores);
        model.setReasons(reasons);
    }

    private static List<RuntimeActionEntryModel> toFixedEntries(List<FixedActionSegmentEntry> entries) {
        List<RuntimeActionEntryModel> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        int limit = Math.min(entries.size(), MAX_RENDERED_FIXED_ENTRIES_PER_SEGMENT);
        for (int i = 0; i < limit; i++) {
            FixedActionSegmentEntry entry = entries.get(i);
            if (entry == null) {
                continue;
            }
            RuntimeActionEntryModel model = new RuntimeActionEntryModel();
            model.type = nameOf(entry.getType());
            model.target = entry.getTarget() != null ? entry.getTarget() : "";
            model.count = entry.getCount();
            model.firstTimestamp = entry.getFirstTimestamp();
            model.lastTimestamp = entry.getLastTimestamp();
            model.rawStartIndex = entry.getRawStartIndex();
            model.rawEndIndex = entry.getRawEndIndex();
            model.detailTags = toDetailTags(entry.getDetailTags());
            result.add(model);
        }
        return result;
    }

    private static List<RuntimeActionEntryModel> toMovingEntries(List<MovingActionSegmentEntry> entries) {
        List<RuntimeActionEntryModel> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        int limit = Math.min(entries.size(), MAX_RENDERED_MOVING_ENTRIES);
        for (int i = 0; i < limit; i++) {
            MovingActionSegmentEntry entry = entries.get(i);
            if (entry == null) {
                continue;
            }
            RuntimeActionEntryModel model = new RuntimeActionEntryModel();
            model.type = nameOf(entry.getType());
            model.target = entry.isFolded() ? "" : (entry.getTarget() != null ? entry.getTarget() : "");
            model.count = entry.getCount();
            model.firstTimestamp = entry.getFirstTimestamp();
            model.lastTimestamp = entry.getLastTimestamp();
            model.rawStartIndex = -1;
            model.rawEndIndex = -1;
            model.detailTags = toDetailTags(entry.getDetailTags());
            model.folded = entry.isFolded();
            model.foldedTargets = entry.getFoldedTargets() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(entry.getFoldedTargets());
            result.add(model);
        }
        return result;
    }

    private static List<String> toDetailTags(Iterable<DetailTag> detailTags) {
        List<String> result = new ArrayList<>();
        if (detailTags == null) {
            return result;
        }
        for (DetailTag detailTag : detailTags) {
            if (detailTag != null) {
                result.add(detailTag.toString());
                if (result.size() >= MAX_RENDERED_DETAIL_TAGS_PER_ENTRY) {
                    break;
                }
            }
        }
        return result;
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

    private static String summarizeFixedEntries(List<FixedActionSegmentEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "(empty)";
        }
        List<String> parts = new ArrayList<>();
        for (FixedActionSegmentEntry entry : entries) {
            if (entry == null || entry.getType() == null) {
                continue;
            }
            parts.add(shortActionLabel(entry.getType(), entry.getCount(), entry.getTarget()));
            if (parts.size() >= 4) {
                break;
            }
        }
        return parts.isEmpty() ? "(empty)" : String.join(" · ", parts);
    }

    private static String summarizeMovingEntries(List<MovingActionSegmentEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "(empty)";
        }
        List<String> parts = new ArrayList<>();
        for (MovingActionSegmentEntry entry : entries) {
            if (entry == null || entry.getType() == null) {
                continue;
            }
            parts.add(shortActionLabel(
                    entry.getType(),
                    entry.getCount(),
                    entry.isFolded() ? String.join(",", entry.getFoldedTargets()) : entry.getTarget()));
            if (parts.size() >= 4) {
                break;
            }
        }
        return parts.isEmpty() ? "(empty)" : String.join(" · ", parts);
    }

    private static List<String> summarizeBoundaryActions(FixedActionSegment segment) {
        if (segment == null || segment.getEntries() == null || segment.getEntries().isEmpty()) {
            return List.of();
        }

        ArrayList<String> lines = new ArrayList<>(4);
        appendBoundaryAction(lines, "enter registered location",
                collectTargets(segment.getEntries(), ActionType.ENTER_REGISTERED_LOCATION));
        appendBoundaryAction(lines, "leave registered location",
                collectTargets(segment.getEntries(), ActionType.LEAVE_REGISTERED_LOCATION));
        appendBoundaryAction(lines, "enter major structure",
                collectTargets(segment.getEntries(), ActionType.ENTER_MAJOR_STRUCTURE));
        appendBoundaryAction(lines, "exit major structure",
                collectTargets(segment.getEntries(), ActionType.EXIT_MAJOR_STRUCTURE));
        return lines.isEmpty() ? List.of() : lines;
    }

    private static void appendBoundaryAction(List<String> lines, String label, List<String> targets) {
        if (lines == null || label == null || targets == null || targets.isEmpty()) {
            return;
        }
        lines.add(label + ": " + String.join(", ", targets));
    }

    private static List<String> collectTargets(List<FixedActionSegmentEntry> entries, ActionType type) {
        if (entries == null || entries.isEmpty() || type == null) {
            return List.of();
        }

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (FixedActionSegmentEntry entry : entries) {
            if (entry == null || type != entry.getType()) {
                continue;
            }
            String target = entry.getTarget();
            if (target == null) {
                continue;
            }
            String normalized = target.trim();
            if (!normalized.isEmpty()) {
                targets.add(normalized);
            }
        }
        return targets.isEmpty() ? List.of() : List.copyOf(targets);
    }

    private static String shortActionLabel(ActionType type, int count, String target) {
        String typeLabel = type == null ? "UNKNOWN" : type.name().toLowerCase(Locale.ROOT);
        String normalizedTarget = target == null ? "" : target.trim();
        if (normalizedTarget.isEmpty()) {
            return typeLabel + " x" + Math.max(1, count);
        }
        return typeLabel + " x" + Math.max(1, count) + " @ " + normalizedTarget;
    }

    private static String formatIntentHeadline(SegmentIntentSignals analysis) {
        if (analysis == null || !analysis.hasSignal()) {
            return "no clear intent";
        }
        String headline = analysis.getDominantIntent().name() + " " + formatScore(analysis.getDominantScore());
        if (analysis.getRunnerUpIntent() != null && analysis.getRunnerUpScore() > 0.0) {
            headline += " | " + analysis.getRunnerUpIntent().name() + " " + formatScore(analysis.getRunnerUpScore());
        }
        if (analysis.isAmbiguous()) {
            headline += " | ambiguous";
        }
        return headline;
    }

    private static String formatScore(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String nameOf(Enum<?> value) {
        return value != null ? value.name() : "";
    }

    private static SegmentIntentSignals intentSignalsOf(FixedActionSegment segment) {
        if (segment == null || segment.getSplitterSignals() == null) {
            return SegmentIntentSignals.empty();
        }
        return segment.getSplitterSignals().getIntentSignals();
    }

    public static final class RuntimeActionTimelineFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public int fixedSegmentSeconds = ActionTimelineRuntimeSettings.getFixedSegmentSeconds();
        public int movingWindowSeconds = ActionTimelineRuntimeSettings.getMovingWindowSeconds();
        public int registeredLocationLeaveExtraBlocks = ActionTimelineRuntimeSettings.getRegisteredLocationLeaveExtraBlocks();
        public int majorStructureEnterRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureEnterRadiusBlocks();
        public int majorStructureExitRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureExitRadiusBlocks();
        public List<String> majorStructureWhitelist =
                new ArrayList<>(ActionTimelineRuntimeSettings.getMajorStructureWhitelist());
        public int rawActionCount;
        public int fixedSegmentCount;
        public List<RuntimeFixedSegmentModel> fixedSegments = new ArrayList<>();
        public RuntimeMovingWindowModel movingWindow = new RuntimeMovingWindowModel();
    }

    private interface RuntimeIntentCarrier {
        void setHeadline(String headline);

        void setDominantIntent(String dominantIntent);

        void setDominantScore(double dominantScore);

        void setRunnerUpIntent(String runnerUpIntent);

        void setRunnerUpScore(double runnerUpScore);

        void setAmbiguous(boolean ambiguous);

        void setScores(Map<String, Double> scores);

        void setReasons(Map<String, List<String>> reasons);
    }

    public static final class RuntimeFixedSegmentModel implements RuntimeIntentCarrier {
        public int index;
        public long startTimestamp;
        public long endTimestamp;
        public int rawStartIndex = -1;
        public int rawEndIndex = -1;
        public String headline = "";
        public String summary = "";
        public String dominantIntent = "";
        public double dominantScore;
        public String runnerUpIntent = "";
        public double runnerUpScore;
        public boolean ambiguous;
        public Map<String, Double> scores = new LinkedHashMap<>();
        public Map<String, List<String>> reasons = new LinkedHashMap<>();
        public List<String> observedActionTypes = new ArrayList<>();
        public List<String> boundaryActions = new ArrayList<>();
        public List<RuntimeActionEntryModel> entries = new ArrayList<>();

        @Override
        public void setHeadline(String headline) {
            this.headline = headline != null ? headline : "";
        }

        @Override
        public void setDominantIntent(String dominantIntent) {
            this.dominantIntent = dominantIntent != null ? dominantIntent : "";
        }

        @Override
        public void setDominantScore(double dominantScore) {
            this.dominantScore = dominantScore;
        }

        @Override
        public void setRunnerUpIntent(String runnerUpIntent) {
            this.runnerUpIntent = runnerUpIntent != null ? runnerUpIntent : "";
        }

        @Override
        public void setRunnerUpScore(double runnerUpScore) {
            this.runnerUpScore = runnerUpScore;
        }

        @Override
        public void setAmbiguous(boolean ambiguous) {
            this.ambiguous = ambiguous;
        }

        @Override
        public void setScores(Map<String, Double> scores) {
            this.scores = scores != null ? scores : new LinkedHashMap<>();
        }

        @Override
        public void setReasons(Map<String, List<String>> reasons) {
            this.reasons = reasons != null ? reasons : new LinkedHashMap<>();
        }
    }

    public static final class RuntimeMovingWindowModel implements RuntimeIntentCarrier {
        public long startTimestamp;
        public long endTimestamp;
        public boolean open;
        public String headline = "";
        public String summary = "";
        public String dominantIntent = "";
        public double dominantScore;
        public String runnerUpIntent = "";
        public double runnerUpScore;
        public boolean ambiguous;
        public Map<String, Double> scores = new LinkedHashMap<>();
        public Map<String, List<String>> reasons = new LinkedHashMap<>();
        public List<RuntimeActionEntryModel> entries = new ArrayList<>();

        @Override
        public void setHeadline(String headline) {
            this.headline = headline != null ? headline : "";
        }

        @Override
        public void setDominantIntent(String dominantIntent) {
            this.dominantIntent = dominantIntent != null ? dominantIntent : "";
        }

        @Override
        public void setDominantScore(double dominantScore) {
            this.dominantScore = dominantScore;
        }

        @Override
        public void setRunnerUpIntent(String runnerUpIntent) {
            this.runnerUpIntent = runnerUpIntent != null ? runnerUpIntent : "";
        }

        @Override
        public void setRunnerUpScore(double runnerUpScore) {
            this.runnerUpScore = runnerUpScore;
        }

        @Override
        public void setAmbiguous(boolean ambiguous) {
            this.ambiguous = ambiguous;
        }

        @Override
        public void setScores(Map<String, Double> scores) {
            this.scores = scores != null ? scores : new LinkedHashMap<>();
        }

        @Override
        public void setReasons(Map<String, List<String>> reasons) {
            this.reasons = reasons != null ? reasons : new LinkedHashMap<>();
        }
    }

    public static final class RuntimeActionEntryModel {
        public String type = "";
        public String target = "";
        public int count;
        public long firstTimestamp;
        public long lastTimestamp;
        public int rawStartIndex = -1;
        public int rawEndIndex = -1;
        public List<String> detailTags = new ArrayList<>();
        public boolean folded;
        public List<String> foldedTargets = new ArrayList<>();
    }
}
