package dreamdust.talking_weapon.basic_talking_weapon.agent.action;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音动作：在 ActionBar 上逐行展示文字。
 * 这是当前最核心的输出方式。
 * <p>
 * {@link #perform()} 设计为在 <b>worker 线程</b>上调用（因为需要 Thread.sleep 控制节奏），
 * 内部通过 {@code Minecraft.getInstance().execute()} 将每行文字提交到主线程显示。
 */
public class SpeechAction extends AgentAction {

    private static final int MAX_CHARS_PER_LINE = 26;

    /** 要显示的完整文本 */
    private final String text;

    public SpeechAction(String text) {
        super("speech");
        this.text = text;
    }

    /**
     * 在 worker 线程上执行：逐行将文字提交到主线程 ActionBar 显示。
     * 可被 Thread.interrupt() 打断（用于左键取消）。
     */
    @Override
    public void perform() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        List<String> lines = wrapByChars(text, MAX_CHARS_PER_LINE);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(line), true);
                }
            });

            if (i < lines.size() - 1) {
                try {
                    Thread.sleep(calculateLineDisplayMillis(line));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public long estimatedDurationMs() {
        List<String> lines = wrapByChars(text, MAX_CHARS_PER_LINE);
        long total = 0;
        for (String line : lines) {
            total += calculateLineDisplayMillis(line);
        }
        return total;
    }

    public String getText() { return text; }

    @Override
    public String toString() {
        return "SpeechAction[" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "]";
    }

    // ── 文本工具 ──────────────────────────────────────────

    static long calculateLineDisplayMillis(String line) {
        int len = line == null ? 0 : line.length();
        long millis = 1000L + len * 55L;
        return Math.max(1200L, Math.min(2800L, millis));
    }

    static List<String> wrapByChars(String text, int maxCharsPerLine) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        String[] paragraphs = text.replace("\r", "").split("\n");
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            int start = 0;
            while (start < paragraph.length()) {
                int end = Math.min(start + maxCharsPerLine, paragraph.length());
                lines.add(paragraph.substring(start, end));
                start = end;
            }
        }
        return lines;
    }
}
