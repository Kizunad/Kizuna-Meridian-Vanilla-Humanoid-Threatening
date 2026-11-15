package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Decision;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment.Stance;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 大脑目标切换、脱离和释放时清理定时施放计划。 */
public final class HumanoidMindTest {
    private static final long FOLLOW_UP_OPENS = 4200;
    private static final long FLIGHT_MILLIS = 400;
    private static final long FIRST_HIT_MILLIS = 900;
    private static final long DUE_MILLIS = 2700;
    private static final long EXPIRES_MILLIS = 3200;
    private static final int PATHED_TICK = 40;
    private static final int REPATH_TICKS = 5;
    private static final long RETREAT_STARTED_MILLIS = 10_000;
    private static final long DECISION_MILLIS = 500;
    private static final double HALF_SECOND = 0.5;
    private static final double TOLERANCE = 1e-9;

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

    @Test
    public void idleRepathWaitsOutMinimumInterval() {
        // 导航空闲时两次寻路之间要隔够 tick；没寻过路或实体 tick 被重置都不算在间隔内。
        HumanoidMind mind = new HumanoidMind();
        assertFalse(mind.pathedWithin(PATHED_TICK, REPATH_TICKS));
        mind.pathed(PATHED_TICK);
        assertTrue(mind.pathedWithin(PATHED_TICK, REPATH_TICKS));
        assertTrue(mind.pathedWithin(PATHED_TICK + REPATH_TICKS - 1, REPATH_TICKS));
        assertFalse(mind.pathedWithin(PATHED_TICK + REPATH_TICKS, REPATH_TICKS));
        assertFalse(mind.pathedWithin(PATHED_TICK - 1, REPATH_TICKS));
    }

    @Test
    public void unpressedTimeOnlyAccumulatesWhileRetreatingAndUnpressed() {
        // 对敌中不计；转入撤退的那次决策起算，没被逼就一直累计，被逼一次从头来。
        HumanoidMind mind = new HumanoidMind();
        long now = RETREAT_STARTED_MILLIS;
        assertEquals(0, mind.notePressure(seen(now), false /* pressed */), TOLERANCE);
        mind.decided(0, Stance.RETREAT, Decision.of(Decision.Action.RETREAT, "测试撤退"));
        now += DECISION_MILLIS;
        assertEquals(HALF_SECOND, mind.notePressure(seen(now), false /* pressed */), TOLERANCE);
        now += DECISION_MILLIS;
        assertEquals(2 * HALF_SECOND, mind.notePressure(seen(now), false /* pressed */), TOLERANCE);
        now += DECISION_MILLIS;
        assertEquals(0, mind.notePressure(seen(now), true /* pressed */), TOLERANCE);
        now += DECISION_MILLIS;
        assertEquals(HALF_SECOND, mind.notePressure(seen(now), false /* pressed */), TOLERANCE);
        mind.decided(0, Stance.ENGAGE, Decision.of(Decision.Action.APPROACH, "测试对敌"));
        now += DECISION_MILLIS;
        assertEquals(0, mind.notePressure(seen(now), false /* pressed */), TOLERANCE);
    }

    @Test
    public void losingTheOpponentForgetsWhereItWasSeen() {
        // 威胁消失后不留上次看到的对手，下一个对手没得比，由大脑当作刚被逼过。
        HumanoidMind mind = new HumanoidMind();
        mind.notePressure(seen(RETREAT_STARTED_MILLIS), false /* pressed */);
        assertTrue(mind.sighting().isPresent());
        mind.disengage();
        assertFalse(mind.sighting().isPresent());
    }

    private static HumanoidPressure.Sighting seen(long millis) {
        // 位置和挨打时刻由 HumanoidPressure 比较，这里只关心时钟。
        return new HumanoidPressure.Sighting(Vec3.ZERO, HumanoidPressure.NEVER_STRUCK, millis);
    }

    private static HumanoidMind.CastPlan plan() {
        return new HumanoidMind.CastPlan("test:strike", null, Vec3.ZERO, FLIGHT_MILLIS,
                FIRST_HIT_MILLIS, DUE_MILLIS, EXPIRES_MILLIS, "测试计划");
    }

    @Test
    public void followUpWindowIsReportedOnceAndSurvivesRoutineDecisions() {
        // 看过正在放的式：后面有能接的，窗口打开那一刻报一次；之前的例行决策不把它冲掉；报过以后要重新看新的一式。
        // 后面没有能接的，看过以后既不等也不再读身体，直到这一式放完。
        HumanoidMind mind = new HumanoidMind();
        assertFalse(mind.followUpExamined());
        mind.followUpExamined(OptionalLong.of(FOLLOW_UP_OPENS));
        assertTrue(mind.followUpExamined() && mind.awaitingFollowUp());
        mind.clearDecisionWaits();
        assertFalse(mind.takeFollowUp(FOLLOW_UP_OPENS - 1));
        assertTrue(mind.takeFollowUp(FOLLOW_UP_OPENS));
        assertFalse(mind.awaitingFollowUp() || mind.followUpExamined());
        mind.followUpExamined(OptionalLong.empty());
        assertTrue(mind.followUpExamined());
        assertFalse(mind.awaitingFollowUp());
        mind.castIdle();
        assertFalse(mind.followUpExamined());
    }
}
