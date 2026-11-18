package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.HumanoidAssessment;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.MeleeGuardRules;
import dev.kizuna.meridian.technique.fabric.world.TechniqueMovementSpeed;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueMovement;
import java.util.OptionalDouble;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** 任职方登记的近身守卫策略；不识别职业和具体功法。 */
final class HumanoidMeleeGuard {
    static final String WITHDRAWN_TAG = "meridian_humanoid.duty.melee_guard_withdrawn";
    private static final double HALF = 0.5;
    private static final double GROUND_PROBE_BLOCKS = 0.01;

    private HumanoidMeleeGuard() { }

    static boolean enabled(LivingEntity entity) {
        return entity instanceof Villager && entity.entityTags().contains(HumanoidDuties.MELEE_GUARD_TAG)
                && HumanoidDuties.guardsVillage(entity)
                && HumanoidTiers.of(entity).filter(tier -> tier != HumanoidTier.MORTAL).isPresent();
    }

    static boolean holdsCrossbow(LivingEntity entity) {
        return entity.isHolding(Items.CROSSBOW);
    }

    static boolean withdrawn(LivingEntity entity) {
        return entity.entityTags().contains(WITHDRAWN_TAG);
    }

    static HumanoidAssessment.Stance assess(Mob mob, HumanoidMind mind, Fighter self,
                                            HumanoidAssessment.Separation separation) {
        HumanoidAssessment.Stance previous = mind.stance();
        if (withdrawn(mob)) {
            previous = HumanoidAssessment.Stance.RETREAT;
        }
        var stance = HumanoidAssessment.guarding(self, previous, separation, HumanoidBrain.config().assessment());
        if (stance == HumanoidAssessment.Stance.RETREAT) {
            mob.addTag(WITHDRAWN_TAG);
        } else {
            mob.removeTag(WITHDRAWN_TAG);
        }
        return stance;
    }

    static boolean reaches(Mob mob, LivingEntity target, TechniqueDefinition definition) {
        if (!enabled(mob)) {
            return true;
        }
        var first = HumanoidRepertoires.firstStrike(definition).orElseThrow();
        if (first.kind() != HumanoidRepertoires.Kind.CLOSE) {
            return true;
        }
        long firstHitMillis = first.millis();
        boolean grapple = definition.getEffectsList().stream()
                .anyMatch(effect -> effect.getAtMillis() == firstHitMillis && effect.hasGrapple());
        var selfBox = mob.getBoundingBox();
        var targetBox = target.getBoundingBox();
        Vec3 offsetBlocks = target.position().subtract(mob.position());
        var geometry = MeleeGuardRules.Geometry.CENTER;
        if (!grapple) {
            offsetBlocks = targetBox.getCenter().subtract(selfBox.getCenter());
            geometry = MeleeGuardRules.Geometry.BOX;
        }
        Vec3 relativeBlocksPerTick = velocityBlocksPerTick(target).subtract(velocityBlocksPerTick(mob));
        var motion = new MeleeGuardRules.Motion(vector(offsetBlocks), vector(relativeBlocksPerTick));
        var halfExtents = new MeleeGuardRules.Vector((selfBox.getXsize() + targetBox.getXsize()) * HALF,
                (selfBox.getYsize() + targetBox.getYsize()) * HALF,
                (selfBox.getZsize() + targetBox.getZsize()) * HALF);
        var strike = new MeleeGuardRules.Strike(firstHitMillis, first.distance(), geometry, halfExtents);
        return MeleeGuardRules.reaches(motion, strike);
    }

    private static Vec3 velocityBlocksPerTick(LivingEntity entity) {
        // 地面碰撞已阻止下落，速度中遗留的向下重力不能外推成持续下落。
        Vec3 velocity = entity.getDeltaMovement();
        boolean supported = entity.onGround()
                || !entity.level().noCollision(entity, entity.getBoundingBox().move(0, -GROUND_PROBE_BLOCKS, 0));
        if (supported && velocity.y < 0) {
            return new Vec3(velocity.x, 0, velocity.z);
        }
        return velocity;
    }

    private static MeleeGuardRules.Vector vector(Vec3 value) {
        return new MeleeGuardRules.Vector(value.x, value.y, value.z);
    }

    static boolean followsWarmup(Mob mob, HumanoidMind mind) {
        // 仅地面跟随的起手可以走；首击、擒持及出手段仍按原约束站定。
        return groundFollow(mob, mind) && HumanoidTechniqueHost.beforeWarmupEnd(mob);
    }

    private static boolean groundFollow(Mob mob, HumanoidMind mind) {
        return enabled(mob) && mob.onGround() && !HumanoidRestrictions.locked(mob)
                && mind.opponent().isPresent()
                && mind.stance() == HumanoidAssessment.Stance.ENGAGE
                && mind.directive().movement() == HumanoidMotion.Movement.FOLLOW;
    }

    static OptionalDouble warmupPursuit(Mob mob, HumanoidMind mind) {
        var active = HumanoidTechniqueHost.activeCast(mob);
        if (active.isEmpty() || !groundFollow(mob, mind) || withdrawn(mob)
                || !HumanoidTechniqueHost.beforeWarmupEnd(mob)) {
            return OptionalDouble.empty();
        }
        var cast = active.orElseThrow();
        var request = cast.getRequest();
        var target = mind.opponent().orElseThrow();
        if (!(target instanceof Pillager) || !holdsCrossbow(target) || !target.isAlive()
                || !target.getStringUUID().equals(request.getTargetId()) || !HumanoidGuards.withinPost(mob, target)
                || !HumanoidDuties.permitsWarmupPursuit(mob, request.getTechnique().getId())) {
            return OptionalDouble.empty();
        }
        double multiplier = TechniqueMovement.multiplier(cast);
        if (multiplier <= 0) {
            return OptionalDouble.empty();
        }
        // 只抵消本次施放自己的限速；伤势、状态与其他属性修饰继续参与原版移动。
        double approach = HumanoidBrain.config().villager().guardApproachSpeed();
        return OptionalDouble.of(approach / TechniqueMovementSpeed.attributeMultiplier(mob, multiplier));
    }

    static HumanoidMotion.Speeds speeds(Mob mob, HumanoidMind mind, HumanoidMotion.Speeds ordinary) {
        // 只提高未施放的接敌追击；施放跟进、撤离与无对手时保留原导航速度。
        if (!enabled(mob) || withdrawn(mob) || HumanoidTechniqueHost.hasActiveCast(mob)
                || mind.stance() != HumanoidAssessment.Stance.ENGAGE
                || mind.directive().movement() != HumanoidMotion.Movement.FOLLOW
                || mind.opponent().isEmpty()) {
            return ordinary;
        }
        return new HumanoidMotion.Speeds(HumanoidBrain.config().villager().guardApproachSpeed(), ordinary.flee());
    }
}
