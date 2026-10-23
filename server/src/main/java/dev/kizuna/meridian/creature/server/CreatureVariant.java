package dev.kizuna.meridian.creature.server;

import dev.kizuna.meridian.core.Realm;

/** 生人的两档经脉变体；变体决定境界、开拓层级和密度区间。 */
public enum CreatureVariant {
    WANDERING_QI(Realm.WANDERING_QI, 1),
    TRUE_QI(Realm.TRUE_QI, 2);

    private final Realm realm;
    private final int maximumAttributeTier;

    CreatureVariant(Realm realm, int maximumAttributeTier) {
        // 属性阶数上限与境界绑定，避免调用方单独传入互相矛盾的组合。
        this.realm = realm;
        this.maximumAttributeTier = maximumAttributeTier;
    }

    public Realm realm() {
        // 生成快照时使用 Framework 的统一境界枚举。
        return realm;
    }

    public int maximumAttributeTier() {
        // Qi-Variants 的抽取上限由变体固定。
        return maximumAttributeTier;
    }
}
