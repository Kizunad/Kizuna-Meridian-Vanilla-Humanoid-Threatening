package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TargetDistanceBand;
import dev.kizuna.meridian.technique.server.TechniqueSelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** 行矢选择所需的世界事实，同一轮决策只采集一次。 */
final class HumanoidWayfarerFacts {
    static final double NEAR_DISTANCE = 8;
    static final double MEDIUM_DISTANCE = 16;
    private static final double TICKS_PER_SECOND = 20;
    private static final double FAN_HALF_ANGLE_DEGREES = 45;
    private static final double FAN_COSINE = Math.cos(Math.toRadians(FAN_HALF_ANGLE_DEGREES));

    record Target(double distance, TechniqueSelector.Visibility visibility, double speedMetersPerSecond,
                  TechniqueSelector.Approach approach, int nearbyEnemies) {
        boolean visible() {
            return visibility == TechniqueSelector.Visibility.VISIBLE;
        }

        boolean approaching() {
            return approach == TechniqueSelector.Approach.APPROACHING;
        }
    }

    private HumanoidWayfarerFacts() {
        // 事实来自实体、速度和敌意，不推测客户端瞄准或另存目标记忆。
    }

    static Target read(Mob mob, LivingEntity target) {
        // 逼近看双方相对水平速度：自己站定而敌人冲来同样算，不要求自己正在后退。
        Vec3 offset = target.position().subtract(mob.position()).multiply(1, 0, 1);
        Vec3 velocity = target.getDeltaMovement();
        Vec3 relative = velocity.subtract(mob.getDeltaMovement()).multiply(1, 0, 1);
        double speed = velocity.horizontalDistance() * TICKS_PER_SECOND;
        var visibility = mob.hasLineOfSight(target)
                ? TechniqueSelector.Visibility.VISIBLE : TechniqueSelector.Visibility.HIDDEN;
        var approach = offset.dot(relative) < 0
                ? TechniqueSelector.Approach.APPROACHING : TechniqueSelector.Approach.NOT_APPROACHING;
        return new Target(offset.length(), visibility, speed, approach, nearbyEnemies(mob, target));
    }

    static TechniqueSelector.TargetContext context(Mob mob, Target target) {
        // 距离使用同一份水平观测；原版生物不耗箭，备用武器来自真实五格库存。
        return new TechniqueSelector.TargetContext(band(target.distance()), target.visibility(),
                target.speedMetersPerSecond(), target.approach(),
                TechniqueSelector.Ammunition.AVAILABLE, HumanoidWayfarerWeapons.reserves(mob));
    }

    private static TargetDistanceBand band(double distance) {
        if (distance < NEAR_DISTANCE) {
            return TargetDistanceBand.NEAR;
        }
        if (distance <= MEDIUM_DISTANCE) {
            return TargetDistanceBand.MEDIUM;
        }
        return TargetDistanceBand.FAR;
    }

    private static int nearbyEnemies(Mob mob, LivingEntity target) {
        // 横风只数八格内、前方九十度扇面中的敌人；同队和袭击同伴不计入人数。
        Vec3 forward = mob.getLookAngle().multiply(1, 0, 1).normalize();
        var area = mob.getBoundingBox().inflate(NEAR_DISTANCE);
        int count = 0;
        for (LivingEntity candidate : mob.level().getEntitiesOfClass(LivingEntity.class, area,
                other -> other != mob && other.isAlive() && !other.isSpectator()
                        && (!(other instanceof Player player) || !player.isCreative())
                        && HumanoidThreats.hittable(mob, other)
                        && (other == target || HumanoidThreats.enemy(mob, other)))) {
            Vec3 offset = candidate.position().subtract(mob.position()).multiply(1, 0, 1);
            double distance = offset.length();
            if (distance < NEAR_DISTANCE && (distance == 0 || forward.dot(offset) >= FAN_COSINE * distance)) {
                count++;
            }
        }
        return count;
    }
}
