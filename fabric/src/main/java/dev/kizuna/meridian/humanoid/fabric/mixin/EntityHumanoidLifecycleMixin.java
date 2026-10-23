package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidFabric;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在实体真正离开世界时结算类人实体身体；区块卸载和跨维度由宿主保留。 */
@Mixin(Entity.class)
public abstract class EntityHumanoidLifecycleMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void meridian$onRemove(Entity.RemovalReason reason, CallbackInfo callback) {
        HumanoidFabric.onEntityRemoved((Entity) (Object) this, reason);
    }
}
