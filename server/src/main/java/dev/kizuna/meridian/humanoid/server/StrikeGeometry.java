package dev.kizuna.meridian.humanoid.server;

import java.util.OptionalLong;

/** 来袭打击的水平几何：扇形是否覆盖我，以及走出覆盖所需的位移与时间。 */
public final class StrikeGeometry {
    private static final double EXIT_MARGIN = 0.3;
    private static final double MILLIS_PER_SECOND = 1000;
    private static final double RIGHT_ANGLE_DEGREES = 90;
    private static final double FULL_TURN_DEGREES = 360;
    private static final double HALF_TURN_DEGREES = 180;

    /** 水平面上的方块坐标。 */
    public record Point(double x, double z) { }

    /** 施放者位置、朝向（度，原版 yaw 约定：0 朝 +z，90 朝 −x）、半径与半张角（度）。 */
    public record Sector(Point origin, double facingDegrees, double reach, double halfAngleDegrees) { }

    private StrikeGeometry() {
        // 纯几何函数，不读实体。
    }

    public static boolean covers(Sector sector, Point me) {
        // 距离在半径内，且相对施放者朝向的夹角不超过半张角。
        return distance(sector.origin(), me) <= sector.reach()
                && Math.abs(offsetDegrees(sector, me)) <= sector.halfAngleDegrees();
    }

    public static double exitDistance(Sector sector, Point me) {
        // 径向走出半径与侧向越过扇形边线取较短者，再加安全余量；未覆盖时为 0。
        if (!covers(sector, me)) {
            return 0;
        }
        double distance = distance(sector.origin(), me);
        double radial = sector.reach() - distance;
        double edgeDegrees = sector.halfAngleDegrees() - Math.abs(offsetDegrees(sector, me));
        double lateral = Double.POSITIVE_INFINITY;
        if (edgeDegrees < RIGHT_ANGLE_DEGREES) {
            lateral = distance * Math.sin(Math.toRadians(edgeDegrees));
        }
        return Math.min(radial, lateral) + EXIT_MARGIN;
    }

    public static long exitMillis(double exitDistance, double walkBlocksPerSecond, OptionalLong leapMillis) {
        // 走路耗时与跃步耗时取较短者；不需要位移时为 0，走不动又不能跃步时为无穷久。
        if (exitDistance <= 0) {
            return 0;
        }
        long walk = Long.MAX_VALUE;
        if (walkBlocksPerSecond > 0) {
            walk = Math.round(exitDistance / walkBlocksPerSecond * MILLIS_PER_SECOND);
        }
        if (leapMillis.isPresent()) {
            return Math.min(walk, leapMillis.getAsLong());
        }
        return walk;
    }

    public static double offsetDegrees(Sector sector, Point me) {
        // 我相对施放者的方位角减去朝向，归一到 [−180, 180)。
        double dx = me.x() - sector.origin().x();
        double dz = me.z() - sector.origin().z();
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double offset = (bearing - sector.facingDegrees()) % FULL_TURN_DEGREES;
        if (offset >= HALF_TURN_DEGREES) {
            return offset - FULL_TURN_DEGREES;
        }
        if (offset < -HALF_TURN_DEGREES) {
            return offset + FULL_TURN_DEGREES;
        }
        return offset;
    }

    private static double distance(Point from, Point to) {
        // 水平欧氏距离。
        return Math.hypot(to.x() - from.x(), to.z() - from.z());
    }
}
