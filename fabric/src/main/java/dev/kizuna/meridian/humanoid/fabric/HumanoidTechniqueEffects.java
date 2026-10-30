package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
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
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 一个已加载实体的效果回执与 PA 表现；身体状态始终归 Framework。 */
final class HumanoidTechniqueEffects {
    private static final double TICKS_PER_SECOND = 20;
    private final LivingEntity entity;
    private final TechniqueWorldEffects world;
    private final Map<Identity, EffectIntent> receipts = new HashMap<>();
    private final Set<Identity> executing = new HashSet<>();
    private final Set<String> castingIds = new HashSet<>();
    private final HumanoidTechniquePresentation presentation = new HumanoidTechniquePresentation();

    HumanoidTechniqueEffects(LivingEntity entity) {
        // 共用世界执行器只接收敌我策略和 Damage 出手边界，不复制伤害规则。
        this.entity = entity;
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
        } else if (effect.hasWeaponStrike()) {
            world.strike(effect);
        } else if (effect.hasBreakBlocks()) {
            world.breakBlocks(effect);
        } else if (effect.hasMovement()) {
            movement(effect);
        } else if (effect.hasImpulse()) {
            double speed = effect.getImpulse().getSpeed().getValue() / TICKS_PER_SECOND;
            TechniqueWorldImpulses.apply(entity, effect.getImpulse().getDirection(),
                    direction(effect).multiply(speed));
        } else if (effect.hasPresentation()) {
            presentation.apply(entity, effect);
        } else {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
    }

    private void movement(EffectIntent effect) {
        // 属性身份按施放区分，承接移除旧倍率不会删掉新式的限速。
        var attribute = entity.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (attribute == null) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        UUID id = UUID.nameUUIDFromBytes(("meridian_humanoid:technique:" + effect.getCastId())
                .getBytes(StandardCharsets.UTF_8));
        if (effect.getMovement().getAction() == MovementAction.LOCK) {
            if (attribute.getModifier(id) == null) {
                attribute.addTemporaryModifier(new EntityAttributeModifier(id, "meridian_humanoid:technique",
                        TechniqueMovement.multiplier(effect.getMovement()) - 1,
                        EntityAttributeModifier.Operation.MULTIPLY_TOTAL));
            }
            castingIds.add(effect.getCastId());
        } else {
            attribute.removeModifier(id);
            castingIds.remove(effect.getCastId());
            world.finish(effect.getCastId());
        }
        if (entity instanceof MobEntity mob) {
            mob.setAttacking(!castingIds.isEmpty());
        }
    }

    boolean isCasting() {
        // 施放状态用于阻止重复决策和原版近战，不再禁止寻路。
        return !castingIds.isEmpty();
    }

    private Vec3d direction(EffectIntent effect) {
        // 输入是世界水平向量，目标冲量使用当前实体位置。
        Vec3d forward = Vec3d.fromPolar(0, entity.getYaw());
        return switch (effect.getImpulse().getDirection()) {
            case UP -> new Vec3d(0, 1, 0);
            case FORWARD -> forward;
            case BACKWARD -> forward.negate();
            case INPUT -> inputDirection(effect, forward);
            case TOWARD_TARGET -> towardTarget(effect.getTargetId());
            default -> throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        };
    }

    private Vec3d inputDirection(EffectIntent effect, Vec3d forward) {
        // 无移动输入按算子声明退后或前进。
        var input = effect.getDirection();
        double scale = Math.max(Math.abs(input.getX()), Math.abs(input.getZ()));
        if (scale == 0) {
            if (effect.getImpulse().getInputDefaultDirection() == ImpulseDirection.BACKWARD) {
                return forward.negate();
            }
            return forward;
        }
        return new Vec3d(input.getX() / scale, 0, input.getZ() / scale).normalize();
    }

    private Vec3d towardTarget(String targetId) {
        // 目标必须仍在同世界，失效时保留原效果供服务重试或取消。
        LivingEntity target = HumanoidFabric.findEntity(UUID.fromString(targetId));
        if (target == null || target.getWorld() != entity.getWorld()) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        Vec3d direction = target.getPos().subtract(entity.getPos()).multiply(1, 0, 1);
        if (direction.lengthSquared() == 0) {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        return direction.normalize();
    }

    private enum IdentityKind { REQUEST, EFFECT }

    private record Identity(IdentityKind kind, String castId, String id) { }
}
