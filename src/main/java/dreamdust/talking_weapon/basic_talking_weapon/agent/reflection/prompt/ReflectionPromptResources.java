package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection 链提示词资源入口。
 * <p>
 * task 专属 prompt 与 module 公用 prompt 全部落在 resources 下，
 * 这里统一负责按 classpath 路径加载并做简单缓存。
 */
public final class ReflectionPromptResources {

    private static final String ROOT = "/assets/talking_weapon/reflection_prompts/";

    public static final String TASK_EPISODE_SYNTHESIS_SYSTEM_HEADER =
            ROOT + "task/episode_synthesis/system_header.txt";
    public static final String TASK_EPISODE_SYNTHESIS_USER_PROMPT =
            ROOT + "task/episode_synthesis/user_prompt.txt";
    public static final String TASK_AGENT_RECENT_STATE_UPDATE_SYSTEM_HEADER =
            ROOT + "task/recent_state_update/system_header.txt";
    public static final String TASK_AGENT_RECENT_STATE_UPDATE_USER_PROMPT =
            ROOT + "task/recent_state_update/user_prompt.txt";

    public static final String TASK_SHARED_IDENTITY_SECTION =
            ROOT + "task/shared/identity_section.txt";
    public static final String TASK_SHARED_REFLECTION_SYSTEM_HEADER =
            ROOT + "task/shared/reflection_system_header.txt";
    public static final String TASK_SHARED_STATE_SNAPSHOT_SECTION =
            ROOT + "task/shared/state_snapshot_section.txt";
    public static final String TASK_SHARED_RELEVANT_PERSONA_HEADER =
            ROOT + "task/shared/relevant_persona_header.txt";
    public static final String TASK_SHARED_RELEVANT_PERSONA_ENTRY =
            ROOT + "task/shared/relevant_persona_entry.txt";
    public static final String TASK_SHARED_RELEVANT_PERSONA_NONE =
            ROOT + "task/shared/relevant_persona_none.txt";
    public static final String TASK_SHARED_TASK_CONSTRAINTS =
            ROOT + "task/shared/task_constraints.txt";
    public static final String TASK_SHARED_OUTPUT_RULES =
            ROOT + "task/shared/output_rules.txt";

    public static final String MODULE_SHARED_MEMORY_SYNTHESIS_INSTRUCTION =
            ROOT + "module/shared/memory_synthesis_instruction.txt";
    public static final String MODULE_SHARED_PLAYER_PROFILE_UPDATE_INSTRUCTION =
            ROOT + "module/shared/player_profile_update_instruction.txt";
    public static final String MODULE_SHARED_RECAP_UPDATE_INSTRUCTION =
            ROOT + "module/shared/recap_update_instruction.txt";
    public static final String MODULE_SHARED_FAVORABILITY_UPDATE_INSTRUCTION =
            ROOT + "module/shared/favorability_update_instruction.txt";
    public static final String MODULE_SHARED_EMOTION_UPDATE_INSTRUCTION =
            ROOT + "module/shared/emotion_update_instruction.txt";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private ReflectionPromptResources() {
    }

    public static String loadRequired(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        return CACHE.computeIfAbsent(resourcePath, ReflectionPromptResources::readRequired);
    }

    public static String formatRequired(String resourcePath, Object... args) {
        return loadRequired(resourcePath).formatted(args);
    }

    private static String readRequired(String resourcePath) {
        try (InputStream inputStream = ReflectionPromptResources.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing reflection prompt resource: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read reflection prompt resource: " + resourcePath, exception);
        }
    }
}
