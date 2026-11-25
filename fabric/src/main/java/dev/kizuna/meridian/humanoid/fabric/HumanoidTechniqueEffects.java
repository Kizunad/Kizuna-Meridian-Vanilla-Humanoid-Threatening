package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.human.fabric.HumanLimbStrikes;
import dev.kizuna.meridian.human.fabric.HumanProjectileEffects;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.fabric.world.TechniqueMovementSpeed;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldEffects;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWorldImpulses;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.server.TechniqueMovement;
import dev.kizuna.meridian.technique.server.TechniqueErrors;
import dev.kizuna.meridian.technique.server.TechniqueHost;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** 一个已加载实体的效果回执与 PA 表现；身体状态始终归 Framework。 */
final class HumanoidTechniqueEffects {
    private static final double TICKS_PER_SECOND = 20;
    private final LivingEntity entity;
    private final TechniqueWorldEffects world;
    private final HumanLimbStrikes limbs;
    private final HumanProjectileEffects projectiles;
    private final Map<Identity, EffectIntent> receipts = new HashMap<>();
    private final Set<Identity> executing = new HashSet<>();
    private final Set<String> castingIds = new HashSet<>();
    final HumanoidTechniquePresentation presentation = new HumanoidTechniquePresentation();

    HumanoidTechniqueEffects(LivingEntity entity) {
        // 共用世界执行器只接收敌我策略和 Damage 出手边界，不复制伤害规则；拳脚走 Human 的共用打击执行器，和玩家同一份。
        this.entity = entity;
        limbs = new HumanLimbStrikes(entity, new HumanoidLimbHost(entity));
        projectiles = new HumanProjectileEffects(entity, target -> HumanoidThreats.hittable(entity, target));
        world = new TechniqueWorldEffects(entity, () -> TechniqueWorldEffects.Policy.MOB,
                target -> HumanoidThreats.hittable(entity, target),
                (actor, source, strike) -> {
                    try (var ignored = VanillaStrikeContext.attack(actor, source)) {
                        strike.run();
                    }
                }, "meridian_door_breaker");
    }

    TechniqueHost.EffectCommit submit(TechniqueHost.EffectSubmission submission) {
        // 请求身份和施放内效果身份都必须保持内容一致，执行中重入不算成功。
        EffectIntent effect = submission.effect();
        TechniqueProtocol.validateEffect(effect);
        Identity request = new Identity(IdentityKind.REQUEST, "", submission.requestId());
        String declaredEffectId = effect.getEffectId();
        String effectId;
        if (declaredEffectId.isEmpty()) {
            effectId = submission.requestId();
        } else {
            effectId = declaredEffectId;
        }
        Identity identity = new Identity(IdentityKind.EFFECT, effect.getCastId(), effectId);
        checkIdentity(request, effect);
        checkIdentity(identity, effect);
        if (!receipts.containsKey(request) && !receipts.containsKey(identity)) {
            executing.add(request);
            executing.add(identity);
            try {
                execute(effect);
            } finally {
                executing.remove(request);
                executing.remove(identity);
            }
        }
        receipts.put(request, effect);
        receipts.put(identity, effect);
        return new TechniqueHost.EffectCommit(submission.requestId());
    }

    private void checkIdentity(Identity identity, EffectIntent effect) {
        // 相同身份换内容明确拒绝，失败可以用原身份重试。
        EffectIntent previous = receipts.get(identity);
        if (previous != null && !previous.equals(effect)) {
            throw new TechniqueErrors.HostRejected(FailureCode.REQUEST_ID_CONFLICT);
        }
        if (executing.contains(identity)) {
            throw new TechniqueErrors.HostRejected(FailureCode.INTERNAL_ERROR);
        }
    }

