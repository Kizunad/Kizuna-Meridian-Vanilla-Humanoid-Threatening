package dev.kizuna.meridian.humanoid.server;

import java.util.Map;

/** 类人大脑配置：决策节奏、敌意记忆、威胁评估、应对阈值与学卷表。 */
public record HumanoidBrainConfig(Schedule schedule, ThreatRules threat, AssessmentRules assessment,
                                  ResponseRules response, VolumeTable volumes, ConcurrencyRules concurrency,
                                  VillagerRules villager, VolumePool volumePool) {
    /** 决策是否逐条写日志。 */
    public enum DecisionLog { QUIET, VERBOSE }

    /** 每个实体错相的决策间隔与日志开关。 */
    public record Schedule(int decisionIntervalTicks, DecisionLog log) { }

    /** 敌意记忆秒数、同伴受击时共同敌对的半径、放下敌人的距离、撤退要拉开到的安全距离、按实体 ID 的盯上距离，以及选敌打分。 */
    public record ThreatRules(double memorySeconds, double allyRadius, double forgetDistance, double safeDistance,
                              Map<String, Double> noticeDistances, ThreatScoring scoring) {
        public ThreatRules {
            // 盯上距离表由加载器校验；这里只冻结副本，运行中不被外部改写。
            noticeDistances = Map.copyOf(noticeDistances);
        }
    }

    /** 战术阈值，以及突击落点离目标的距离、同一式连续施放失败后的屏蔽规则、对手胸甲护甲值到多少算「甲厚」。 */
    public record ResponseRules(TacticRules tactics, double assaultStandOff, int failureLimit,
                                long failureBlockMillis, double armoredChestArmor) { }

    /** 双通道策略：准备式保持跃步带的概率，以及调息时是否继续沿退路移动。 */
    public record ConcurrencyRules(double holdRangeProbability, boolean allowRecoveryMovement) {
        private static final double DEFAULT_HOLD_RANGE_PROBABILITY = 0.5;
        public static final ConcurrencyRules DEFAULTS = new ConcurrencyRules(
                DEFAULT_HOLD_RANGE_PROBABILITY, true);

        /** 当前身体阶段，用于约束技法与移动意图的组合。 */
        public enum CastPhase { IDLE, PREPARE, RECOVER, STRIKE, STAGGERED, FLYING }

        public ConcurrencyRules {
            // 概率由配置加载器校验；直接构造时也拒绝 NaN 和越界值。
            if (!Double.isFinite(holdRangeProbability) || holdRangeProbability < 0
                    || holdRangeProbability > 1) {
                throw new IllegalArgumentException("保持距离带概率必须位于 [0, 1]");
            }
        }

        public boolean allows(CastPhase phase, Decision.Movement movement) {
            // 框架禁止施放或飞行中起跳，调息只允许原地或沿退路移动。
            if (phase == null || movement == null) {
                throw new IllegalArgumentException("并行阶段和移动意图不能为空");
            }
            return switch (phase) {
                case IDLE, STAGGERED -> true;
                case PREPARE, STRIKE -> movement != Decision.Movement.ASSAULT
                        && movement != Decision.Movement.KITE;
                case RECOVER -> movement == Decision.Movement.STAY
                        || movement == Decision.Movement.RETREAT;
                case FLYING -> movement == Decision.Movement.KEEP;
            };
        }
    }

    /** 村民的出击距离、再评估间隔、人数劣势权重，以及近身守卫未施放时的接敌导航倍率。 */
    public record VillagerRules(double sortieDistance, double sortieReconsiderSeconds, double outnumberedWeight,
                                double guardApproachSpeed) {
        public static final double DEFAULT_GUARD_APPROACH_SPEED = 0.805;
        private static final double DEFAULT_SORTIE_DISTANCE = 16;
        private static final double DEFAULT_RECONSIDER_SECONDS = 5;
        public static final VillagerRules DEFAULTS = new VillagerRules(
                DEFAULT_SORTIE_DISTANCE, DEFAULT_RECONSIDER_SECONDS, 1 /* outnumberedWeight */);

        public VillagerRules(double sortieDistance, double sortieReconsiderSeconds, double outnumberedWeight) {
            this(sortieDistance, sortieReconsiderSeconds, outnumberedWeight, DEFAULT_GUARD_APPROACH_SPEED);
        }

        public VillagerRules {
            // 距离、间隔和导航倍率为正，人数权重不为负；直接构造与配置加载使用相同约束。
            if (!(sortieDistance > 0) || !(sortieReconsiderSeconds > 0) || !(outnumberedWeight >= 0)
                    || !Double.isFinite(guardApproachSpeed) || guardApproachSpeed <= 0) {
                throw new IllegalArgumentException("出击距离、再评估间隔和导航倍率必须为正，人数权重不能为负");
            }
        }
    }

    public HumanoidBrainConfig withVolumes(VolumeTable next) {
        // 其余各节不动，只换学卷表。
        return new HumanoidBrainConfig(schedule, threat, assessment, response, next, concurrency, villager, volumePool);
    }

    public HumanoidBrainConfig(Schedule schedule, ThreatRules threat, AssessmentRules assessment,
                               ResponseRules response, VolumeTable volumes, ConcurrencyRules concurrency,
                               VillagerRules villager) {
        this(schedule, threat, assessment, response, volumes, concurrency, villager, VolumePool.DISABLED);
    }

    public HumanoidBrainConfig(Schedule schedule, ThreatRules threat, AssessmentRules assessment,
                               ResponseRules response, VolumeTable volumes, ConcurrencyRules concurrency) {
        // 旧的测试与宿主构造入口沿用村民出击的默认数值。
        this(schedule, threat, assessment, response, volumes, concurrency, VillagerRules.DEFAULTS);
    }

    public HumanoidBrainConfig(Schedule schedule, ThreatRules threat, AssessmentRules assessment,
                               ResponseRules response, VolumeTable volumes) {
        // 旧的测试与宿主构造入口沿用双通道默认策略。
        this(schedule, threat, assessment, response, volumes,
                ConcurrencyRules.DEFAULTS);
    }
}
