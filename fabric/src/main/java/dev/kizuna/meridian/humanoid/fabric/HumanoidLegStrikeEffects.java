package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.technique.fabric.world.TechniqueSounds;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegKnockbackKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeFacing;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.server.TechniqueStrikeTargets;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

/** 类人生物的腿击执行器；伤害身份、目标预算和击退均来自目录意图。 */
final class HumanoidLegStrikeEffects {
    private static final double HALF = 0.5;
    private static final double TARGET_MARGIN = 0.1;
    private static final double SINGLE_TARGET_ANGLE = 60;
    private static final float ATTACK_SOUND_VOLUME = 1.0F;
    private static final float ATTACK_SOUND_PITCH = 1.0F;

    private final Map<String, CastHits> casts = new HashMap<>();

    void strike(LivingEntity actor, EffectIntent effect) {
        // 一次腿击在同一个 Damage 上下文内完成目标选择、做功、伤害和击退。
        LegStrikeIntent intent = effect.getLegStrike();
        LegStrike strike = intent.getStrike();
        LegRules rules = intent.getLegRules();
        CastHits hits = casts.computeIfAbsent(effect.getCastId(), ignored -> new CastHits());
        StrikeData data = strikeData(actor, strike, rules);
        actor.swing(InteractionHand.MAIN_HAND);
        playAttackSound(actor);
        List<LivingEntity> candidates = candidates(actor, strike);
        List<String> selected = TechniqueStrikeTargets.select(strike, hits.groups,
                candidates.stream().map(LivingEntity::getStringUUID).toList());
        Set<String> group = hits.groups.computeIfAbsent(strike.getDedupGroup(), ignored -> new HashSet<>());
        group.addAll(selected);
        try (var ignored = VanillaStrikeContext.attack(actor, data.source())) {
            HitContext context = new HitContext(actor, candidates, selected, strike, data, intent.getHitSoundsList());
            hitTargets(context);
        }
    }

    void finish(String castId) {
        // 收势或取消后释放本次腿击的去重预算，下一次施放从空集合开始。
        casts.remove(castId);
    }

    private static StrikeData strikeData(LivingEntity actor, LegStrike strike, LegRules rules) {
        // 生物没有玩家靴子事实，腿法剂量只取书级基础伤害和当前打击倍率。
        double dose = rules.getBaseDamage().getValue() * strike.getMultiplier().getValue();
        DamageSource source = declaredDamageSource(actor, strike.getDamageTypeId());
        return new StrikeData(source, dose);
    }

