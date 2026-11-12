package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.brewing.fabric.BrewItemStacks;
import dev.kizuna.meridian.brewing.server.BrewKind;
import dev.kizuna.meridian.brewing.server.BrewQuality;
import dev.kizuna.meridian.brewing.server.BrewUse;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.WitchRules;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.Vec3;

/** 行药诀选择叠在原版女巫 AI 上，正式绑定、费用、持物和效果仍由功法服务执行。 */
final class HumanoidWitchAi {
    static final String IMBIBE = "meridian_witch:imbibe";
    static final String ANOINT = "meridian_witch:anoint";
    static final String LOB = "meridian_witch:lob_seed";
    static final String FAN = "meridian_witch:fling_seed";
    static final String MIST = "meridian_witch:spray_trigger";
    private static final Map<UUID, Long> directCharmReady = new HashMap<>();
    private static WitchRules config;
    private static BrewQuality wanderingQuality;
    private static BrewQuality trueQuality;

    private record Turn(Witch witch, BodySnapshot body, HumanoidTier tier,
                        BrewQuality quality, List<LivingEntity> enemies) { }

    private HumanoidWitchAi() {
        // 只缓存无功法冷却的原版魅惑投掷间隔，残药与免疫始终读 Brewing。
    }

    static void initialize(Path directory) {
        // 完整装配固定加载配置并注册临时持物生命周期。
        WitchRules loaded = WitchRules.load(directory.resolve("meridian-witch-ai.toml"));
        wanderingQuality = BrewQuality.fromComponentValue(loaded.quality().wandering());
        trueQuality = BrewQuality.fromComponentValue(loaded.quality().trueQi());
        config = loaded;
        HumanoidWitchHands.initialize();
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> directCharmReady.remove(entity.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> directCharmReady.clear());
    }

    static WitchRules rules() {
        // 由服务端启动装配，测试与世界执行读取同一配置。
        return config;
    }

    static boolean decide(Witch witch) {
        // 没有敌人且不在袭击中时，只允许已有会话继续自疗，不能为闲置女巫创建身体。
        List<LivingEntity> enemies = HumanoidWitchFacts.enemies(witch, config.range().triggerDistance());
        if (enemies.isEmpty() && !witch.hasActiveRaid() && !HumanoidTechniqueHost.hasSession(witch)) {
            return false;
        }
        // 凡人完全交还原版；喝药、魅惑锁和已有施放不允许再起式。
        BodySnapshot body = HumanoidTechniqueHost.prepare(witch).body().snapshot();
        HumanoidTier tier = HumanoidTier.of(body);
        if (tier == HumanoidTier.MORTAL || witch.isDrinkingPotion() || HumanoidRestrictions.locked(witch)
                || HumanoidTechniqueHost.hasActiveCast(witch)) {
            return true;
        }
        BrewQuality quality = wanderingQuality;
        if (tier == HumanoidTier.TRUE_QI) {
            quality = trueQuality;
        }
        var turn = new Turn(witch, body, tier, quality, enemies);
        for (var plan : plans(turn)) {
            Optional<TechniqueReference> reference = available(turn, plan.technique());
            if (reference.isPresent() && HumanoidWitchHands.cast(witch, plan, quality, reference.orElseThrow())) {
                return true;
            }
        }
        return directCharm(turn) || HumanoidWitchFacts.ally(witch, witch.getTarget());
    }

    private static List<HumanoidWitchHands.Plan> plans(Turn turn) {
        // 先救急再进攻，处境不满足的式不进入候选；聚气汤没有 AI 候选。
        var plans = new ArrayList<HumanoidWitchHands.Plan>();
        var limbs = HumanoidWitchFacts.limbs(turn.body());
        boolean safe = turn.enemies().stream()
                .noneMatch(enemy -> turn.witch().distanceTo(enemy) < config.range().enemyRadius());
        if (limbs.allHurt() && limbs.combined() < config.healing().imbibeHpRatio() && safe) {
            plans.add(plan(IMBIBE, BrewKind.BITTER_YELLOW, null, limbs.worst()));
        }
        if (Math.min(limbs.arms(), limbs.legs()) < config.healing().anointHpRatio()) {
            plans.add(plan(ANOINT, BrewKind.BITTER_YELLOW, null, limbs.worst()));
        }
        if (turn.tier() != HumanoidTier.TRUE_QI) {
            return plans;
        }
        allyPlan(turn).ifPresent(plans::add);
        target(turn).ifPresent(target -> offensivePlans(turn, target, plans));
        return plans;
    }

    private static Optional<HumanoidWitchFacts.Target> target(Turn turn) {
        // 有效原版敌对目标优先，其余按现有敌意与距离选择，治疗同伴不会进入害药候选。
        Witch witch = turn.witch();
        LivingEntity current = witch.getTarget();
        if (current != null && turn.enemies().contains(current)) {
            return Optional.of(HumanoidWitchFacts.target(witch, current, config));
        }
        return turn.enemies().stream().min(Comparator.comparingDouble(witch::distanceToSqr))
                .map(entity -> HumanoidWitchFacts.target(witch, entity, config));
    }

