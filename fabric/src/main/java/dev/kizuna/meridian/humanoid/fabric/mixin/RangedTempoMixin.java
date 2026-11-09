package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidTempo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RangedAttackGoal.class)
public abstract class RangedTempoMixin {
    @Shadow @Final private Mob mob;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;floor(F)I"))
    private int meridian$shotInterval(float ticks) {
        // 女巫等远程攻击保留原版距离插值，再缩放攻击间隔。
        return HumanoidTempo.attackInterval(mob, Mth.floor(ticks));
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;floor(D)I"))
    private int meridian$initialInterval(double ticks) {
        // 初次锁定目标的等待也遵守同一 tempo，避免第一发绕过迟滞。
        return HumanoidTempo.attackInterval(mob, Mth.floor(ticks));
    }
}
