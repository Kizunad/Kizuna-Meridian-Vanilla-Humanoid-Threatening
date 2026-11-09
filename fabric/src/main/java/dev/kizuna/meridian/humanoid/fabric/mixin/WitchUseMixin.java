package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidCharmController;
import dev.kizuna.meridian.humanoid.fabric.HumanoidRestrictions;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Witch.class)
public abstract class WitchUseMixin extends Raider {
    @Shadow @Final private static Identifier SPEED_MODIFIER_DRINKING_ID;

    protected WitchUseMixin(EntityType<? extends Raider> type, Level level) {
        // Mixin 继承原父类，跳过自喝分支时仍调用其正常实体与 AI 推进。
        super(type, level);
    }

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void meridian$rejectSelfDrink(CallbackInfo callback) {
        // 女巫内置喝药绕过普通物品入口；只跳过其自喝分支，保留父类运动与攻击 AI。
        Witch witch = (Witch) (Object) this;
        if (!level().isClientSide() && HumanoidRestrictions.blocksDrinking(witch)) {
            if (witch.isDrinkingPotion()) {
                witch.setUsingItem(false /* drinking */);
                witch.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                witch.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SPEED_MODIFIER_DRINKING_ID);
            }
            super.aiStep();
            callback.cancel();
        }
    }

    @Inject(method = "performRangedAttack", at = @At("HEAD"), cancellable = true)
    private void meridian$rejectLockedThrow(LivingEntity target, float power, CallbackInfo callback) {
        // 定身与盟友保护也覆盖直接调用女巫投掷入口。
        Witch witch = (Witch) (Object) this;
        if (HumanoidRestrictions.locked(witch) || HumanoidCharmController.protects(witch, target)) {
            callback.cancel();
        }
    }
}
