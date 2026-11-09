package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidTempo;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RangedCrossbowAttackGoal.class)
public abstract class CrossbowTempoMixin {
    @Shadow @Final private Monster mob;
    @Shadow private int attackDelay;
    @Unique private int meridian$previousDelay;

    @Inject(method = "tick", at = @At("HEAD"))
    private void meridian$rememberDelay(CallbackInfo callback) {
        // 只识别原版刚重设的射击等待，逐 tick 倒数不得反复缩放。
        meridian$previousDelay = attackDelay;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void meridian$scaleNewDelay(CallbackInfo callback) {
        // 上弦完成后新增的原版等待按手臂速度拉长，功法的射击不经过此 Goal。
        if (attackDelay > meridian$previousDelay) {
            attackDelay = HumanoidTempo.attackInterval(mob, attackDelay);
        }
    }
}
