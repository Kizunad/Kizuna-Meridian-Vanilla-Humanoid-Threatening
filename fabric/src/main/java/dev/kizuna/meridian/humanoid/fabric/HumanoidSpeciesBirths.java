package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;

import java.util.Objects;

/** 按指定档位出生身体：实体已创建并就位、尚未加入世界时调用，之后宿主的惰性出生不会再抽档。 */
public final class HumanoidSpeciesBirths {
    private HumanoidSpeciesBirths() {
        // 纯静态入口。
    }

    public static void birth(LivingEntity entity, HumanoidTier tier) {
        // 只接受已认领物种的服务端实体；已有身体或存档时拒绝，出生档位不能覆盖已有事实。
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(tier, "tier");
        if (!(entity.getWorld() instanceof ServerWorld world)) {
            throw new IllegalStateException("只能在服务端出生类人身体");
        }
        String entityId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        HumanoidSpecies species = HumanoidSpecies.fromEntityId(entityId)
                .orElseThrow(() -> new IllegalStateException("未登记的类人物种：" + entityId));
        var access = (HumanoidEntityAccess) entity;
        if (access.meridian$humanoidBody() != null || access.meridian$humanoidSavedBody() != null) {
            throw new IllegalStateException("指定出生档位不能覆盖已有身体");
        }
        var subject = new HumanoidBodyState.BirthSubject(species, entity.getUuid(), tier);
        var body = HumanoidBodyState.born(subject, world.getSeed(), HumanoidFabric.region(entity),
                HumanoidFabric.now());
        access.meridian$humanoidBody(body);
    }
}
