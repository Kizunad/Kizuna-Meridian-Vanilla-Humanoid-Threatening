package dev.kizuna.meridian.humanoid.server;

/** 类人大脑配置：决策节奏、敌意记忆、威胁评估、应对阈值与学卷表。 */
public record HumanoidBrainConfig(Schedule schedule, ThreatRules threat, AssessmentRules assessment,
                                  ResponseRules response, VolumeTable volumes) {
    /** 决策是否逐条写日志。 */
    public enum DecisionLog { QUIET, VERBOSE }

    /** 每个实体错相的决策间隔与日志开关。 */
    public record Schedule(int decisionIntervalTicks, DecisionLog log) { }

    /** 敌意记忆秒数、同伴受击时共同敌对的半径、放下敌人的距离与撤退要拉开到的安全距离。 */
    public record ThreatRules(double memorySeconds, double allyRadius, double forgetDistance, double safeDistance) { }

    /** 战术阈值，以及突击落点离目标的距离和同一式连续施放失败后的屏蔽规则。 */
    public record ResponseRules(TacticRules tactics, double assaultStandOff, int failureLimit,
                                long failureBlockMillis) { }
}