    private static void offensivePlans(Turn turn, HumanoidWitchFacts.Target target,
                                       List<HumanoidWitchHands.Plan> plans) {
        // 足量残药与调息都可催发；魅惑必须重新通过醒后免疫门禁。
        Witch witch = turn.witch();
        LivingEntity enemy = target.entity();
        double distance = witch.distanceTo(enemy);
        if (charmWanted(turn, target) && witch.hasLineOfSight(enemy)) {
            LivingEntity charmTarget = enemy;
            if (distance <= config.charm().closeDistance()) {
                charmTarget = witch;
            }
            plans.add(plan(LOB, BrewKind.CHARM, charmTarget, BrewPartGroup.LEGS));
        }
        if (distance <= config.range().triggerDistance()) {
            target.residues().entrySet().stream()
                    .filter(entry -> entry.getValue() >= config.charm().residueTriggerDose()
                            || target.mind().recovering())
                    .filter(entry -> entry.getKey() != BrewKind.CHARM || charmAvailable(target))
                    .sorted(Map.Entry.<BrewKind, Double>comparingByValue().reversed()
                            .thenComparing(entry -> entry.getKey().name()))
                    .findFirst().ifPresent(entry -> plans.add(plan(MIST, entry.getKey(), enemy, BrewPartGroup.TORSO)));
        }
        if (!witch.hasLineOfSight(enemy) || allyNear(witch, enemy.position())) {
            return;
        }
        if (distance <= config.range().fanDistance()) {
            BrewKind kind = BrewKind.DEPLETION;
            if (target.environment().heavyArmor()) {
                kind = BrewKind.ARMOR_ROT;
            }
            plans.add(plan(FAN, kind, enemy, BrewPartGroup.FRONT_CHEST_ARMS));
        } else if (distance >= config.range().throwMinDistance()
                && distance <= config.range().throwMaxDistance() && target.residues().isEmpty()) {
            plans.add(throwPlan(witch, target));
        }
    }

    private static HumanoidWitchHands.Plan throwPlan(Witch witch, HumanoidWitchFacts.Target target) {
        // 重甲逼近优先蚀甲，跑离优先枯竭腿部，其次才在适合环境用招虫水。
        LivingEntity enemy = target.entity();
        Vec3 offset = enemy.position().subtract(witch.position()).multiply(1, 0, 1);
        Vec3 relative = enemy.getDeltaMovement().subtract(witch.getDeltaMovement()).multiply(1, 0, 1);
        if (target.environment().heavyArmor() && offset.dot(relative) < 0) {
            return plan(LOB, BrewKind.ARMOR_ROT, enemy, target.armorGroup());
        }
        if (offset.dot(relative) > 0
                && enemy.getDeltaMovement().horizontalDistance() >= config.environment().runningSpeedPerTick()) {
            return plan(LOB, BrewKind.DEPLETION, enemy, BrewPartGroup.LEGS);
        }
        if (!target.environment().water() && !target.environment().rain()
                && HumanoidWitchFacts.insects(witch, config)) {
            return plan(LOB, BrewKind.INSECT_LURE, enemy, BrewPartGroup.TORSO);
        }
        return plan(LOB, BrewKind.DEPLETION, enemy, BrewPartGroup.TORSO);
    }

    private static Optional<HumanoidWitchHands.Plan> allyPlan(Turn turn) {
        // 只治疗同一活动袭击中的 Raider，避开敌人邻近处，防止苦黄膏迟滞拖累正在贴身作战的同伴。
        Witch witch = turn.witch();
        if (!witch.hasActiveRaid()) {
            return Optional.empty();
        }
        double radius = config.range().allyRadius();
        return witch.level().getEntitiesOfClass(Raider.class, witch.getBoundingBox().inflate(radius),
                        ally -> ally != witch && ally.isAlive() && HumanoidWitchFacts.ally(witch, ally)
                                && witch.distanceTo(ally) <= radius && witch.hasLineOfSight(ally))
                .stream().filter(ally -> !enemyNear(witch, ally)).map(ally -> {
                    var body = HumanoidTechniqueHost.prepare(ally).body().snapshot();
                    return Map.entry(ally, HumanoidWitchFacts.limbs(body));
                }).filter(entry -> Math.min(entry.getValue().arms(), entry.getValue().legs())
                        < config.healing().allyHpRatio())
                .min(Comparator.comparingDouble(entry -> entry.getValue().combined()))
                .map(entry -> plan(LOB, BrewKind.BITTER_YELLOW, entry.getKey(), entry.getValue().worst()));
    }

    private static boolean enemyNear(Witch witch, Raider ally) {
        // 治疗的安全半径同时看同伴当前敌人和女巫已知敌意。
        double radius = config.range().allyEnemyClearance();
        return !witch.level().getEntitiesOfClass(LivingEntity.class, ally.getBoundingBox().inflate(radius),
                entity -> entity.isAlive() && !HumanoidWitchFacts.ally(witch, entity)
                        && (HumanoidWitchFacts.hostile(witch, entity) || HumanoidThreats.enemy(ally, entity))
                        && ally.distanceTo(entity) <= radius).isEmpty();
    }

