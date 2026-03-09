package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 感知上下文的不可变快照。
 * <p>
 * 核心字段（生命、坐标、群系等）由 {@code BaseContextCollector} 填充；
 * 任意扩展数据由其他 {@code IContextCollector} 写入 {@code extras} Map。
 * <p>
 * 在主线程上采集，随后传递给后台 Agent 任务使用。
 */
public final class AgentContext {

    /** 快照采集的游戏 tick 时间戳 */
    private final long timestamp;

    /** 玩家当前生命值 */
    private final float health;

    /** 玩家最大生命值 */
    private final float maxHealth;

    /** 玩家饥饿值 */
    private final int foodLevel;

    /** 玩家所在生物群系名称 */
    private final String biome;

    /** 玩家坐标 (x, y, z) */
    private final double posX;
    private final double posY;
    private final double posZ;

    /** 游戏内时间 (tick) */
    private final long dayTime;

    /** 是否正在下雨 */
    private final boolean raining;

    /** 截图的 Base64 编码 (可为 null 表示本次无视觉输入) */
    private final String screenshotBase64;

    /** 触发来源描述，如 "right_click", "attack", "enter_biome" */
    private final String triggerSource;

    /** 采集器写入的扩展数据（key → 人类可读文本），不可变 */
    private final Map<String, String> extras;

    private AgentContext(Builder builder) {
        this.timestamp = builder.timestamp;
        this.health = builder.health;
        this.maxHealth = builder.maxHealth;
        this.foodLevel = builder.foodLevel;
        this.biome = builder.biome;
        this.posX = builder.posX;
        this.posY = builder.posY;
        this.posZ = builder.posZ;
        this.dayTime = builder.dayTime;
        this.raining = builder.raining;
        this.screenshotBase64 = builder.screenshotBase64;
        this.triggerSource = builder.triggerSource;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extras));
    }

    // ---- Getters ----

    public long getTimestamp() { return timestamp; }
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public int getFoodLevel() { return foodLevel; }
    public String getBiome() { return biome; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public long getDayTime() { return dayTime; }
    public boolean isRaining() { return raining; }
    public String getScreenshotBase64() { return screenshotBase64; }
    public String getTriggerSource() { return triggerSource; }

    /** 获取所有扩展数据（不可变视图） */
    public Map<String, String> getExtras() { return extras; }

    /** 按 key 获取单条扩展数据，不存在返回 null */
    public String getExtra(String key) { return extras.get(key); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentContext{")
          .append("trigger=").append(triggerSource)
          .append(", biome=").append(biome)
          .append(", hp=").append(health).append("/").append(maxHealth)
          .append(", pos=(").append((int) posX).append(",").append((int) posY).append(",").append((int) posZ).append(")")
          .append(", dayTime=").append(dayTime)
          .append(", raining=").append(raining)
          .append(", hasScreenshot=").append(screenshotBase64 != null);

        if (!extras.isEmpty()) {
            sb.append(", extras={");
            extras.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
            sb.setLength(sb.length() - 2); // 去掉末尾 ", "
            sb.append("}");
        }

        sb.append('}');
        return sb.toString();
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long timestamp;
        private float health;
        private float maxHealth;
        private int foodLevel;
        private String biome = "unknown";
        private double posX, posY, posZ;
        private long dayTime;
        private boolean raining;
        private String screenshotBase64;
        private String triggerSource = "unknown";
        private final Map<String, String> extras = new LinkedHashMap<>();

        private Builder() {}

        public Builder timestamp(long val) { this.timestamp = val; return this; }
        public Builder health(float val) { this.health = val; return this; }
        public Builder maxHealth(float val) { this.maxHealth = val; return this; }
        public Builder foodLevel(int val) { this.foodLevel = val; return this; }
        public Builder biome(String val) { this.biome = val; return this; }
        public Builder pos(double x, double y, double z) { this.posX = x; this.posY = y; this.posZ = z; return this; }
        public Builder dayTime(long val) { this.dayTime = val; return this; }
        public Builder raining(boolean val) { this.raining = val; return this; }
        public Builder screenshotBase64(String val) { this.screenshotBase64 = val; return this; }
        public Builder triggerSource(String val) { this.triggerSource = val; return this; }

        /** 写入一条扩展数据（由 IContextCollector 调用） */
        public Builder extra(String key, String value) { this.extras.put(key, value); return this; }

        public AgentContext build() {
            return new AgentContext(this);
        }
    }
}
