package dev.kizuna.meridian.humanoid.fabric.mixin;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 读旧区块时即使还没有身体，也不能当作今次新刷的个体。结构和命令同样会读 NBT，按原版来路区分。 */
@Mixin(EntityType.class)
public abstract class EntityTypeVolumeBirthMixin {
    @Inject(method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;)"
            + "Lnet/minecraft/world/entity/Entity;", at = @At("RETURN"))
    private void meridian$markExisting(Level level, EntitySpawnRequest request, CallbackInfoReturnable<Entity> result) {
        var reason = request.reason();
        if ((reason == EntitySpawnReason.LOAD || reason == EntitySpawnReason.DIMENSION_TRAVEL)
                && result.getReturnValue() instanceof HumanoidEntityAccess access) {
            access.meridian$skipVolumeBirth();
        }
    }
}
