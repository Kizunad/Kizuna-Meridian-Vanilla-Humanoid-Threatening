package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.HumanGuardStamina;
import dev.kizuna.meridian.human.server.HumanStamina;
import dev.kizuna.meridian.humanoid.server.GuardResponse;
import dev.kizuna.meridian.humanoid.server.HumanoidBodyGenerator;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.Random;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ShieldItem;

/** 个体只记威胁和自己的施放身份；成架、费用、破防和收势由功法宿主推进。 */
final class HumanoidArmGuard {
    static final TechniqueReference GUARD = TechniqueReference.newBuilder()
            .setId("meridian_basic_techniques:guard")
            .setVersion(1)
            .build();
    static final long MILLIS_PER_TICK = 50;
    private static final long IDLE = Long.MAX_VALUE;
    private HumanoidGuardThreat seen;
    private long due = IDLE;
    private long lastSeen;
    private long restUntil;
    private CastRequest owned;
    private boolean lowering;
    private Random random;
    private int candidates;
    private int checks;
    private int requests;
    private long selectedMillis;
    private float facingYaw;

    boolean tick(Mob mob, GuardResponse rules) {
        var current = HumanoidGuardThreat.find(mob, seen, rules);
        var previous = seen;
        seen = current;
        long now = mob.level().getGameTime() * MILLIS_PER_TICK;
        if (current != null) {
            lastSeen = now;
        }
        if (owned != null) {
            return maintain(mob, previous, now, rules);
        }
        if (current == null) {
            due = IDLE;
            return false;
        }
        if (!current.equals(previous)) {
            due = IDLE;
            if (now >= restUntil) {
                consider(mob, rules, now);
            }
        }
        if (due == IDLE) {
            return false;
        }
        if (now < due) {
            return true;
        }
        due = IDLE;
        var body = admitted(mob);
        if (body == null) {
            return false;
        }
        return raise(mob, body);
    }

    private void consider(Mob mob, GuardResponse rules, long now) {
        var body = admitted(mob);
        if (body == null) {
            return;
        }
        var tier = HumanoidTier.of(body.snapshot());
        var reaction = rules.reactions().get(tier);
        if (reaction.chance() == 0) {
            return;
        }
        if (random == null) {
            var level = (ServerLevel) mob.level();
            random = new Random(HumanoidBodyGenerator.seedFor(level.getSeed(), mob.getUUID()));
        }
        candidates++;
        if (random.nextDouble() < reaction.chance()) {
            facingYaw = mob.getYRot();
            selectedMillis = body.currentBody().settledAt().elapsedMillis();
            due = now + reaction.delayMillis();
        }
    }

    private HumanoidBodyState admitted(Mob mob) {
        // seen 每 tick 取自现场；仅在威胁开始或反应到期才读取结清后的身体。
        checks++;
        if (seen == null || mob.isHolding(stack -> stack.getItem() instanceof ShieldItem)
                || HumanoidTechniqueHost.hasActiveCast(mob) || HumanoidRestrictions.locked(mob)) {
            return null;
        }
        var body = HumanoidTechniqueHost.prepare(mob).body();
        var rules = HumanoidTechniqueHost.staminaRules(TechniqueFabric.service(), GUARD).orElseThrow();
        var snapshot = body.currentBody();
        if (VanillaDamageFabric.preferences().guard().selectArm(body.snapshot()).isEmpty()
                || HumanGuardStamina.rejection(snapshot, GUARD.getId(), rules).isPresent()) {
            return null;
        }
        var stamina = HumanStamina.from(snapshot);
        double capacity = stamina.capacityJoules();
        if (capacity * rules.getRestartRatio() - stamina.availableJoules() > Math.ulp(capacity)) {
            return null;
        }
        return body;
    }

    private boolean raise(Mob mob, HumanoidBodyState body) {
        var request = CastRequest.newBuilder()
                .setTechnique(GUARD)
                .setBodyId(body.snapshot().getId())
                .setCastId(UUID.randomUUID().toString())
                .build();
        requests++;
        var outcome = TechniqueFabric.service().cast(mob.getUUID(), request);
        if (outcome.getPhase() != CastPhase.WARMUP) {
            return false;
        }
        owned = request;
        lowering = false;
        mob.stopUsingItem();
        return true;
    }

    private boolean maintain(Mob mob, HumanoidGuardThreat previous, long now, GuardResponse rules) {
        var active = HumanoidTechniqueHost.activeCast(mob);
        if (active.isEmpty() || !active.get().getRequest().equals(owned)) {
            owned = null;
            restUntil = now + rules.timing().restMillis();
            return false;
        }
        if (seen == null && (previous != null && previous.ended(mob, rules.range())
                || now - lastSeen >= rules.timing().quietMillis())
                && !active.get().getSustained().hasEndRequestedMillis()) {
            lower(mob.getUUID());
        }
        return true;
    }

    void releaseControl(UUID entity) {
        due = IDLE;
        seen = null;
        if (owned != null) {
            lower(entity);
        }
    }

    void face(Mob mob, GuardResponse rules) {
        if (due == IDLE && owned == null) {
            return;
        }
        if (seen != null) {
            var direction = seen.source().position().subtract(mob.position());
            float desired = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            facingYaw = Mth.approachDegrees(facingYaw, desired, (float) rules.turnDegreesPerTick());
        }
        mob.setYRot(facingYaw);
        mob.setYHeadRot(facingYaw);
        mob.yBodyRot = facingYaw;
    }

    private void lower(UUID entity) {
        if (lowering) {
            return;
        }
        var end = EndActiveRequest.newBuilder()
                .setBodyId(owned.getBodyId())
                .setCastId(owned.getCastId())
                .build();
        var receipt = TechniqueFabric.service().endActive(entity, end);
        lowering = receipt.getPhase() != CastPhase.CAST_PHASE_UNSPECIFIED;
    }

    int candidates() {
        return candidates;
    }

    int checks() {
        return checks;
    }

    int requests() {
        return requests;
    }

    long selectedMillis() {
        return selectedMillis;
    }
}
