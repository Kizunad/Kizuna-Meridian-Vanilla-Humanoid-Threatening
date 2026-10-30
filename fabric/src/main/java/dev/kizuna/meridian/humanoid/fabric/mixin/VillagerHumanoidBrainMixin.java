package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidVillagerAdapter;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 村民的原版大脑经类人接入转一道：大脑不指挥时原样执行。 */
@Mixin(VillagerEntity.class)
public abstract class VillagerHumanoidBrainMixin {
    @Redirect(method = "mobTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/ai/brain/Brain;tick(Lnet/minecraft/server/world/ServerWorld;"
                    + "Lnet/minecraft/entity/LivingEntity;)V"))
    private void meridian$humanoidBrain(Brain<VillagerEntity> brain, ServerWorld world, LivingEntity entity) {
        // 交给接入适配器决定走原版任务还是只推进记忆与感知。
        HumanoidVillagerAdapter.tick(brain, world, (VillagerEntity) (Object) this);
    }
}
