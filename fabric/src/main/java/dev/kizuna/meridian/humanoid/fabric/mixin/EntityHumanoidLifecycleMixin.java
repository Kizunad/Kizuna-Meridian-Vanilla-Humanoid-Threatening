package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidFabric;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在实体真正离开世界时结算类人实体身体；区块卸载和跨维度由宿主保留。 */
@Mixin(Entity.class)
public abstract class EntityHumanoidLifecycleMixin {
    @Inject(method = "restoreFrom", at = @At("HEAD"))
    private void meridian$beforeRestoreFrom(Entity original, CallbackInfo callback) {
        // 跨维度副本先从源实体提交身体快照，再由原版复制实体数据。
        HumanoidFabric.beforeDimensionTransfer(original, (Entity) (Object) this);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void meridian$onRemove(Entity.RemovalReason reason, CallbackInfo callback) {
        // 移除原因由宿主区分，卸载保留身体，死亡和丢弃写入释放账本。
        HumanoidFabric.onEntityRemoved((Entity) (Object) this, reason);
    }
}
