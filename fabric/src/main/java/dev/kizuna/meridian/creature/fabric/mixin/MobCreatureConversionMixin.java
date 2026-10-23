package dev.kizuna.meridian.creature.fabric.mixin;

import dev.kizuna.meridian.creature.fabric.CreatureFabric;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 村民被雷击转换成女巫时迁移同一具身体，不把转换误判为死亡释放。 */
@Mixin(MobEntity.class)
public abstract class MobCreatureConversionMixin {
    @Inject(method = "convertTo", at = @At("HEAD"))
    private void meridian$beginMigration(EntityType<? extends MobEntity> type, boolean keepEquipment,
                                          CallbackInfoReturnable<MobEntity> callback) {
        if (type == EntityType.WITCH && (Object) this instanceof LivingEntity oldEntity) {
            CreatureFabric.markConversion(oldEntity, true);
        }
    }

    @Inject(method = "convertTo", at = @At("RETURN"))
    private void meridian$migrateTo(EntityType<? extends MobEntity> type, boolean keepEquipment,
                                     CallbackInfoReturnable<MobEntity> callback) {
        if (type == EntityType.WITCH && (Object) this instanceof LivingEntity oldEntity) {
            LivingEntity newEntity = callback.getReturnValue();
            if (newEntity == null) {
                CreatureFabric.markConversion(oldEntity, false);
            } else {
                CreatureFabric.migrateBody(oldEntity, newEntity);
            }
        }
    }
}
