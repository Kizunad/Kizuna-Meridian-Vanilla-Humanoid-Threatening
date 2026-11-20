package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewNausea;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueService.StaggerKind;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 魅惑定身、被擒、倒地都停生物的大脑，受击减速不停；反胃读取 Brewing 的真实身体期限。 */
public final class HumanoidRestrictions {
    private static final Set<StaggerKind> LOCKING = EnumSet.of(StaggerKind.CHARM, StaggerKind.HOLD, StaggerKind.DOWN);
    private static final Map<UUID, Set<StaggerKind>> locked = new HashMap<>();

    private HumanoidRestrictions() {
        // 只缓存事件身份，计时与身体事实仍归各自服务。
    }

    static void initialize() {
        // 到期事件只撤自己那一种锁，受击减速不能延长或缩短它们。
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addStaggerListener(HumanoidRestrictions::apply));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> locked.remove(entity.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> locked.clear());
    }

    private static void apply(TechniqueService.StaggerChanged event) {
        // 开始时记下这一种并当场停手；到期只撤这一种，三种都撤完大脑才恢复。
        if (!LOCKING.contains(event.kind())) {
            return;
        }
        UUID entityId = event.entityUuid();
        if (event.remainingMillis() > 0) {
            locked.computeIfAbsent(entityId, ignored -> EnumSet.noneOf(StaggerKind.class)).add(event.kind());
            if (HumanoidFabric.findEntity(entityId) instanceof Mob mob) {
                stop(mob);
            }
            return;
        }
        Set<StaggerKind> kinds = locked.getOrDefault(entityId, EnumSet.noneOf(StaggerKind.class));
        kinds.remove(event.kind());
        if (kinds.isEmpty()) {
            locked.remove(entityId);
        }
    }

    public static boolean locked(LivingEntity entity) {
        // 非生物玩家由 Human 处理，避免重复干预客户端身体。
        return entity instanceof Mob && locked.containsKey(entity.getUUID());
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
        return entity instanceof Mob && (locked(entity) || HumanoidTechniqueHost.castingGuard(entity)
                || BrewNausea.blocks(entity, stack));
    }

    public static boolean blocksDrinking(LivingEntity entity) {
        // 女巫的内置自喝不走 startUsingItem，因此显式以药水语义检查。
        return HumanoidWitchHands.occupied(entity) || HumanoidTechniqueHost.hasActiveCast(entity)
                || blocksUse(entity, new ItemStack(Items.POTION));
    }
}
