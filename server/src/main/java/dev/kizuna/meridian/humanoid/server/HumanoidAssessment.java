package dev.kizuna.meridian.humanoid.server;

/** 敌我威胁评估：硬性撤退条件、进攻手段、战力差依次判定，撤退后按滞回门槛回归。 */
public final class HumanoidAssessment {
    /** 对敌或撤退。 */
    public enum Stance { ENGAGE, RETREAT }

    /** 和对手拉开的情况：现在相距多少格，撤退中连着多少秒没被它逼近、也没挨它打。 */
    public record Separation(double distance, double unpressedSeconds) { }

    private HumanoidAssessment() {
        // 纯函数集合，不持有实体或配置。
    }

    public static Stance stance(Fighter self, Fighter opponent, Stance previous, Separation separation,
                                AssessmentRules rules) {
        // 只比一对一的战力，不看人数。
        return stance(self, opponent, previous, separation, rules, 0 /* handicap */);
    }

    public static double outnumbered(int enemies, int allies, double weight) {
        // 人数上的劣势：对方每多一个、扣掉我方帮手以后还多出来的，各算一份权重；不多就是 0。
        return weight * Math.max(0, enemies - 1 - allies);
    }

    public static Stance stance(Fighter self, Fighter opponent, Stance previous, Separation separation,
                                AssessmentRules rules, double handicap) {
        // 重伤、双臂虚脱、没有进攻手段一律撤退；撤退中的一方恢复并拉开以后才重新比较战力。
        // handicap 是人数上的劣势，从战力差里减掉。
        if (mustRetreat(self, rules.retreat()) || !offensive(self)) {
            return Stance.RETREAT;
        }
        if (previous == Stance.RETREAT && !recovered(self, separation, rules.recovery())) {
            return Stance.RETREAT;
        }
        double margin = power(self, rules) - power(opponent, rules) - handicap;
        if (margin >= rules.engageMargin()) {
            return Stance.ENGAGE;
        }
        return Stance.RETREAT;
    }

    public static Stance standing(Fighter self, AssessmentRules rules) {
        // 有「不退」职守的一方不比战力、不看伤势：只有两臂虚脱或没有进攻手段时才撤，其余一律对敌。
        boolean spent = self.condition().armStrengthRatio() < rules.retreat().armStrengthRatio();
        return spent || !offensive(self) ? Stance.RETREAT : Stance.ENGAGE;
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

    private static boolean recovered(Fighter self, Separation separation, AssessmentRules.Recovery recovery) {
        // 完好率、臂力达标，并且拉开了，才结束撤退。
        var condition = self.condition();
        return condition.hpRatio() >= recovery.hpRatio()
                && condition.armStrengthRatio() >= recovery.armStrengthRatio()
                && apart(separation, recovery);
    }

    private static boolean apart(Separation separation, AssessmentRules.Recovery recovery) {
        // 到了安全距离算拉开；屋里、谷底这些地方永远退不到那么远，对手连着一阵不逼近、也没打到我，也算拉开。
        return separation.distance() >= recovery.distance()
                || separation.unpressedSeconds() >= recovery.unpressedSeconds();
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
