package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.fabric.mixin.BrainHumanoidInvoker;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

/** 村民接入：大脑指挥时原版大脑只走记忆与感知、跳过任务，由大脑驱动导航，并按原版恐慌节奏召唤铁傀儡。 */
public final class HumanoidVillagerAdapter {
    private static final HumanoidMotion.Speeds SPEEDS = new HumanoidMotion.Speeds(0.5, 0.75);
    private static final long GOLEM_INTERVAL_TICKS = 100;
    private static final int GOLEM_REQUIRED_VILLAGERS = 3;
    private static final List<MemoryModuleType<?>> ROUTINE_MEMORIES = List.of(MemoryModuleType.WALK_TARGET,
            MemoryModuleType.PATH, MemoryModuleType.LOOK_TARGET, MemoryModuleType.INTERACTION_TARGET,
            MemoryModuleType.BREED_TARGET, MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
    private static final Set<Villager> directed = Collections.newSetFromMap(new WeakHashMap<>());

    private HumanoidVillagerAdapter() {
        // 只记哪些村民正被大脑指挥，实体卸载后弱引用自然释放。
    }

    public static void tick(Brain<Villager> brain, ServerLevel world, Villager villager) {
        // 不指挥时原样走原版大脑；接手的那一刻停掉原版任务并忘掉日常的走路与交互记忆。
        if (!HumanoidBrain.directing(villager)) {
            directed.remove(villager);
            brain.tick(world, villager);
            return;
        }
        var invoker = (BrainHumanoidInvoker) brain;
        invoker.meridian$tickMemories();
        invoker.meridian$tickSensors(world, villager);
        if (directed.add(villager)) {
            brain.stopAll(world, villager);
            ROUTINE_MEMORIES.forEach(brain::eraseMemory);
        }
        HumanoidBrain.move(villager, SPEEDS);
        summonGolem(world, villager);
    }

    private static void summonGolem(ServerLevel world, Villager villager) {
        // 原版恐慌每 100 tick 以三人门槛召唤一次；有对手时撤退或对敌都照此召唤。
        long time = world.getGameTime();
        if (HumanoidBrain.hasOpponent(villager) && time % GOLEM_INTERVAL_TICKS == 0) {
            villager.spawnGolemIfNeeded(world, time, GOLEM_REQUIRED_VILLAGERS);
        }
    }
}
