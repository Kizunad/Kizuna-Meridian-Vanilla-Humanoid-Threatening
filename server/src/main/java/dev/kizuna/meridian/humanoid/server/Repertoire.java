package dev.kizuna.meridian.humanoid.server;

import java.util.List;
import java.util.Set;

/** 本次决策时可施放的式：冷却、绑定和费用已经通过，只剩战术条件待判。 */
public record Repertoire(List<Strike> strikes, List<Support> supports, Timing timing) {
    /** 打击式：首击毫秒、打击距离和按当前对手估算的僵直毫秒，0 表示打不出僵直。 */
    public record Strike(String technique, long firstHitMillis, double reach, long staggerMillis) { }

    /** 目录 ai_hints.support 声明的辅助式。 */
    public record Support(String technique, Role role, int order, Gate gate, long refreshBeforeExpiryMillis) { }

    /** 辅助式的触发门槛：到目标或最近威胁的距离范围、真元占容和所绑定肌肉疲劳。 */
    public record Gate(double minimumDistance, double maximumDistance, double minimumQiRatio, Fatigue fatigue,
                       Set<Requirement> requirements) {
        public Gate {
            // 要求集合只读。
            requirements = Set.copyOf(requirements);
        }
    }

    /** 门槛疲劳比与当前疲劳比；疲劳比为 1 减去所绑定肌肉有效力量与基础力量之比。 */
    public record Fatigue(double minimum, double current) { }

    /** 辅助角色：接敌前准备、远离威胁时恢复、取得机动状态。 */
    public enum Role { PREPARE, RECOVER, REPOSITION }

    /** 辅助式的持物要求。 */
    public enum Requirement { MELEE_WEAPON }

    /** 轻身剩余、自己下一击剩余、走出来袭范围所需毫秒，以及跃步追击的最小距离。 */
    public record Timing(long lightBodyRemainingMillis, long ownNextHitMillis, long exitMillis,
                         double leapMinimumDistance) { }

    public Repertoire {
        // 候选列表只读。
        strikes = List.copyOf(strikes);
        supports = List.copyOf(supports);
    }
}
