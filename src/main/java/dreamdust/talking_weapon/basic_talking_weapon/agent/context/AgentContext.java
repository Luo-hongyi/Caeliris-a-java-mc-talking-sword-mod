package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;

import java.util.*;

/**
 * Agent 感知上下文的不可变快照。
 * <p>
 * 核心字段（生命、坐标、群系等）由 {@code BaseContextCollector} 填充；
 * 任意扩展数据由其他 {@code IContextCollector} 写入 {@code extras} Map。
 * <p>
 * 在主线程上采集，随后传递给后台 Agent 任务使用。
 */
public final class AgentContext {

    /** 快照采集时的物理时间戳（毫秒） */
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

    /** 单调递增的绝对游戏时间（tick），用于衰减与跨轮 delta。 */
    private final long gameTime;

    /** 当前是第几天（1-based），用于叙事锚点。 */
    private final long dayIndex;

    /** 当前当天时段的 tick，范围 [0, 23999]。 */
    private final long timeOfDayTick;

    /** 是否正在下雨 */
    private final boolean raining;

    /** 截图的 Base64 编码 (可为 null 表示本次无视觉输入) */
    private final String screenshotBase64;

    /** 触发来源描述，如 "right_click", "attack", "enter_biome" */
    private final String triggerSource;

    /** 采集器写入的扩展数据（key → 人类可读文本），不可变 */
    private final Map<String, String> extras;

    /** 采集器写入的带标签上下文条目，不可变 */
    private final List<TaggedContextEntry> taggedEntries;

    private AgentContext(Builder builder) {
        this.timestamp = builder.timestamp;
        this.health = builder.health;
        this.maxHealth = builder.maxHealth;
        this.foodLevel = builder.foodLevel;
        this.biome = builder.biome;
        this.posX = builder.posX;
        this.posY = builder.posY;
        this.posZ = builder.posZ;
        this.gameTime = builder.gameTime;
        this.dayIndex = builder.dayIndex;
        this.timeOfDayTick = builder.timeOfDayTick;
        this.raining = builder.raining;
        this.screenshotBase64 = builder.screenshotBase64;
        this.triggerSource = builder.triggerSource;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extras));
        this.taggedEntries = Collections.unmodifiableList(new ArrayList<>(builder.taggedEntries));
    }

    // ---- Getters ----

    public long getTimestamp() {
        return timestamp;
    }

    public float getHealth() {
        return health;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public int getFoodLevel() {
        return foodLevel;
    }

    public String getBiome() {
        return biome;
    }

    public double getPosX() {
        return posX;
    }

    public double getPosY() {
        return posY;
    }

    public double getPosZ() {
        return posZ;
    }

    public long getGameTime() {
        return gameTime;
    }

    public long getDayIndex() {
        return dayIndex;
    }

    public long getTimeOfDayTick() {
        return timeOfDayTick;
    }

    public boolean isRaining() {
        return raining;
    }

    public String getScreenshotBase64() {
        return screenshotBase64;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    /** 获取所有扩展数据（不可变视图） */
    public Map<String, String> getExtras() {
        return extras;
    }

    /** 按 key 获取单条扩展数据，不存在返回 null */
    public String getExtra(String key) {
        return extras.get(key);
    }

    /** 获取所有带标签的上下文条目（不可变列表） */
    public List<TaggedContextEntry> getTaggedEntries() {
        return taggedEntries;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentContext{")
                .append("trigger=").append(triggerSource)
                .append(", biome=").append(biome)
                .append(", hp=").append(health).append("/").append(maxHealth)
                .append(", pos=(").append((int) posX).append(",").append((int) posY).append(",").append((int) posZ)
                .append(")")
                .append(", gameTime=").append(gameTime)
                .append(", dayIndex=").append(dayIndex)
                .append(", timeOfDayTick=").append(timeOfDayTick)
                .append(", raining=").append(raining)
                .append(", hasScreenshot=").append(screenshotBase64 != null);

        if (!extras.isEmpty()) {
            sb.append(", extras={");
            extras.forEach((k, v) -> sb.append(k).append("=").append(v).append(", "));
            sb.setLength(sb.length() - 2); // 去掉末尾 ", "
            sb.append("}");
        }

        if (!taggedEntries.isEmpty()) {
            sb.append(", taggedEntries=").append(taggedEntries.size()).append("条");
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
        private long gameTime = -1L;
        private long dayIndex = -1L;
        private long timeOfDayTick = -1L;
        private boolean raining;
        private String screenshotBase64;
        private String triggerSource = "unknown";
        private final Map<String, String> extras = new LinkedHashMap<>();
        private final List<TaggedContextEntry> taggedEntries = new ArrayList<>();

        private Builder() {
        }

        public Builder timestamp(long val) {
            this.timestamp = val;
            return this;
        }

        public Builder health(float val) {
            this.health = val;
            return this;
        }

        public Builder maxHealth(float val) {
            this.maxHealth = val;
            return this;
        }

        public Builder foodLevel(int val) {
            this.foodLevel = val;
            return this;
        }

        public Builder biome(String val) {
            this.biome = val;
            return this;
        }

        public Builder pos(double x, double y, double z) {
            this.posX = x;
            this.posY = y;
            this.posZ = z;
            return this;
        }

        public Builder gameTime(long val) {
            this.gameTime = val;
            return this;
        }

        public Builder dayIndex(long val) {
            this.dayIndex = val;
            return this;
        }

        public Builder timeOfDayTick(long val) {
            this.timeOfDayTick = val;
            return this;
        }

        public Builder raining(boolean val) {
            this.raining = val;
            return this;
        }

        public Builder screenshotBase64(String val) {
            this.screenshotBase64 = val;
            return this;
        }

        public Builder triggerSource(String val) {
            this.triggerSource = val;
            return this;
        }

        /** 写入一条扩展数据（由 IContextCollector 调用） */
        public Builder extra(String key, String value) {
            this.extras.put(key, value);
            return this;
        }

        /** 写入一条带标签的上下文条目（由 IContextCollector 调用） */
        public Builder addTaggedEntry(TaggedContextEntry entry) {
            this.taggedEntries.add(entry);
            return this;
        }

        public AgentContext build() {
            return new AgentContext(this);
        }
    }
}
