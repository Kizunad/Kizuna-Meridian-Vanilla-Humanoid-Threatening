package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.humanoid.server.Fighter;
import dev.kizuna.meridian.humanoid.server.Fighter.Means;
import dev.kizuna.meridian.humanoid.server.HumanoidBrainConfig;
import dev.kizuna.meridian.humanoid.server.ThreatScoring;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.pathfinder.Path;

/**
 * 选敌打分：远近、血量、打过我、盯着我、对方威胁、召唤物、走不走得到和换目标惯性加权求和。
 * 只读内存里的身体、原版状态和观察缓存，不给候选转身体协议；寻路只给前两名算，结果缓存。
 */
final class HumanoidThreatScores {
    private static final double TICKS_PER_SECOND = 20;
    private static final double DAMAGE_CAP = 20;
    private static final double HEALTH_CAP = 40;
    private static final double REALM_SHARE = 0.5;
    private static final double ARMED_SHARE = 0.25;
    private static final double CASTING_SHARE = 0.25;
    private static final int MAX_TIER = Fighter.tier(Realm.values()[Realm.values().length - 1]);
    private static final int PATHED_CANDIDATES = 2;
    private static final int FRESH_PATHS_PER_DECISION = 1;
    private static final long REACH_MEMORY_TICKS = 40;
    private static final long CASTING_MEMORY_TICKS = 20;
    private static final Comparator<Scored> ORDER = Comparator.comparingDouble(Scored::score).reversed()
            .thenComparingDouble(Scored::distanceSqr);
    private static final Map<LivingEntity, Sighting> sightings = new WeakHashMap<>();
    private static final Map<Mob, Map<LivingEntity, Reach>> reaches = new WeakHashMap<>();
    private static final Map<Mob, Long> switchedAt = new WeakHashMap<>();

    /** 一个候选：被打分的实体，以及算到它头上的来源（它自己和它的召唤物）。 */
    record Candidate(LivingEntity entity, List<LivingEntity> sources) { }

    /** 大脑观察对手时看到的境界档与是否在起手。 */
    private record Sighting(int tier, boolean casting, long at) { }

    /** 一次寻路结果与算的时刻。 */
    private record Reach(boolean reachable, long at) { }

    /** 一个候选的分数与距离平方，同分取近的。 */
    private record Scored(LivingEntity entity, double score, double distanceSqr) { }

    private HumanoidThreatScores() {
        // 缓存按实体弱引用保存，实体卸载后自然释放。
    }

    static void seen(LivingEntity entity, Fighter fighter) {
        // 大脑每次观察对手后登记，别的类人给同一实体打分时不必再观察一次。
        sightings.put(entity, new Sighting(fighter.tier(), fighter.has(Means.CASTING), entity.level().getGameTime()));
    }

