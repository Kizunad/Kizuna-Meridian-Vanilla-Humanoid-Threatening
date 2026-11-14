package dev.kizuna.meridian.humanoid.server;

import java.util.Map;

/** 选敌打分：各因素权重、换目标门槛与换过后的最短持续秒数；按实体 ID 覆盖，未列的物种用默认。 */
public record ThreatScoring(Weights defaults, Map<String, Weights> species) {
    /**
     * 每项因素已归一到 0–1，分数是加权和；召唤物和走不到两项是扣分，权重不为正。
     * 除 distance 外全设 0（含 switchMargin、minHoldSeconds）时退化为只按远近选。
     */
    public record Weights(double distance, double lowHealth, double lowHealthAbsolute, double recentDamage,
                          double targetingMe, double danger, double summon, double unreachable,
                          double stickiness, double switchMargin, double minHoldSeconds) { }

    public ThreatScoring {
        // 物种表由加载器校验；这里只冻结副本。
        species = Map.copyOf(species);
    }

    public Weights of(String entityId) {
        // 物种覆盖在加载时已与默认合并，这里直接取整份。
        return species.getOrDefault(entityId, defaults);
    }
}
