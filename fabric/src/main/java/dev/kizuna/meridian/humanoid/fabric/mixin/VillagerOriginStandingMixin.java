package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.OriginTalents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 出身带的村民声望加在原版闲话声望上：原版打折、铁傀儡认人都读这一个数，所以挂在它的返回处。 */
@Mixin(Villager.class)
public abstract class VillagerOriginStandingMixin {
    @Inject(method = "getPlayerReputation", at = @At("RETURN"), cancellable = true)
    private void meridian$originStanding(Player player, CallbackInfoReturnable<Integer> callback) {
        // 只有服务端玩家才有出身；没选出身或不带这条天赋时原样返回。
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        int standing = OriginTalents.reputation(serverPlayer);
        if (standing != 0) {
            callback.setReturnValue(callback.getReturnValue() + standing);
        }
    }
}
