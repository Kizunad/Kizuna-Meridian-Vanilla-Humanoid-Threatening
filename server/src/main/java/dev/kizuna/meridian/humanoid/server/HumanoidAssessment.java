package dev.kizuna.meridian.humanoid.server;

/** 敌我威胁评估：硬性撤退条件、进攻手段、战力差依次判定，撤退后按滞回门槛回归。 */
public final class HumanoidAssessment {
    /** 对敌或撤退。 */
    public enum Stance { ENGAGE, RETREAT }

    private HumanoidAssessment() {
        // 纯函数集合，不持有实体或配置。
    }

    public static Stance stance(Fighter self, Fighter opponent, Stance previous, double distance,
                                AssessmentRules rules) {
        // 重伤、双臂虚脱、没有进攻手段一律撤退；撤退中的一方恢复并拉开距离后才重新比较战力。
        if (mustRetreat(self, rules.retreat()) || !offensive(self)) {
            return Stance.RETREAT;
        }
        if (previous == Stance.RETREAT && !recovered(self, distance, rules.recovery())) {
            return Stance.RETREAT;
        }
        double margin = power(self, rules) - power(opponent, rules);
        if (margin >= rules.engageMargin()) {
            return Stance.ENGAGE;
        }
        return Stance.RETREAT;
    }

    public static double power(Fighter fighter, AssessmentRules rules) {
        // 境界档、可施放打击式、持武器、身体与臂力中较弱者、真元占容和正在施放线性相加。
        var weights = rules.weights();
        var condition = fighter.condition();
        double body = Math.min(condition.hpRatio(), condition.armStrengthRatio());
        return weights.realm() * fighter.tier()
                + weights.strike() * presence(fighter, Fighter.Means.CASTABLE_STRIKE)
                + weights.armed() * armedPresence(fighter)
                + weights.body() * body
                + weights.qi() * condition.qiRatio()
                + rules.castingWeight() * presence(fighter, Fighter.Means.CASTING);
    }

    private static boolean mustRetreat(Fighter self, AssessmentRules.Retreat retreat) {
        // 任一门槛低于下限都直接撤退，不再比较对方。
        var condition = self.condition();
        return condition.hpRatio() < retreat.hpRatio()
                || condition.weakestPartHpRatio() < retreat.partHpRatio()
                || condition.armStrengthRatio() < retreat.armStrengthRatio();
    }

    private static boolean offensive(Fighter self) {
        // 会可施放的打击式，或本身有原版攻击能力。
        return self.has(Fighter.Means.CASTABLE_STRIKE) || self.has(Fighter.Means.VANILLA_OFFENSE);
    }

    private static boolean recovered(Fighter self, double distance, AssessmentRules.Recovery recovery) {
        // 完好率、臂力和距离同时达标才结束撤退。
        var condition = self.condition();
        return condition.hpRatio() >= recovery.hpRatio()
                && condition.armStrengthRatio() >= recovery.armStrengthRatio()
                && distance >= recovery.distance();
    }

    private static double presence(Fighter fighter, Fighter.Means means) {
        // 有该项手段计 1，否则计 0。
        if (fighter.has(means)) {
            return 1;
        }
        return 0;
    }

    private static double armedPresence(Fighter fighter) {
        // 持任一种武器计 1。
        if (fighter.armed()) {
            return 1;
        }
        return 0;
    }
}