    private void execute(EffectIntent effect) {
        // 实体移动和表现不绕开原版物理，打击与劈门由共享工具执行。
        if (effect.hasStatusBenefit()) {
            TechniqueStatusBenefits.apply(entity, effect.getStatusBenefit());
        } else if (effect.hasTargetLock()) {
            projectiles.lockTarget(effect);
        } else if (effect.hasLaunchProjectile()) {
            projectiles.launch(effect);
        } else if (effect.hasLegStrike() || effect.hasLimbStrike()) {
            limbs.strike(effect);
        } else if (effect.hasWeaponStrike()) {
            world.strike(effect);
        } else if (effect.hasBreakBlocks()) {
            world.breakBlocks(effect);
        } else if (effect.hasMovement()) {
            movement(effect);
        } else if (effect.hasImpulse()) {
            double speed = effect.getImpulse().getSpeed().getValue() / TICKS_PER_SECOND;
            TechniqueWorldImpulses.apply(entity, effect.getImpulse().getDirection(),
                    direction(effect).scale(speed));
        } else if (effect.hasPresentation()) {
            presentation.apply(entity, effect);
            if (effect.getPresentation().getAction()
                    == dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction.STOP) {
                projectiles.finish(effect.getCastId());
            }
        } else {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
    }

    private void movement(EffectIntent effect) {
        // 属性身份按施放区分，承接移除旧倍率不会删掉新式的限速。
        var attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        // 26.2 的修饰按 ID 识别、不再有名字；ID 路径沿用按施放派生的 UUID，仍是一次施放一个修饰。
        String castId = effect.getCastId();
        Identifier id = movementId(castId);
        if (effect.getMovement().getAction() == MovementAction.LOCK) {
            if (attribute.getModifier(id) == null) {
                // 经 Technique 的统一换算：怪物按 √倍率 改属性，实际移速和玩家一样是倍率倍。
                double multiplier = TechniqueMovement.multiplier(effect.getMovement());
                attribute.addTransientModifier(TechniqueMovementSpeed.modifier(entity, id, multiplier));
            }
            castingIds.add(castId);
            stopRangedUse();
        } else {
            attribute.removeModifier(id);
            castingIds.remove(castId);
            world.finish(castId);
            limbs.finish(castId);
            projectiles.finish(castId);
        }
        if (entity instanceof Mob mob) {
            mob.setAggressive(!castingIds.isEmpty());
        }
    }

    private static Identifier movementId(String castId) {
        UUID castUuid = UUID.nameUUIDFromBytes(("meridian_humanoid:technique:" + castId)
                .getBytes(StandardCharsets.UTF_8));
        return Identifier.fromNamespaceAndPath("meridian_humanoid", "technique/" + castUuid);
    }

    void detach() {
        // 封存时只撤实体上的临时效果，身体中的施放留给重新绑定时的恢复流程。
        if (!castingIds.isEmpty()) {
            var attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
            for (String castId : castingIds) {
                attribute.removeModifier(movementId(castId));
            }
            castingIds.clear();
            if (entity instanceof Mob mob) {
                mob.setAggressive(false /* aggressive */);
            }
        }
        presentation.stopAll(entity);
    }

    private void stopRangedUse() {
        // 原版拉弦和导航在行矢起手时停止，原上弦组件和冲量速度保留。
        if (!HumanoidTechniqueHost.castingWayfarer(entity)) {
            return;
        }
        entity.stopUsingItem();
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        if (entity instanceof Pillager pillager) {
            pillager.setChargingCrossbow(false /* charging */);
        }
    }

    boolean isCasting() {
        // 施放状态用于阻止重复决策和原版近战，不再禁止寻路。
        return !castingIds.isEmpty();
    }

    private Vec3 direction(EffectIntent effect) {
        // 输入是世界水平向量，目标冲量使用当前实体位置。
        Vec3 forward = Vec3.directionFromRotation(0, entity.getYRot());
        return switch (effect.getImpulse().getDirection()) {
            case UP -> new Vec3(0, 1, 0);
            case FORWARD -> forward;
            case BACKWARD -> forward.reverse();
            case INPUT -> inputDirection(effect, forward);
            case TOWARD_TARGET -> towardTarget(effect.getTargetId(), forward);
            default -> throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        };
    }

    private Vec3 inputDirection(EffectIntent effect, Vec3 forward) {
        // 无移动输入按算子声明退后或前进。
        var input = effect.getDirection();
        double scale = Math.max(Math.abs(input.getX()), Math.abs(input.getZ()));
        if (scale == 0) {
            if (effect.getImpulse().getInputDefaultDirection() == ImpulseDirection.BACKWARD) {
                return forward.reverse();
            }
            return forward;
        }
        return new Vec3(input.getX() / scale, 0, input.getZ() / scale).normalize();
    }

    private Vec3 towardTarget(String targetId, Vec3 forward) {
        // 和玩家宿主同一条规则：起手时就没有目标的，按面朝的方向推进，不让整式放到一半失败。
        // 起手时有目标、中途目标没了或不在同一个世界，照旧保留原效果供服务重试或取消。
        if (targetId.isEmpty()) {
            return forward;
        }
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetId);
        } catch (IllegalArgumentException invalid) {
            // 带了目标却不是合法编号，是上游的错，照旧拒绝。
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        LivingEntity target = HumanoidFabric.findEntity(targetUuid);
        if (target == null || target.level() != entity.level()) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        Vec3 direction = target.position().subtract(entity.position()).multiply(1, 0, 1);
        if (direction.lengthSqr() == 0) {
            // 和目标水平重合（踩在它头顶、它在正上方或正下方）：同样没有方向可朝，按面朝的方向推进。
            return forward;
        }
        return direction.normalize();
    }

    private enum IdentityKind { REQUEST, EFFECT }

    private record Identity(IdentityKind kind, String castId, String id) { }
}
