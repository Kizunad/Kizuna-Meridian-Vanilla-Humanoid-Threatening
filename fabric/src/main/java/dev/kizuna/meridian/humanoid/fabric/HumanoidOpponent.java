package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.server.TechniqueForecasts;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.minecraft.entity.LivingEntity;

import java.util.Objects;
import java.util.Optional;

/** 本次决策的对手：实体与一次只读观察；没有功法宿主的对手观察为空。 */
record HumanoidOpponent(LivingEntity entity, Optional<TechniqueService.Observation> observation) {
    HumanoidOpponent {
        // 实体必有，观察可空。
        Objects.requireNonNull(entity);
        Objects.requireNonNull(observation);
    }

    Optional<TechniqueForecasts.CastForecast> cast() {
        // 对手当前施放的预报。
        return observation.flatMap(TechniqueService.Observation::cast);
    }
}
