package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.humanoid.server.StrikeGeometry.Point;
import dev.kizuna.meridian.humanoid.server.StrikeGeometry.Sector;
import org.junit.Test;

import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 来袭扇形的覆盖、方位、走出距离与走出耗时。 */
public class StrikeGeometryTest {
    private static final Point ORIGIN = new Point(0, 0);
    private static final double REACH = 3;
    private static final double HALF_ANGLE = 60;
    private static final double HALF_TURN = 180;
    private static final double FACING_SOUTH = 0;
    private static final double FACING_WEST = 90;
    private static final double INSIDE = 2;
    private static final double BEYOND = 4;
    private static final double NEAR_EDGE_DEGREES = 50;
    private static final double NEAR_EDGE_DISTANCE = 2.5;
    private static final double EXIT_MARGIN = 0.3;
    private static final double WALK_SPEED = 4.3;
    private static final long LEAP_MILLIS = 250;
    private static final long WALK_MILLIS = 302;
    private static final double TOLERANCE = 1e-9;
    private static final Sector SOUTH = new Sector(ORIGIN, FACING_SOUTH, REACH, HALF_ANGLE);

    @Test
    public void coversPointsInsideReachAndAngle() {
        // 原版 yaw 约定：0 朝 +z，90 朝 −x；身后与半径外都不覆盖。
        var west = new Sector(ORIGIN, FACING_WEST, REACH, HALF_ANGLE);
        assertTrue(StrikeGeometry.covers(SOUTH, new Point(0, INSIDE)));
        assertFalse(StrikeGeometry.covers(SOUTH, new Point(0, -INSIDE)));
        assertFalse(StrikeGeometry.covers(SOUTH, new Point(0, BEYOND)));
        assertTrue(StrikeGeometry.covers(west, new Point(-INSIDE, 0)));
        assertFalse(StrikeGeometry.covers(west, new Point(INSIDE, 0)));
    }

    @Test
    public void offsetIsNormalizedToHalfOpenRange() {
        // 正前方为 0，正后方归到 −180，偏角在 [−180, 180)。
        var west = new Sector(ORIGIN, FACING_WEST, REACH, HALF_ANGLE);
        assertEquals(0, StrikeGeometry.offsetDegrees(west, new Point(-INSIDE, 0)), TOLERANCE);
        assertEquals(-HALF_TURN, StrikeGeometry.offsetDegrees(west, new Point(INSIDE, 0)), TOLERANCE);
        assertEquals(NEAR_EDGE_DEGREES, StrikeGeometry.offsetDegrees(SOUTH, edgePoint()), TOLERANCE);
    }

    @Test
    public void exitTakesShorterOfRadialAndLateral() {
        // 正前方径向更近；靠近边线时侧向更近；全向范围只能径向走出；未覆盖时不需要位移。
        double lateral = NEAR_EDGE_DISTANCE * Math.sin(Math.toRadians(HALF_ANGLE - NEAR_EDGE_DEGREES));
        var round = new Sector(ORIGIN, FACING_SOUTH, REACH, HALF_TURN);
        assertEquals(REACH - INSIDE + EXIT_MARGIN,
                StrikeGeometry.exitDistance(SOUTH, new Point(0, INSIDE)), TOLERANCE);
        assertEquals(lateral + EXIT_MARGIN, StrikeGeometry.exitDistance(SOUTH, edgePoint()), TOLERANCE);
        assertEquals(REACH - INSIDE + EXIT_MARGIN,
                StrikeGeometry.exitDistance(round, new Point(0, INSIDE)), TOLERANCE);
        assertEquals(0, StrikeGeometry.exitDistance(SOUTH, new Point(0, -INSIDE)), TOLERANCE);
    }

    @Test
    public void exitMillisPrefersFasterOfWalkAndLeap() {
        // 走 1.3 格约 302 ms，能跃步时取更短的跃步；无需位移为 0；走不动又不能跃步为无穷久。
        double exit = REACH - INSIDE + EXIT_MARGIN;
        assertEquals(WALK_MILLIS, StrikeGeometry.exitMillis(exit, WALK_SPEED, OptionalLong.empty()));
        assertEquals(LEAP_MILLIS, StrikeGeometry.exitMillis(exit, WALK_SPEED, OptionalLong.of(LEAP_MILLIS)));
        assertEquals(0, StrikeGeometry.exitMillis(0, WALK_SPEED, OptionalLong.empty()));
        assertEquals(Long.MAX_VALUE, StrikeGeometry.exitMillis(exit, 0, OptionalLong.empty()));
    }

    private static Point edgePoint() {
        // 相对朝南偏 50 度、距施放者 2.5 格，离 60 度边线只差 10 度。
        double radians = Math.toRadians(NEAR_EDGE_DEGREES);
        return new Point(-NEAR_EDGE_DISTANCE * Math.sin(radians), NEAR_EDGE_DISTANCE * Math.cos(radians));
    }
}