    static Optional<LivingEntity> best(Mob mob, HumanoidBrainConfig.ThreatRules rules, List<Candidate> candidates,
                                       LivingEntity previous) {
        // 先算不含寻路的分，再给前两名补上走不到的扣分，最后过换目标门槛。
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var weights = rules.scoring().of(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        var scored = new ArrayList<Scored>();
        for (Candidate candidate : candidates) {
            scored.add(score(mob, rules, weights, candidate, previous));
        }
        scored.sort(ORDER);
        if (weights.unreachable() != 0) {
            penalizeUnreachable(mob, weights, scored);
            scored.sort(ORDER);
        }
        return Optional.of(held(mob, weights, scored, previous));
    }

    private static Scored score(Mob mob, HumanoidBrainConfig.ThreatRules rules, ThreatScoring.Weights weights,
                                Candidate candidate, LivingEntity previous) {
        // 各因素归一到 0–1 后加权求和；召唤物打我、盯我都算在主人头上。
        LivingEntity entity = candidate.entity();
        double distanceSqr = mob.distanceToSqr(entity);
        double forget = rules.forgetDistance();
        double damage = candidate.sources().stream()
                .mapToDouble(source -> HumanoidThreats.recentDamage(mob, source, rules))
                .sum();
        boolean targetingMe = candidate.sources().stream()
                .anyMatch(source -> source instanceof Mob hunter && hunter.getTarget() == mob);
        double score = weights.distance() * (1 - Math.min(Math.sqrt(distanceSqr), forget) / forget)
                + weights.lowHealth() * (1 - healthRatio(entity))
                + weights.lowHealthAbsolute() * (1 - Math.min(health(entity), HEALTH_CAP) / HEALTH_CAP)
                + weights.recentDamage() * Math.min(damage, DAMAGE_CAP) / DAMAGE_CAP
                + weights.targetingMe() * flag(targetingMe)
                + weights.danger() * danger(entity)
                + weights.summon() * flag(HumanoidThreats.owner(entity) != null)
                + weights.stickiness() * flag(entity == previous);
        return new Scored(entity, score, distanceSqr);
    }

    private static double healthRatio(LivingEntity entity) {
        // 类人看身体完好率，读内存里的已提交快照；其余看原版血量。
        var body = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
        if (body != null) {
            return Math.clamp(body.currentBody().partHpRatio(), 0, 1);
        }
        return Math.clamp(entity.getHealth() / entity.getMaxHealth(), 0, 1);
    }

    private static double health(LivingEntity entity) {
        // 剩余血量的绝对值：类人按完好率折算原版血量上限。
        var body = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
        if (body != null) {
            return healthRatio(entity) * entity.getMaxHealth();
        }
        return entity.getHealth();
    }

    private static double danger(LivingEntity entity) {
        // 境界档读内存里的身体或观察缓存；持武器读原版手持；起手只认刚观察到的。
        Sighting sighting = sightings.get(entity);
        var means = HumanoidFighters.vanillaMeans(entity);
        boolean armed = means.contains(Means.MELEE_WEAPON) || means.contains(Means.RANGED_WEAPON);
        boolean casting = sighting != null && sighting.casting()
                && entity.level().getGameTime() - sighting.at() <= CASTING_MEMORY_TICKS;
        return REALM_SHARE * tier(entity, sighting) / MAX_TIER + ARMED_SHARE * flag(armed)
                + CASTING_SHARE * flag(casting);
    }

    private static int tier(LivingEntity entity, Sighting sighting) {
        // 已绑定身体的类人直接读境界；其余用最近一次观察，没观察过按凡人档。
        var body = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
        if (body != null) {
            return Fighter.tier(body.currentBody().realm());
        }
        if (sighting == null) {
            return Fighter.MORTAL_TIER;
        }
        return sighting.tier();
    }

    private static void penalizeUnreachable(Mob mob, ThreatScoring.Weights weights, List<Scored> scored) {
        // 只有一个候选、或第一名领先到扣了分也还是第一时不算路，一对一的交手不额外寻路。
        // 否则只给前两名算；每次决策最多现算一条，没缓存又轮不到现算的这次按走得到。
        if (scored.size() < PATHED_CANDIDATES
                || scored.get(0).score() - scored.get(1).score() >= -weights.unreachable()) {
            return;
        }
        int fresh = FRESH_PATHS_PER_DECISION;
        for (int index = 0; index < Math.min(PATHED_CANDIDATES, scored.size()); index++) {
            Scored entry = scored.get(index);
            if (shootable(mob, entry.entity())) {
                continue;
            }
            Reach cached = cachedReach(mob, entry.entity());
            boolean reachable;
            if (cached != null) {
                reachable = cached.reachable();
            } else if (fresh > 0) {
                fresh--;
                reachable = reachable(mob, entry.entity());
            } else {
                reachable = true;
            }
            if (!reachable) {
                scored.set(index, new Scored(entry.entity(), entry.score() + weights.unreachable(),
                        entry.distanceSqr()));
            }
        }
    }

    private static boolean shootable(Mob mob, LivingEntity target) {
        // 会远射、远施法或投药的，看得见对方就不必走到跟前。
        boolean ranged = mob instanceof RangedAttackMob || mob instanceof SpellcasterIllager
                || mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem;
        return ranged && mob.getSensing().hasLineOfSight(target);
    }

    private static Reach cachedReach(Mob mob, LivingEntity target) {
        // 缓存只在 40 tick 内算数。
        var byTarget = reaches.get(mob);
        Reach cached = byTarget == null ? null : byTarget.get(target);
        if (cached == null || mob.level().getGameTime() - cached.at() > REACH_MEMORY_TICKS) {
            return null;
        }
        return cached;
    }

    static boolean reachable(Mob mob, LivingEntity target) {
        // 现算一条路，能走到对方跟前才算走得到；站在高台上、隔着断崖或飞着的对方都算走不到。
        // 自己正在空中（跃步、被击退）时原版算不了路，这一刻不算走不到，也不记缓存。
        if (!mob.onGround()) {
            return true;
        }
        Reach cached = cachedReach(mob, target);
        if (cached != null) {
            return cached.reachable();
        }
        Path path = mob.getNavigation().createPath(target, 0);
        boolean reachable = path != null && path.canReach();
        reaches.computeIfAbsent(mob, ignored -> new WeakHashMap<>())
                .put(target, new Reach(reachable, mob.level().getGameTime()));
        return reachable;
    }

    private static LivingEntity held(Mob mob, ThreatScoring.Weights weights, List<Scored> scored,
                                     LivingEntity previous) {
        // 当前对手还在候选里时，新目标要高出门槛、且离上次换对手过了最短持续时间才换；对手失效照换。
        Scored best = scored.getFirst();
        long now = mob.level().getGameTime();
        Optional<Scored> current = scored.stream().filter(entry -> entry.entity() == previous).findFirst();
        if (current.isPresent() && best.entity() != previous) {
            Long last = switchedAt.get(mob);
            boolean holding = last != null && now - last < Math.round(weights.minHoldSeconds() * TICKS_PER_SECOND);
            if (holding || best.score() < current.get().score() + weights.switchMargin()) {
                return previous;
            }
        }
        if (best.entity() != previous) {
            switchedAt.put(mob, now);
        }
        return best.entity();
    }

    private static double flag(boolean value) {
        // 是非因素记 1 或 0。
        if (value) {
            return 1;
        }
        return 0;
    }

    static void clear() {
        // 服务器停止时丢弃全部缓存。
        sightings.clear();
        reaches.clear();
        switchedAt.clear();
    }
}
