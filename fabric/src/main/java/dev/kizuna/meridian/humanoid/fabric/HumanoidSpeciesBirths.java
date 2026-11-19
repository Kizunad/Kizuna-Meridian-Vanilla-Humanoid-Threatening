package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** 按指定档位出生身体：实体已创建并就位、尚未加入世界时调用，之后宿主的惰性出生不会再抽档。 */
public final class HumanoidSpeciesBirths {
    private HumanoidSpeciesBirths() {
        // 纯静态入口。
    }

    public static void birth(LivingEntity entity, HumanoidTier tier) {
        // 只接受已认领物种的服务端实体；已有身体或存档时拒绝，出生档位不能覆盖已有事实。
        Objects.requireNonNull(tier, "tier");
        born(entity, tier, Optional.of(tier), HumanoidBodyState.BirthGrade.ORDINARY);
    }

    static void birthDefault(LivingEntity entity, HumanoidTier tier) {
        born(entity, tier, Optional.empty(), HumanoidBodyState.BirthGrade.ORDINARY);
    }

    static void birthElite(LivingEntity entity) {
        // 招敌派来的精锐头目：真炁档，两层经脉全开、装填取上限；其余和指定档位出生一样。
        born(entity, HumanoidTier.TRUE_QI, Optional.of(HumanoidTier.TRUE_QI), HumanoidBodyState.BirthGrade.ELITE);
    }

    private static void born(LivingEntity entity, HumanoidTier tier, Optional<HumanoidTier> requested,
                             HumanoidBodyState.BirthGrade grade) {
        // 按物种和等级出生身体，挂到实体上，再按档位配兵器。
        Objects.requireNonNull(entity, "entity");
        if (!(entity.level() instanceof ServerLevel world)) {
            throw new IllegalStateException("只能在服务端出生类人身体");
        }
        HumanoidSpecies species = HumanoidSpeciesHooks.claimed(entity)
                .orElseThrow(() -> new IllegalStateException("未认领的类人实体：" + entity.getUUID()));
        var access = (HumanoidEntityAccess) entity;
        if (access.meridian$humanoidBody() != null || access.meridian$humanoidSavedBody() != null) {
            throw new IllegalStateException("指定出生档位不能覆盖已有身体");
        }
        var captured = HumanoidBirthPlan.of(access, species);
        var plan = requested.map(captured::requested).orElse(captured);
        access.meridian$birthPlan(plan);
        var subject = new HumanoidBodyState.BirthSubject(species, entity.getUUID(), tier, grade, plan);
        var body = HumanoidBodyState.born(subject, world.getSeed(), HumanoidFabric.region(entity),
                HumanoidFabric.now());
        access.meridian$humanoidBody(body);
        HumanoidWayfarerWeapons.equipAtBirth(entity, tier);
    }
}
