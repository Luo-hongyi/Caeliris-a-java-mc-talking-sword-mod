package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionInteraction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawSegmentMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionScope;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionPlayerAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionMaterialMerger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionPromptFormatter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.PersonaEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成一份 episode synthesis reflection prompt 预览。
 * <p>
 * 该入口不依赖完整运行时，只复用正式的 merger / formatter / task prompt 组装逻辑，
 * 方便直接检查最终发给模型的 system / user prompt 长什么样。
 */
public final class EpisodeSynthesisPromptMockPreview {

    private static final long SEGMENT_0_START = 120_000L;
    private static final long SEGMENT_0_END = 150_000L;
    private static final long SEGMENT_1_START = 150_000L;
    private static final long SEGMENT_1_END = 180_000L;

    private EpisodeSynthesisPromptMockPreview() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length >= 1 && args[0] != null && !args[0].isBlank()
                ? Path.of(args[0])
                : Path.of("testdata", "reflection_prompt_mock_preview");
        Files.createDirectories(outputDir);

        ReflectionRawMaterial rawMaterial = buildRawMaterial();
        ReflectionMaterial material = new DefaultReflectionMaterialMerger().merge(rawMaterial);
        ReflectionPromptDraft promptDraft = new DefaultReflectionPromptFormatter().format(material);
        ReflectionTaskInput taskInput = new ReflectionTaskInput(
                material,
                buildStateSnapshot(),
                buildRelevantPersonaEntries());
        EpisodeSynthesisReflectionTask task = new EpisodeSynthesisReflectionTask(
                buildSignal(),
                96_000L,
                4L,
                12_000L);

        String systemPrompt = task.buildSystemPrompt(taskInput, promptDraft);
        String userPrompt = task.buildUserPrompt();

        write(outputDir.resolve("system_prompt.txt"), systemPrompt);
        write(outputDir.resolve("user_prompt.txt"), userPrompt);
        write(outputDir.resolve("prompt_draft.txt"), promptDraft.draftText());
        write(outputDir.resolve("segment_reference_map.txt"), promptDraft.segmentReferenceMap());
        write(outputDir.resolve("preview_summary.txt"), buildSummary(material));

