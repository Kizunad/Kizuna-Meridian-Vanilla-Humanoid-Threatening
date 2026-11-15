package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.fabric.mixin.BrainHumanoidInvoker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.Vec3;

/**
 * 「守村」职守的两件事：谁算闯进来的（给选敌加来源），以及村子遭袭击、手上又没有对手时到钟边守着。
 *
 * <p>僵尸围城原版没有可查的状态，这里只认袭击；围城时的僵尸照旧由村民的敌对记忆带进选敌。</p>
 */
final class HumanoidGuards {
    /** 守在钟边多少格以内；也是主动认闯入者的半径。 */
    static final double POST_RADIUS = 16;
    /** 村里声望到这个数及以下的玩家，守村的见了就动手；和铁傀儡的门槛相同。 */
    static final int HOSTILE_REPUTATION = -100;
    private static final double WALK_SPEED = 0.6;
    private static final int REPATH_TICKS = 20;
    private static final int STEP_BLOCKS = 10;
    private static final int STEP_HEIGHT = 7;
    private static final double STEP_SPREAD_RADIANS = Math.PI / 2;
    private static final Set<Villager> posted = Collections.newSetFromMap(new WeakHashMap<>());

    private HumanoidGuards() {
        // 只记哪些村民正守在钟边，实体卸载后弱引用自然释放。
    }

    static List<LivingEntity> intruders(Villager guard) {
        // 身边声望极差的玩家，和正在袭击里的队伍成员；看不看得见、放不放下由选敌统一筛。
        var box = guard.getBoundingBox().inflate(POST_RADIUS);
        List<LivingEntity> found = new ArrayList<>();
        found.addAll(guard.level().getEntitiesOfClass(Player.class, box,
                player -> guard.getPlayerReputation(player) <= HOSTILE_REPUTATION));
        found.addAll(guard.level().getEntitiesOfClass(Raider.class, box, Raider::hasActiveRaid));
        return found;
    }

    static boolean posted(ServerLevel world, Villager villager) {
        // 带着守村、村子正遭袭击、记得钟在哪，三样都有才去守；别的时候照原版日常。
        if (!HumanoidDuties.guardsVillage(villager) || bell(world, villager).isEmpty()) {
            posted.remove(villager);
            return false;
        }
        Raid raid = world.getRaidAt(villager.blockPosition());
        if (raid == null || !raid.isActive() || raid.isOver()) {
            posted.remove(villager);
            return false;
        }
        return true;
    }

    static void hold(Brain<Villager> brain, ServerLevel world, Villager villager) {
        // 记忆和感知照常推进（才看得见来的人），原版的躲藏、敲钟这些任务停掉；离钟远于守卫半径就走过去，到了就站住。
        var invoker = (BrainHumanoidInvoker) brain;
        invoker.meridian$tickMemories();
        invoker.meridian$tickSensors(world, villager);
        if (posted.add(villager)) {
            brain.stopAll(world, villager);
        }
        Vec3 bell = Vec3.atBottomCenterOf(bell(world, villager).orElseThrow());
        if (villager.position().distanceToSqr(bell) <= POST_RADIUS * POST_RADIUS) {
            villager.getNavigation().stop();
        } else if (villager.getNavigation().isDone() || villager.tickCount % REPATH_TICKS == 0) {
            walkToward(villager, bell);
        }
    }

    private static void walkToward(Villager villager, Vec3 bell) {
        // 和原版走向记忆点的做法一样：先直接朝钟寻路（够不着也会给一段靠近的路）；一条路都找不到时，
        // 朝钟的方向挑一个近处落得了脚的点先走过去，下一次再找。
        var navigation = villager.getNavigation();
        if (navigation.moveTo(bell.x, bell.y, bell.z, WALK_SPEED)) {
            return;
        }
        Vec3 step = DefaultRandomPos.getPosTowards(villager, STEP_BLOCKS, STEP_HEIGHT, bell, STEP_SPREAD_RADIANS);
        if (step != null) {
            navigation.moveTo(step.x, step.y, step.z, WALK_SPEED);
        }
    }

    private static Optional<BlockPos> bell(ServerLevel world, Villager villager) {
        // 村民记着的集合点就是钟；记的是别的维度的不算。
        return villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT)
                .filter(point -> point.dimension() == world.dimension())
                .map(GlobalPos::pos);
    }
}
