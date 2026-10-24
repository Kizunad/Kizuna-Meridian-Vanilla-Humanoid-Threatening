package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.core.Realm;

/** 生人的两档经脉变体；变体决定境界、开拓层级和密度区间。 */
public enum HumanoidVariant {
    WANDERING_QI(Realm.WANDERING_QI),
    TRUE_QI(Realm.TRUE_QI);

    private static final int TRUE_QI_ATTRIBUTE_TIER = 2;
    private final Realm realm;

    HumanoidVariant(Realm realm) {
        // 属性阶数上限与境界绑定，避免调用方单独传入互相矛盾的组合。
        this.realm = realm;
    }

    public Realm realm() {
        // 生成快照时使用 Framework 的统一境界枚举。
        return realm;
    }

    public int maximumAttributeTier() {
        // Qi-Variants 的抽取上限由变体固定。
        return this == TRUE_QI ? TRUE_QI_ATTRIBUTE_TIER : 1;
    }
}
