package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/** 刷怪蛋与管理命令共用的指定档位出生入口。 */
public final class HumanoidSpawns {
    private HumanoidSpawns() {
        // 出生只接收明确的物种和档位，不能修改已经存在的身体。
    }

    static MobEntity spawn(ServerWorld world, Selection selection, Vec3d position, SpawnReason reason) {
        // 原版初始化负责装备；首次创建身体时指定档位，随后才将完整实体加入世界。
        var entity = selection.entityType().create(world);
        if (!(entity instanceof MobEntity mob)) {
            throw new IllegalStateException("无法创建类人实体：" + selection.species().entityId());
        }
        mob.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        mob.initialize(world, world.getLocalDifficulty(mob.getBlockPos()), reason, null, null);
        mob.setPersistent();
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.of(selection.variant()));
        if (!world.spawnEntity(mob)) {
            throw new IllegalStateException("类人实体无法加入世界");
        }
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    public record Selection(HumanoidSpecies species, HumanoidVariant variant) {
        public EntityType<?> entityType() {
            // 实体仍是原版类型，不注册十四种平行实体身份。
            return Registries.ENTITY_TYPE.get(new Identifier(species.entityId()));
        }

        public String path() {
            // 物品身份由固定物种与档位组成，原版 give 可直接补全。
            String speciesPath = new Identifier(species.entityId()).getPath();
            return speciesPath + "_" + variant.name().toLowerCase(Locale.ROOT) + "_spawn_egg";
        }
    }
}