    private static DamageSource declaredDamageSource(LivingEntity actor, String damageTypeId) {
        // 目录提供完整 Damage ID，直接解析并查注册表，不拼接固定命名空间。
        Identifier identifier = Identifier.parse(damageTypeId);
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, identifier);
        Holder<DamageType> type = actor.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(key);
        return new DamageSource(type, actor);
    }

    private static void hitTargets(HitContext context) {
        // 盾挡只影响伤害是否成功，不吞掉声明的击退。
        LivingEntity actor = context.actor();
        for (LivingEntity target : context.candidates()) {
            if (!context.selected().contains(target.getStringUUID())) {
                continue;
            }
            boolean blocked = target.isBlocking() && facesAttacker(actor, target);
            float dose = (float) context.data().dose();
            boolean damaged = target.hurtServer((ServerLevel) actor.level(), context.data().source(), dose);
            LegStrike strike = context.strike();
            if (damaged || (blocked && strike.getKnockbackWhenBlocked())) {
                KnockbackContext knockback = new KnockbackContext(actor, target, strike.getKnockbackKind(),
                        strike.getKnockback().getValue(), context.data().source(), dose, strike);
                applyKnockback(knockback);
            }
            if (damaged) {
                TechniqueSounds.playAll(target, context.hitSounds());
            }
        }
    }

    private static void applyKnockback(KnockbackContext context) {
        // 四种腿击方向沿用原版速度入口；绊倒保留竖直速度，踩落只压低竖直分量。
        switch (context.kind()) {
            case PUSH -> push(context);
            case SIDEWAYS -> sideways(context);
            case TRIP -> trip(context.target());
            case DOWN -> down(context.target(), context.amount());
            case NONE -> { }
            default -> throw new IllegalArgumentException("未知腿击击退方式");
        }
    }

    private static void push(KnockbackContext context) {
        // 推开沿攻击者到目标的反方向计算，击退抗性仍由原版处理。
        Vec3 delta = context.actor().position().subtract(context.target().position());
        context.target().knockback(context.amount(), delta.x, delta.z, context.source(), context.dose());
    }

    private static void sideways(KnockbackContext context) {
        // 横推沿当前腿击朝向的右侧，身前与身后腿的方向都保持可预测。
        Vec3 facing = facing(context.actor(), context.strike());
        Vec3 side = new Vec3(-facing.z, 0, facing.x);
        context.target().knockback(context.amount(), side.x, side.z, context.source(), context.dose());
    }

    private static void trip(LivingEntity target) {
        // 绊倒清空水平速度，保留正在下落或跳跃的竖直速度。
        Vec3 velocity = target.getDeltaMovement();
        target.setDeltaMovement(0, velocity.y, 0);
        target.hurtMarked = true;
    }

    private static void down(LivingEntity target, double amount) {
        // 踩落不抹掉目标横向运动，只确保竖直速度向下。
        Vec3 velocity = target.getDeltaMovement();
        double downward = Math.min(velocity.y, -amount);
        target.setDeltaMovement(velocity.x, downward, velocity.z);
        target.hurtMarked = true;
    }

    private static boolean facesAttacker(LivingEntity actor, LivingEntity target) {
        // 盾只保护正面，背后命中不能触发被挡时击退分支。
        Vec3 toward = actor.position().subtract(target.position()).multiply(1, 0, 1);
        Vec3 view = target.getViewVector(1).multiply(1, 0, 1);
        if (toward.lengthSqr() == 0 || view.lengthSqr() == 0) {
            return false;
        }
        return view.normalize().dot(toward.normalize()) > 0;
    }

    private static void playAttackSound(LivingEntity actor) {
        // 生物腿击统一使用原版重击声，避免按具体实体复制声音表。
        actor.level().playSound(null, actor.getX(), actor.getY(), actor.getZ(),
                net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE,
                ATTACK_SOUND_VOLUME, ATTACK_SOUND_PITCH);
    }

    private static List<LivingEntity> candidates(LivingEntity actor, LegStrike strike) {
        // 先按世界实体过滤，再按腿击朝向、距离和形状稳定排序。
        double distance = strike.getDistance().getValue();
        List<LivingEntity> nearby = actor.level().getEntitiesOfClass(LivingEntity.class,
                        actor.getBoundingBox().inflate(distance), target -> eligible(actor, target))
                .stream()
                .sorted(Comparator.<LivingEntity>comparingDouble(actor::distanceToSqr)
                        .thenComparing(LivingEntity::getStringUUID))
                .toList();
        if (strike.getShape() == StrikeShape.SINGLE_TARGET) {
            LivingEntity aimed = aimed(actor, nearby, strike);
            if (aimed != null) {
                return List.of(aimed);
            }
        }
        return nearby.stream()
                .filter(target -> actor.distanceToSqr(target) <= distance * distance)
                .filter(target -> inSector(actor, target, strike))
                .toList();
    }

    private static boolean eligible(LivingEntity actor, LivingEntity target) {
        // 目标资格沿用类人敌意规则，不能伤害同伴或自己。
        if (target == actor || !target.isAlive() || target.isSpectator() || !actor.hasLineOfSight(target)) {
            return false;
        }
        if (!HumanoidThreats.hittable(actor, target)) {
            return false;
        }
        return !(target instanceof ArmorStand stand) || !stand.isMarker();
    }

    private static LivingEntity aimed(LivingEntity actor, List<LivingEntity> nearby, LegStrike strike) {
        // 单体腿击先取视线内第一个，视线方向按前腿或后腿调整。
        double distance = strike.getDistance().getValue();
        Vec3 start = actor.getEyePosition();
        Vec3 end = start.add(facing(actor, strike).scale(distance));
        var block = actor.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, actor));
        Vec3 limit = block.getLocation();
        return nearby.stream()
                .filter(target -> target.getBoundingBox().inflate(TARGET_MARGIN).clip(start, limit).isPresent())
                .min(Comparator.comparingDouble(target -> target.getBoundingBox().inflate(TARGET_MARGIN)
                        .clip(start, limit).orElseThrow().distanceToSqr(start)))
                .orElse(null);
    }

    private static boolean inSector(LivingEntity actor, LivingEntity target, LegStrike strike) {
        // 扇形只比较水平夹角，目标高度仍由最大距离和视线过滤控制。
        Vec3 delta = target.position().subtract(actor.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() == 0) {
            return true;
        }
        double angle = strike.getAngle().getValue();
        if (strike.getShape() == StrikeShape.SINGLE_TARGET) {
            angle = SINGLE_TARGET_ANGLE;
        }
        double threshold = Math.cos(Math.toRadians(angle * HALF));
        return facing(actor, strike).dot(delta.normalize()) >= threshold;
    }

    private static Vec3 facing(LivingEntity actor, LegStrike strike) {
        // 身后腿把目标方向翻转 180 度，前腿和双腿沿实体当前视角。
        float yaw = actor.getYRot();
        if (strike.getFacing() == StrikeFacing.STRIKE_BACKWARD) {
            yaw += 180;
        }
        return Vec3.directionFromRotation(0, yaw);
    }

    private record StrikeData(DamageSource source, double dose) { }

    private record HitContext(LivingEntity actor, List<LivingEntity> candidates, List<String> selected,
                              LegStrike strike, StrikeData data, List<SoundCue> hitSounds) { }

    private record KnockbackContext(LivingEntity actor, LivingEntity target, LegKnockbackKind kind,
                                    double amount, DamageSource source, float dose, LegStrike strike) { }

    private static final class CastHits {
        private final Map<String, Set<String>> groups = new HashMap<>();
    }
}
