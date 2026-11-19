package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.CrisisRaiders;
import dev.kizuna.meridian.humanoid.fabric.HumanoidBrain;
import dev.kizuna.meridian.humanoid.fabric.HumanoidCharmController;
import dev.kizuna.meridian.humanoid.fabric.HumanoidRestrictions;
import net.minecraft.server.level.ServerLevel;
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

    @Inject(method = "tick", at = @At("HEAD"))
    private void meridian$checkIndividualClaim(CallbackInfo callback) {
        Mob mob = (Mob) (Object) this;
        if (!mob.level().isClientSide()) {
            HumanoidBrain.updateBodyProjection(mob, goalSelector, targetSelector);
        }
    }

    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void meridian$humanoidBrain(CallbackInfo callback) {
        // 在原版 Goal 与村民大脑之前决策；未接管的物种（含 brain = false）在入口即跳过。
        Mob mob = (Mob) (Object) this;
        if (HumanoidRestrictions.locked(mob)) {
            HumanoidRestrictions.stop(mob);
            callback.cancel();
            return;
        }
        HumanoidBrain.tick(mob, goalSelector, targetSelector);
    }

    @Inject(method = "serverAiStep", at = @At("TAIL"))
    private void meridian$followCaster(CallbackInfo callback) {
        // 凡人不进入修炼者决策，原版任务运行后仍对齐无敌人时的跟随导航。
        HumanoidCharmController.follow((Mob) (Object) this);
    }

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void meridian$targetChanged(LivingEntity target, CallbackInfo callback) {
        // 原版改目标时同步「以谁为目标」的反查表，只在服务端记；被魅惑的、招敌派来的只接受指定的对手。
        Mob self = (Mob) (Object) this;
        if (!self.level().isClientSide() && (!HumanoidCharmController.acceptsTarget(self, target)
                || !CrisisRaiders.acceptsTarget(self, target))) {
            callback.cancel();
            return;
        }
        if (!self.level().isClientSide()) {
            HumanoidBrain.targetChanged(self, self.getTarget(), target);
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void meridian$blockVanillaAttack(ServerLevel level, Entity target,
                                             CallbackInfoReturnable<Boolean> callback) {
        // 功法打击自行结算，原版近战不能在施放或撤退中叠加一次攻击。
        Mob mob = (Mob) (Object) this;
        if (HumanoidBrain.blocksMelee(mob) || HumanoidCharmController.spares(mob, target)) {
            callback.setReturnValue(false /* attacked */);
        }
    }
}
