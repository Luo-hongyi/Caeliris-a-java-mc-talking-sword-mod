package dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service;

import com.google.gson.Gson;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent LLM 响应解析器：将 LLM 输出的字符串解析为 {@link AgentResponse}。
 * <p>
 * 解析策略（优先级由高到低）：
 * <ol>
 * <li>从原始字符串提取第一个 {@code {...}} 块，用 Gson 直接反序列化。</li>
 * <li>如果上一步失败，尝试从 Markdown 代码块（<code>```json ... ```</code>）中提取 JSON。</li>
 * <li>如果仍失败，构造一个 fallback {@link AgentResponse}：把整段文字当作 {@code speech}。</li>
 * </ol>
 * <p>
 * 任何情况下都不会抛出异常——调用方可直接使用返回值。
 */
public final class AgentResponseParser {

    private static final Gson GSON = new Gson();

    /** 匹配第一个 JSON 对象块（大括号包裹） */
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*}", Pattern.DOTALL);

    /** 匹配 Markdown 代码块 ```json ... ``` */
    private static final Pattern MARKDOWN_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.DOTALL);

    private AgentResponseParser() {
    }

    /**
     * 解析 LLM 原始输出字符串为 {@link AgentResponse}。
     * 永远不抛出，失败时优雅降级。
     *
     * @param raw LLM 返回的原始文本
     * @return 解析结果；失败时 speech 字段为原始文本
     */
    public static AgentResponse parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return fallback("（剑沉默着）");
        }

        // ── 策略 1：直接提取 JSON 对象块 ──
        Matcher jsonMatcher = JSON_BLOCK.matcher(raw);
        if (jsonMatcher.find()) {
            AgentResponse result = tryDeserialize(jsonMatcher.group());
            if (result != null)
                return result;
        }

        // ── 策略 2：从 Markdown 代码块提取 ──
        Matcher mdMatcher = MARKDOWN_JSON.matcher(raw);
        if (mdMatcher.find()) {
            AgentResponse result = tryDeserialize(mdMatcher.group(1).trim());
            if (result != null)
                return result;
        }

        // ── 策略 3：降级 — 全文当作 speech ──
        return fallback(raw.trim());
    }

    /**
     * 尝试用 Gson 反序列化，失败时返回 null。
     */
    private static AgentResponse tryDeserialize(String json) {
        try {
            AgentResponse resp = GSON.fromJson(json, AgentResponse.class);
            // 确保至少有一个有效字段
            if (resp != null && (resp.hasActions()
                    || resp.hasThinking())) {
                return resp;
            }
        } catch (Exception ignored) {
            // 解析失败，继续尝试下一策略
        }
        return null;
    }

    /**
     * 构建降级响应：将整段文字作为 speech action，其他字段留空。
     */
    private static AgentResponse fallback(String rawText) {
        AgentResponse resp = new AgentResponse();
        resp.getActions().add(new AgentResponse.ActionRequest("speech", rawText));
        return resp;
    }
}
