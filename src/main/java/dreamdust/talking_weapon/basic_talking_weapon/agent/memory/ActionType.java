package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;

import java.util.EnumSet;
import java.util.Set;

/**
 * 玩家动作类型枚举。
 * <p>
 * 每个动作类型携带一个主注意力域和可选的跨域注意力标签。
 * 例如 {@code EAT} 既属于 {@link FocusTag#STATUS} 又属于 {@link FocusTag#CONSUMABLE}。
 * <p>
 * 由 {@link ActionRecord} 使用，由 {@code TickEventWatcher} 在监听 Forge 事件时赋值。
 */
public enum ActionType {

    // ── 战斗域 ──
    ATTACK(FocusTag.COMBAT),
    KILL(FocusTag.COMBAT),
    HURT_BY(FocusTag.COMBAT, FocusTag.HAZARD),
    DEATH(FocusTag.COMBAT, FocusTag.HAZARD),

    // ── 劳作域 ──
    MINE_BLOCK(FocusTag.LABOR, FocusTag.BLOCK),
    PLACE_BLOCK(FocusTag.LABOR, FocusTag.BLOCK),

    // ── 生存域（跨域） ──
    EAT(FocusTag.STATUS, FocusTag.CONSUMABLE),
    USE_ITEM(FocusTag.TOOL),

    // ── 移动域（高度概括的锚点位移） ──
    TRAVEL(FocusTag.MOVEMENT, FocusTag.LANDSCAPE),

    // ── 环境变化域 ──
    BIOME_CHANGE(FocusTag.LANDSCAPE, FocusTag.MOVEMENT),
    DIM_CHANGE(FocusTag.LANDSCAPE, FocusTag.MOVEMENT),

    // ── 社交域 ──
    TRADE(FocusTag.SOCIAL),
    INTERACT(FocusTag.SOCIAL);

    /** 主注意力域 */
    private final FocusTag primaryFocus;

    /** 跨域注意力标签（可为空） */
    private final FocusTag[] secondaryFocus;

    ActionType(FocusTag primary, FocusTag... secondary) {
        this.primaryFocus = primary;
        this.secondaryFocus = secondary;
    }

    public FocusTag getPrimaryFocus() {
        return primaryFocus;
    }

    public FocusTag[] getSecondaryFocus() {
        return secondaryFocus;
    }

    /**
     * 返回此动作类型关联的所有 FocusTag（主 + 跨域），以 EnumSet 形式。
     */
    public Set<FocusTag> getAllFocusTags() {
        EnumSet<FocusTag> tags = EnumSet.of(primaryFocus);
        for (FocusTag f : secondaryFocus) {
            tags.add(f);
        }
        return tags;
    }
}
