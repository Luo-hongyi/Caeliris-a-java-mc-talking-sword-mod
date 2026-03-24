package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.Set;

/**
 * 动作时间段条目的公共只读视图。
 * <p>
 * moving / fixed 两类 entry 共享这组基础字段，
 * 但仍保留各自的专属元数据。
 */
public interface ActionSegmentEntryView {

    ActionType getType();

    String getTarget();

    int getCount();

    long getFirstTimestamp();

    long getLastTimestamp();

    Set<DetailTag> getDetailTags();
}
