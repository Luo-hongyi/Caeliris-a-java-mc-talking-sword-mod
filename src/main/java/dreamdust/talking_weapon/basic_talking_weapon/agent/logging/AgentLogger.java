package dreamdust.talking_weapon.basic_talking_weapon.agent.logging;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AttentionFilter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponse;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 调试日志：每次触发生成一个独立的可读 log 文件。
 * <p>
 * 日志文件存放在 {@code <游戏目录>/talking_weapon_logs/} 下，
 * 文件名格式 {@code trigger_<时间戳>_<触发源>.log}。
 * <p>
 * 日志内容分五段：
 * <ol>
 * <li>触发器信息（类型、原因）</li>
 * <li>Collector 采集结果</li>
 * <li>Agent 状态与记忆</li>
 * <li>人格提示词与合成后的完整提示词</li>
 * <li>Agent 的行动（LLM 回复、执行动作）</li>
 * </ol>
 */
public final class AgentLogger {

    private static final String LOG_DIR = "talking_weapon_logs";
    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DISPLAY_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 当前触发会话的日志写入器（全局唯一，因运行时是单工作线程顺序执行） */
    private static final AtomicReference<LogSession> CURRENT_SESSION = new AtomicReference<>();

    private AgentLogger() {
    }

    // ═══════════════════════════════════════════════════════════════
    // 会话生命周期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 开始一次新的日志会话。在 AgentSensor.fireTrigger() 开头调用。
     *
     * @param triggerSource 触发源名称（如 "right_click", "biome_change"）
     * @param userMessage 本轮作为 UserMessage 发送的“动作/事件描述”
     * @param eventInstruction 注入 system prompt 的事件指令
     */
    public static void beginSession(String triggerSource, String userMessage, String eventInstruction) {
        try {
            Path dir = ensureLogDir();
            String timestamp = LocalDateTime.now().format(FILE_TIME_FMT);
            String fileName = "trigger_" + timestamp + "_" + sanitize(triggerSource) + ".log";
            File file = dir.resolve(fileName).toFile();
            PrintWriter writer = new PrintWriter(new FileWriter(file, false), true);

            LogSession session = new LogSession(writer, file.getAbsolutePath());
            CURRENT_SESSION.set(session);

            writer.println("╔══════════════════════════════════════════════════════════════╗");
            writer.println("║          TALKING WEAPON — AGENT TRIGGER LOG                  ║");
            writer.println("╚══════════════════════════════════════════════════════════════╝");
            writer.println();
            writer.println("时间: " + LocalDateTime.now().format(DISPLAY_TIME_FMT));
            writer.println("日志: " + file.getName());
            writer.println();

            // ── 第 1 段：触发器信息 ──
            writeSectionHeader(writer, "1. 触发器信息");
            writer.println("触发源:   " + triggerSource);
            writer.println("UserMessage: " + (userMessage == null ? "(null)" : userMessage));
            writer.println("EventInstruction: " + (eventInstruction == null ? "(null)" : eventInstruction));
            writer.println();
        } catch (IOException e) {
            // 日志失败不阻塞游戏
            e.printStackTrace();
        }
    }