        System.out.println("[EpisodeSynthesisPromptMockPreview] generated files under " + outputDir.toAbsolutePath());
    }

    private static ReflectionRawMaterial buildRawMaterial() {
        ReflectionRawSegmentMaterial segment0 = new ReflectionRawSegmentMaterial(
                0,
                41L,
                SEGMENT_0_START,
                SEGMENT_0_END,
                new ReflectionContextSnapshot(
                        List.of(
                                contextEntry(
                                        SEGMENT_0_START,
                                        "玩家刚进入潮湿洞穴入口，脚边有浅水和碎石。",
                                        FocusTag.EXPLORE,
                                        FocusTag.LANDSCAPE,
                                        DetailTag.nativeGeneric("minecraft:cave"),
                                        DetailTag.customGeneric("underground_entry"))),
                        orderedExtras(
                                "dimension", "minecraft:overworld",
                                "biome", "minecraft:dripstone_caves",
                                "time_desc", "午后，洞穴入口仍有些亮")),
                List.of(
                        contextEntry(
                                SEGMENT_0_START + 5_000L,
                                "洞穴深处传来骷髅的零散脚步声。",
                                FocusTag.HAZARD,
                                FocusTag.CREATURE,
                                DetailTag.nativeSpecific("minecraft:skeleton")),
                        contextEntry(
                                SEGMENT_0_START + 18_000L,
                                "附近岩壁上可见成片铁矿脉。",
                                FocusTag.LABOR,
                                FocusTag.BLOCK,
                                DetailTag.nativeSpecific("minecraft:iron_ore"))),
                List.of(
                        new ReflectionPlayerAction(
                                SEGMENT_0_START + 9_000L,
                                ActionType.ENTER_MAJOR_STRUCTURE,
                                "dripstone cave entrance",
                                1,
                                Set.of(DetailTag.customGeneric("explore_cautious"))),
                        new ReflectionPlayerAction(
                                SEGMENT_0_START + 16_000L,
                                ActionType.MINE_BLOCK,
                                "minecraft:iron_ore",
                                3,
                                Set.of(
                                        DetailTag.nativeSpecific("minecraft:iron_ore"),
                                        DetailTag.customGeneric("resource_collection")))),
                List.of(
                        new ReflectionInteraction(
                                SEGMENT_0_START + 22_000L,
                                "player_action_window",
                                "前面有骷髅和矿，我先挖还是先打？",
                                List.of(new AgentOutputRecord(
                                        AgentOutputRecord.Type.SPEECH,
                                        "先处理威胁，再慢慢采矿。你现在的位置不适合被远程牵制。")),
                                new ReflectionContextSnapshot(
                                        List.of(
                                                contextEntry(
                                                        SEGMENT_0_START + 22_000L,
                                                        "玩家正站在狭窄洞口，前方视野被钟乳石和阴影切碎。",
                                                        FocusTag.HAZARD,
                                                        FocusTag.LANDSCAPE,
                                                        DetailTag.customGeneric("line_of_sight_broken"))),
                                        orderedExtras(
                                                "dimension", "minecraft:overworld",
                                                "biome", "minecraft:dripstone_caves",
                                                "time_desc", "午后",
                                                "combat_readiness", "有潜在远程威胁")))));

        ReflectionRawSegmentMaterial segment1 = new ReflectionRawSegmentMaterial(
                1,
                42L,
                SEGMENT_1_START,
                SEGMENT_1_END,
                new ReflectionContextSnapshot(
                        List.of(
                                contextEntry(
                                        SEGMENT_1_START,
                                        "战斗后洞穴安静下来，玩家继续向深处推进。",
                                        FocusTag.EXPLORE,
                                        FocusTag.COMBAT,
                                        DetailTag.customGeneric("post_combat_push"))),
                        orderedExtras(
                                "dimension", "minecraft:overworld",
                                "biome", "minecraft:dripstone_caves",
                                "time_desc", "洞穴深处已几乎没有自然光")),
                List.of(
                        contextEntry(
                                SEGMENT_1_START + 6_000L,
                                "一只蝙蝠从头顶掠过，前方有废弃矿道的木板结构。",
                                FocusTag.EXPLORE,
                                FocusTag.CREATURE,
                                DetailTag.nativeSpecific("minecraft:bat"),
                                DetailTag.customSpecific("abandoned_mineshaft")),
                        contextEntry(
                                SEGMENT_1_START + 17_000L,
                                "地上掉落着骨头和箭，说明刚才的战斗已经结束。",
                                FocusTag.COMBAT,
                                FocusTag.LABOR,
                                DetailTag.customGeneric("combat_cleanup"))),
                List.of(
                        new ReflectionPlayerAction(
                                SEGMENT_1_START + 8_000L,
                                ActionType.KILL,
                                "minecraft:skeleton",
                                1,
                                Set.of(
                                        DetailTag.nativeSpecific("minecraft:skeleton"),
                                        DetailTag.customGeneric("threat_removed"))),
                        new ReflectionPlayerAction(
                                SEGMENT_1_START + 23_000L,
                                ActionType.OPEN_LOOT_CONTAINER,
                                "abandoned_mineshaft_chest",
                                1,
                                Set.of(DetailTag.customSpecific("abandoned_mineshaft")))),
                List.of(
                        new ReflectionInteraction(
                                SEGMENT_1_START + 25_000L,
                                "loot_pause",
                                "这里像废弃矿道。你觉得值得继续深入吗？",
                                List.of(
                                        new AgentOutputRecord(
                                                AgentOutputRecord.Type.INNER_MONOLOGUE,
                                                "她已经把威胁清掉了，接下来更像是一次有目的的试探。"),
                                        new AgentOutputRecord(
                                                AgentOutputRecord.Type.SPEECH,
                                                "可以继续，但别太贪。这里很可能还有第二波敌人。")),
                                new ReflectionContextSnapshot(
                                        List.of(
                                                contextEntry(
                                                        SEGMENT_1_START + 25_000L,
                                                        "玩家停在矿道木梁边，脚边有刚打开的旧箱子。",
                                                        FocusTag.EXPLORE,
                                                        FocusTag.BLOCK,
                                                        DetailTag.customSpecific("abandoned_mineshaft"))),
                                        orderedExtras(
                                                "dimension", "minecraft:overworld",
                                                "biome", "minecraft:dripstone_caves",
                                                "time_desc", "洞穴深处",
                                                "loot_state", "刚开启废弃矿道箱子")))));

        return new ReflectionRawMaterial(
                ReflectionScope.REGION,
                1001L,
                41L,
                42L,
                40L,
                41L,
                42L,
                43L,
                SEGMENT_0_START,
                SEGMENT_1_END,
                SEGMENT_0_START,
                SEGMENT_1_END,
                List.of(segment0, segment1));
    }

    private static ReflectionStateSnapshot buildStateSnapshot() {
        return new ReflectionStateSnapshot(
                "塞勒瑞思",
                "你是塞勒瑞思，被封印在剑中的古精灵灵魂。",
                "冷静、敏锐，会在危险前先做判断，但也会被玩家的探索欲带动。",
                "CURIOUS",
                57,
                46,
                "你",
                "explore_unknown",
                "想确认主人此刻是在谨慎探索，还是开始因战利品而冒进。",
                18,
                "玩家最近在地下探索时表现出谨慎推进、先清威胁后搜刮的倾向。",
                "前一段时间里，玩家持续向地下推进，并开始主动询问你对风险和路线的判断。");
    }

    private static RelevantPersonaEntries buildRelevantPersonaEntries() {
        PersonaEntry combatEntry = new PersonaEntry(
                "persona_combat_caution",
                "比起鲁莽冲锋，你更欣赏主人先判断威胁再行动的战斗方式。",
                FocusTag.COMBAT)
                .withAlwaysRecalled(true)
                .withDetailTag("native_specific:minecraft:skeleton")
                .withDetailTag("native_specific:minecraft:bow");
        combatEntry.setFocusTags(List.of(FocusTag.COMBAT, FocusTag.HAZARD));

        PersonaEntry explorationEntry = new PersonaEntry(
                "persona_mineshaft_interest",
                "你对废弃遗迹和被遗忘的地下结构有天然兴趣，但会提醒主人不要为了战利品冒进。",
                FocusTag.EXPLORE)
                .withDetailTag("native_specific:minecraft:chest")
                .withDetailTag("native_specific:minecraft:oak_planks");
        explorationEntry.setFocusTags(List.of(FocusTag.EXPLORE, FocusTag.LANDSCAPE));

        return new RelevantPersonaEntries(List.of(combatEntry, explorationEntry));
    }

    private static EpisodeSynthesisTriggerSignal buildSignal() {
        return new EpisodeSynthesisTriggerSignal(
                1001L,
                40L,
                41L,
                41L,
                42L,
                42L,
                43L,
                SEGMENT_0_START,
                SEGMENT_1_END,
                SEGMENT_0_START,
                SEGMENT_1_END);
    }

    private static TaggedContextEntry contextEntry(
            long timestamp,
            String description,
            FocusTag focusA,
            FocusTag focusB,
            DetailTag... detailTags) {
        TaggedContextEntry.Builder builder = TaggedContextEntry.builder()
                .timestamp(timestamp)
                .description(description)
                .importance(0.7f)
                .memoryEligible(true);
        if (focusA != null) {
            builder.focus(focusA);
        }
        if (focusB != null) {
            builder.focus(focusB);
        }
        if (detailTags != null) {
            for (DetailTag detailTag : detailTags) {
                if (detailTag != null) {
                    builder.detail(detailTag);
                }
            }
        }
        return builder.build();
    }

    private static Map<String, String> orderedExtras(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("orderedExtras expects even number of key/value strings");
        }
        LinkedHashMap<String, String> extras = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            extras.put(keyValues[i], keyValues[i + 1]);
        }
        return extras;
    }

    private static String buildSummary(ReflectionMaterial material) {
        return """
                episode_synthesis prompt mock preview

                regionId: %s
                segmentRange: %s -> %s
                timeRange: %s -> %s
                stmSlice: %s -> %s
                segmentCount: %s

                generated files:
                - system_prompt.txt
                - user_prompt.txt
                - prompt_draft.txt
                - segment_reference_map.txt
                """.formatted(
                material.regionId(),
                material.startSegmentId(),
                material.endSegmentId(),
                material.startTimestamp(),
                material.endTimestamp(),
                material.stmSliceStartTimestamp(),
                material.stmSliceExclusiveTimestamp(),
                material.segments().size()).trim();
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
    }
}
