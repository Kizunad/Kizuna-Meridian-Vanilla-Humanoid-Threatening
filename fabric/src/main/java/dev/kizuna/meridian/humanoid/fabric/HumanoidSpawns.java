package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

/** 刷怪蛋与管理命令共用的指定档位出生入口。 */
public final class HumanoidSpawns {
    private HumanoidSpawns() {
        // 出生只接收明确的物种和档位，不能修改已经存在的身体。
    }

    static Mob spawn(ServerLevel world, Selection selection, Vec3 position, MobSpawnType reason) {
        // 原版初始化负责装备；首次创建身体时指定档位，随后才将完整实体加入世界。
        var entity = selection.entityType().create(world);
        if (!(entity instanceof Mob mob)) {
            throw new IllegalStateException("无法创建类人实体：" + selection.species().entityId());
        }
        mob.moveTo(position.x, position.y, position.z, 0, 0);
        mob.finalizeSpawn(world, world.getCurrentDifficultyAt(mob.blockPosition()), reason, null, null);
        mob.setPersistenceRequired();
        HumanoidSpeciesBirths.birth(mob, HumanoidTier.of(selection.variant()));
        if (!world.addFreshEntity(mob)) {
            throw new IllegalStateException("类人实体无法加入世界");
        }
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    public record Selection(HumanoidSpecies species, HumanoidVariant variant) {
        public EntityType<?> entityType() {
            // 实体仍是原版类型，不注册十四种平行实体身份。
            return BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(species.entityId()));
        }

        public String path() {
            // 物品身份由固定物种与档位组成，原版 give 可直接补全。
            String speciesPath = new ResourceLocation(species.entityId()).getPath();
            return speciesPath + "_" + variant.name().toLowerCase(Locale.ROOT) + "_spawn_egg";
        }
    }
}
