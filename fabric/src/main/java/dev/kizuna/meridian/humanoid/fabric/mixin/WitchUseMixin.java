package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidCharmController;
import dev.kizuna.meridian.humanoid.fabric.HumanoidRestrictions;
import dev.kizuna.meridian.humanoid.fabric.HumanoidBrain;
import dev.kizuna.meridian.humanoid.fabric.HumanoidWitchHands;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Witch.class)
public abstract class WitchUseMixin extends Raider {
    @Shadow @Final private static Identifier SPEED_MODIFIER_DRINKING_ID;
    @Unique private boolean meridian$blocksSelfDrink;

    protected WitchUseMixin(EntityType<? extends Raider> type, Level level) {
        // Mixin 继承原父类，不替换女巫的完整 AI 推进。
        super(type, level);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void meridian$rejectSelfDrink(CallbackInfo callback) {
        // 中止内置自喝但继续原 aiStep，保留同伴治疗冷却、环境事件与父类运动。
        Witch witch = (Witch) (Object) this;
        meridian$blocksSelfDrink = !level().isClientSide() && HumanoidRestrictions.blocksDrinking(witch);
        if (meridian$blocksSelfDrink) {
            if (witch.isDrinkingPotion()) {
                witch.setUsingItem(false /* drinking */);
                witch.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                witch.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SPEED_MODIFIER_DRINKING_ID);
            }
        }
    }

    @Redirect(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;nextFloat()F"),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/monster/Witch;isDrinkingPotion()Z"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/alchemy/PotionContents;"
                            + "createItemStack(Lnet/minecraft/world/item/Item;Lnet/minecraft/core/Holder;)"
                            + "Lnet/minecraft/world/item/ItemStack;")), require = 4)
    private float meridian$gateSelfDrink(RandomSource random) {
        // 只拦四种自喝的概率入口，保留原随机推进；后面的环境粒子概率不受影响。
        float roll = random.nextFloat();
        return meridian$blocksSelfDrink ? 1 : roll;
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void meridian$aimBrew(CallbackInfo callback) {
        // 原版看向控制完成后对齐功法瞄准，效果时刻读取的仍是实体实际朝向。
        if (!level().isClientSide()) {
            HumanoidWitchHands.aim((Witch) (Object) this);
        }
    }

    @Inject(method = "performRangedAttack", at = @At("HEAD"), cancellable = true)
    private void meridian$rejectLockedThrow(LivingEntity target, float power, CallbackInfo callback) {
        // 定身与盟友保护也覆盖直接调用女巫投掷入口。
        Witch witch = (Witch) (Object) this;
        if (HumanoidBrain.blocksRanged(witch) || HumanoidWitchHands.occupied(witch)
                || HumanoidCharmController.spares(witch, target)) {
            callback.cancel();
        }
    }
}
