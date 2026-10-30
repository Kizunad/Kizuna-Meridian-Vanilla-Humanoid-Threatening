package dev.kizuna.meridian.humanoid.fabric;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/** 把大脑指令落到原版导航与朝向上；每 tick 由接入适配器调用，路径按固定节奏重算。 */
final class HumanoidMotion {
    private static final float LOOK_YAW_SPEED = 30;
    private static final float LOOK_PITCH_SPEED = 30;
    private static final int PATH_REFRESH_TICKS = 10;
    private static final int RETREAT_HORIZONTAL_RANGE = 16;
    private static final int RETREAT_VERTICAL_RANGE = 7;
    private static final double RETREAT_STEP = 8;
    private static final double ARRIVED_DISTANCE = 1;

    /** 指令里的移动方式：交还原版、跟随对手、走到某点、原地不动。 */
    enum Movement { VANILLA, FOLLOW, MOVE_TO, STAY }

    /** 导航快慢：接近与逃离，各接入方式给出自己的倍率。 */
    enum Pace { APPROACH, FLEE }

    /** 一条移动指令；只有走到某点时有目的地。 */
    record Directive(Movement movement, Optional<Vec3d> destination, Pace pace) {
        static final Directive RELEASED = new Directive(Movement.VANILLA, Optional.empty(), Pace.APPROACH);
        static final Directive FOLLOWING = new Directive(Movement.FOLLOW, Optional.empty(), Pace.APPROACH);
        static final Directive STAYING = new Directive(Movement.STAY, Optional.empty(), Pace.APPROACH);

        static Directive moveTo(Vec3d destination, Pace pace) {
            // 走到某点的指令。
            return new Directive(Movement.MOVE_TO, Optional.of(destination), pace);
        }
    }

    /** 一种接入方式的导航倍率。 */
    record Speeds(double approach, double flee) {
        double of(Pace pace) {
            // 按快慢取倍率。
            if (pace == Pace.FLEE) {
                return flee;
            }
            return approach;
        }
    }

    private HumanoidMotion() {
        // 纯执行，不保存指令。
    }

    static void apply(MobEntity mob, Directive directive, Optional<LivingEntity> opponent, Speeds speeds) {
        // 有对手时始终看着它；跟随与走点按节奏重算路径，原地时停下导航。
        opponent.ifPresent(target -> mob.getLookControl().lookAt(target, LOOK_YAW_SPEED, LOOK_PITCH_SPEED));
        boolean refresh = mob.getNavigation().isIdle() || mob.age % PATH_REFRESH_TICKS == 0;
        switch (directive.movement()) {
            case FOLLOW -> {
                if (refresh && opponent.isPresent()) {
                    mob.getNavigation().startMovingTo(opponent.get(), speeds.of(directive.pace()));
                }
            }
            case MOVE_TO -> {
                Vec3d destination = directive.destination().orElseThrow();
                if (refresh && mob.getPos().squaredDistanceTo(destination) > ARRIVED_DISTANCE * ARRIVED_DISTANCE) {
                    mob.getNavigation().startMovingTo(destination.x, destination.y, destination.z,
                            speeds.of(directive.pace()));
                }
            }
            case STAY -> mob.getNavigation().stop();
            case VANILLA -> {
                // 原版控制自己移动。
            }
        }
    }

    static Vec3d retreatPoint(MobEntity mob, LivingEntity threat) {
        // 原版无惩罚寻路找一处背离威胁的点；找不到时沿背离方向直走一段。
        if (mob instanceof PathAwareEntity walker) {
            Vec3d found = NoPenaltyTargeting.findFrom(walker, RETREAT_HORIZONTAL_RANGE, RETREAT_VERTICAL_RANGE,
                    threat.getPos());
            if (found != null) {
                return found;
            }
        }
        return mob.getPos().add(away(mob, threat).multiply(RETREAT_STEP));
    }

    static Vec3d away(Entity self, Entity threat) {
        // 从威胁指向自己的水平单位向量；重合时取自己朝向的反方向。
        Vec3d delta = self.getPos().subtract(threat.getPos()).multiply(1, 0, 1);
        if (delta.lengthSquared() == 0) {
            return self.getRotationVector().multiply(-1, 0, -1).normalize();
        }
        return delta.normalize();
    }

    static double distance(Entity self, Entity other) {
        // 水平距离。
        double dx = other.getX() - self.getX();
        double dz = other.getZ() - self.getZ();
        return Math.hypot(dx, dz);
    }
}