    private static boolean allyNear(Witch witch, Vec3 point) {
        // 害药瞄点附近有同伴时放弃本轮投掷或泼洒。
        double radius = config.range().allyEnemyClearance();
        return !witch.level().getEntitiesOfClass(LivingEntity.class, witch.getBoundingBox().inflate(
                        config.range().triggerDistance()), entity -> entity != witch && entity.isAlive()
                        && HumanoidWitchFacts.ally(witch, entity)
                        && entity.distanceToSqr(point) <= radius * radius).isEmpty();
    }

    private static boolean charmAvailable(HumanoidWitchFacts.Target target) {
        // 受控期间和醒后免疫期间都不浪费新瓶。
        return target.mind().charmImmunityMillis() == 0 && !target.mind().charmed();
    }

    private static boolean charmWanted(Turn turn, HumanoidWitchFacts.Target target) {
        // 近身压力、被围或低脑力才用魅惑；已有安全距离时不无条件投魅惑。
        Witch witch = turn.witch();
        if (!charmAvailable(target) || allyNear(witch, target.entity().position())) {
            return false;
        }
        double close = config.charm().closeDistance();
        long crowd = turn.enemies().stream()
                .filter(enemy -> witch.distanceTo(enemy) <= config.charm().crowdRadius()).count();
        return witch.distanceTo(target.entity()) <= close
                || crowd >= config.charm().crowdCount()
                || target.mind().power() < config.charm().brainCeiling();
    }

    private static Optional<TechniqueReference> available(Turn turn, String id) {
        // 候选必须真的学会、冷却已过、身体可绑定、费用足够且做得了功，随后正式起手再次核验。
        var body = BodyProtobuf.fromMessage(turn.body());
        var progress = TechniqueState.read(body);
        var reference = progress.getKnownList().stream().map(known -> known.getTechnique())
                .filter(known -> known.getId().equals(id)).findFirst();
        if (reference.isEmpty() || progress.getCooldownsMap().getOrDefault(id, 0L)
                > body.settledAt().elapsedMillis()) {
            return Optional.empty();
        }
        var definition = TechniqueFabric.service().registry().find(reference.orElseThrow()).orElseThrow();
        var items = HumanoidWayfarerWeapons.inspect(turn.witch());
        var binding = TechniqueBinder.bind(body, definition, Hand.HAND_UNSPECIFIED, items.getMainHandSide());
        if (!binding.hasBinding() || !HumanoidRepertoires.payable(body, definition, binding.getBinding())) {
            return Optional.empty();
        }
        return reference;
    }

    private static boolean directCharm(Turn turn) {
        // 游炁没有中卷时沿原版抛射器直接投药，不走下引处理器。
        Witch witch = turn.witch();
        UUID witchId = witch.getUUID();
        if (turn.tier() != HumanoidTier.WANDERING_QI
                || witch.level().getGameTime() < directCharmReady.getOrDefault(witchId, 0L)) {
            return false;
        }
        var target = target(turn).filter(value -> charmWanted(turn, value))
                .filter(value -> witch.hasLineOfSight(value.entity()));
        if (target.isEmpty()) {
            return false;
        }
        throwCharm(turn, target.orElseThrow().entity());
        directCharmReady.put(witchId, witch.level().getGameTime()
                + config.charm().directCooldownTicks());
        return true;
    }

    private static void throwCharm(Turn turn, LivingEntity target) {
        // 近身往脚下，其他情况瞄人群中的目标；速度、散布和抛高沿原版女巫公式。
        Witch witch = turn.witch();
        Vec3 destination = target.position().add(target.getDeltaMovement());
        if (witch.distanceTo(target) <= config.charm().closeDistance()) {
            destination = witch.position();
        }
        Vec3 offset = destination.subtract(witch.position());
        double distance = offset.horizontalDistance();
        double speed = config.vanilla().longSpeed();
        if (distance <= config.vanilla().shortDistance()) {
            speed = config.vanilla().shortSpeed();
        }
        double vertical = offset.y + target.getEyeHeight() - config.vanilla().targetEyeOffset()
                + distance * config.vanilla().lobHeight();
        var stack = BrewItemStacks.create(BrewKind.CHARM, turn.quality(), BrewUse.SPLASH);
        Projectile.spawnProjectileUsingShoot(ThrownSplashPotion::new, (ServerLevel) witch.level(), stack, witch,
                offset.x, vertical, offset.z, (float) speed, (float) config.vanilla().scatter());
        witch.level().playSound(null, witch.getX(), witch.getY(), witch.getZ(), SoundEvents.WITCH_THROW,
                SoundSource.HOSTILE, (float) config.sound().volume(), (float) config.sound().pitch());
    }

    private static HumanoidWitchHands.Plan plan(String technique, BrewKind kind, LivingEntity target,
                                                BrewPartGroup aim) {
        // 一份选择同时携带正式式 ID、药种、目标和实际视线分段。
        return new HumanoidWitchHands.Plan(technique, kind, target, aim);
    }
}
