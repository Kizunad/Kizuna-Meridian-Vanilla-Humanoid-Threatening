package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.HumanoidVariant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.phys.Vec3;

/** 刷怪蛋与管理命令共用的指定档位出生入口；登记物种可以提供自己的放置钩子。 */
public final class HumanoidSpawns {
    private static final Map<HumanoidSpecies, Placement> PLACEMENTS = new ConcurrentHashMap<>();
    private static final Map<HumanoidSpecies, Supplier<SpawnGroupData>> COMMAND_GROUPS = new ConcurrentHashMap<>();

    /** 指令放置可指定原版生成组数据，例如只生成能被该物种认领的成年个体。 */
    public static void registerCommandGroup(HumanoidSpecies species, Supplier<SpawnGroupData> group) {
        if (COMMAND_GROUPS.putIfAbsent(species, group) != null) {
            throw new IllegalStateException("物种的指令生成组已登记：" + species.entityId());
        }
    }

    private HumanoidSpawns() {
        // 出生只接收明确的物种和档位，不能修改已经存在的身体。
    }

    /**
     * 登记物种的放置钩子：负责创建实体、用 {@link HumanoidSpeciesBirths#birth} 按档位出生身体并加入世界，
     * 返回已入世界的实体。sourceYaw 是命令源的水平朝向，钩子自行决定实体朝向。
     */
    @FunctionalInterface
    public interface Placement {
        LivingEntity place(ServerLevel world, Vec3 position, float sourceYaw, HumanoidTier tier);
    }

    public static void registerPlacement(HumanoidSpecies species, Placement placement) {
        // 只有登记物种可以自带放置，内置生人固定走原版生物出生；同一物种只能登记一次，应在模组初始化阶段调用。
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(placement, "placement");
        if (species.definition().isEmpty()) {
            throw new IllegalArgumentException("内置物种不能登记放置钩子：" + species.entityId());
        }
        if (PLACEMENTS.putIfAbsent(species, placement) != null) {
            throw new IllegalStateException("物种放置钩子已登记：" + species.entityId());
        }
    }

    static Mob spawn(ServerLevel world, Selection selection, Vec3 position, EntitySpawnReason reason) {
        // 刷怪蛋与测试按选定的物种和变体出生。
        HumanoidTier tier = HumanoidTier.of(selection.variant());
        return spawnMob(world, selection.species(), mob -> HumanoidSpeciesBirths.birth(mob, tier), position, reason);
    }

    static LivingEntity command(ServerLevel world, HumanoidSpecies species, Optional<HumanoidTier> tier,
                                Vec3 position, float sourceYaw) {
        // 登记了放置钩子的物种交给所属附属；其余按原版生物出生，未指定档位时沿用自然抽档。
        Placement placement = PLACEMENTS.get(species);
        var defaultTier = species.definition().map(entry -> entry.defaultTier());
        if (placement != null) {
            HumanoidTier chosen = tier.or(() -> defaultTier).orElseThrow(
                    () -> new IllegalStateException("登记物种出生缺少档位：" + species.entityId()));
            return placement.place(world, position, sourceYaw, chosen);
        }
        return spawnMob(world, species, mob -> {
            if (tier.isPresent()) {
                HumanoidSpeciesBirths.birth(mob, tier.get());
            } else if (defaultTier.isPresent()) {
                HumanoidSpeciesBirths.birthDefault(mob, defaultTier.get());
            }
        }, position, EntitySpawnReason.COMMAND);
    }

    static Mob event(ServerLevel world, HumanoidSpecies species, Consumer<Mob> birth, Vec3 position) {
        // 招敌来人：原版事件出生（不当巡逻队、不随机扛旗），身体由调用方按档位或精锐出生。
        return spawnMob(world, species, birth, position, EntitySpawnReason.EVENT);
    }

    private static Mob spawnMob(ServerLevel world, HumanoidSpecies species, Consumer<Mob> birth,
                                Vec3 position, EntitySpawnReason reason) {
        // 原版初始化负责装备；出生回调在入世界前创建身体，什么都不做时由技法宿主首次准备时按自然概率抽档。
        var entity = entityType(species).create(world, reason);
        if (!(entity instanceof Mob mob)) {
            throw new IllegalStateException("无法创建类人实体：" + species.entityId());
        }
        mob.snapTo(position.x, position.y, position.z, 0, 0);
        SpawnGroupData group = null;
        var groupFactory = COMMAND_GROUPS.get(species);
        if (reason == EntitySpawnReason.COMMAND && groupFactory != null) {
            group = groupFactory.get();
        }
        mob.finalizeSpawn(world, world.getCurrentDifficultyAt(mob.blockPosition()), reason, group);
        if (!HumanoidFabric.isClaimed(mob)) {
            throw new IllegalStateException("物种未认领此次生成的实体：" + species.entityId());
        }
        mob.setPersistenceRequired();
        birth.accept(mob);
        if (!world.addFreshEntity(mob)) {
            throw new IllegalStateException("类人实体无法加入世界");
        }
        HumanoidTechniqueHost.prepare(mob);
        return mob;
    }

    private static EntityType<?> entityType(HumanoidSpecies species) {
        // 实体仍是原版或登记模组自己的类型，不注册平行实体身份；未注册 ID 与旧版一样取默认类型。
        return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(species.entityId()));
    }

    public record Selection(HumanoidSpecies species, HumanoidVariant variant) {
        public EntityType<?> entityType() {
            // 实体仍是原版类型，不注册十四种平行实体身份。
            return HumanoidSpawns.entityType(species);
        }

        public String path() {
            // 物品身份由固定物种与档位组成，原版 give 可直接补全。
            String speciesPath = Identifier.parse(species.entityId()).getPath();
            return speciesPath + "_" + variant.name().toLowerCase(Locale.ROOT) + "_spawn_egg";
        }
    }
}
