package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodePendingMark;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegionGateDecision;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegionLifecycleState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeReasonRuntimeConfig;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSplitPoint;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Episode v2 的 workspace runtime state 文件。
 * <p>
 * 当前实现仍主要承载 Studio / 本地测试所需的 runtime 快照；
 * 后续会继续收敛为 episode runtime state 的唯一真源文件，
 * 由 Studio 直接消费同一份 timeline / region 状态。
 */
public final class StudioRuntimeEpisodeTimelineFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_episode_timeline.json";
    public static final String VERSION_KEY = "runtimeEpisodeTimeline";
    public static final int SCHEMA_VERSION = 3;
    private static final long MIN_WRITE_INTERVAL_MS = 750L;

    private static long lastWriteAt = 0L;
    private static RuntimeEpisodeTimelineFileModel cachedFile;

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

    public static synchronized List<EpisodeRegion> readPersistedRegions() {
        RuntimeEpisodeTimelineFileModel file = readOrEmpty();
        if (file == null || file.regions == null || file.regions.isEmpty()) {
            return List.of();
        }
        ArrayList<EpisodeRegion> persistedRegions = new ArrayList<>();
        for (RuntimeEpisodeRegionModel regionModel : file.regions) {
            if (regionModel == null) {
                continue;
            }
            EpisodeRegion region = regionModel.toEpisodeRegion();
            if (region != null) {
                persistedRegions.add(region);
            }
        }
        return List.copyOf(persistedRegions);
    }

    public static synchronized boolean recordSnapshot(AgentMemory memory) {
        long now = System.currentTimeMillis();
        if (now - lastWriteAt < MIN_WRITE_INTERVAL_MS) {
            return false;
        }
        RuntimeEpisodeTimelineFileModel previous = readOrEmpty();
        EpisodeTimeline timeline = memory == null
                ? EpisodeTimeline.empty()
                : memory.getEpisodeTimelineStore().snapshot();
        String timelineSignatureToken = timelineSignatureTokenOf(timeline);
        if (previous != null && timelineSignatureToken.equals(previous.timelineSignatureToken)) {
            lastWriteAt = now;
            return true;
        }
        writeCurrentSnapshot(memory, previous, timeline, timelineSignatureToken);
        return true;
    }

    public static synchronized void writeCurrentSnapshot(AgentMemory memory) {
        RuntimeEpisodeTimelineFileModel previous = readOrEmpty();
        EpisodeTimeline timeline = memory == null
                ? EpisodeTimeline.empty()
                : memory.getEpisodeTimelineStore().snapshot();
        writeCurrentSnapshot(memory, previous, timeline, timelineSignatureTokenOf(timeline));
    }

    private static void writeCurrentSnapshot(
            AgentMemory memory,
            RuntimeEpisodeTimelineFileModel previous,
            EpisodeTimeline timeline,
            String timelineSignatureToken) {
        RuntimeEpisodeTimelineFileModel current = build(memory, previous, timeline, timelineSignatureToken);
        write(current);
    }

    private static RuntimeEpisodeTimelineFileModel build(
            AgentMemory memory,
            RuntimeEpisodeTimelineFileModel previous,
            EpisodeTimeline timeline,
            String timelineSignatureToken) {
        RuntimeEpisodeTimelineFileModel file = new RuntimeEpisodeTimelineFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        file.fallbackQuietWindowSegments = EpisodeRuntimeSettings.getFallbackQuietWindowSegments();
        file.reasonConfigs = createReasonConfigModels(EpisodeRuntimeSettings.snapshotConfigs());
        file.timelineSignatureToken = timelineSignatureToken == null ? "" : timelineSignatureToken;
        file.segmentCount = timeline.segmentCount();
        file.pendingMarkCount = timeline.pendingMarks().size();
        file.splitPointCount = timeline.splitPoints().size();
        file.regionCount = timeline.regions().size();

        for (int i = 0; i < timeline.segments().size(); i++) {
            file.segments.add(toSegmentModel(i, timeline.segments().get(i)));
        }
        for (EpisodePendingMark pendingMark : timeline.pendingMarks()) {
            if (pendingMark != null) {
                file.pendingMarks.add(RuntimeEpisodePendingMarkModel.from(pendingMark));
            }
        }
        for (EpisodeSplitPoint splitPoint : timeline.splitPoints()) {
            if (splitPoint != null) {
                file.splitPoints.add(RuntimeEpisodeSplitPointModel.from(splitPoint));
            }
        }
        for (EpisodeRegion region : timeline.regions()) {
            if (region != null) {
                file.regions.add(RuntimeEpisodeRegionModel.from(region));
            }
        }

        file.lastUpdate = RuntimeEpisodeUpdateModel.diff(previous, file);
        file.normalize();
        return file;
    }

    private static String timelineSignatureTokenOf(EpisodeTimeline timeline) {
        if (timeline == null) {
            return "empty";
        }

        StringBuilder token = new StringBuilder();
        token.append(timeline.segmentCount())
                .append("|").append(timeline.pendingMarks().size())
                .append("|").append(timeline.splitPoints().size())
                .append("|").append(timeline.regions().size());

        FixedActionSegment firstSegment = timeline.segments().isEmpty() ? null : timeline.segments().get(0);
        FixedActionSegment lastSegment = timeline.segments().isEmpty() ? null : timeline.segments().get(timeline.segments().size() - 1);
        token.append("|").append(segmentTokenOf(firstSegment))
                .append("|").append(segmentTokenOf(lastSegment));

        EpisodePendingMark lastPendingMark = timeline.pendingMarks().isEmpty()
                ? null
                : timeline.pendingMarks().get(timeline.pendingMarks().size() - 1);
        token.append("|").append(pendingMarkTokenOf(lastPendingMark));

        EpisodeSplitPoint lastSplitPoint = timeline.splitPoints().isEmpty()
                ? null
                : timeline.splitPoints().get(timeline.splitPoints().size() - 1);
        token.append("|").append(splitPointTokenOf(lastSplitPoint));

        EpisodeRegion lastRegion = timeline.regions().isEmpty()
                ? null
                : timeline.regions().get(timeline.regions().size() - 1);
        token.append("|").append(regionTokenOf(lastRegion));
        return token.toString();
    }

    private static String segmentTokenOf(FixedActionSegment segment) {
        if (segment == null) {
            return "null";
        }
        return segment.getSegmentId()
                + ":" + segment.getStartTimestamp()
                + ":" + segment.getEndTimestamp()
                + ":" + segment.getRawStartIndex()
                + ":" + segment.getRawEndIndex();
    }

    private static String pendingMarkTokenOf(EpisodePendingMark pendingMark) {
        if (pendingMark == null) {
            return "null";
        }
        return pendingMark.splitTimelineIndex()
                + ":" + pendingMark.splitLeftSegmentId()
                + ":" + pendingMark.splitRightSegmentId()
                + ":" + pendingMark.reason()
                + ":" + pendingMark.confirmAtTimelineIndex();
    }

    private static String splitPointTokenOf(EpisodeSplitPoint splitPoint) {
        if (splitPoint == null) {
            return "null";
        }
        return splitPoint.splitTimelineIndex()
                + ":" + splitPoint.splitLeftSegmentId()
                + ":" + splitPoint.splitRightSegmentId()
                + ":" + splitPoint.reason()
                + ":" + splitPoint.confirmationTimelineIndex();
    }

    private static String regionTokenOf(EpisodeRegion region) {
        if (region == null) {
            return "null";
        }
        return region.regionId()
                + ":" + region.startSegmentId()
                + ":" + region.endSegmentId()
                + ":" + region.gateDecision()
                + ":" + region.lifecycleState();
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

    private static RuntimeEpisodeSegmentModel toSegmentModel(int index, FixedActionSegment segment) {
        RuntimeEpisodeSegmentModel model = new RuntimeEpisodeSegmentModel();
        model.index = index;
        if (segment == null) {
            return model;
        }

        model.segmentId = segment.getSegmentId();
        model.startTimestamp = segment.getStartTimestamp();
        model.endTimestamp = segment.getEndTimestamp();
        model.rawStartIndex = segment.getRawStartIndex();
        model.rawEndIndex = segment.getRawEndIndex();
        model.summary = summarizeFixedEntries(segment.getEntries());
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
        for (int i = 0; i < limit; i++) {
            FixedActionSegmentEntry entry = entries.get(i);
            if (entry == null || entry.getType() == null) {
                continue;
            }
            String label = entry.getType().name().toLowerCase(Locale.ROOT) + " x" + Math.max(1, entry.getCount());
            if (entry.getTarget() != null && !entry.getTarget().isBlank()) {
                label += " @ " + entry.getTarget().trim();
            }
            summary.add(label);
        }
        if (entries.size() > limit) {
            summary.add("+" + (entries.size() - limit) + " more");
        }
        return summary.isEmpty() ? "(empty)" : String.join(" | ", summary);
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

    private static PlayerIntent parseIntent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PlayerIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static EpisodeRegionGateDecision parseGateDecision(String value) {
        if (value == null || value.isBlank()) {
            return EpisodeRegionGateDecision.UNSET;
        }
        try {
            return EpisodeRegionGateDecision.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return EpisodeRegionGateDecision.UNSET;
        }
    }

    private static EpisodeRegionLifecycleState parseLifecycleState(
            String value,
            EpisodeRegionLifecycleState fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return EpisodeRegionLifecycleState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static final class RuntimeEpisodeTimelineFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public int fallbackQuietWindowSegments = EpisodeRuntimeSettings.getFallbackQuietWindowSegments();
        public String timelineSignatureToken = "";
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

        private void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            if (timelineSignatureToken == null) {
                timelineSignatureToken = "";
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
            ArrayList<RuntimeEpisodeRegionModel> normalizedRegions = new ArrayList<>();
            for (RuntimeEpisodeRegionModel region : regions) {
                if (region != null) {
                    normalizedRegions.add(region);
                }
            }
            for (int i = 0; i < normalizedRegions.size(); i++) {
                normalizedRegions.get(i).normalize(i == normalizedRegions.size() - 1);
            }
            regions = normalizedRegions;
            if (lastUpdate == null) {
                lastUpdate = new RuntimeEpisodeUpdateModel();
            }
            lastUpdate.normalize();
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
        public long segmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long startTimestamp;
        public long endTimestamp;
        public int rawStartIndex = -1;
        public int rawEndIndex = -1;
        public String summary = "";
        public String dominantIntent = "";
        public double dominantScore;
        public String runnerUpIntent = "";
        public double runnerUpScore;
        public boolean ambiguous;
        public List<String> observedActionTypes = new ArrayList<>();

        String signature() {
            return index + "|" + segmentId + "|" + startTimestamp + "|" + endTimestamp + "|" + rawStartIndex + "|" + rawEndIndex;
        }
    }

    public static final class RuntimeEpisodePendingMarkModel {
        public int detectedAtTimelineIndex;
        public long detectedAtSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public int splitTimelineIndex;
        public long splitLeftSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long splitRightSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long splitTimestamp;
        public String reason = "";
        public int confirmAtTimelineIndex;
        public String ownerSplitterId = "";
        public boolean highPriority;
        public int coverBeforeSegments;
        public int coverAfterSegments;

        public static RuntimeEpisodePendingMarkModel from(EpisodePendingMark pendingMark) {
            RuntimeEpisodePendingMarkModel model = new RuntimeEpisodePendingMarkModel();
            model.detectedAtTimelineIndex = pendingMark.detectedAtTimelineIndex();
            model.detectedAtSegmentId = pendingMark.detectedAtSegmentId();
            model.splitTimelineIndex = pendingMark.splitTimelineIndex();
            model.splitLeftSegmentId = pendingMark.splitLeftSegmentId();
            model.splitRightSegmentId = pendingMark.splitRightSegmentId();
            model.splitTimestamp = pendingMark.splitTimestamp();
            model.reason = nameOf(pendingMark.reason());
            model.confirmAtTimelineIndex = pendingMark.confirmAtTimelineIndex();
            model.ownerSplitterId = pendingMark.ownerSplitterId();
            model.highPriority = pendingMark.highPriority();
            model.coverBeforeSegments = pendingMark.coverBeforeSegments();
            model.coverAfterSegments = pendingMark.coverAfterSegments();
            return model;
        }

        String signature() {
            if (splitLeftSegmentId > 0L || splitRightSegmentId > 0L) {
                return splitLeftSegmentId + "|" + splitRightSegmentId + "|" + confirmAtTimelineIndex + "|" + reason + "|" + ownerSplitterId;
            }
            return splitTimelineIndex + "|" + confirmAtTimelineIndex + "|" + reason + "|" + ownerSplitterId;
        }

        String summary() {
            return reason + "@split=" + splitTimelineIndex
                    + " confirm@" + confirmAtTimelineIndex
                    + " owner=" + ownerSplitterId;
        }
    }

    public static final class RuntimeEpisodeSplitPointModel {
        public int splitTimelineIndex;
        public long splitLeftSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long splitRightSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long splitTimestamp;
        public int confirmationTimelineIndex;
        public long confirmationSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long confirmationTimestamp;
        public String reason = "";
        public String previousPrimaryIntent = "";
        public String previousSecondaryIntent = "";
        public String nextPrimaryIntent = "";
        public String nextSecondaryIntent = "";

        public static RuntimeEpisodeSplitPointModel from(EpisodeSplitPoint splitPoint) {
            RuntimeEpisodeSplitPointModel model = new RuntimeEpisodeSplitPointModel();
            model.splitTimelineIndex = splitPoint.splitTimelineIndex();
            model.splitLeftSegmentId = splitPoint.splitLeftSegmentId();
            model.splitRightSegmentId = splitPoint.splitRightSegmentId();
            model.splitTimestamp = splitPoint.splitTimestamp();
            model.confirmationTimelineIndex = splitPoint.confirmationTimelineIndex();
            model.confirmationSegmentId = splitPoint.confirmationSegmentId();
            model.confirmationTimestamp = splitPoint.confirmationTimestamp();
            model.reason = nameOf(splitPoint.reason());
            model.previousPrimaryIntent = nameOf(splitPoint.previousPrimaryIntent());
            model.previousSecondaryIntent = nameOf(splitPoint.previousSecondaryIntent());
            model.nextPrimaryIntent = nameOf(splitPoint.nextPrimaryIntent());
            model.nextSecondaryIntent = nameOf(splitPoint.nextSecondaryIntent());
            return model;
        }

        String signature() {
            if (splitLeftSegmentId > 0L || splitRightSegmentId > 0L) {
                return splitLeftSegmentId + "|" + splitRightSegmentId + "|" + splitTimestamp + "|" + reason;
            }
            return splitTimelineIndex + "|" + splitTimestamp + "|" + reason;
        }

        String summary() {
            return reason + "@split=" + splitTimelineIndex
                    + " confirm@" + confirmationTimelineIndex;
        }
    }

    public static final class RuntimeEpisodeRegionModel {
        public long regionId;
        public int startSegmentIndex;
        public int endSegmentIndex;
        public long startSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long endSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long startTimestamp;
        public long endTimestamp;
        public int segmentCount;
        public String gateDecision = "";
        public String lifecycleState = "";
        public String primaryIntent = "";
        public double primaryScore;
        public String secondaryIntent = "";
        public double secondaryScore;
        public Map<String, Double> aggregateIntentScores = new LinkedHashMap<>();

        public static RuntimeEpisodeRegionModel from(EpisodeRegion region) {
            RuntimeEpisodeRegionModel model = new RuntimeEpisodeRegionModel();
            model.regionId = region.regionId();
            model.startSegmentIndex = region.startSegmentIndex();
            model.endSegmentIndex = region.endSegmentIndex();
            model.startSegmentId = region.startSegmentId();
            model.endSegmentId = region.endSegmentId();
            model.startTimestamp = region.startTimestamp();
            model.endTimestamp = region.endTimestamp();
            model.segmentCount = region.segmentCount();
            model.gateDecision = nameOf(region.gateDecision());
            model.lifecycleState = nameOf(region.lifecycleState());
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
            return regionId + "|" + startSegmentId + "|" + endSegmentId + "|" + gateDecision + "|" + lifecycleState;
        }

        String summary() {
            return "#" + regionId
                    + " [" + startSegmentIndex + ".." + endSegmentIndex + "]"
                    + " state=" + lifecycleState
                    + " gate=" + gateDecision
                    + " primary=" + primaryIntent
                    + " " + String.format(Locale.ROOT, "%.2f", primaryScore);
        }

        void normalize(boolean tailRegion) {
            if (gateDecision == null) {
                gateDecision = "";
            }
            if (lifecycleState == null) {
                lifecycleState = "";
            }
            if (primaryIntent == null) {
                primaryIntent = "";
            }
            if (secondaryIntent == null) {
                secondaryIntent = "";
            }
            if (aggregateIntentScores == null) {
                aggregateIntentScores = new LinkedHashMap<>();
            } else {
                aggregateIntentScores = new LinkedHashMap<>(aggregateIntentScores);
            }

            EpisodeRegionGateDecision normalizedGateDecision = tailRegion
                    ? EpisodeRegionGateDecision.UNSET
                    : parseGateDecision(gateDecision);
            EpisodeRegionLifecycleState normalizedLifecycleState = tailRegion
                    ? EpisodeRegionLifecycleState.ACTIVE
                    : parseLifecycleState(lifecycleState, EpisodeRegionLifecycleState.HOLDING);

            if (!tailRegion) {
                if (normalizedLifecycleState == EpisodeRegionLifecycleState.ACTIVE
                        || normalizedLifecycleState == EpisodeRegionLifecycleState.PENDING) {
                    normalizedLifecycleState = EpisodeRegionLifecycleState.HOLDING;
                }
            }

            gateDecision = normalizedGateDecision.name();
            lifecycleState = normalizedLifecycleState.name();
        }

        EpisodeRegion toEpisodeRegion() {
            EnumMap<PlayerIntent, Double> parsedScores = new EnumMap<>(PlayerIntent.class);
            for (PlayerIntent intent : PlayerIntent.values()) {
                parsedScores.put(intent, 0.0);
            }
            for (Map.Entry<String, Double> entry : aggregateIntentScores.entrySet()) {
                PlayerIntent intent = parseIntent(entry.getKey());
                if (intent == null || entry.getValue() == null) {
                    continue;
                }
                parsedScores.put(intent, entry.getValue());
            }
            return new EpisodeRegion(
                    startSegmentIndex,
                    endSegmentIndex,
                    startSegmentId,
                    endSegmentId,
                    startTimestamp,
                    endTimestamp,
                    parsedScores,
                    parseIntent(primaryIntent),
                    primaryScore,
                    parseIntent(secondaryIntent),
                    secondaryScore,
                    regionId,
                    parseGateDecision(gateDecision),
                    parseLifecycleState(lifecycleState, EpisodeRegionLifecycleState.HOLDING));
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

    @FunctionalInterface
    private interface SignatureFn<T> {
        String signature(T value);
    }

    @FunctionalInterface
    private interface SummaryFn<T> {
        String summary(T value);
    }
}
