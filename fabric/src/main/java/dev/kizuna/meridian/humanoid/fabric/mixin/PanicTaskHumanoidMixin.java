package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidBrain;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.task.PanicTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 被大脑接管的村民由大脑判断撤退，原版恐慌不再因受击或近处敌对启动。 */
@Mixin(PanicTask.class)
public abstract class PanicTaskHumanoidMixin {
    @Inject(method = "wasHurt", at = @At("HEAD"), cancellable = true)
    private static void meridian$brainOwnsHurt(LivingEntity entity, CallbackInfoReturnable<Boolean> callback) {
        // 受击记忆仍保留给大脑读取，只是不触发原版恐慌。
        if (HumanoidBrain.managed(entity)) {
            callback.setReturnValue(false /* hurt */);
        }
    }

    @Inject(method = "isHostileNearby", at = @At("HEAD"), cancellable = true)
    private static void meridian$brainOwnsHostile(LivingEntity entity, CallbackInfoReturnable<Boolean> callback) {
        // 近处敌对记忆仍保留给大脑读取，只是不触发原版恐慌。
        if (HumanoidBrain.managed(entity)) {
            callback.setReturnValue(false /* hostileNearby */);
        }
    }
}
