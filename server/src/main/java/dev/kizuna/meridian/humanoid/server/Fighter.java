package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.core.Realm;

import java.util.Set;

/** 威胁评估中的一方：境界档、身体状况和手段都来自身体快照与持物事实，不按物种区分。 */
public record Fighter(int tier, Condition condition, Set<Means> means) {
    /** 没有经脉身体的原版生物按凡人档计。 */
    public static final int MORTAL_TIER = 0;

    /** 完好率、最弱存活部位比例、真元占容，以及两臂、两腿中较强一侧相对基础力量的有效比例。 */
    public record Condition(double hpRatio, double weakestPartHpRatio, double qiRatio,
                            double armStrengthRatio, double legStrengthRatio) { }

    /** 进攻手段与当前状态；判断的是能力和持物，不是物种。 */
    public enum Means { CASTABLE_STRIKE, VANILLA_OFFENSE, MELEE_WEAPON, RANGED_WEAPON, SHIELD, CASTING }

    public Fighter {
        // 手段集合只读，评估过程中不能被调用方改写。
        means = Set.copyOf(means);
    }

    public static int tier(Realm realm) {
        // 游炁为 1 档，逐境加一；凡人档留给没有身体的一方。
        return realm.ordinal() + 1;
    }

    public boolean has(Means value) {
        // 按名字查询单项事实。
        return means.contains(value);
    }

    public boolean armed() {
        // 近战武器或远程武器都算持武器。
        return means.contains(Means.MELEE_WEAPON) || means.contains(Means.RANGED_WEAPON);
    }
}
