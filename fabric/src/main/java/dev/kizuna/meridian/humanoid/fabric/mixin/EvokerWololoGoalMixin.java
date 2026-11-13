package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEvokerSpells;
import net.minecraft.world.entity.monster.illager.Evoker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 修炼者唤魔者不变羊，凡人照原版（C6c SPEC 4.5）；变羊目标不走父类 canUse，单独拦。 */
@Mixin(targets = "net.minecraft.world.entity.monster.illager.Evoker$EvokerWololoSpellGoal")
public abstract class EvokerWololoGoalMixin {
    @Shadow @Final Evoker this$0;

    @Inject(method = "canUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;"
            + "getNearbyEntities(Ljava/lang/Class;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"
            + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"),
            cancellable = true)
    private void meridian$skipWololoForCultivators(CallbackInfoReturnable<Boolean> callback) {
        // 原版没目标、没在施法、冷却过了、允许生物破坏这几条都过了，要找蓝羊之前才查档位，平时不多查身体。
        if (HumanoidEvokerSpells.blocksWololo(this$0)) {
            callback.setReturnValue(false);
        }
    }
}
