package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

/**
 * Agent 显式状态机：描述武器灵魂当前的情绪、熟悉度等状态。
 * <p>
 * 此状态会注入到 system prompt 中影响 LLM 输出风格，
 * 同时也可被游戏渲染器读取用于视觉效果。
 */
public class AgentState {

    /** 武器灵魂当前的情绪 */
    private Mood mood;

    /** 对玩家的熟悉度 (0-100) */
    private int familiarity;

    /** 当前目标/意图描述 */
    private String currentGoal;

    public AgentState() {
        this.mood = Mood.NEUTRAL;
        this.familiarity = 0;
        this.currentGoal = "";
    }

    // ---- Getters & Setters ----

    public Mood getMood() { return mood; }
    public void setMood(Mood mood) { this.mood = mood; }

    public int getFamiliarity() { return familiarity; }
    public void setFamiliarity(int familiarity) {
        this.familiarity = Math.max(0, Math.min(100, familiarity));
    }

    public String getCurrentGoal() { return currentGoal; }
    public void setCurrentGoal(String currentGoal) { this.currentGoal = currentGoal; }

    /**
     * 生成用于注入 system prompt 的状态描述。
     */
    public String toPromptSnippet() {
        return "[AgentState] mood=" + mood.name()
                + ", familiarity=" + familiarity
                + ", goal=" + currentGoal;
    }

    @Override
    public String toString() {
        return toPromptSnippet();
    }

    // ---- Mood Enum ----

    public enum Mood {
        NEUTRAL,
        HAPPY,
        ANGRY,
        EXCITED,
        BORED,
        SCARED,
        SAD,
        CURIOUS
    }
}
