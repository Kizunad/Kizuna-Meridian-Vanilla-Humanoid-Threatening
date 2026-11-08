package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 大脑目标切换、脱离和释放时清理定时施放计划。 */
public final class HumanoidMindTest {
    private static final long FLIGHT_MILLIS = 400;
    private static final long FIRST_HIT_MILLIS = 900;
    private static final long DUE_MILLIS = 2700;
    private static final long EXPIRES_MILLIS = 3200;

    @Test
    public void targetSwitchDisengageAndReleaseClearCastPlan() {
        // 原计划只绑定原目标；切换目标、脱离威胁和交还原版都不能留下定时起手。
        HumanoidMind mind = new HumanoidMind();
        mind.planCast(plan());
        assertTrue(mind.castPlan().isPresent());

        mind.disengage();
        assertFalse(mind.castPlan().isPresent());

        mind.planCast(plan());
        mind.release();
        assertFalse(mind.castPlan().isPresent());
    }

    @Test
    public void firstPlanWinsUntilExplicitlyCleared() {
        // 同一蓄力只登记一次；新的候选不能覆盖已经按峰值倒排的式。
        HumanoidMind mind = new HumanoidMind();
        HumanoidMind.CastPlan initial = plan();
        HumanoidMind.CastPlan replacement = new HumanoidMind.CastPlan("test:other", null, Vec3.ZERO,
                FLIGHT_MILLIS, FIRST_HIT_MILLIS, DUE_MILLIS, EXPIRES_MILLIS, "其他");
        mind.planCast(initial);
        mind.planCast(replacement);
        assertTrue(mind.castPlan().filter(initial::equals).isPresent());
        mind.clearCastPlan();
        assertFalse(mind.castPlan().isPresent());
    }

    private static HumanoidMind.CastPlan plan() {
        return new HumanoidMind.CastPlan("test:strike", null, Vec3.ZERO, FLIGHT_MILLIS,
                FIRST_HIT_MILLIS, DUE_MILLIS, EXPIRES_MILLIS, "测试计划");
    }
}
