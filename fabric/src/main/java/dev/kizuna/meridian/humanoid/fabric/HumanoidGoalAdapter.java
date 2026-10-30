package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;

import java.util.EnumSet;

/** 非村民接入：在原版 GoalSelector 里装最高优先级的移动/朝向 Goal 与目标 Goal，大脑指挥时压过原版 Goal。 */
final class HumanoidGoalAdapter {
    private static final int CONTROL_PRIORITY = -1;
    private static final HumanoidMotion.Speeds SPEEDS = new HumanoidMotion.Speeds(1.0, 1.2);

    private HumanoidGoalAdapter() {
        // 只负责装 Goal，状态在 HumanoidMind。
    }

    static void install(MobEntity mob, HumanoidMind mind, GoalSelector goals, GoalSelector targets) {
        // 每个实体对象只装一次；村民走大脑重定向，不装 Goal。
        if (mind.adapted() || mob instanceof VillagerEntity) {
            return;
        }
        goals.add(CONTROL_PRIORITY, new CombatGoal(mob));
        targets.add(CONTROL_PRIORITY, new TargetGoal(mob));
        mind.markAdapted();
    }

    /** 大脑指挥期间占住移动与朝向，每 tick 执行当前指令。 */
    private static final class CombatGoal extends Goal {
        private final MobEntity mob;

        private CombatGoal(MobEntity mob) {
            // 与原版移动类 Goal 争同一组控制。
            this.mob = mob;
            setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            // 大脑给了非原版指令或施放中仍有对手。
            return HumanoidBrain.directing(mob);
        }

        @Override
        public boolean shouldContinue() {
            // 与开始条件相同，交还原版时立即让出。
            return HumanoidBrain.directing(mob);
        }

        @Override
        public boolean shouldRunEveryTick() {
            // 朝向与路径刷新跟随每个实体 tick。
            return true;
        }

        @Override
        public void tick() {
            // 施放中举起武器姿态，随后执行指令。
            mob.setAttacking(HumanoidTechniqueHost.isCasting(mob));
            HumanoidBrain.move(mob, SPEEDS);
        }

        @Override
        public void stop() {
            // 让出控制时收起姿态并停下导航。
            mob.setAttacking(false /* attacking */);
            mob.getNavigation().stop();
        }
    }

    /** 有对手时占住目标控制，把原版目标对齐到大脑选的对手。 */
    private static final class TargetGoal extends Goal {
        private final MobEntity mob;

        private TargetGoal(MobEntity mob) {
            // 与原版选敌 Goal 争目标控制。
            this.mob = mob;
            setControls(EnumSet.of(Control.TARGET));
        }

        @Override
        public boolean canStart() {
            // 大脑有对手时开始。
            return HumanoidBrain.hasOpponent(mob);
        }

        @Override
        public boolean shouldContinue() {
            // 对手消失时让原版选敌接手。
            return HumanoidBrain.hasOpponent(mob);
        }

        @Override
        public void start() {
            // 开始即对齐一次。
            align();
        }

        @Override
        public void tick() {
            // 换对手时重新对齐。
            align();
        }

        private void align() {
            // 原版目标与大脑对手不同时改写。
            LivingEntity opponent = HumanoidBrain.opponent(mob).orElse(null);
            if (opponent != null && mob.getTarget() != opponent) {
                mob.setTarget(opponent);
            }
        }
    }
}
