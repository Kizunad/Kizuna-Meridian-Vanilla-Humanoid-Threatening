package dev.kizuna.meridian.humanoid.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一场了结后来人怎么走：不再盯人，往离最近的玩家远的方向走，走够 3 秒、48 格内没有玩家看得见就消失。
 * 打了标签、却既不在盯人也不在撤的（崩服、停服前没收拾掉的）一加载进来就清走。
 */
final class CrisisRetreats {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid/crisis");
    private static final int MIN_TICKS = 60;
    private static final double WATCHED_WITHIN = 48;
    private static final double STRIDE = 16;
    private static final double SPEED = 1.0;
    private static final List<Orphan> ORPHANS = new ArrayList<>();

    /** 加载进来、没人认领的来人：在哪个世界、是哪一个。 */
    private record Orphan(ServerLevel level, UUID entity) { }

    private CrisisRetreats() {
        // 只有静态做法和待清的孤儿表，服务端主线程读写。
    }

    static void loaded(Entity entity, ServerLevel level) {
        // 加载回调里不能删实体，先记下，下一刻再看；刚现身的来人这时还没登记，下一刻就登记上了，不会误删。
        if (entity instanceof Mob mob && mob.entityTags().contains(CrisisArrivals.RAIDER_TAG)
                && !CrisisRaiders.sent(mob)) {
            ORPHANS.add(new Orphan(level, mob.getUUID()));
        }
    }

    static void tick(MinecraftServer server) {
        // 每 tick 清一次上一刻记下的孤儿；撤走的每 20 tick 走一步。
        sweep();
        if (server.getTickCount() % CrisisEngine.STEP_TICKS == 0) {
            walk(server);
        }
    }

    private static void sweep() {
        // 此刻还在、还是没人认领的就清走。
        List<Orphan> due = List.copyOf(ORPHANS);
        ORPHANS.clear();
        for (Orphan orphan : due) {
            if (orphan.level().getEntity(orphan.entity()) instanceof Mob mob && !CrisisRaiders.sent(mob)) {
                LOGGER.info("清走没人认领的招敌来人 {} {}", mob.getType(), mob.getUUID());
                mob.discard();
            }
        }
    }

    private static void walk(MinecraftServer server) {
        // 已经不在了（死了、卸载了）的放掉，卸载的以后加载进来按孤儿清；撤够了又没人看得见的消失；其余接着走远。
        int now = server.getTickCount();
        for (Map.Entry<UUID, CrisisRaiders.Retreat> entry : CrisisRaiders.retreats().entrySet()) {
            Optional<Mob> found = find(server, entry.getKey());
            if (found.isEmpty()) {
                CrisisRaiders.dismiss(entry.getKey());
                continue;
            }
            Mob mob = found.get();
            if (now - entry.getValue().since() >= MIN_TICKS && unwatched(mob)) {
                mob.discard();
                CrisisRaiders.dismiss(entry.getKey());
                continue;
            }
            away(mob);
        }
    }

    static Optional<Mob> find(MinecraftServer server, UUID id) {
        // 招敌管着的只有几个，挨个世界找活着的。
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive()) {
                return Optional.of(mob);
            }
        }
        return Optional.empty();
    }

    static boolean unwatched(Mob mob) {
        // 48 格内没有哪名玩家（旁观的不算）看得见它。
        return mob.level().players().stream().noneMatch(player -> !player.isSpectator()
                && player.distanceTo(mob) <= WATCHED_WITHIN && player.hasLineOfSight(mob));
    }

    private static void away(Mob mob) {
        // 不打人；最近的玩家在 48 格内就朝背离他的方向走一段，没有就站着等消失。
        mob.setTarget(null);
        Player nearest = mob.level().getNearestPlayer(mob, WATCHED_WITHIN);
        if (nearest == null) {
            return;
        }
        Vec3 away = mob.position().subtract(nearest.position()).multiply(1, 0, 1);
        Vec3 heading = away.lengthSqr() < 1e-6 ? mob.getLookAngle().multiply(-1, 0, -1) : away.normalize();
        Vec3 goal = mob.position().add(heading.scale(STRIDE));
        mob.getNavigation().moveTo(goal.x, goal.y, goal.z, SPEED);
    }

    static void clear() {
        // 停服时清空待清表。
        ORPHANS.clear();
    }
}
