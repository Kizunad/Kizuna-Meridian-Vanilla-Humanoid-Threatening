package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostFacts;
import net.minecraft.world.entity.LivingEntity;

/** 从当前生物实体采集功法宿主需要的移动事实。 */
final class HumanoidTechniqueFacts {
    private HumanoidTechniqueFacts() {
        // 事实只从可信实体读取，不保存第二份环境状态。
    }

    static TechniqueHostFacts inspect(LivingEntity entity) {
        // 五项移动状态始终显式回传，腿法条件不依赖客户端或默认值。
        return TechniqueHostFacts.newBuilder()
                .setGrounded(entity.onGround())
                .setInWater(entity.isInWater())
                .setClimbing(entity.onClimbable())
                .setRiding(entity.isPassenger())
                .setGliding(entity.isFallFlying())
                .build();
    }
}
