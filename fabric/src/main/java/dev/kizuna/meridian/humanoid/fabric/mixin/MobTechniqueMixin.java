package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidBrain;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobTechniqueMixin {
    @Shadow @Final protected GoalSelector goalSelector;
    @Shadow @Final protected GoalSelector targetSelector;

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void meridian$humanoidBrain(CallbackInfo callback) {
        // 在原版 Goal 与村民大脑之前决策；未接管的物种（含 brain = false）在入口即跳过。
        HumanoidBrain.tick((Mob) (Object) this, goalSelector, targetSelector);
    }

    @Inject(method = "setTarget", at = @At("HEAD"))
    private void meridian$targetChanged(LivingEntity target, CallbackInfo callback) {
        // 原版改目标时同步「以谁为目标」的反查表，只在服务端记。
        Mob self = (Mob) (Object) this;
        if (!self.level().isClientSide) {
            HumanoidBrain.targetChanged(self, self.getTarget(), target);
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void meridian$blockVanillaAttack(Entity target, CallbackInfoReturnable<Boolean> callback) {
        // 功法打击自行结算，原版近战不能在施放或撤退中叠加一次攻击。
        if (HumanoidBrain.blocksMelee((Mob) (Object) this)) {
            callback.setReturnValue(false /* attacked */);
        }
    }
}
