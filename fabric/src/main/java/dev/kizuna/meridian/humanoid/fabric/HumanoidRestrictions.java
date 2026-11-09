package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewNausea;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 生物只认 CHARM 定身；反胃读取 Brewing 的真实身体期限。 */
public final class HumanoidRestrictions {
    private static final Set<UUID> locked = new HashSet<>();

    private HumanoidRestrictions() {
        // 只缓存事件身份，计时与身体事实仍归各自服务。
    }

    static void initialize() {
        // 到期事件只撤魅惑锁，受击减速不能延长或缩短它。
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addStaggerListener(event -> {
            if (event.kind() != TechniqueService.StaggerKind.CHARM) {
                return;
            }
            UUID entityId = event.entityUuid();
            if (event.remainingMillis() > 0) {
                locked.add(entityId);
                LivingEntity entity = HumanoidFabric.findEntity(entityId);
                if (entity instanceof Mob mob) {
                    stop(mob);
                }
            } else {
                locked.remove(entityId);
            }
        }));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> locked.remove(entity.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> locked.clear());
    }

    public static boolean locked(LivingEntity entity) {
        // 非生物玩家由 Human 处理，避免重复干预客户端身体。
        return entity instanceof Mob && locked.contains(entity.getUUID());
    }

    public static void stop(Mob mob) {
        // 只停主动移动与动作，保留已有击退和重力。
        mob.getNavigation().stop();
        mob.stopUsingItem();
        mob.setSpeed(0);
        mob.setXxa(0);
        mob.setZza(0);
        mob.setJumping(false /* jumping */);
        HumanoidBrain.releaseControl(mob.getUUID());
    }

    public static boolean blocksUse(LivingEntity entity, ItemStack stack) {
        // 生物喝药和普通吃喝同一门禁，普通受击不会拦截使用。
        return entity instanceof Mob && (locked(entity) || BrewNausea.blocks(entity, stack));
    }

    public static boolean blocksDrinking(LivingEntity entity) {
        // 女巫的内置自喝不走 startUsingItem，因此显式以药水语义检查。
        return blocksUse(entity, new ItemStack(Items.POTION));
    }
}
