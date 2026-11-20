package dev.kizuna.meridian.humanoid.fabric.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.kizuna.meridian.humanoid.fabric.HumanoidBirthPlan;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpawnerAccess;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpawns;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerHumanoidMixin implements HumanoidSpawnerAccess {
    @Unique private static final String meridian$TIER = "meridian_humanoid_tier";
    @Shadow private SpawnData nextSpawnData;
    @Shadow private WeightedList<SpawnData> spawnPotentials;
    @Shadow private Entity displayEntity;

    @Override
    public void meridian$setBirthTier(HumanoidSpawns.Selection selection) {
        // 改笼后每轮都采用这个物种和档位，保留笼子的刷怪规则及装备表。
        var entity = new CompoundTag();
        entity.putString("id", selection.species().entityId());
        entity.putString(meridian$TIER, selection.tier().id());
        var rules = nextSpawnData.getCustomSpawnRules();
        var equipment = nextSpawnData.getEquipment();
        nextSpawnData = new SpawnData(entity, rules, equipment);
        spawnPotentials = WeightedList.of();
        displayEntity = null;
    }

    @Inject(method = "setEntityId", at = @At("RETURN"))
    private void meridian$clearBirthTier(CallbackInfo callback) {
        // 原版蛋重新设定物种时清掉旧档位，即使两颗蛋的物种相同。
        nextSpawnData.getEntityToSpawn().remove(meridian$TIER);
    }

    @Redirect(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/CompoundTag;size()I"))
    private int meridian$countVanillaEntityFields(CompoundTag entity) {
        // 原版只在实体数据只有 id 时调用 finalizeSpawn。档位字段不替代原版初始化。
        int size = entity.size();
        if (entity.contains(meridian$TIER)) {
            return size - 1;
        }
        return size;
    }

    @ModifyArg(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;"
                    + "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;"
                    + "Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;"),
            index = 3)
    private SpawnGroupData meridian$selectGroup(SpawnGroupData original, @Local SpawnData data) {
        var entity = data.getEntityToSpawn();
        if (meridian$tier(entity).isEmpty()) {
            return original;
        }
        return entity.getString("id").flatMap(HumanoidSpecies::fromEntityId)
                .map(HumanoidSpawns::spawnGroup).orElse(original);
    }

    @Inject(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers"
                    + "(Lnet/minecraft/world/entity/Entity;)Z"))
    private void meridian$recordBirthTier(CallbackInfo callback,
                                          @Local(argsOnly = true) ServerLevel level,
                                          @Local SpawnData data, @Local Entity entity) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        meridian$tier(data.getEntityToSpawn()).ifPresent(tier -> {
            var difficulty = level.getCurrentDifficultyAt(mob.blockPosition());
            HumanoidBirthPlan.captureSpawner(mob, difficulty, tier);
        });
    }

    @Unique
    private static Optional<HumanoidTier> meridian$tier(CompoundTag entity) {
        return entity.getString(meridian$TIER).flatMap(HumanoidTier::fromId);
    }
}
