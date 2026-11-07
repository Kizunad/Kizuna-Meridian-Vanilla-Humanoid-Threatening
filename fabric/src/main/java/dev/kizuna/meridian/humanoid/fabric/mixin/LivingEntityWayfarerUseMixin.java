package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.human.fabric.HumanProjectileItems;
import dev.kizuna.meridian.humanoid.fabric.HumanoidBrain;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 生物施放行矢期间屏蔽原版弓弩使用入口。 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityWayfarerUseMixin {
    @Inject(method = "startUsingItem", at = @At("HEAD"), cancellable = true)
    private void meridian$blockRangedUse(InteractionHand hand, CallbackInfo callback) {
        // 仅拦当前行矢施放者的远程武器，盾、药水和其他生物不受影响。
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity instanceof Mob mob && HumanoidBrain.blocksRanged(mob)
                && HumanProjectileItems.category(mob.getItemInHand(hand))
                != ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED) {
            callback.cancel();
        }
    }
}
