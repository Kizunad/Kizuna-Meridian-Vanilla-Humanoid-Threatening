package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidPressure.Sighting;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 撤退中「被对手逼着」的判定：它朝我挪近、或我又挨了它一下才算；它站着、走开或横着挪都不算。 */
public final class HumanoidPressureTest {
    private static final long DECISION_MILLIS = 500;
    private static final int STRUCK_TICK = 40;
    private static final Vec3 SELF = new Vec3(0, 64, 0);
    private static final Vec3 OPPONENT = new Vec3(6, 64, 0);
    private static final Vec3 STEP_CLOSER = new Vec3(5, 64, 0);
    private static final Vec3 CREEP_CLOSER = new Vec3(5.9, 64, 0);
    private static final Vec3 STEP_AWAY = new Vec3(7, 64, 0);
    private static final Vec3 STEP_SIDEWAYS = new Vec3(6, 64, 1);
    private static final Vec3 DROPPED_DOWN = new Vec3(6, 60, 0);

    @Test
    public void opponentWalkingTowardMeIsPressing() {
        // 半秒里朝我走了 1 格（每秒 2 格）算逼近；每秒 0.2 格的蹭动不算。
        assertTrue(HumanoidPressure.pressing(SELF, seen(OPPONENT, 0), seen(STEP_CLOSER, DECISION_MILLIS)));
        assertFalse(HumanoidPressure.pressing(SELF, seen(OPPONENT, 0), seen(CREEP_CLOSER, DECISION_MILLIS)));
    }

    @Test
    public void standingStillLeavingOrSidesteppingIsNotPressing() {
        // 它站着、走开、横着挪、只是掉下去，都没有朝我逼近。
        for (Vec3 next : new Vec3[]{OPPONENT, STEP_AWAY, STEP_SIDEWAYS, DROPPED_DOWN}) {
            assertFalse(next.toString(),
                    HumanoidPressure.pressing(SELF, seen(OPPONENT, 0), seen(next, DECISION_MILLIS)));
        }
    }

    @Test
    public void anotherHitIsPressingButExpiredMemoryIsNot() {
        // 又挨了一下（时刻变大）算被逼；原版受击记忆过期、时刻变回「没有」不算新挨的一下。
        var never = new Sighting(OPPONENT, HumanoidPressure.NEVER_STRUCK, 0);
        var struck = new Sighting(OPPONENT, STRUCK_TICK, DECISION_MILLIS);
        var struckAgain = new Sighting(OPPONENT, STRUCK_TICK + 1, 2 * DECISION_MILLIS);
        var expired = new Sighting(OPPONENT, HumanoidPressure.NEVER_STRUCK, 2 * DECISION_MILLIS);
        assertTrue(HumanoidPressure.pressing(SELF, never, struck));
        assertTrue(HumanoidPressure.pressing(SELF, struck, struckAgain));
        assertFalse(HumanoidPressure.pressing(SELF, struck, expired));
    }

    @Test
    public void sameInstantCannotShowAnApproach() {
        // 两次看到的身体时钟相同时没有速度可言，位移不算逼近。
        assertFalse(HumanoidPressure.pressing(SELF, seen(OPPONENT, DECISION_MILLIS),
                seen(STEP_CLOSER, DECISION_MILLIS)));
    }

    private static Sighting seen(Vec3 position, long millis) {
        // 没挨过打的一次观察。
        return new Sighting(position, HumanoidPressure.NEVER_STRUCK, millis);
    }
}
