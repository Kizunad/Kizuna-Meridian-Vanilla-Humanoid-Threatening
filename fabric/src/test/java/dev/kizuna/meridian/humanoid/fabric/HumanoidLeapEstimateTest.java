package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Repertoire;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 跃步按目标每 tick 的速度预估未来位置，与逐 tick 后撤的实际位移对照。 */
public final class HumanoidLeapEstimateTest {
    private static final long MILLIS_PER_TICK = 50;
    private static final int FLIGHT_TICKS = 10;
    private static final int FIRST_HIT_TICKS = 8;
    private static final int LATE_FIRST_HIT_TICKS = 18;
    private static final long LONG_FLIGHT_MILLIS = 600;
    private static final long KNOCK_FIRST_HIT_MILLIS = 450;
    private static final long KNOCK_SECOND_HIT_MILLIS = 800;
    private static final long KNOCK_THIRD_HIT_MILLIS = 1250;
    private static final double TARGET_DISTANCE = 9;
    private static final double LANDING_DISTANCE = 7;
    private static final double STRIKE_REACH = 2;
    private static final double SLOW_RETREAT_PER_TICK = 0.05;
    private static final double RETREAT_PER_TICK = 0.2;
    private static final double TOLERANCE = 1e-9;
    private static final Vec3 START = new Vec3(0, 0, 1);
    private static final Vec3 VELOCITY_PER_TICK = new Vec3(0, 0, RETREAT_PER_TICK);

    @Test
    public void flightEstimateMatchesUniformRetreat() {
        assertRetreatEstimate(FLIGHT_TICKS);
    }

    @Test
    public void safetyHorizonMatchesUniformRetreat() {
        assertRetreatEstimate(FLIGHT_TICKS + FIRST_HIT_TICKS);
    }

    @Test
    public void stationaryTargetStaysAtTheSamePosition() {
        Vec3 predicted = HumanoidActions.predictedTargetPosition(START, Vec3.ZERO, FLIGHT_TICKS * MILLIS_PER_TICK);
        assertEquals(0, predicted.distanceTo(START), TOLERANCE);
    }

    @Test
    public void firstHitBeforeLandingUsesLandingTime() {
        assertSafetyEstimate(FIRST_HIT_TICKS, FLIGHT_TICKS);
    }

    @Test
    public void firstHitAtLandingMatchesUniformRetreat() {
        assertSafetyEstimate(FLIGHT_TICKS, FLIGHT_TICKS);
    }

    @Test
    public void firstHitAfterLandingMatchesUniformRetreat() {
        assertSafetyEstimate(LATE_FIRST_HIT_TICKS, LATE_FIRST_HIT_TICKS);
    }

    @Test
    public void hitAfterLandingWithinReachIsAccepted() {
        var definition = hits(KNOCK_FIRST_HIT_MILLIS, KNOCK_SECOND_HIT_MILLIS, KNOCK_THIRD_HIT_MILLIS);
        Vec3 velocity = new Vec3(0, 0, SLOW_RETREAT_PER_TICK);
        assertAccepted(definition, velocity);
    }

    @Test
    public void hitAfterLandingBeyondReachIsRejected() {
        var definition = hits(KNOCK_FIRST_HIT_MILLIS, KNOCK_SECOND_HIT_MILLIS, KNOCK_THIRD_HIT_MILLIS);
        Vec3 position = new Vec3(0, 0, TARGET_DISTANCE);
        assertFalse("落地时对方已离开起手范围却仍被接受",
                HumanoidActions.landingStrikeInRange(plan(), definition, position, VELOCITY_PER_TICK));
    }

    @Test
    public void allHitsBeforeLandingAreRejected() {
        var definition = hits(KNOCK_FIRST_HIT_MILLIS);
        Vec3 position = new Vec3(0, 0, TARGET_DISTANCE);
        assertFalse("所有击均早于落地却仍被接受",
                HumanoidActions.landingStrikeInRange(plan(), definition, position, Vec3.ZERO));
    }

    @Test
    public void hitExactlyAtLandingIsAccepted() {
        assertAccepted(hits(KNOCK_FIRST_HIT_MILLIS, LONG_FLIGHT_MILLIS), Vec3.ZERO);
    }

    private static void assertAccepted(TechniqueDefinition definition, Vec3 velocityPerTick) {
        Vec3 position = new Vec3(0, 0, TARGET_DISTANCE);
        assertTrue("落地时或之后还有击且在范围内却被拒绝",
                HumanoidActions.landingStrikeInRange(plan(), definition, position, velocityPerTick));
    }

    private static HumanoidActions.LeapPlan plan() {
        Vec3 landing = new Vec3(0, 0, LANDING_DISTANCE);
        var preview = new TechniqueLeaps.Preview(landing, LONG_FLIGHT_MILLIS);
        var strike = new Repertoire.Strike("knock", KNOCK_FIRST_HIT_MILLIS, STRIKE_REACH, 0);
        return new HumanoidActions.LeapPlan(strike, preview, landing, LONG_FLIGHT_MILLIS, KNOCK_FIRST_HIT_MILLIS);
    }

    private static TechniqueDefinition hits(long... hitMillis) {
        var definition = TechniqueDefinition.newBuilder();
        for (long millis : hitMillis) {
            var effect = TechniqueEffect.newBuilder()
                    .setAtMillis(millis)
                    .setWeaponStrike(WeaponStrike.getDefaultInstance())
                    .build();
            definition.addEffects(effect);
        }
        return definition.build();
    }

    private static void assertSafetyEstimate(int firstHitTicks, int expectedTicks) {
        long flightMillis = FLIGHT_TICKS * MILLIS_PER_TICK;
        long firstHitMillis = firstHitTicks * MILLIS_PER_TICK;
        Vec3 predicted = HumanoidActions.predictedTargetAtLandingOrFirstHit(START, VELOCITY_PER_TICK,
                flightMillis, firstHitMillis);
        Vec3 actual = measuredRetreat(expectedTicks);
        System.out.printf("leap-safety flight_ms=%d first_hit_ms=%d predicted_z=%.6f actual_z=%.6f%n",
                flightMillis, firstHitMillis, predicted.z, actual.z);
        assertEquals("落地或首击较晚时刻的位置预报偏差", 0, predicted.distanceTo(actual), TOLERANCE);
    }

    private static Vec3 measuredRetreat(int ticks) {
        Vec3 actual = START;
        for (int tick = 0; tick < ticks; tick++) {
            actual = actual.add(VELOCITY_PER_TICK);
        }
        return actual;
    }

    private static void assertRetreatEstimate(int ticks) {
        Vec3 actual = measuredRetreat(ticks);
        long horizonMillis = ticks * MILLIS_PER_TICK;
        Vec3 predicted = HumanoidActions.predictedTargetPosition(START, VELOCITY_PER_TICK, horizonMillis);
        System.out.printf("leap-estimate horizon_ms=%d predicted_displacement=%.6f actual_displacement=%.6f%n",
                horizonMillis, predicted.distanceTo(START), actual.distanceTo(START));
        assertEquals("匀速后撤位置预估偏差", 0, predicted.distanceTo(actual), TOLERANCE);
    }
}
