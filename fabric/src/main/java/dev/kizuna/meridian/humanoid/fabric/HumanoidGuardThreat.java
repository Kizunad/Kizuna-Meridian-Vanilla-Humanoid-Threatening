package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.server.GuardResponse;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.server.TechniqueForecasts;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.GuardRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 跟踪正在逼近的敌手或正在瞄准本人的射手。 */
record HumanoidGuardThreat(LivingEntity source, Kind kind) {
    enum Kind { MELEE, DRAW }

    static HumanoidGuardThreat find(Mob mob, HumanoidGuardThreat previous, GuardResponse rules) {
        if (previous != null && previous.present(mob, rules, rules.range().meleeExit())) {
            return previous;
        }
        var target = mob.getTarget();
        if (target != null) {
            var threat = approaching(mob, target, rules);
            if (threat != null) {
                return threat;
            }
        }
        for (var source : HumanoidThreats.pressers(mob)) {
            var threat = approaching(mob, source, rules);
            if (threat != null) {
                return threat;
            }
        }
        for (var player : mob.level().players()) {
            if (player.isUsingItem() || player.swinging) {
                var threat = approaching(mob, player, rules);
                if (threat != null) {
                    return threat;
                }
            }
        }
        return null;
    }

    private static HumanoidGuardThreat approaching(Mob mob, LivingEntity source, GuardResponse rules) {
        Kind kind = Kind.MELEE;
        if (bow(source.getUseItem())) {
            kind = Kind.DRAW;
        }
        var threat = new HumanoidGuardThreat(source, kind);
        if (threat.present(mob, rules, rules.range().meleeEnter())) {
            return threat;
        }
        return null;
    }

    private boolean present(Mob mob, GuardResponse rules, double meleeLimit) {
        if (!source.isAlive() || source.isRemoved() || source.level() != mob.level() || source.isSpectator()
                || source instanceof Player player && player.isCreative()
                || HumanoidCharmController.spares(mob, source) || HumanoidCharmController.spares(source, mob)
                || !HumanoidCharmController.acceptsTarget(mob, source) || !CrisisRaiders.acceptsTarget(mob, source)) {
            return false;
        }
        Vec3 towardSource = source.position().subtract(mob.position());
        Vec3 facing = Vec3.directionFromRotation(0, mob.getYRot());
        if (!GuardRules.withinAngle(horizontal(facing), horizontal(towardSource), rules.angleDegrees())) {
            return false;
        }
        if (kind == Kind.DRAW) {
            return aiming(mob, rules.range().ranged());
        }
        if (meleeLimit <= 0 || towardSource.lengthSqr() > meleeLimit * meleeLimit
                || !mob.getSensing().hasLineOfSight(source)) {
            return false;
        }
        if (HumanoidThreats.recentMelee(mob, source, rules.timing().recentMeleeMillis())) {
            return true;
        }
        boolean targeting = source instanceof Mob hunter && hunter.getTarget() == mob;
        if (source.swinging && (targeting || lookingAt(mob, meleeLimit))) {
            return true;
        }
        if (HumanoidSpeciesHooks.isLivingHumanoid(source) && !HumanoidTechniqueHost.hasActiveCast(source)) {
            return false;
        }
        return HumanoidBrain.observe(source).flatMap(TechniqueService.Observation::cast)
                .filter(cast -> cast.phase() == CastPhase.WARMUP)
                .filter(cast -> {
                    var request = cast.active().getRequest();
                    return request.getTargetId().equals(mob.getStringUUID())
                            || request.getTargetId().isEmpty() && (targeting || lookingAt(mob, meleeLimit));
                })
                .flatMap(cast -> TechniqueFabric.service().registry().find(cast.active().getRequest().getTechnique()))
                .flatMap(TechniqueForecasts::firstHit).filter(TechniqueForecasts.FirstHit::close).isPresent();
    }

    private boolean aiming(Mob mob, double range) {
        if (!bow(source.getUseItem()) || range <= 0
                || mob.distanceToSqr(source) > range * range || !mob.getSensing().hasLineOfSight(source)) {
            return false;
        }
        return lookingAt(mob, range);
    }

    private boolean lookingAt(Mob mob, double range) {
        Vec3 eye = source.getEyePosition();
        Vec3 end = eye.add(source.getLookAngle().scale(range));
        return mob.getBoundingBox().clip(eye, end).isPresent();
    }

    boolean ended(Mob mob, GuardResponse.Range range) {
        if (!source.isAlive() || source.isRemoved() || source.level() != mob.level()) {
            return false;
        }
        return kind == Kind.DRAW || mob.distanceToSqr(source) > range.meleeExit() * range.meleeExit();
    }

    private static GuardRules.Direction horizontal(Vec3 vector) {
        return new GuardRules.Direction(vector.x, vector.z);
    }

    private static boolean bow(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof BowItem || item instanceof CrossbowItem;
    }
}
