package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidBrain;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.Pillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 掠夺者的原版射击入口与行矢施放互斥。 */
@Mixin(Pillager.class)
public abstract class PillagerWayfarerAttackMixin {
    @Inject(method = "performRangedAttack", at = @At("HEAD"), cancellable = true)
    private void meridian$blockVanillaShot(LivingEntity target, float power, CallbackInfo callback) {
        // 已上弦原版弩不能在功法期间另射一发，原有上弦组件因此保持不变。
        if (HumanoidBrain.blocksRanged((Pillager) (Object) this)) {
            callback.cancel();
        }
    }
}
