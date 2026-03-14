package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

public class DistanceFuzzer {

    /**
     * 将精确的方块距离转换为适合剑灵提起的模糊距离描述：
     * - 1个区块内 (<= 16格): 附近
     * - 2个区块内 (<= 32格): 不远处
     * - 3个区块以上 (> 32格): 较远
     */
    public static String fuzzify(double distance) {
        if (distance <= 16.0) {
            return "附近";
        } else if (distance <= 32.0) {
            return "不远处";
        } else {
            return "较远的地方";
        }
    }
}
