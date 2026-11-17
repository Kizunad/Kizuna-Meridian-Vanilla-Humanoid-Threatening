package dev.kizuna.meridian.humanoid.server;

/** 近身守卫的首击预测：当前相对位移按 tick 速度直线外推，不预测转向或承接。 */
public final class MeleeGuardRules {
    public static final double STRIKE_SAFETY_MARGIN = 0.2;
    private static final double MILLIS_PER_TICK = 50;

    public enum Geometry { CENTER, BOX }

    public record Vector(double x, double y, double z) { }

    public record Motion(Vector offsetBlocks, Vector relativeBlocksPerTick) { }

    public record Strike(long firstHitMillis, double reachBlocks, Geometry geometry, Vector halfExtents) { }

    private MeleeGuardRules() { }

    public static boolean reaches(Motion motion, Strike strike) {
        double ticksUntilHit = strike.firstHitMillis() / MILLIS_PER_TICK;
        Vector offset = motion.offsetBlocks();
        Vector velocity = motion.relativeBlocksPerTick();
        double x = offset.x() + velocity.x() * ticksUntilHit;
        double y = offset.y() + velocity.y() * ticksUntilHit;
        double z = offset.z() + velocity.z() * ticksUntilHit;
        double safeReach = Math.max(0, strike.reachBlocks() - STRIKE_SAFETY_MARGIN);
        if (strike.geometry() == Geometry.CENTER) {
            return x * x + y * y + z * z <= safeReach * safeReach;
        }
        Vector extents = strike.halfExtents();
        return Math.abs(x) <= safeReach + extents.x()
                && Math.abs(y) <= safeReach + extents.y()
                && Math.abs(z) <= safeReach + extents.z();
    }
}
