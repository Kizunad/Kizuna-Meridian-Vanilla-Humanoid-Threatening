package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 已绑定身体的村民沿用原版和平分支承受普通雷击，不转化为女巫。 */
@Mixin(Villager.class)
public abstract class VillagerHumanoidLightningMixin {
    @Redirect(method = "thunderHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getDifficulty()Lnet/minecraft/world/Difficulty;"))
    private Difficulty meridian$lightningDifficulty(ServerLevel world) {
        // 只影响此处的分支判断；父类仍负责着火和经健康宿主结算的伤害。
        HumanoidEntityAccess access = (HumanoidEntityAccess) this;
        if (access.meridian$humanoidBody() != null) {
            return Difficulty.PEACEFUL;
        }
        return world.getDifficulty();
    }
}
