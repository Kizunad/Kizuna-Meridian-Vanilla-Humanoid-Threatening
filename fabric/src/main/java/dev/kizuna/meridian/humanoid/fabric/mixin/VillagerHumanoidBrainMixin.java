package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidVillagerAdapter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 村民的原版大脑经类人接入转一道：大脑不指挥时原样执行。 */
@Mixin(Villager.class)
public abstract class VillagerHumanoidBrainMixin {
    @Redirect(method = "customServerAiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void meridian$humanoidBrain(Brain<Villager> brain, ServerLevel world, LivingEntity entity) {
        // 交给接入适配器决定走原版任务还是只推进记忆与感知。
        HumanoidVillagerAdapter.tick(brain, world, (Villager) (Object) this);
    }
}
