package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.server.MeleeGuardRules.Geometry;
import dev.kizuna.meridian.humanoid.server.MeleeGuardRules.Motion;
import dev.kizuna.meridian.humanoid.server.MeleeGuardRules.Strike;
import dev.kizuna.meridian.humanoid.server.MeleeGuardRules.Vector;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MeleeGuardRulesTest {
    private static final long LONG_WARMUP_MILLIS = 1900;
    private static final double REACH = 2.5;
    private static final double NEAR_EDGE = 1.9;
    private static final double ESCAPE_BLOCKS_PER_TICK = 0.2;
    private static final double APPROACH_BLOCKS_PER_TICK = -0.02;
    private static final double BOX_HALF_EXTENTS = 0.6;
    private static final Vector ZERO = new Vector(0, 0, 0);

    @Test
    public void movingTargetEscapesBeforeLongWarmupEnds() {
        var motion = new Motion(new Vector(NEAR_EDGE, 0, 0), new Vector(ESCAPE_BLOCKS_PER_TICK, 0, 0));
        assertFalse(MeleeGuardRules.reaches(motion, strike(Geometry.CENTER)));
        assertFalse(MeleeGuardRules.reaches(motion, strike(Geometry.BOX)));
    }

    @Test
    public void stationaryAndClosingTargetsRemainReachable() {
        var stationary = new Motion(new Vector(NEAR_EDGE, 0, 0), ZERO);
        var closing = new Motion(new Vector(REACH, 0, 0), new Vector(APPROACH_BLOCKS_PER_TICK, 0, 0));
        var centerStrike = strike(Geometry.CENTER);
        assertTrue(MeleeGuardRules.reaches(stationary, centerStrike));
        assertTrue(MeleeGuardRules.reaches(closing, centerStrike));
    }

    @Test
    public void grappleUsesCentersAndFistUsesExpandedBoxesWithMargin() {
        var edge = new Motion(new Vector(REACH, 0, 0), ZERO);
        assertFalse(MeleeGuardRules.reaches(edge, strike(Geometry.CENTER)));
        assertTrue(MeleeGuardRules.reaches(edge, strike(Geometry.BOX)));
    }

    private static Strike strike(Geometry geometry) {
        var extents = new Vector(BOX_HALF_EXTENTS, BOX_HALF_EXTENTS, BOX_HALF_EXTENTS);
        return new Strike(LONG_WARMUP_MILLIS, REACH, geometry, extents);
    }
}
