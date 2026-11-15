package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.CrisisRaiders;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 修为招敌：玩家死在来人手里时，头目要在死亡掉落之前从身上拿走一件，所以挂在原版死亡的最开头。 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerCrisisMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void meridian$settleCrisisLoss(DamageSource source, CallbackInfo callback) {
        // Fabric 的死后事件排在掉落之后，赶不上拿东西；这里只转给招敌，不改原版死亡。
        CrisisRaiders.playerDying((ServerPlayer) (Object) this, source);
    }
}
