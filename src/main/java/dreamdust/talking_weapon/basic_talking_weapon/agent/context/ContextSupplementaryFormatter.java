package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 context extras 中面向叙事补充的信息整理成稳定的人类可读文本。
 * <p>
 * 这里只消费一小组已经明确约定的 key，避免把任意 extras 都直接泄露到 prompt。
 */
public final class ContextSupplementaryFormatter {

    private ContextSupplementaryFormatter() {
    }

    public static List<String> buildLines(Map<String, String> extras) {
        if (extras == null || extras.isEmpty()) {
            return List.of();
        }

        ArrayList<String> lines = new ArrayList<>();
        String timeDesc = normalize(extras.get("time_desc"));
        String dimension = normalize(extras.get("dimension"));
        String biome = normalize(extras.get("biome"));
        String registeredLocation = normalize(extras.get("registered_location_prompt"));
        String playerPos = normalize(extras.get("player_pos"));
        String groundLevel = normalize(extras.get("ground_level"));
        String nearbyCreatures = normalize(extras.get("nearby_creatures"));
        String nearbyObjects = normalize(extras.get("nearby_objects"));
        String blocksSummary = normalize(extras.get("surrounding_blocks_summary"));

        if (timeDesc != null || dimension != null || biome != null) {
            StringBuilder line = new StringBuilder("环境: ");
            boolean appended = false;
            if (timeDesc != null) {
                line.append("时间为").append(timeDesc);
                appended = true;
            }
            if (dimension != null) {
                if (appended) {
                    line.append("  ");
                }
                line.append("身处维度:").append(dimension);
                appended = true;
            }
            if (biome != null) {
                if (appended) {
                    line.append("  ");
                }
                line.append("群系:").append(biome);
            }
            lines.add(line.toString());
        }
        if (registeredLocation != null) {
            lines.add("当前登记地点: " + registeredLocation);
        }
        if (playerPos != null) {
            StringBuilder line = new StringBuilder("主人当前坐标: ").append(playerPos);
            if (groundLevel != null) {
                line.append("  地面高度Y=").append(groundLevel);
            }
            lines.add(line.toString());
        }
        if (nearbyCreatures != null) {
            lines.add("附近生物: " + nearbyCreatures);
        }
        if (nearbyObjects != null) {
            lines.add("附近实体: " + nearbyObjects);
        }
        if (blocksSummary != null) {
            lines.add(blocksSummary);
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        return List.copyOf(lines);
    }

    public static boolean appendSection(
            StringBuilder sb,
            String header,
            String linePrefix,
            Map<String, String> extras) {
        if (sb == null) {
            return false;
        }
        List<String> lines = buildLines(extras);
        if (lines.isEmpty()) {
            return false;
        }

        if (header != null && !header.isBlank()) {
            sb.append(header).append("\n");
        }
        String resolvedPrefix = linePrefix == null ? "" : linePrefix;
        for (String line : lines) {
            sb.append(resolvedPrefix).append(line).append("\n");
        }
        return true;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
