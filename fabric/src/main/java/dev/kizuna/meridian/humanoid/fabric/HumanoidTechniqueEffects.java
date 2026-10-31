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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

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
                    direction(effect).scale(speed));
        } else if (effect.hasPresentation()) {
            presentation.apply(entity, effect);
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
        UUID castUuid = UUID.nameUUIDFromBytes(("meridian_humanoid:technique:" + effect.getCastId())
                .getBytes(StandardCharsets.UTF_8));
        Identifier id = Identifier.fromNamespaceAndPath("meridian_humanoid", "technique/" + castUuid);
        if (effect.getMovement().getAction() == MovementAction.LOCK) {
            if (attribute.getModifier(id) == null) {
                attribute.addTransientModifier(new AttributeModifier(id,
                        TechniqueMovement.multiplier(effect.getMovement()) - 1,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            castingIds.add(effect.getCastId());
        } else {
            attribute.removeModifier(id);
            castingIds.remove(effect.getCastId());
            world.finish(effect.getCastId());
        }
        if (entity instanceof Mob mob) {
            mob.setAggressive(!castingIds.isEmpty());
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
            case TOWARD_TARGET -> towardTarget(effect.getTargetId());
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

    private Vec3 towardTarget(String targetId) {
        // 目标必须仍在同世界，失效时保留原效果供服务重试或取消。
        LivingEntity target = HumanoidFabric.findEntity(UUID.fromString(targetId));
        if (target == null || target.level() != entity.level()) {
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        }
        Vec3 direction = target.position().subtract(entity.position()).multiply(1, 0, 1);
        if (direction.lengthSqr() == 0) {
            throw new TechniqueErrors.HostRejected(FailureCode.INVALID_REQUEST);
        }
        return direction.normalize();
    }

    private enum IdentityKind { REQUEST, EFFECT }

    private record Identity(IdentityKind kind, String castId, String id) { }
}
