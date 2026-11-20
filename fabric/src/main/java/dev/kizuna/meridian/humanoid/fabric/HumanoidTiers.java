package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;

/** 给别的附属查一个生人是哪一档；身体还没出生的会在这里按需出生，和大脑第一次遇到威胁时一样。 */
public final class HumanoidTiers {
    private HumanoidTiers() {
        // 纯静态入口。
    }

    /**
     * 这个生人的档位。不是已认领的生人、世界还没接好、或区域服务这一刻不可用时为空，调用方下一 tick 再问。
     */
    public static Optional<HumanoidTier> of(LivingEntity entity) {
        // 准备会话时身体按需出生、按学卷表学卷；档位从身体快照读回，和生成命令、body status 同一口径。
        if (!HumanoidFabric.isReady() || !HumanoidSpeciesHooks.isLivingHumanoid(entity)) {
            return Optional.empty();
        }
        try {
            return Optional.of(HumanoidTier.of(HumanoidTechniqueHost.prepare(entity).body().snapshot()));
        } catch (HumanoidEnvironmentClient.Unavailable unavailable) {
            return Optional.empty();
        }
    }
}
