package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionTimelineRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedSegmentDiscardWindow;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedSegmentBufferRuntimeState;
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
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/**
 * 将 player-action 正式输出写入 Studio workspace。
 */
public final class StudioRuntimeActionTimelineFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_action_timeline.json";
    public static final String VERSION_KEY = "runtimeActionTimeline";
    public static final int SCHEMA_VERSION = 8;
    private static final long MIN_WRITE_INTERVAL_MS = 750L;

    private static long lastWriteAt = 0L;
    private static RuntimeActionTimelineFileModel cachedFile;

    private StudioRuntimeActionTimelineFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeActionTimelineFileModel readOrEmpty() {
        if (cachedFile != null) {
            return cachedFile;
        }
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            cachedFile = new RuntimeActionTimelineFileModel();
            cachedFile.normalize();
            return cachedFile;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeActionTimelineFileModel file = GSON.fromJson(reader, RuntimeActionTimelineFileModel.class);
            if (file == null) {
                file = new RuntimeActionTimelineFileModel();
            }
            file.normalize();
            cachedFile = file;
            return cachedFile;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionTimeline] 读取失败：" + e.getMessage());
            cachedFile = new RuntimeActionTimelineFileModel();
            cachedFile.normalize();
            return cachedFile;
        }
    }

    public static synchronized boolean recordSnapshot(AgentMemory memory) {
        long now = System.currentTimeMillis();
        if (now - lastWriteAt < MIN_WRITE_INTERVAL_MS) {
            return false;
        }
        writeCurrentSnapshot(memory);
        return true;
    }

    public static synchronized void writeCurrentSnapshot(AgentMemory memory) {
        RuntimeActionTimelineFileModel previous = readOrEmpty();
        RuntimeActionTimelineFileModel file = build(memory, previous);
        boolean externalizedSegments = StudioRuntimeActionSegmentStoreFile.writeCurrentSegments(
                file.fixedSegmentSequenceToken,
                file.fixedSegments);
        if (externalizedSegments) {
            file.fixedSegments = new ArrayList<>();
        }
        StudioRuntimeActionDiscardLogFile.syncCurrentDiscardWindows(file.discardWindows);
        write(file);
    }

    private static synchronized void write(RuntimeActionTimelineFileModel file) {
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
            System.err.println("[TalkingWeapon/StudioRuntimeActionTimeline] 写入失败：" + e.getMessage());
        }
    }

    public static synchronized FixedSegmentBufferRuntimeState readPersistedFixedSegmentRuntimeState() {
        RuntimeActionTimelineFileModel file = readOrEmpty();
        if (file == null || file.runtimeState == null) {
            return FixedSegmentBufferRuntimeState.empty();
        }
        file.normalize();
        List<RuntimeFixedSegmentModel> fixedSegments = StudioRuntimeActionSegmentStoreFile.readFixedSegmentsOrEmpty();
        return file.runtimeState.toRuntimeState(
                fixedSegments,
                StudioRuntimeActionDiscardLogFile.readDiscardWindowsOrEmpty());
    }

    private static RuntimeActionTimelineFileModel build(
            AgentMemory memory,
            RuntimeActionTimelineFileModel previous) {
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

        FixedSegmentBufferRuntimeState bufferRuntimeState =
                memory.getActionArchive().snapshotFixedSegmentRuntimeState(ActionTimelineRuntimeSettings.getFixedSegmentMs());
        List<FixedActionSegment> fixedSegments = bufferRuntimeState.getSealedSegments();
        file.fixedSegmentSequenceToken = fixedSegmentSequenceTokenOf(fixedSegments);
        MovingActionSegment movingWindow = memory.getMovingActionBuffer().snapshotPromptWindowSegment();
        SegmentIntentSignals movingAnalysis = movingWindow == null || movingWindow.getEntries().isEmpty()
                ? null
                : PlayerIntentScorer.analyze(movingWindow);

        file.rawActionCount = memory.getActionArchive().size();
        file.fixedSegmentCount = fixedSegments.size();
        if (previous != null
                && Objects.equals(previous.fixedSegmentSequenceToken, file.fixedSegmentSequenceToken)) {
            file.fixedSegments = StudioRuntimeActionSegmentStoreFile.readFixedSegmentsViewOrEmpty();
        } else {
            List<RuntimeFixedSegmentModel> previousFixedSegmentModels =
                    StudioRuntimeActionSegmentStoreFile.readFixedSegmentsViewOrEmpty();
            file.fixedSegments = buildFixedSegmentModels(
                    fixedSegments,
                    previousFixedSegmentModels);
        }
        file.runtimeState = RuntimeFixedSegmentBufferStateModel.from(bufferRuntimeState);
        file.discardWindows = bufferRuntimeState.getDiscardWindows();

        file.movingWindow = toMovingWindowModel(movingWindow, movingAnalysis);
        file.normalize();
        return file;
    }

    private static List<RuntimeFixedSegmentModel> buildFixedSegmentModels(
            List<FixedActionSegment> fixedSegments,
            List<RuntimeFixedSegmentModel> previousModels) {
        if (fixedSegments == null || fixedSegments.isEmpty()) {
            return new ArrayList<>();
        }

        List<RuntimeFixedSegmentModel> safePrevious = previousModels == null ? List.of() : previousModels;
        int sharedPrefix = 0;
        int limit = Math.min(fixedSegments.size(), safePrevious.size());
        while (sharedPrefix < limit
                && canReuseFixedSegmentModel(safePrevious.get(sharedPrefix), fixedSegments.get(sharedPrefix), sharedPrefix)) {
            sharedPrefix++;
        }

        ArrayList<RuntimeFixedSegmentModel> models = new ArrayList<>(fixedSegments.size());
        for (int i = 0; i < sharedPrefix; i++) {
            models.add(safePrevious.get(i));
        }
        for (int i = sharedPrefix; i < fixedSegments.size(); i++) {
            FixedActionSegment segment = fixedSegments.get(i);
            models.add(toFixedSegmentModel(i, segment, intentSignalsOf(segment)));
        }
        return models;
    }

    private static boolean canReuseFixedSegmentModel(
            RuntimeFixedSegmentModel previous,
            FixedActionSegment current,
            int expectedIndex) {
        if (previous == null || current == null) {
            return false;
        }
        return previous.index == expectedIndex
                && sameStableIdentity(previous, current);
    }

    private static String fixedSegmentSequenceTokenOf(List<FixedActionSegment> fixedSegments) {
        if (fixedSegments == null || fixedSegments.isEmpty()) {
            return "empty";
        }
        FixedActionSegment first = fixedSegments.get(0);
        FixedActionSegment last = fixedSegments.get(fixedSegments.size() - 1);
        return fixedSegments.size()
                + "|" + segmentIdentityTokenOf(first)
                + "|" + segmentIdentityTokenOf(last);
    }

    private static String segmentIdentityTokenOf(FixedActionSegment segment) {
        if (segment == null) {
            return "null";
        }
        return segment.stableIdentityToken();
    }

    private static boolean sameStableIdentity(
            RuntimeFixedSegmentModel previous,
            FixedActionSegment current) {
        if (previous == null || current == null) {
            return false;
        }
        if (previous.segmentId > 0L && current.hasAssignedSegmentId()) {
            return previous.segmentId == current.getSegmentId()
                    && previous.startTimestamp == current.getStartTimestamp()
                    && previous.endTimestamp == current.getEndTimestamp();
        }
        return previous.startTimestamp == current.getStartTimestamp()
                && previous.endTimestamp == current.getEndTimestamp()
                && previous.rawStartIndex == current.getRawStartIndex()
                && previous.rawEndIndex == current.getRawEndIndex();
    }

    private static RuntimeFixedSegmentModel toFixedSegmentModel(
            int index,
            FixedActionSegment segment,
            SegmentIntentSignals analysis) {
        RuntimeFixedSegmentModel model = new RuntimeFixedSegmentModel();
        model.index = index;
        if (segment != null) {
            model.segmentId = segment.getSegmentId();
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
        for (FixedActionSegmentEntry entry : entries) {
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
        for (MovingActionSegmentEntry entry : entries) {
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
            }
        }
        return result;
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
        public String fixedSegmentSequenceToken = "";
        public transient List<RuntimeFixedSegmentModel> fixedSegments = new ArrayList<>();
        public transient List<FixedSegmentDiscardWindow> discardWindows = new ArrayList<>();
        public RuntimeFixedSegmentBufferStateModel runtimeState = new RuntimeFixedSegmentBufferStateModel();
        public RuntimeMovingWindowModel movingWindow = new RuntimeMovingWindowModel();

        public void normalize() {
            fixedSegmentSequenceToken = fixedSegmentSequenceToken == null ? "" : fixedSegmentSequenceToken;
            majorStructureWhitelist = majorStructureWhitelist == null ? new ArrayList<>() : new ArrayList<>(majorStructureWhitelist);
            fixedSegments = fixedSegments == null ? new ArrayList<>() : new ArrayList<>(fixedSegments);
            discardWindows = discardWindows == null ? new ArrayList<>() : new ArrayList<>(discardWindows);
            for (RuntimeFixedSegmentModel fixedSegment : fixedSegments) {
                if (fixedSegment != null) {
                    fixedSegment.normalize();
                }
            }
            runtimeState = runtimeState == null ? new RuntimeFixedSegmentBufferStateModel() : runtimeState;
            runtimeState.normalize();
            movingWindow = movingWindow == null ? new RuntimeMovingWindowModel() : movingWindow;
            movingWindow.normalize();
        }
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
        public long segmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
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

        public void normalize() {
            headline = headline == null ? "" : headline;
            summary = summary == null ? "" : summary;
            dominantIntent = dominantIntent == null ? "" : dominantIntent;
            runnerUpIntent = runnerUpIntent == null ? "" : runnerUpIntent;
            scores = scores == null ? new LinkedHashMap<>() : new LinkedHashMap<>(scores);
            reasons = reasons == null ? new LinkedHashMap<>() : new LinkedHashMap<>(reasons);
            observedActionTypes = observedActionTypes == null ? new ArrayList<>() : new ArrayList<>(observedActionTypes);
            boundaryActions = boundaryActions == null ? new ArrayList<>() : new ArrayList<>(boundaryActions);
            entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
            for (RuntimeActionEntryModel entry : entries) {
                if (entry != null) {
                    entry.normalize();
                }
            }
        }

        public FixedActionSegment toFixedActionSegment(FixedActionSegmentContext context) {
            return new FixedActionSegment(
                    segmentId,
                    startTimestamp,
                    endTimestamp,
                    rawStartIndex,
                    rawEndIndex,
                    restoreFixedEntries(entries),
                    null,
                    context == null ? FixedActionSegmentContext.empty() : context);
        }

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

    public static final class RuntimeFixedSegmentBufferStateModel {
        public long fixedSegmentMs = -1L;
        public long nextSegmentId = 1L;
        public Map<Long, FixedActionSegmentContext> sealedSegmentContexts = new LinkedHashMap<>();
        public RuntimePersistedFixedSegmentModel openSegment;

        public static RuntimeFixedSegmentBufferStateModel from(FixedSegmentBufferRuntimeState runtimeState) {
            RuntimeFixedSegmentBufferStateModel model = new RuntimeFixedSegmentBufferStateModel();
            if (runtimeState == null || runtimeState.isEmpty()) {
                return model;
            }
            model.fixedSegmentMs = runtimeState.getFixedSegmentMs();
            model.nextSegmentId = runtimeState.getNextSegmentId();
            if (runtimeState.getSealedSegments() != null) {
                for (FixedActionSegment segment : runtimeState.getSealedSegments()) {
                    if (segment == null
                            || segment.getSegmentId() <= 0L
                            || segment.getContext() == null
                            || !segment.getContext().hasAnyContext()) {
                        continue;
                    }
                    model.sealedSegmentContexts.put(segment.getSegmentId(), segment.getContext());
                }
            }
            if (runtimeState.getOpenSegment() != null) {
                model.openSegment = RuntimePersistedFixedSegmentModel.from(runtimeState.getOpenSegment());
            }
            model.normalize();
            return model;
        }

        public void normalize() {
            sealedSegmentContexts = sealedSegmentContexts == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(sealedSegmentContexts);
        }

        public FixedSegmentBufferRuntimeState toRuntimeState(
                List<RuntimeFixedSegmentModel> fixedSegments,
                List<FixedSegmentDiscardWindow> discardWindows) {
            ArrayList<FixedActionSegment> restoredSealedSegments = new ArrayList<>();
            if (fixedSegments != null) {
                for (RuntimeFixedSegmentModel segmentModel : fixedSegments) {
                    if (segmentModel == null) {
                        continue;
                    }
                    FixedActionSegmentContext context = sealedSegmentContexts.getOrDefault(
                            segmentModel.segmentId,
                            FixedActionSegmentContext.empty());
                    restoredSealedSegments.add(segmentModel.toFixedActionSegment(context));
                }
            }
            FixedActionSegment restoredOpenSegment = openSegment == null ? null : openSegment.toFixedActionSegment();
            long resolvedNextSegmentId = Math.max(1L, nextSegmentId);
            long maxObservedSegmentId = restoredSealedSegments.stream()
                    .mapToLong(FixedActionSegment::getSegmentId)
                    .max()
                    .orElse(FixedActionSegment.UNASSIGNED_SEGMENT_ID);
            if (maxObservedSegmentId > 0L) {
                resolvedNextSegmentId = Math.max(resolvedNextSegmentId, maxObservedSegmentId + 1L);
            }
            return new FixedSegmentBufferRuntimeState(
                    fixedSegmentMs,
                    resolvedNextSegmentId,
                    restoredSealedSegments,
                    restoredOpenSegment,
                    discardWindows);
        }
    }

    public static final class RuntimePersistedFixedSegmentModel {
        public long segmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long startTimestamp;
        public long endTimestamp;
        public int rawStartIndex = -1;
        public int rawEndIndex = -1;
        public List<FixedActionSegmentEntry> entries = new ArrayList<>();
        public FixedActionSegmentContext context = FixedActionSegmentContext.empty();

        public static RuntimePersistedFixedSegmentModel from(FixedActionSegment segment) {
            RuntimePersistedFixedSegmentModel model = new RuntimePersistedFixedSegmentModel();
            if (segment == null) {
                return model;
            }
            model.segmentId = segment.getSegmentId();
            model.startTimestamp = segment.getStartTimestamp();
            model.endTimestamp = segment.getEndTimestamp();
            model.rawStartIndex = segment.getRawStartIndex();
            model.rawEndIndex = segment.getRawEndIndex();
            model.entries = segment.getEntries() == null ? new ArrayList<>() : new ArrayList<>(segment.getEntries());
            model.context = segment.getContext() == null ? FixedActionSegmentContext.empty() : segment.getContext();
            return model;
        }

        public FixedActionSegment toFixedActionSegment() {
            return new FixedActionSegment(
                    segmentId,
                    startTimestamp,
                    endTimestamp,
                    rawStartIndex,
                    rawEndIndex,
                    entries,
                    null,
                    context);
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

        public void normalize() {
            headline = headline == null ? "" : headline;
            summary = summary == null ? "" : summary;
            dominantIntent = dominantIntent == null ? "" : dominantIntent;
            runnerUpIntent = runnerUpIntent == null ? "" : runnerUpIntent;
            scores = scores == null ? new LinkedHashMap<>() : new LinkedHashMap<>(scores);
            reasons = reasons == null ? new LinkedHashMap<>() : new LinkedHashMap<>(reasons);
            entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
            for (RuntimeActionEntryModel entry : entries) {
                if (entry != null) {
                    entry.normalize();
                }
            }
        }

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

        public void normalize() {
            type = type == null ? "" : type;
            target = target == null ? "" : target;
            detailTags = detailTags == null ? new ArrayList<>() : new ArrayList<>(detailTags);
            foldedTargets = foldedTargets == null ? new ArrayList<>() : new ArrayList<>(foldedTargets);
        }
    }

    private static List<FixedActionSegmentEntry> restoreFixedEntries(List<RuntimeActionEntryModel> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        ArrayList<FixedActionSegmentEntry> restored = new ArrayList<>(entries.size());
        for (RuntimeActionEntryModel entry : entries) {
            FixedActionSegmentEntry restoredEntry = toFixedEntry(entry);
            if (restoredEntry != null) {
                restored.add(restoredEntry);
            }
        }
        return List.copyOf(restored);
    }

    private static FixedActionSegmentEntry toFixedEntry(RuntimeActionEntryModel entry) {
        if (entry == null) {
            return null;
        }
        ActionType actionType = parseActionType(entry.type);
        if (actionType == null) {
            return null;
        }
        return new FixedActionSegmentEntry(
                actionType,
                entry.target,
                entry.count,
                entry.firstTimestamp,
                entry.lastTimestamp,
                entry.rawStartIndex,
                entry.rawEndIndex,
                parseDetailTags(entry.detailTags));
    }

    private static ActionType parseActionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ActionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Set<DetailTag> parseDetailTags(List<String> rawDetailTags) {
        if (rawDetailTags == null || rawDetailTags.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<DetailTag> detailTags = new LinkedHashSet<>();
        for (String rawDetailTag : rawDetailTags) {
            if (rawDetailTag == null || rawDetailTag.isBlank()) {
                continue;
            }
            try {
                detailTags.add(DetailTag.parse(rawDetailTag));
            } catch (IllegalArgumentException ignored) {
                // Keep restore tolerant to old / malformed snapshot data.
            }
        }
        return detailTags;
    }
}