    /**
     * 记录 Collector 采集的上下文信息。在所有 collector 执行完毕后调用。
     */
    public static void logContext(AgentContext context) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "2. Collector 采集结果");

        w.println("基础信息:");
        w.println("  生命值:   " + context.getHealth() + " / " + context.getMaxHealth());
        w.println("  饥饿值:   " + context.getFoodLevel());
        w.println("  坐标:     (" + (int) context.getPosX() + ", " + (int) context.getPosY() + ", "
                + (int) context.getPosZ() + ")");
        w.println("  群系:     " + context.getBiome());
        w.println("  游戏时钟: " + context.getGameTime() + " tick");
        w.println("  第几天:   " + context.getDayIndex());
        w.println("  当日时段: " + context.getTimeOfDayTick() + " tick");
        w.println("  雨天:     " + (context.isRaining() ? "是" : "否"));
        w.println("  截图:     " + (context.getScreenshotBase64() != null ? "已采集 (base64 省略)" : "未采集"));
        w.println();

        Map<String, String> extras = context.getExtras();
        if (!extras.isEmpty()) {
            w.println("扩展数据 (Collector extras):");
            for (Map.Entry<String, String> entry : extras.entrySet()) {
                w.println("  [" + entry.getKey() + "]");
                // 对长文本做缩进换行
                String val = entry.getValue();
                if (val.length() > 100) {
                    w.println("    " + val.substring(0, 100) + "...");
                    w.println("    (共 " + val.length() + " 字符)");
                } else {
                    w.println("    " + val);
                }
            }
        }

        // ── 带标签的上下文条目 ──
        java.util.List<TaggedContextEntry> entries = context.getTaggedEntries();
        if (!entries.isEmpty()) {
            w.println("标签化上下文 (Tagged Context, " + entries.size() + " 条):");
            for (int i = 0; i < entries.size(); i++) {
                TaggedContextEntry entry = entries.get(i);
                float subjectiveScore = AttentionFilter.calculateFinalScore(entry, 0.5f);
                w.println("  [" + i + "] importance=" + String.format("%.2f", entry.getBaseImportance())
                        + " subjective=" + String.format("%.2f", subjectiveScore)
                        + (entry.isAssociativeOnly() ? " [ASSOCIATIVE]" : ""));
                w.println("      focus:   " + entry.getFocusTags());
                w.println("      details: " + entry.getDetailTags());
                w.println("      desc:    " + entry.getDescription());
            }
        }

        w.println("──────────────────────────────────────────");
        w.println("原始快照字段（用于对照当前 Tagged Context 与 extras 输出）");
        w.println("  [Context] health=" + context.getHealth() + "/" + context.getMaxHealth()
                + ", food=" + context.getFoodLevel()
                + ", pos=(" + (int) context.getPosX() + "," + (int) context.getPosY() + "," + (int) context.getPosZ() + ")"
                + ", biome=" + context.getBiome()
                + ", gameTime=" + context.getGameTime()
                + ", dayIndex=" + context.getDayIndex()
                + ", timeOfDayTick=" + context.getTimeOfDayTick()
                + ", raining=" + context.isRaining());
        w.println("  [Extra Context] (extras 键值对 — 以下字段是否已被 Tagged Context 覆盖？)");
        for (Map.Entry<String, String> e : extras.entrySet()) {
            String key = e.getKey();
            if (key.equals("user_message") || key.equals("event_instruction") || key.equals("output_schema")) continue;
            w.println("    " + key + " = " + e.getValue());
        }
        w.println("──────────────────────────────────────────");

        w.println();
    }

    /**
     * 将整个 RecentActionBuffer 快照单独输出到一个日志文件中，方便调试。
     * 文件名格式: recent_action_buffer_dump_<时间戳>.log
     */
    public static void logRecentActionBufferDump(
            dreamdust.talking_weapon.basic_talking_weapon.agent.memory.RecentActionBuffer buffer) {
        try {
            Path dir = ensureLogDir();
            String timestamp = LocalDateTime.now().format(FILE_TIME_FMT);
            File file = dir.resolve("recent_action_buffer_dump_" + timestamp + ".log").toFile();
            try (PrintWriter w = new PrintWriter(new FileWriter(file, false), true)) {
                w.println("╔══════════════════════════════════════════════════════════════╗");
                w.println("║       TALKING WEAPON — RECENT ACTION BUFFER DUMP            ║");
                w.println("╚══════════════════════════════════════════════════════════════╝");
                w.println("时间: " + LocalDateTime.now().format(DISPLAY_TIME_FMT));
                w.println("动作总数: " + buffer.size());
                w.println();

                java.util.List<dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionRecord> records = buffer
                        .snapshot();
                for (int i = 0; i < records.size(); i++) {
                    dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionRecord r = records.get(i);
                    w.println(String.format("[%03d] Type: %-15s Target: %-20s Count: %-3d Focus: %s Details: %s",
                            i, r.getType(), r.getTarget(), r.getCount(),
                            r.getType().getAllFocusTags(), r.getDetailTags()));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 将整个 ActionArchive 快照单独输出到一个日志文件中，方便验证原始动作时间线。
     * <p>
     * 当前仅作为 right_click fire 时的临时测试钩子，后续应删除。
     * 文件名格式: action_archive_dump_<时间戳>.log
     */
    public static void logActionArchiveDump(
            dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionArchive archive) {
        try {
            Path dir = ensureLogDir();
            String timestamp = LocalDateTime.now().format(FILE_TIME_FMT);
            File file = dir.resolve("action_archive_dump_" + timestamp + ".log").toFile();
            try (PrintWriter w = new PrintWriter(new FileWriter(file, false), true)) {
                w.println("╔══════════════════════════════════════════════════════════════╗");
                w.println("║         TALKING WEAPON — ACTION ARCHIVE DUMP               ║");
                w.println("╚══════════════════════════════════════════════════════════════╝");
                w.println("时间: " + LocalDateTime.now().format(DISPLAY_TIME_FMT));
                w.println("说明: 当前仅作为 right_click fire 时的临时测试日志，后续应删除");
                w.println("动作总数: " + archive.size());
                w.println();

                java.util.List<dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionRecord> records = archive
                        .snapshot();
                for (int i = 0; i < records.size(); i++) {
                    dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionRecord r = records.get(i);
                    w.println(String.format(
                            "[%04d] ts=%d Type: %-24s Target: %-24s Count: %-3d Focus: %s Details: %s",
                            i, r.getTimestamp(), r.getType(), r.getTarget(), r.getCount(),
                            r.getType().getAllFocusTags(), r.getDetailTags()));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 记录 Agent 状态与记忆。在构建 prompt 之前调用。
     */
    public static void logAgentState(AgentMemory memory, AgentState state) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "3. Agent 状态与记忆");

        w.println("状态:");
        w.println("  情绪:     " + state.getPrimaryEmotion().name()
                + " (强度: " + state.getEmotionIntensity() + "/100)");
        w.println("  熟悉度:   " + state.getFamiliarity());
        w.println("  专注度:   " + String.format("%.2f", state.getConcentration()));
        w.println("  随想值:   " + String.format("%.2f", state.getWanderingMind()));
        w.println("  忆旧值:   " + String.format("%.2f", state.getNostalgia()));
        w.println("  召回预设: " + state.getRecallPreset().name());
        w.println("  植入保底: " + (state.isImplantedMemorySafetyNetEnabled() ? "开启" : "关闭"));
        w.println();

        w.println("记忆:");
        w.println("  身份 (Identity):");
        String identity = memory.getIdentity();
        if (identity == null || identity.isEmpty()) {
            w.println("    (未设置)");
        } else {
            for (String line : identity.split("\n")) {
                w.println("    " + line);
            }
        }
        w.println();

        w.println("  长期记忆切片 (" + memory.getLtmRecords().size() + " 条):");
        if (memory.getLtmRecords().isEmpty()) {
            w.println("    (空)");
        } else {
            for (int i = 0; i < memory.getLtmRecords().size(); i++) {
                w.println("    [" + i + "] " + memory.getLtmRecords().get(i));
            }
        }
        Map<UUID, Integer> suppressionTable = memory.getRecentRecallSuppressionTable();
        List<Map.Entry<UUID, Integer>> suppressionEntries = new ArrayList<>(suppressionTable.entrySet());
        suppressionEntries.sort(Comparator
                .<Map.Entry<UUID, Integer>, Integer>comparing(Map.Entry::getValue)
                .reversed()
                .thenComparing(entry -> entry.getKey().toString()));
        w.println("  显性回忆抑制表 (" + suppressionEntries.size()
                + " 条, 抑制时长=" + memory.getRecentRecallWindow() + " 轮):");
        if (suppressionEntries.isEmpty()) {
            w.println("    (空)");
        } else {
            for (int i = 0; i < suppressionEntries.size(); i++) {
                Map.Entry<UUID, Integer> entry = suppressionEntries.get(i);
                w.println("    [" + i + "] cooldown=" + entry.getValue() + " memoryId=" + entry.getKey());
            }
        }
        w.println();

        java.util.List<InteractionTurn> turns = memory.getSTMPool().turnSnapshot();
        w.println("  短期记忆回合 (" + turns.size() + " 轮):");
        if (turns.isEmpty()) {
            w.println("    (空)");
        } else {
            for (int i = 0; i < turns.size(); i++) {
                InteractionTurn turn = turns.get(i);
                String p = turn.getUserPrompt();
                if (p != null && p.length() > 60) p = p.substring(0, 60) + "...";
                w.println("    [" + i + "] 触发: " + turn.getTriggerSource()
                        + " | Prompt: " + (p != null ? p : "null")
                        + (turn.hasImage() ? " [+图片]" : ""));
                for (AgentOutputRecord rec : turn.getAgentActions()) {
                    String t = rec.getText();
                    if (t.length() > 60) t = t.substring(0, 60) + "...";
                    w.println("        -> " + rec.getType() + ": " + t);
                }
            }
        }
        w.println();
    }

    /**
     * 记录人格提示词和合成后的完整提示词。
     */
    public static void logPrompts(String systemPrompt, String userMessage, String eventInstruction, boolean hasScreenshot) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "4. 提示词");

        w.println("User Message:");
        w.println("  " + userMessage);
        w.println("  附带截图: " + (hasScreenshot ? "是" : "否"));
        w.println();

        w.println("System Prompt (完整合成):");
        w.println("┌──────────────────────────────────────────────────────────────");
        for (String line : systemPrompt.split("\n")) {
            w.println("│ " + line);
        }
        w.println("└──────────────────────────────────────────────────────────────");
        w.println();
    }

    /**
     * 记录 LLM 的思考内容（对玩家不可见，仅保留在日志中）。
     */
    public static void logThinking(String thinkingContent) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "4.5 LLM 思考过程（对玩家不可见）");
        w.println("┌──────────────────────────────────────────────────────────────");
        for (String line : thinkingContent.split("\n")) {
            w.println("│ " + line);
        }
        w.println("└──────────────────────────────────────────────────────────────");
        w.println();
    }

    /**
     * 记录 LLM 输出的原内容（包括未解析前的思考部分和回复部分）。
     */
    public static void logRawResponse(String rawContent) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "4.6 LLM 原始响应内容 (Raw Response)");
        w.println("┌──────────────────────────────────────────────────────────────");
        for (String line : rawContent.split("\n")) {
            w.println("│ " + line);
        }
        w.println("└──────────────────────────────────────────────────────────────");
        w.println();
    }

    /**
     * 记录 JSON 解析后的结构化响应。
     */
    public static void logParsedResponse(AgentResponse response) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "4.7 解析后的结构化响应 (Parsed Response)");
        w.println("thinking:        " + (response.hasThinking() ? "(有)" : "(无)"));
        w.println("actions:         " + response.getActions().size() + " 个");
        for (dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponse.ActionRequest req : response.getActions()) {
            String contentSnippet = req.content != null && req.content.length() > 50 
                    ? req.content.substring(0, 50) + "..." 
                    : req.content;
            w.println("  - [" + req.type + "] " + contentSnippet);
        }
        w.println();
    }

    /**
     * 记录 Agent 的行动结果。
     *
     * @param actionType   动作类型 (如 "speech")
     * @param actionDetail 动作内容 (如 LLM 回复文本)
     */
    public static void logAction(String actionType, String actionDetail) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "5. Agent 行动");

        w.println("动作类型: " + actionType);
        w.println("动作内容:");
        w.println("┌──────────────────────────────────────────────────────────────");
        for (String line : actionDetail.split("\n")) {
            w.println("│ " + line);
        }
        w.println("└──────────────────────────────────────────────────────────────");
        w.println();
    }

    /**
     * 记录调试信息（extras dump 等辅助数据）。
     */
    public static void logDebug(String debugContent) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "DEBUG");
        for (String line : debugContent.split("\n")) {
            w.println("│ " + line);
        }
        w.println();
    }

    /**
     * 记录错误信息。
     */
    public static void logError(String message, Throwable error) {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        writeSectionHeader(w, "ERROR");
        w.println("错误: " + message);
        if (error != null) {
            w.println("异常: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            error.printStackTrace(w);
        }
        w.println();
    }

    /**
     * 结束当前会话，关闭文件。在任务执行完毕后调用。
     */
    public static void endSession() {
        LogSession session = CURRENT_SESSION.get();
        if (session == null)
            return;
        PrintWriter w = session.writer;

        w.println("═══════════════════════════════════════════════════════════════");
        w.println("  日志结束 — " + LocalDateTime.now().format(DISPLAY_TIME_FMT));
        w.println("═══════════════════════════════════════════════════════════════");
        w.flush();
        w.close();
        CURRENT_SESSION.set(null);
    }

    // ═══════════════════════════════════════════════════════════════
    // 清空日志
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除日志目录下的所有 .log 文件。
     *
     * @return 删除的文件数
     */
    public static int clearAllLogs() {
        try {
            Path dir = getLogDir();
            if (!Files.exists(dir))
                return 0;

            File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".log"));
            if (files == null)
                return 0;

            int count = 0;
            for (File f : files) {
                if (f.delete())
                    count++;
            }
            return count;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * 获取日志目录路径。
     */
    public static Path getLogDir() {
        return Path.of(LOG_DIR);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════════

    private static Path ensureLogDir() throws IOException {
        Path dir = getLogDir();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static void writeSectionHeader(PrintWriter w, String title) {
        w.println("───────────────────────────────────────────────────────────────");
        w.println("  " + title);
        w.println("───────────────────────────────────────────────────────────────");
    }

    /** 日志会话持有者 */
    private static class LogSession {
        final PrintWriter writer;
        final String filePath;

        LogSession(PrintWriter writer, String filePath) {
            this.writer = writer;
            this.filePath = filePath;
        }
    }
}
